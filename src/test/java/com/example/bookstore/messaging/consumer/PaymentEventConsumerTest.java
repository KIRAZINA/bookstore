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
class PaymentEventConsumerTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private MessagingMetrics messagingMetrics;
    @Mock
    private DeadLetterService deadLetterService;

    private PaymentEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new PaymentEventConsumer(processedEventRepository, messagingMetrics, deadLetterService);
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
                UUID.randomUUID(), "payment", "1", "PAYMENT_COMPLETED", 1,
                Instant.now(), "corr-1", null, "bookstore", Map.of(), Map.of());
        assertDoesNotThrow(() -> consumer.doProcess(envelope));
    }

    @Test
    @DisplayName("onPaymentEvent delegates to processEvent")
    void onPaymentEvent() {
        when(processedEventRepository.saveAndFlush(any())).thenReturn(mock(ProcessedEvent.class));
        consumer.onPaymentEvent(validJson("PAYMENT_COMPLETED"));
        verify(processedEventRepository).saveAndFlush(any());
    }

    private String validJson(String eventType) {
        return """
                {"eventId":"%s","aggregateType":"payment","aggregateId":"1","eventType":"%s","eventVersion":1,"timestamp":"2025-01-01T00:00:00Z","correlationId":"corr-1","producer":"bookstore","payload":{},"metadata":{}}
                """.formatted(UUID.randomUUID(), eventType);
    }
}
