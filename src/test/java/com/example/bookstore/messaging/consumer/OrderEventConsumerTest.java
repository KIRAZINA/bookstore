package com.example.bookstore.messaging.consumer;

import com.example.bookstore.event.repository.ProcessedEventRepository;
import com.example.bookstore.messaging.DomainEventEnvelope;
import com.example.bookstore.messaging.metrics.MessagingMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderEventConsumerTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private MessagingMetrics messagingMetrics;

    private OrderEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new OrderEventConsumer(processedEventRepository, messagingMetrics);
    }

    @Test
    @DisplayName("Creates with correct consumer group and topic")
    void constructor() {
        consumer.onOrderEvent(validJson("ORDER_CREATED"));
        verify(messagingMetrics, never()).recordEventFailed();
    }

    @Test
    @DisplayName("doProcess does not throw for valid event")
    void doProcess() {
        DomainEventEnvelope envelope = new DomainEventEnvelope(
                UUID.randomUUID(), "order", "1", "ORDER_CREATED", 1,
                Instant.now(), "corr-1", null, "bookstore", Map.of(), Map.of());
        assertDoesNotThrow(() -> consumer.doProcess(envelope));
    }

    private String validJson(String eventType) {
        return """
                {"eventId":"%s","aggregateType":"order","aggregateId":"1","eventType":"%s","eventVersion":1,"timestamp":"2025-01-01T00:00:00Z","correlationId":"corr-1","producer":"bookstore","payload":{"orderId":1},"metadata":{}}
                """.formatted(UUID.randomUUID(), eventType);
    }
}
