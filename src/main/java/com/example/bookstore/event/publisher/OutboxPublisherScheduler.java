package com.example.bookstore.event.publisher;

import com.example.bookstore.config.KafkaTopicConfig;
import com.example.bookstore.event.EventOutbox;
import com.example.bookstore.event.EventTypes;
import com.example.bookstore.event.repository.EventOutboxRepository;
import com.example.bookstore.messaging.DomainEventEnvelope;
import com.example.bookstore.messaging.EnvelopeConverter;
import com.example.bookstore.notification.email.EmailService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Drains {@code event_outbox} rows and delivers them to their external system using a
 * two-phase claim protocol.
 *
 * <p>Phase 1 claims a bounded batch of PENDING rows into CLAIMED in a short transaction
 * using {@code FOR UPDATE SKIP LOCKED}, so two schedulers can never claim the same row.
 * Phase 2 performs the external call with no database transaction held. Phase 3 writes
 * the terminal status in its own {@code REQUIRES_NEW} transaction guarded by the claim
 * token: a worker that lost its claim (reaped and re-claimed by another worker) updates
 * zero rows and records a fencing signal instead of overwriting the newer owner.</p>
 *
 * <p>A crash between Phase 2 and Phase 3 leaves the row CLAIMED; the stale-claim reaper
 * eventually reverts it to PENDING for redelivery. That is at-least-once delivery, which
 * Kafka and SMTP consumers already implement.</p>
 */
