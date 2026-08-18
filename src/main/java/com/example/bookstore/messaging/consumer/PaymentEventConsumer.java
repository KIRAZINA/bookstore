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
public class PaymentEventConsumer extends AbstractIdempotentConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    public PaymentEventConsumer(ProcessedEventRepository processedEventRepository,
                                 MessagingMetrics messagingMetrics) {
        super(processedEventRepository, messagingMetrics, KafkaTopicConfig.CONSUMER_GROUP_PAYMENTS);
    }

    @KafkaListener(topics = KafkaTopicConfig.TOPIC_PAYMENTS,
                   groupId = KafkaTopicConfig.CONSUMER_GROUP_PAYMENTS,
                   containerFactory = "kafkaListenerContainerFactory")
    public void onPaymentEvent(String message) {
        processEvent(message, "payment");
    }

    @Override
    protected void doProcess(DomainEventEnvelope envelope) {
        String eventType = envelope.eventType();
        log.info("Processing payment event: type={}, aggregateId={}, eventId={}",
                eventType, envelope.aggregateId(), envelope.eventId());
    }
}
