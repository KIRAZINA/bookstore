package com.example.bookstore.messaging.consumer;

import com.example.bookstore.event.ProcessedEvent;
import com.example.bookstore.event.repository.ProcessedEventRepository;
import com.example.bookstore.messaging.DomainEventEnvelope;
import com.example.bookstore.messaging.metrics.MessagingMetrics;
import com.example.bookstore.notification.email.EmailService;
import com.example.bookstore.notification.template.EmailTemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationEventConsumerTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private MessagingMetrics messagingMetrics;
    @Mock
    private EmailTemplateEngine templateEngine;
    @Mock
    private EmailService emailService;

    private NotificationEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new NotificationEventConsumer(processedEventRepository, messagingMetrics,
                templateEngine, emailService);
    }

    private DomainEventEnvelope envelope(String eventType, Map<String, Object> payload) {
        return new DomainEventEnvelope(UUID.randomUUID(), "notification", "1", eventType,
                1, Instant.now(), "corr-1", null, "bookstore", payload, Map.of());
    }

    @Nested
    @DisplayName("doProcess")
    class DoProcess {
        @Test
        @DisplayName("Handles ORDER_CREATED event")
        void orderCreated() {
            when(templateEngine.render(anyString(), anyMap())).thenReturn("<html>body</html>");
            Map<String, Object> data = Map.of(
                    "orderId", 1, "userName", "test", "userEmail", "a@b.com", "totalPrice", 99.99);
            consumer.doProcess(envelope("ORDER_CREATED", data));

            verify(templateEngine).render(eq("order-confirmation"), anyMap());
            verify(emailService).sendEmail("a@b.com", "Order Confirmation #1", "<html>body</html>", "ORDER_CONFIRMATION");
        }

        @Test
        @DisplayName("Handles ORDER_STATUS_CHANGED event")
        void orderStatusChanged() {
            when(templateEngine.render(anyString(), anyMap())).thenReturn("<html>body</html>");
            Map<String, Object> data = Map.of(
                    "orderId", 1, "userName", "test", "userEmail", "a@b.com",
                    "previousStatus", "PENDING", "newStatus", "PAID");
            consumer.doProcess(envelope("ORDER_STATUS_CHANGED", data));

            verify(templateEngine).render(eq("order-status-changed"), anyMap());
            verify(emailService).sendEmail("a@b.com", "Order Status Update #1", "<html>body</html>", "ORDER_STATUS_CHANGED");
        }

        @Test
        @DisplayName("Handles PAYMENT_COMPLETED event")
        void paymentCompleted() {
            when(templateEngine.render(anyString(), anyMap())).thenReturn("<html>body</html>");
            Map<String, Object> data = Map.of(
                    "orderId", 1, "userName", "test", "userEmail", "a@b.com",
                    "amount", 49.99, "transactionId", "TXN-123");
            consumer.doProcess(envelope("PAYMENT_COMPLETED", data));

            verify(templateEngine).render(eq("payment-success"), anyMap());
            verify(emailService).sendEmail("a@b.com", "Payment Successful #1", "<html>body</html>", "PAYMENT_SUCCESS");
        }

        @Test
        @DisplayName("Handles PAYMENT_FAILED event")
        void paymentFailed() {
            when(templateEngine.render(anyString(), anyMap())).thenReturn("<html>body</html>");
            Map<String, Object> data = Map.of(
                    "orderId", 1, "userName", "test", "userEmail", "a@b.com",
                    "amount", 49.99, "failureReason", "Insufficient funds");
            consumer.doProcess(envelope("PAYMENT_FAILED", data));

            verify(templateEngine).render(eq("payment-failed"), anyMap());
            verify(emailService).sendEmail("a@b.com", "Payment Failed #1", "<html>body</html>", "PAYMENT_FAILED");
        }

        @Test
        @DisplayName("Handles PAYMENT_REFUNDED event")
        void paymentRefunded() {
            when(templateEngine.render(anyString(), anyMap())).thenReturn("<html>body</html>");
            Map<String, Object> data = Map.of(
                    "orderId", 1, "userName", "test", "userEmail", "a@b.com",
                    "amount", 49.99, "transactionId", "TXN-REF-123");
            consumer.doProcess(envelope("PAYMENT_REFUNDED", data));

            verify(templateEngine).render(eq("refund-notification"), anyMap());
            verify(emailService).sendEmail("a@b.com", "Refund Processed #1", "<html>body</html>", "REFUND_NOTIFICATION");
        }

        @Test
        @DisplayName("Handles unknown event type gracefully")
        void unknownEventType() {
            Map<String, Object> data = Map.of("orderId", 1);
            consumer.doProcess(envelope("UNKNOWN_EVENT", data));

            verify(templateEngine, never()).render(anyString(), anyMap());
            verify(emailService, never()).sendEmail(anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("Uses empty string when email is null")
        void nullEmail() {
            HashMap<String, Object> data = new HashMap<>();
            data.put("orderId", 1);
            data.put("userName", "test");
            data.put("userEmail", null);
            data.put("totalPrice", 99.99);
            when(templateEngine.render(anyString(), anyMap())).thenReturn("<html>body</html>");
            consumer.doProcess(envelope("ORDER_CREATED", data));

            verify(emailService).sendEmail(eq(""), anyString(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("onNotificationEvent delegation")
    class OnNotificationEvent {
        @Test
        @DisplayName("Calls processEvent with correct JSON")
        void delegates() {
            when(processedEventRepository.saveAndFlush(any(ProcessedEvent.class)))
                    .thenReturn(mock(ProcessedEvent.class));
            consumer.onNotificationEvent(validJson("NOTIFICATION_SEND"));

            verify(processedEventRepository).saveAndFlush(any(ProcessedEvent.class));
        }

        private String validJson(String eventType) {
            return """
                    {"eventId":"%s","aggregateType":"notification","aggregateId":"1","eventType":"%s","eventVersion":1,"timestamp":"2025-01-01T00:00:00Z","correlationId":"corr-1","producer":"bookstore","payload":{},"metadata":{}}
                    """.formatted(UUID.randomUUID(), eventType);
        }
    }
}
