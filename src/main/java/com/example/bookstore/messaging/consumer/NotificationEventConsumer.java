package com.example.bookstore.messaging.consumer;

import com.example.bookstore.config.KafkaTopicConfig;
import com.example.bookstore.event.repository.ProcessedEventRepository;
import com.example.bookstore.messaging.DomainEventEnvelope;
import com.example.bookstore.messaging.metrics.MessagingMetrics;
import com.example.bookstore.notification.email.EmailService;
import com.example.bookstore.notification.template.EmailTemplateEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ConditionalOnProperty(value = "spring.kafka.bootstrap-servers")
public class NotificationEventConsumer extends AbstractIdempotentConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventConsumer.class);

    private final EmailTemplateEngine templateEngine;
    private final EmailService emailService;

    public NotificationEventConsumer(ProcessedEventRepository processedEventRepository,
                                      MessagingMetrics messagingMetrics,
                                      EmailTemplateEngine templateEngine,
                                      EmailService emailService) {
        super(processedEventRepository, messagingMetrics, KafkaTopicConfig.CONSUMER_GROUP_NOTIFICATIONS);
        this.templateEngine = templateEngine;
        this.emailService = emailService;
    }

    @KafkaListener(topics = KafkaTopicConfig.TOPIC_NOTIFICATIONS,
                   groupId = KafkaTopicConfig.CONSUMER_GROUP_NOTIFICATIONS,
                   containerFactory = "kafkaListenerContainerFactory")
    public void onNotificationEvent(String message) {
        processEvent(message, "notification");
    }

    @Override
    protected void doProcess(DomainEventEnvelope envelope) {
        String eventType = envelope.eventType();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = envelope.payload();

        switch (eventType) {
            case "ORDER_CREATED" -> handleOrderCreated(payload);
            case "ORDER_STATUS_CHANGED" -> handleOrderStatusChanged(payload);
            case "PAYMENT_COMPLETED" -> handlePaymentCompleted(payload);
            case "PAYMENT_FAILED" -> handlePaymentFailed(payload);
            case "PAYMENT_REFUNDED" -> handlePaymentRefunded(payload);
            default -> log.warn("Unknown notification event type: {}", eventType);
        }
    }

    private void handleOrderCreated(Map<String, Object> data) {
        Map<String, Object> templateData = Map.of(
            "orderId", data.get("orderId"),
            "userName", data.get("userName"),
            "totalPrice", data.get("totalPrice"));
        String body = templateEngine.render("order-confirmation", templateData);
        emailService.sendEmail(
                stringOr(data.get("userEmail")),
                "Order Confirmation #" + data.get("orderId"),
                body, "ORDER_CONFIRMATION");
    }

    private void handleOrderStatusChanged(Map<String, Object> data) {
        Map<String, Object> templateData = Map.of(
            "orderId", data.get("orderId"),
            "userName", data.get("userName"),
            "previousStatus", data.get("previousStatus"),
            "newStatus", data.get("newStatus"));
        String body = templateEngine.render("order-status-changed", templateData);
        emailService.sendEmail(
                stringOr(data.get("userEmail")),
                "Order Status Update #" + data.get("orderId"),
                body, "ORDER_STATUS_CHANGED");
    }

    private void handlePaymentCompleted(Map<String, Object> data) {
        Map<String, Object> templateData = Map.of(
            "orderId", data.get("orderId"),
            "userName", data.get("userName"),
            "amount", data.get("amount"),
            "transactionId", data.get("transactionId"));
        String body = templateEngine.render("payment-success", templateData);
        emailService.sendEmail(
                stringOr(data.get("userEmail")),
                "Payment Successful #" + data.get("orderId"),
                body, "PAYMENT_SUCCESS");
    }

    private void handlePaymentFailed(Map<String, Object> data) {
        Map<String, Object> templateData = Map.of(
            "orderId", data.get("orderId"),
            "userName", data.get("userName"),
            "amount", data.get("amount"),
            "failureReason", data.get("failureReason"));
        String body = templateEngine.render("payment-failed", templateData);
        emailService.sendEmail(
                stringOr(data.get("userEmail")),
                "Payment Failed #" + data.get("orderId"),
                body, "PAYMENT_FAILED");
    }

    private void handlePaymentRefunded(Map<String, Object> data) {
        Map<String, Object> templateData = Map.of(
            "orderId", data.get("orderId"),
            "userName", data.get("userName"),
            "amount", data.get("amount"),
            "transactionId", data.get("transactionId"));
        String body = templateEngine.render("refund-notification", templateData);
        emailService.sendEmail(
                stringOr(data.get("userEmail")),
                "Refund Processed #" + data.get("orderId"),
                body, "REFUND_NOTIFICATION");
    }

    private static String stringOr(Object value) {
        return value != null ? value.toString() : "";
    }
}
