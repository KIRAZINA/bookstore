package com.example.bookstore.event.publisher;

import com.example.bookstore.config.KafkaTopicConfig;
import com.example.bookstore.event.EventOutbox;
import com.example.bookstore.event.repository.EventOutboxRepository;
import com.example.bookstore.messaging.DomainEventEnvelope;
import com.example.bookstore.messaging.EnvelopeConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;

@Component
@ConditionalOnProperty(value = "spring.kafka.bootstrap-servers")
public class OutboxPublisherScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherScheduler.class);

    private final EventOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final int maxRetries;

    public OutboxPublisherScheduler(EventOutboxRepository outboxRepository,
                                    KafkaTemplate<String, String> kafkaTemplate,
                                    @Value("${app.outbox.max-retries:5}") int maxRetries) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.maxRetries = maxRetries;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval:1000}")
    @Transactional
    public void publishPendingEvents() {
        List<EventOutbox> pending = outboxRepository.findPendingEvents(maxRetries);
        if (pending.isEmpty()) return;

        log.debug("Publishing {} pending outbox events", pending.size());

        for (EventOutbox outbox : pending) {
            try {
                DomainEventEnvelope envelope = EnvelopeConverter.toEnvelope(outbox);
                String topic = resolveTopic(envelope.eventType());
                String key = envelope.aggregateId();
                String value = EnvelopeConverter.toJson(envelope);

                SendResult<String, String> result = kafkaTemplate.send(topic, key, value).get();
                outboxRepository.markPublished(outbox.getId(), Instant.now());
                log.debug("Published event {} to topic {} partition {} offset {}",
                        outbox.getEventType(), topic,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while publishing event {}", outbox.getEventType());
                break;
            } catch (ExecutionException e) {
                handlePublishFailure(outbox, e);
            } catch (Exception e) {
                handlePublishFailure(outbox, e);
            }
        }
    }

    private void handlePublishFailure(EventOutbox outbox, Exception e) {
        outbox.incrementRetry();
        if (outbox.getRetryCount() >= maxRetries) {
            outboxRepository.markFailed(outbox.getId(), Instant.now());
            log.error("Event {} (id={) permanently failed after {} retries",
                    outbox.getEventType(), outbox.getId(), maxRetries, e);
        } else {
            outboxRepository.incrementRetry(outbox.getId());
            log.warn("Failed to publish event {} (id={}), retry {}/{}",
                    outbox.getEventType(), outbox.getId(), outbox.getRetryCount(), maxRetries, e);
        }
    }

    private static String resolveTopic(String eventType) {
        if (eventType.startsWith("ORDER_")) return KafkaTopicConfig.TOPIC_ORDERS;
        if (eventType.startsWith("PAYMENT_")) return KafkaTopicConfig.TOPIC_PAYMENTS;
        if (eventType.startsWith("INVENTORY_")) return KafkaTopicConfig.TOPIC_INVENTORY;
        if (eventType.startsWith("NOTIFICATION_")) return KafkaTopicConfig.TOPIC_NOTIFICATIONS;
        return KafkaTopicConfig.TOPIC_ORDERS;
    }
}
