package com.example.bookstore.event.publisher;

import com.example.bookstore.config.KafkaTopicConfig;
import com.example.bookstore.event.EventOutbox;
import com.example.bookstore.event.EventTypes;
import com.example.bookstore.event.repository.EventOutboxRepository;
import com.example.bookstore.messaging.DomainEventEnvelope;
import com.example.bookstore.messaging.EnvelopeConverter;
import com.example.bookstore.notification.email.EmailService;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drains {@code event_outbox} rows and delivers them to their external system.
 *
 * <p>Delivery is deliberately outside any database transaction: the outbox row is the
 * durable intent, the external call happens without holding a connection, and only a
 * successful delivery is recorded (in its own short transaction). A crash between the
 * external call and the marker leaves the row pending, so the next tick re-delivers it.
 * That is at-least-once delivery, which is the contract Kafka and SMTP consumers
 * already implement.</p>
 */
@Component
@ConditionalOnProperty("spring.kafka.bootstrap-servers")
public class OutboxPublisherScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherScheduler.class);

    private final EventOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final EmailService emailService;
    private final int maxRetries;

    /** Read-only pickup transaction; null only in the legacy unit-test wiring. */
    private final TransactionTemplate pickupTemplate;

    /** Short independent transaction for status writes; null only in unit-test wiring. */
    private final TransactionTemplate statusTemplate;

    @Autowired
    public OutboxPublisherScheduler(EventOutboxRepository outboxRepository,
                                    KafkaTemplate<String, String> kafkaTemplate,
                                    EmailService emailService,
                                    @Value("${app.outbox.max-retries:5}") int maxRetries,
                                    PlatformTransactionManager transactionManager) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.emailService = emailService;
        this.maxRetries = maxRetries;

        if (transactionManager == null) {
            this.pickupTemplate = null;
            this.statusTemplate = null;
        } else {
            this.pickupTemplate = new TransactionTemplate(transactionManager);
            this.pickupTemplate.setReadOnly(true);

            this.statusTemplate = new TransactionTemplate(transactionManager);
            this.statusTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }
    }

    /**
     * Unit-test constructor. No transaction manager is available, so repository calls run
     * directly against the injected collaborator. Production wiring always uses the
     * five-argument constructor above.
     */
    OutboxPublisherScheduler(EventOutboxRepository outboxRepository,
                             KafkaTemplate<String, String> kafkaTemplate,
                             int maxRetries) {
        this(outboxRepository, kafkaTemplate, null, maxRetries, null);
    }

    /**
     * Not transactional on purpose: the external call must never run while a database
     * transaction (and therefore a connection) is held open.
     */
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval:1000}")
    public void publishPendingEvents() {
        List<EventOutbox> pending = loadPending();
        if (pending == null || pending.isEmpty()) {
            return;
        }

        log.debug("Publishing {} pending outbox events", pending.size());

        for (EventOutbox outbox : pending) {
            try {
                if (EventTypes.NOTIFICATION_REQUESTED.equals(outbox.getEventType())) {
                    deliverEmail(outbox);
                } else {
                    deliverToKafka(outbox);
                }
                markPublished(outbox.getId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while publishing event {}", outbox.getEventType());
                break;
            } catch (Exception e) {
                handlePublishFailure(outbox, e);
            }
        }
    }

    private List<EventOutbox> loadPending() {
        if (pickupTemplate == null) {
            return outboxRepository.findPendingEvents(maxRetries);
        }
        return pickupTemplate.execute(status -> outboxRepository.findPendingEvents(maxRetries));
    }

    private void deliverToKafka(EventOutbox outbox) throws Exception {
        DomainEventEnvelope envelope = EnvelopeConverter.toEnvelope(outbox);
        String topic = resolveTopic(envelope.eventType());
        String key = envelope.aggregateId();
        String value = EnvelopeConverter.toJson(envelope);

        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
        addTraceParentHeader(record, outbox.getTraceId(), outbox.getSpanId());

        SendResult<String, String> result = kafkaTemplate.send(record).get();
        log.debug("Published event {} to topic {} partition {} offset {}",
                outbox.getEventType(), topic,
                result.getRecordMetadata().partition(),
                result.getRecordMetadata().offset());
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

    private void markPublished(UUID id) {
        if (statusTemplate == null) {
            outboxRepository.markPublished(id, Instant.now());
            return;
        }
        statusTemplate.executeWithoutResult(status ->
                outboxRepository.markPublished(id, Instant.now()));
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

    private void handlePublishFailure(EventOutbox outbox, Exception e) {
        outbox.incrementRetry();
        if (outbox.getRetryCount() >= maxRetries) {
            writeStatus(() -> outboxRepository.markFailed(outbox.getId(), Instant.now()));
            log.error("Event {} (id={}) permanently failed after {} retries",
                    outbox.getEventType(), outbox.getId(), maxRetries, e);
        } else {
            writeStatus(() -> outboxRepository.incrementRetry(outbox.getId()));
            log.warn("Failed to publish event {} (id={}), retry {}/{}",
                    outbox.getEventType(), outbox.getId(), outbox.getRetryCount(), maxRetries, e);
        }
    }

    private void writeStatus(Runnable write) {
        if (statusTemplate == null) {
            write.run();
            return;
        }
        statusTemplate.executeWithoutResult(status -> write.run());
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
