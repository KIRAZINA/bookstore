package com.example.bookstore.messaging.consumer;

import com.example.bookstore.config.DeadLetterService;
import com.example.bookstore.event.ProcessedEvent;
import com.example.bookstore.event.repository.ProcessedEventRepository;
import com.example.bookstore.messaging.DomainEventEnvelope;
import com.example.bookstore.messaging.EnvelopeConverter;
import com.example.bookstore.messaging.metrics.MessagingMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

public abstract class AbstractIdempotentConsumer {

    private static final Logger log = LoggerFactory.getLogger(AbstractIdempotentConsumer.class);

    protected final ProcessedEventRepository processedEventRepository;
    protected final MessagingMetrics messagingMetrics;
    private final String consumerGroup;
    private final DeadLetterService deadLetterService;
    private final String topic;

    protected AbstractIdempotentConsumer(ProcessedEventRepository processedEventRepository,
                                          MessagingMetrics messagingMetrics,
                                          String consumerGroup,
                                          DeadLetterService deadLetterService,
                                          String topic) {
        this.processedEventRepository = processedEventRepository;
        this.messagingMetrics = messagingMetrics;
        this.consumerGroup = consumerGroup;
        this.deadLetterService = deadLetterService;
        this.topic = topic;
    }

    @Transactional
    protected void processEvent(String message, String eventTypeLabel) {
        long start = System.currentTimeMillis();
        DomainEventEnvelope envelope = EnvelopeConverter.fromJson(message);

        String eventId = envelope.eventId().toString();

        try {
            processedEventRepository.saveAndFlush(new ProcessedEvent(eventId, consumerGroup));
        } catch (DataIntegrityViolationException e) {
            log.debug("Duplicate event skipped: type={}, eventId={}, consumer={}",
                    envelope.eventType(), eventId, consumerGroup);
            return;
        }

        try {
            doProcess(envelope);
            messagingMetrics.recordEventConsumed();
            log.debug("Processed event: type={}, eventId={}, consumer={}",
                    envelope.eventType(), eventId, consumerGroup);
        } catch (Exception e) {
            messagingMetrics.recordEventFailed();
            log.error("Failed to process event: type={}, eventId={}, consumer={}",
                    envelope.eventType(), eventId, consumerGroup, e);
            throw e;
        } finally {
            messagingMetrics.recordEventProcessingDuration(System.currentTimeMillis() - start);
        }
    }

    protected abstract void doProcess(DomainEventEnvelope envelope);
}
