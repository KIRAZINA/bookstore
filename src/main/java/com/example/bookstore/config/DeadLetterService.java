package com.example.bookstore.config;

import com.example.bookstore.messaging.DomainEventEnvelope;
import com.example.bookstore.messaging.EnvelopeConverter;
import com.example.bookstore.messaging.metrics.MessagingMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty("spring.kafka.bootstrap-servers")
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MessagingMetrics messagingMetrics;

    public DeadLetterService(KafkaTemplate<String, String> kafkaTemplate,
                              MessagingMetrics messagingMetrics) {
        this.kafkaTemplate = kafkaTemplate;
        this.messagingMetrics = messagingMetrics;
    }

    public void sendToDlt(String originalTopic, String key, String value, Throwable exception,
                           int partition, long offset, String consumerGroup, int deliveryAttempt) {
        try {
            DomainEventEnvelope original = EnvelopeConverter.fromJson(value);
            Map<String, Object> metadata = new LinkedHashMap<>(original.metadata());
            metadata.put("dlt_original_topic", originalTopic);
            metadata.put("dlt_error_message", exception.getMessage());
            metadata.put("dlt_error_type", exception.getClass().getName());
            metadata.put("dlt_timestamp", System.currentTimeMillis());
            metadata.put("dlt_partition", partition);
            metadata.put("dlt_offset", offset);
            metadata.put("dlt_consumer_group", consumerGroup);
            metadata.put("dlt_delivery_attempt", deliveryAttempt);

            DomainEventEnvelope dltEnvelope = new DomainEventEnvelope(
                    UUID.randomUUID(),
                    original.aggregateType(),
                    original.aggregateId(),
                    original.eventType(),
                    original.eventVersion(),
                    original.timestamp(),
                    original.correlationId(),
                    original.traceId(),
                    original.producer(),
                    original.payload(),
                    metadata
            );

            String dltJson = EnvelopeConverter.toJson(dltEnvelope);
            kafkaTemplate.send(KafkaTopicConfig.TOPIC_DLT, key, dltJson)
                    .get(10, TimeUnit.SECONDS);

            messagingMetrics.recordEventSentToDlt();
            log.warn("Sent event to DLQ: topic={}, partition={}, offset={}, consumerGroup={}, eventType={}, key={}, error={}",
                    originalTopic, partition, offset, consumerGroup,
                    original.eventType(), key, exception.getMessage());

        } catch (Exception e) {
            log.error("Failed to send event to DLQ: topic={}, partition={}, offset={}, key={}",
                    originalTopic, partition, offset, key, e);
        }
    }
}
