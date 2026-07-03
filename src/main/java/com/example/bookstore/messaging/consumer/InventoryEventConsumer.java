package com.example.bookstore.messaging.consumer;

import com.example.bookstore.config.DeadLetterService;
import com.example.bookstore.config.KafkaTopicConfig;
import com.example.bookstore.event.repository.ProcessedEventRepository;
import com.example.bookstore.messaging.DomainEventEnvelope;
import com.example.bookstore.messaging.metrics.MessagingMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(value = "spring.kafka.bootstrap-servers")
public class InventoryEventConsumer extends AbstractIdempotentConsumer {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventConsumer.class);

    public InventoryEventConsumer(ProcessedEventRepository processedEventRepository,
                                   MessagingMetrics messagingMetrics,
                                   DeadLetterService deadLetterService) {
        super(processedEventRepository, messagingMetrics, KafkaTopicConfig.CONSUMER_GROUP_INVENTORY,
                deadLetterService, KafkaTopicConfig.TOPIC_INVENTORY);
    }

    @KafkaListener(topics = KafkaTopicConfig.TOPIC_INVENTORY,
                   groupId = KafkaTopicConfig.CONSUMER_GROUP_INVENTORY,
                   containerFactory = "kafkaListenerContainerFactory")
    public void onInventoryEvent(String message) {
        processEvent(message, "inventory");
    }

    @Override
    protected void doProcess(DomainEventEnvelope envelope) {
        String eventType = envelope.eventType();
        log.info("Processing inventory event: type={}, aggregateId={}, eventId={}",
                eventType, envelope.aggregateId(), envelope.eventId());
    }
}
