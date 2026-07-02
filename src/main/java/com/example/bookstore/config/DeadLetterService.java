package com.example.bookstore.config;

import com.example.bookstore.config.KafkaTopicConfig;
import com.example.bookstore.messaging.DomainEventEnvelope;
import com.example.bookstore.messaging.EnvelopeConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(value = "spring.kafka.bootstrap-servers")
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    private final KafkaTemplate<String, String> kafkaTemplate;

    public DeadLetterService(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void sendToDlt(String originalTopic, String key, String value, Throwable exception) {
        try {
            DomainEventEnvelope original = EnvelopeConverter.fromJson(value);
            Map<String, Object> metadata = new LinkedHashMap<>(original.metadata());
            metadata.put("dlt_original_topic", originalTopic);
            metadata.put("dlt_error_message", exception.getMessage());
            metadata.put("dlt_error_type", exception.getClass().getName());
            metadata.put("dlt_timestamp", System.currentTimeMillis());

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
            kafkaTemplate.send(KafkaTopicConfig.TOPIC_DLT, key, dltJson);
            log.warn("Sent event to DLQ: topic={}, eventType={}, key={}, error={}",
                    originalTopic, original.eventType(), key, exception.getMessage());

        } catch (Exception e) {
            log.error("Failed to send event to DLQ: topic={}, key={}", originalTopic, key, e);
        }
    }
}
