package com.example.bookstore.messaging.consumer;

import com.example.bookstore.event.ProcessedEvent;
import com.example.bookstore.event.repository.ProcessedEventRepository;
import com.example.bookstore.messaging.DomainEventEnvelope;
import com.example.bookstore.messaging.metrics.MessagingMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AbstractIdempotentConsumerTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private MessagingMetrics messagingMetrics;

    private SpyConsumer consumer;
    private String validJson;

    @BeforeEach
    void setUp() {
        SpyConsumer.resetCallCount();
        consumer = new SpyConsumer(processedEventRepository, messagingMetrics);
        UUID eventId = UUID.randomUUID();
        validJson = """
                {"eventId":"%s","aggregateType":"order","aggregateId":"1","eventType":"ORDER_CREATED","eventVersion":1,"timestamp":"2025-01-01T00:00:00Z","correlationId":"corr-1","producer":"bookstore","payload":{"orderId":1},"metadata":{}}
                """.formatted(eventId);
    }

    @Nested
    @DisplayName("processEvent")
    class ProcessEvent {
        @Test
        @DisplayName("Saves processed event and delegates to doProcess")
        void success() {
            when(processedEventRepository.saveAndFlush(any(ProcessedEvent.class)))
                    .thenReturn(mock(ProcessedEvent.class));

            consumer.processEvent(validJson, "test");

            assertEquals(1, SpyConsumer.processedCount);
            verify(messagingMetrics).recordEventConsumed();
        }

        @Test
        @DisplayName("Skips processing on duplicate event (DataIntegrityViolationException)")
        void duplicateEvent() {
            when(processedEventRepository.saveAndFlush(any(ProcessedEvent.class)))
                    .thenThrow(new DataIntegrityViolationException("Duplicate key"));

            consumer.processEvent(validJson, "test");

            assertEquals(0, SpyConsumer.processedCount);
            verify(messagingMetrics, never()).recordEventConsumed();
        }

        @Test
        @DisplayName("Re-throws exception from doProcess and records failure")
        void doProcessFails() {
            when(processedEventRepository.saveAndFlush(any(ProcessedEvent.class)))
                    .thenReturn(mock(ProcessedEvent.class));
            consumer.throwOnProcess = true;

            assertThrows(RuntimeException.class, () -> consumer.processEvent(validJson, "test"));

            verify(messagingMetrics).recordEventFailed();
        }

        @Test
        @DisplayName("Records processing duration in finally block")
        void recordsDuration() {
            when(processedEventRepository.saveAndFlush(any(ProcessedEvent.class)))
                    .thenReturn(mock(ProcessedEvent.class));

            consumer.processEvent(validJson, "test");

            verify(messagingMetrics).recordEventProcessingDuration(anyLong());
        }

        @Test
        @DisplayName("Records duration even when processing fails")
        void recordsDurationOnFailure() {
            when(processedEventRepository.saveAndFlush(any(ProcessedEvent.class)))
                    .thenReturn(mock(ProcessedEvent.class));
            consumer.throwOnProcess = true;

            assertThrows(RuntimeException.class, () -> consumer.processEvent(validJson, "test"));

            verify(messagingMetrics).recordEventProcessingDuration(anyLong());
        }
    }

    static class SpyConsumer extends AbstractIdempotentConsumer {
        static int processedCount = 0;
        boolean throwOnProcess = false;

        SpyConsumer(ProcessedEventRepository repo, MessagingMetrics metrics) {
            super(repo, metrics, "test-group");
        }

        static void resetCallCount() {
            processedCount = 0;
        }

        @Override
        protected void doProcess(DomainEventEnvelope envelope) {
            processedCount++;
            if (throwOnProcess) {
                throw new RuntimeException("Processing error");
            }
        }
    }
}