@Component
@ConditionalOnProperty("spring.kafka.bootstrap-servers")
public class OutboxPublisherScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherScheduler.class);

    private static final String METRIC_MARK_FENCED_OUT = "outbox.mark.fenced_out";
    private static final String METRIC_REAPER_REVERTED = "outbox.reaper.reverted";

    private final EventOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final EmailService emailService;
    private final int maxRetries;

    /** Upper bound on rows claimed per tick (residual #4). */
    @Value("${app.outbox.batch-size:100}")
    private int batchSize = 100;

    /** Claim age beyond which the reaper reverts a CLAIMED row. SMTP worst-case, not Kafka. */
    @Value("${app.outbox.stale-claim-ms:600000}")
    private long staleClaimMs = 600_000L;

    /** Metrics for fencing and reaper signals; falls back to an isolated registry in unit tests. */
    @Autowired
    private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    /** Phase-1 transaction; not read-only because the claim is an UPDATE. */
    private final TransactionTemplate claimTemplate;

    /** Phase-3 transaction, independent of any caller transaction. */
    private final TransactionTemplate statusTemplate;

    @Autowired
    public OutboxPublisherScheduler(EventOutboxRepository outboxRepository,
                                     KafkaTemplate<String, String> kafkaTemplate,
                                     EmailService emailService,
                                     @Value("${app.outbox.max-retries:5}") int maxRetries,
                                     PlatformTransactionManager transactionManager) {
        Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository must not be null");
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate must not be null");
        this.emailService = emailService;
        this.maxRetries = maxRetries;

        this.claimTemplate = new TransactionTemplate(transactionManager);

        this.statusTemplate = new TransactionTemplate(transactionManager);
        this.statusTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Unit-test constructor. No transaction manager is available, so repository calls run
     * directly against the injected collaborator. Production wiring always uses the
     * five-argument constructor above.
     */
    OutboxPublisherScheduler(EventOutboxRepository outboxRepository,
                             KafkaTemplate<String, String> kafkaTemplate,
                             int maxRetries) {
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository must not be null");
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate must not be null");
        this.emailService = null;
        this.maxRetries = maxRetries;
        this.claimTemplate = null;
        this.statusTemplate = null;
    }

    /**
     * Not transactional on purpose: the external call must never run while a database
     * transaction (and therefore a connection) is held open.
     */
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval:1000}")
    public void publishPendingEvents() {
        List<EventOutbox> claimed = claimPhase();
        if (claimed.isEmpty()) {
            return;
        }

        log.debug("Publishing {} claimed outbox events", claimed.size());

        for (EventOutbox row : claimed) {
            try {
                deliver(row);
                markPublished(row);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while publishing outbox event {}", row.getEventType());
                markFailed(row, e);
            } catch (Exception e) {
                log.warn("outbox delivery failed id={} token={} type={}",
                        row.getId(), row.getClaimToken(), row.getEventType(), e);
                markFailed(row, e);
            }
        }
    }

    /** Phase 1: claim a bounded batch in one short transaction. */
    private List<EventOutbox> claimPhase() {
        Instant now = Instant.now();
        if (claimTemplate == null) {
            return outboxRepository.claimBatch(batchSize, maxRetries, now);
        }
        return claimTemplate.execute(status -> outboxRepository.claimBatch(batchSize, maxRetries, now));
    }

    /** Phase 2: external delivery, with no transaction active. */
    private void deliver(EventOutbox outbox) throws Exception {
        if (EventTypes.NOTIFICATION_REQUESTED.equals(outbox.getEventType())) {
            deliverEmail(outbox);
        } else {
            deliverToKafka(outbox);
        }
    }

    private void deliverToKafka(EventOutbox outbox) throws Exception {
        DomainEventEnvelope envelope = EnvelopeConverter.toEnvelope(outbox);
        String topic = resolveTopic(envelope.eventType());
        String key = envelope.aggregateId();
        String value = EnvelopeConverter.toJson(envelope);

        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
        addTraceParentHeader(record, outbox.getTraceId(), outbox.getSpanId());

        kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);
        log.debug("Published event {} to topic {}", outbox.getEventType(), topic);
    }

    private void deliverEmail(EventOutbox outbox) {
        if (emailService == null) {
            throw new IllegalStateException(
                    "Email delivery is not configured for outbox event " + outbox.getId());
        }
        Map<String, Object> intent = EnvelopeConverter.deserializePayload(outbox.getPayload());
        emailService.sendEmail(
                asText(intent.get("to")),
                asText(intent.get("subject")),
                asText(intent.get("body")),
                asText(intent.get("emailType")));
        log.debug("Delivered email outbox event id={} aggregateId={}",
                outbox.getId(), outbox.getAggregateId());
    }

    private static String asText(Object value) {
        return value == null ? "" : value.toString();
    }

    /** Phase 3 (success): fenced terminal mark. */
    private void markPublished(EventOutbox row) {
        int updated = writeFenced(() -> outboxRepository.markPublished(
                row.getId(), row.getClaimToken(), Instant.now()));
        if (updated == 0) {
            log.warn("outbox mark fenced out id={} token={} reason=claim token no longer held",
                    row.getId(), row.getClaimToken());
            meterRegistry.counter(METRIC_MARK_FENCED_OUT).increment();
        }
    }

    /** Phase 3 (failure): fenced retry / terminal mark. */
    private void markFailed(EventOutbox row, Exception cause) {
        int retryCount = row.getRetryCount() + 1;
        int updated = writeFenced(() -> outboxRepository.markFailedFenced(
                row.getId(), row.getClaimToken(), maxRetries, Instant.now()));
        if (updated == 0) {
            log.warn("outbox failure mark fenced out id={} token={} reason=claim token no longer held",
                    row.getId(), row.getClaimToken());
            meterRegistry.counter(METRIC_MARK_FENCED_OUT).increment();
            return;
        }
        if (retryCount >= maxRetries) {
            log.error("Event {} (id={}) permanently failed after {} retries",
                    row.getEventType(), row.getId(), maxRetries, cause);
        } else {
            log.warn("Failed to publish event {} (id={}), retry {}/{}",
                    row.getEventType(), row.getId(), retryCount, maxRetries, cause);
        }
    }

    private int writeFenced(Supplier<Integer> write) {
        if (statusTemplate == null) {
            return write.get();
        }
        Integer updated = statusTemplate.execute(status -> write.get());
        return updated == null ? 0 : updated;
    }

    /** Reaper: revert stale CLAIMED rows so crashed workers' rows are redelivered. */
    @Scheduled(fixedDelayString = "${app.outbox.reap-interval-ms:60000}")
    public void reapStaleClaims() {
        Instant now = Instant.now();
        Instant threshold = now.minusMillis(staleClaimMs);
        int reverted;
        if (statusTemplate == null) {
            reverted = outboxRepository.revertStaleClaims(threshold, now);
        } else {
            Integer result = statusTemplate.execute(status ->
                    outboxRepository.revertStaleClaims(threshold, now));
            reverted = result == null ? 0 : result;
        }
        if (reverted > 0) {
            log.warn("outbox reaper: reverted {} stale CLAIMED rows", reverted);
            meterRegistry.counter(METRIC_REAPER_REVERTED).increment(reverted);
        }
    }

    /**
     * Injects a W3C {@code traceparent} header so consumers can continue the trace that
     * originated in the business operation which created the outbox event.
     */
    private static void addTraceParentHeader(ProducerRecord<String, String> record,
                                              String traceId, String spanId) {
        if (traceId != null && spanId != null
                && traceId.length() == 32 && spanId.length() == 16) {
            String traceParent = "00-" + traceId + "-" + spanId + "-01";
            record.headers().add("traceparent", traceParent.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String resolveTopic(String eventType) {
        if (eventType.startsWith(EventTypes.AGGREGATE_ORDER + "_")) {
            return KafkaTopicConfig.TOPIC_ORDERS;
        }
        if (eventType.startsWith(EventTypes.AGGREGATE_PAYMENT + "_")) {
            return KafkaTopicConfig.TOPIC_PAYMENTS;
        }
        if (eventType.startsWith(EventTypes.AGGREGATE_INVENTORY + "_")) {
            return KafkaTopicConfig.TOPIC_INVENTORY;
        }
        if (eventType.startsWith(EventTypes.AGGREGATE_NOTIFICATION + "_")) {
            return KafkaTopicConfig.TOPIC_NOTIFICATIONS;
        }
        return KafkaTopicConfig.TOPIC_ORDERS;
    }
}
