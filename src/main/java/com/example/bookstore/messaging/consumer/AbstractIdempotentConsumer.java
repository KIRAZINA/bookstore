package com.example.bookstore.messaging.consumer;

import com.example.bookstore.event.ProcessedEvent;
import com.example.bookstore.event.repository.ProcessedEventRepository;
import com.example.bookstore.messaging.DomainEventEnvelope;
import com.example.bookstore.messaging.EnvelopeConverter;
import com.example.bookstore.messaging.metrics.MessagingMetrics;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public abstract class AbstractIdempotentConsumer {

    private static final Logger log = LoggerFactory.getLogger(AbstractIdempotentConsumer.class);

    private static final TextMapGetter<Headers> HEADERS_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Headers carrier) {
            List<String> keys = new ArrayList<>();
            for (Header header : carrier) {
                keys.add(header.key());
            }
            return keys;
        }

        @Override
        public String get(Headers carrier, String key) {
            Header header = carrier.lastHeader(key);
            return header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
        }
    };

    protected final ProcessedEventRepository processedEventRepository;
    protected final MessagingMetrics messagingMetrics;
    private final String consumerGroup;

    /**
     * TransactionTemplate is used instead of {@code @Transactional} because subclasses invoke
     * {@link #processEvent(String, Headers)} directly through inheritance (self-invocation),
     * which bypasses the Spring AOP proxy. A programmatic template is proxy-free by construction.
     * Null only when a unit test constructs the consumer without a transaction manager.
     */
    private final TransactionTemplate dedupTemplate;

    protected AbstractIdempotentConsumer(ProcessedEventRepository processedEventRepository,
                                          MessagingMetrics messagingMetrics,
                                          String consumerGroup,
                                          PlatformTransactionManager transactionManager) {
        this.processedEventRepository = processedEventRepository;
        this.messagingMetrics = messagingMetrics;
        this.consumerGroup = consumerGroup;
        this.dedupTemplate = transactionManager == null ? null : createDedupTemplate(transactionManager);
    }

    /**
     * Legacy constructor for tests that do not exercise transaction semantics.
     */
    protected AbstractIdempotentConsumer(ProcessedEventRepository processedEventRepository,
                                          MessagingMetrics messagingMetrics,
                                          String consumerGroup) {
        this(processedEventRepository, messagingMetrics, consumerGroup, null);
    }

    private static TransactionTemplate createDedupTemplate(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        return template;
    }

    /**
     * Processes one event atomically: the dedup row and the processing outcome are
     * committed in the same transaction. If processing throws, the transaction rolls
     * back and no dedup row remains, so a Kafka redelivery can retry the event.
     */
    protected void processEvent(String message, Headers headers) {
        long start = System.currentTimeMillis();
        DomainEventEnvelope envelope = EnvelopeConverter.fromJson(message);
        String eventId = envelope.eventId().toString();

        Runnable processing = () -> {
            if (processedEventRepository.existsByEventIdAndConsumerGroup(eventId, consumerGroup)) {
                log.debug("Duplicate event skipped: type={}, eventId={}, consumer={}",
                        envelope.eventType(), eventId, consumerGroup);
                return;
            }

            processedEventRepository.save(new ProcessedEvent(eventId, consumerGroup));
            runInTraceContext(headers, () -> {
                doProcess(envelope);
                messagingMetrics.recordEventConsumed();
                log.debug("Processed event: type={}, eventId={}, consumer={}",
                        envelope.eventType(), eventId, consumerGroup);
            });
        };

        try {
            if (dedupTemplate == null) {
                processing.run();
            } else {
                dedupTemplate.executeWithoutResult(status -> processing.run());
            }
        } catch (Exception e) {
            messagingMetrics.recordEventFailed();
            log.error("Failed to process event: type={}, eventId={}, consumer={}",
                    envelope.eventType(), eventId, consumerGroup, e);
            throw e;
        } finally {
            messagingMetrics.recordEventProcessingDuration(System.currentTimeMillis() - start);
        }
    }

    /**
     * Continues the distributed trace captured at publish time (via the W3C
     * {@code traceparent} Kafka header) unless the OpenTelemetry Java agent has already
     * started a child span for the consumer (in which case that span is already current
     * and must not be replaced).
     *
     * @SuppressWarnings note: PMD's UnusedLocalVariable flags the try-with-resources
     * {@code Scope} variable as unused even though it is implicitly closed; the resource
     * is required and the variable must stay named for scope handling.
     */
    @SuppressWarnings("PMD.UnusedLocalVariable")
    private void runInTraceContext(Headers headers, Runnable action) {
        Span current = Span.current();
        if (current != null && current.getSpanContext().isValid() && !current.getSpanContext().isRemote()) {
            action.run();
            return;
        }

        Context context = W3CTraceContextPropagator.getInstance()
                .extract(Context.current(), headers, HEADERS_GETTER);
        try (Scope scope = context.makeCurrent()) {
            action.run();
        }
    }

    protected abstract void doProcess(DomainEventEnvelope envelope);
}
