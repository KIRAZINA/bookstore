package com.example.bookstore.config;

import com.example.bookstore.messaging.DomainEventEnvelope;
import com.example.bookstore.messaging.EnvelopeConverter;
import com.example.bookstore.messaging.metrics.MessagingMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeadLetterServiceTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;
    @Mock
    private MessagingMetrics messagingMetrics;

    private DeadLetterService deadLetterService;

    @BeforeEach
    void setUp() {
        deadLetterService = new DeadLetterService(kafkaTemplate, messagingMetrics);
    }

    @Nested
    @DisplayName("sendToDlt")
    class SendToDlt {
        @Test
        @DisplayName("Sends enriched envelope to DLT topic")
        void sendsToDlt() throws Exception {
            java.util.concurrent.CompletableFuture<SendResult<String, String>> future =
                    java.util.concurrent.CompletableFuture.completedFuture(mock(SendResult.class));
            when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(future);

            deadLetterService.sendToDlt("orders", "key1", """
                    {"eventId":"%s","aggregateType":"order","aggregateId":"1","eventType":"ORDER_CREATED","eventVersion":1,"timestamp":"2025-01-01T00:00:00Z","correlationId":"corr-1","producer":"bookstore","payload":{"orderId":1},"metadata":{}}
                    """.formatted(UUID.randomUUID()),
                    new RuntimeException("Processing failed"), 0, 123L, "orders-group", 2);

            ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
            verify(kafkaTemplate).send(topicCaptor.capture(), keyCaptor.capture(), valueCaptor.capture());

            assertEquals(KafkaTopicConfig.TOPIC_DLT, topicCaptor.getValue());
            assertEquals("key1", keyCaptor.getValue());

            DomainEventEnvelope envelope = EnvelopeConverter.fromJson(valueCaptor.getValue());
            assertEquals("order", envelope.aggregateType());
            assertEquals("ORDER_CREATED", envelope.eventType());
            assertTrue(envelope.metadata().containsKey("dlt_original_topic"));
            assertTrue(envelope.metadata().containsKey("dlt_error_message"));
            assertTrue(envelope.metadata().containsKey("dlt_error_type"));
            assertTrue(envelope.metadata().containsKey("dlt_partition"));
            assertTrue(envelope.metadata().containsKey("dlt_offset"));
            assertTrue(envelope.metadata().containsKey("dlt_consumer_group"));
            assertTrue(envelope.metadata().containsKey("dlt_delivery_attempt"));
            assertEquals("orders", envelope.metadata().get("dlt_original_topic"));
            assertEquals(2, envelope.metadata().get("dlt_delivery_attempt"));

            verify(messagingMetrics).recordEventSentToDlt();
        }

        @Test
        @DisplayName("Does not throw when Kafka send fails")
        void kafkaFailure() {
            assertDoesNotThrow(() ->
                    deadLetterService.sendToDlt("orders", "key1", "{}",
                            new RuntimeException("fail"), 0, 0L, "group", 1));
        }

        @Test
        @DisplayName("Does not throw when JSON is invalid")
        void invalidJson() {
            assertDoesNotThrow(() ->
                    deadLetterService.sendToDlt("orders", "key1", "not-json",
                            new RuntimeException("fail"), 0, 0L, "group", 1));
        }
    }
}
