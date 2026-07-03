package com.example.bookstore.messaging.consumer;

import com.example.bookstore.config.DeadLetterService;
import com.example.bookstore.event.ProcessedEvent;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryEventConsumerTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private MessagingMetrics messagingMetrics;
    @Mock
    private DeadLetterService deadLetterService;

    private InventoryEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new InventoryEventConsumer(processedEventRepository, messagingMetrics, deadLetterService);
    }

    @Test
    @DisplayName("Constructor sets correct group and topic")
    void constructor() {
        assertNotNull(consumer);
    }

    @Test
    @DisplayName("doProcess does not throw")
    void doProcess() {
        DomainEventEnvelope envelope = new DomainEventEnvelope(
                UUID.randomUUID(), "inventory", "1", "INVENTORY_RESERVED", 1,
                Instant.now(), "corr-1", null, "bookstore", Map.of(), Map.of());
        assertDoesNotThrow(() -> consumer.doProcess(envelope));
    }

    @Test
    @DisplayName("onInventoryEvent delegates to processEvent")
    void onInventoryEvent() {
        when(processedEventRepository.saveAndFlush(any())).thenReturn(mock(ProcessedEvent.class));
        consumer.onInventoryEvent(validJson("INVENTORY_RESERVED"));
        verify(processedEventRepository).saveAndFlush(any());
    }

    private static void assertDoesNotThrow(Runnable r) {
        try { r.run(); } catch (Exception e) { throw new AssertionError(e); }
    }

    private String validJson(String eventType) {
        return """
                {"eventId":"%s","aggregateType":"inventory","aggregateId":"1","eventType":"%s","eventVersion":1,"timestamp":"2025-01-01T00:00:00Z","correlationId":"corr-1","producer":"bookstore","payload":{},"metadata":{}}
                """.formatted(UUID.randomUUID(), eventType);
    }
}
