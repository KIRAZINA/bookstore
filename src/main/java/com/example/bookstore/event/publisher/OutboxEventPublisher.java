package com.example.bookstore.event.publisher;

import com.example.bookstore.event.BaseEvent;
import com.example.bookstore.event.EventOutbox;
import com.example.bookstore.event.repository.EventOutboxRepository;
import com.example.bookstore.messaging.EnvelopeConverter;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
