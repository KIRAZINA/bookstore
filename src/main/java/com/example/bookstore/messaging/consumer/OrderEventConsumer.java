package com.example.bookstore.messaging.consumer;

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
@ConditionalOnProperty("spring.kafka.bootstrap-servers")
public class OrderEventConsumer extends AbstractIdempotentConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    public OrderEventConsumer(ProcessedEventRepository processedEventRepository,
                               MessagingMetrics messagingMetrics) {
        super(processedEventRepository, messagingMetrics, KafkaTopicConfig.CONSUMER_GROUP_ORDERS);
    }

    @KafkaListener(topics = KafkaTopicConfig.TOPIC_ORDERS,
                   groupId = KafkaTopicConfig.CONSUMER_GROUP_ORDERS,
                   containerFactory = "kafkaListenerContainerFactory")
    public void onOrderEvent(String message) {
        processEvent(message, "order");
    }

    @Override
    protected void doProcess(DomainEventEnvelope envelope) {
        String eventType = envelope.eventType();
        log.info("Processing order event: type={}, aggregateId={}, eventId={}",
                eventType, envelope.aggregateId(), envelope.eventId());
    }
}
