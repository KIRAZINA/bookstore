package com.example.bookstore.messaging.consumer;

import com.example.bookstore.config.KafkaTopicConfig;
import com.example.bookstore.event.repository.ProcessedEventRepository;
import com.example.bookstore.messaging.DomainEventEnvelope;
import com.example.bookstore.messaging.metrics.MessagingMetrics;
import com.example.bookstore.service.AuditService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

@Component
@ConditionalOnProperty("spring.kafka.bootstrap-servers")
public class PaymentEventConsumer extends AbstractIdempotentConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final AuditService auditService;

    @Autowired
    public PaymentEventConsumer(ProcessedEventRepository processedEventRepository,
                                 MessagingMetrics messagingMetrics,
                                 AuditService auditService,
                                 PlatformTransactionManager transactionManager) {
        super(processedEventRepository, messagingMetrics,
                KafkaTopicConfig.CONSUMER_GROUP_PAYMENTS, transactionManager);
        this.auditService = auditService;
    }

    /**
     * Legacy constructor for unit tests that do not exercise transaction semantics.
     */
    PaymentEventConsumer(ProcessedEventRepository processedEventRepository,
                         MessagingMetrics messagingMetrics,
                         AuditService auditService) {
        this(processedEventRepository, messagingMetrics, auditService, null);
    }

    @KafkaListener(topics = KafkaTopicConfig.TOPIC_PAYMENTS,
                   groupId = KafkaTopicConfig.CONSUMER_GROUP_PAYMENTS,
                   containerFactory = "kafkaListenerContainerFactory")
    public void onPaymentEvent(ConsumerRecord<String, String> record) {
        processEvent(record.value(), record.headers());
    }

    @Override
    protected void doProcess(DomainEventEnvelope envelope) {
        log.info("Processing payment event: type={}, aggregateId={}, eventId={}",
                envelope.eventType(), envelope.aggregateId(), envelope.eventId());
        auditService.logEventConsumed(envelope.eventType(), envelope.aggregateId(),
                envelope.eventId().toString());
    }
}