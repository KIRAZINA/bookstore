package com.example.bookstore.event.publisher;

import com.example.bookstore.event.BaseEvent;
import com.example.bookstore.event.EventOutbox;
import com.example.bookstore.event.EventTypes;
import com.example.bookstore.event.repository.EventOutboxRepository;
import com.example.bookstore.messaging.EnvelopeConverter;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Component
public class OutboxEventPublisher implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventPublisher.class);

    private final EventOutboxRepository outboxRepository;
    private final ApplicationEventPublisher legacyPublisher;

    public OutboxEventPublisher(EventOutboxRepository outboxRepository,
                                ApplicationEventPublisher legacyPublisher) {
        this.outboxRepository = outboxRepository;
        this.legacyPublisher = legacyPublisher;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(BaseEvent event, String aggregateType, String aggregateId, String eventType) {
        String correlationId = event.getEventId().toString();
        String traceId = extractTraceId();
        String spanId = extractSpanId();

        EventOutbox outbox = EnvelopeConverter.toOutbox(
                event, aggregateType, aggregateId, eventType,
                "bookstore", correlationId, traceId, spanId);

        outboxRepository.save(outbox);

        // In-process delivery of the event to local @TransactionalEventListener beans
        // (e.g. NotificationEventHandler). The outbox row above is what the Kafka
        // integration bus consumes; the two channels are coordinated via the
        // app.notifications.delivery-mode setting (see README).
        legacyPublisher.publishEvent(event);

        log.debug("Event published to outbox: type={}, aggregateId={}, eventId={}",
                eventType, aggregateId, event.getEventId());
    }

    /**
     * Persists an email request as an outbox row inside the caller's transaction. The
     * email is delivered by {@code OutboxPublisherScheduler} after the transaction
     * commits, so a rolled-back business transaction can never deliver the email and a
     * crash between delivery and commit cannot lose the intent.
     *
     * <p>The outbox table already groups every durable intent; the free-text
     * {@code event_type} discriminator distinguishes an email request from a Kafka
     * event, so no schema change is required.</p>
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishEmailRequest(String to, String subject, String body, String emailType,
                                    String aggregateId, String correlationId) {
        String payload = EnvelopeConverter.serializePayload(Map.of(
                "to", to == null ? "" : to,
                "subject", subject == null ? "" : subject,
                "body", body == null ? "" : body,
                "emailType", emailType == null ? "" : emailType));

        EventOutbox outbox = new EventOutbox(
                UUID.randomUUID(),
                EventTypes.AGGREGATE_NOTIFICATION,
                aggregateId == null ? "unknown" : aggregateId,
                EventTypes.NOTIFICATION_REQUESTED,
                1,
                correlationId,
                extractTraceId(),
                extractSpanId(),
                "bookstore",
                payload,
                null);

        outboxRepository.save(outbox);

        log.debug("Email request published to outbox: recipient={}, aggregateId={}, correlationId={}",
                to, aggregateId, correlationId);
    }

    private static String extractTraceId() {
        Span span = Span.current();
        if (span != null && span.getSpanContext().isValid()) {
            return span.getSpanContext().getTraceId();
        }
        return null;
    }

    private static String extractSpanId() {
        Span span = Span.current();
        if (span != null && span.getSpanContext().isValid()) {
            return span.getSpanContext().getSpanId();
        }
        return null;
    }
}
