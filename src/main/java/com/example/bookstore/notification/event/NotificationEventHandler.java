package com.example.bookstore.notification.event;

import com.example.bookstore.event.*;
import com.example.bookstore.notification.email.EmailService;
import com.example.bookstore.notification.template.EmailTemplateEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * In-process notification delivery: renders and sends emails after the business transaction
 * commits. Active only when {@code app.notifications.delivery-mode=in-process} (the default
 * in test and no-Kafka environments). In {@code kafka} mode, email delivery is handled by
 * {@link com.example.bookstore.messaging.consumer.NotificationEventConsumer} instead, so the
 * two paths never both fire for the same event.
 */
@Component
@ConditionalOnProperty(prefix = "app.notifications", name = "delivery-mode",
        havingValue = "in-process", matchIfMissing = true)
public class NotificationEventHandler {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventHandler.class);

    private final EmailTemplateEngine templateEngine;
    private final EmailService emailService;

    public NotificationEventHandler(EmailTemplateEngine templateEngine, EmailService emailService) {
        this.templateEngine = templateEngine;
        this.emailService = emailService;
    }

    @Async("notificationTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrderCreated(OrderCreatedEvent event) {
        log.info("Handling OrderCreatedEvent: orderId={}", event.getOrderId());
        Map<String, Object> data = Map.of(
            "orderId", event.getOrderId(),
            "userName", event.getUserName(),
            "totalPrice", event.getTotalPrice()
        );
        String body = templateEngine.render("order-confirmation", data);
        emailService.sendEmail(event.getUserEmail(), "Order Confirmation #" + event.getOrderId(),
                body, "ORDER_CONFIRMATION");
    }

    @Async("notificationTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrderStatusChanged(OrderStatusChangedEvent event) {
        log.info("Handling OrderStatusChangedEvent: orderId={}, {} -> {}",
                event.getOrderId(), event.getPreviousStatus(), event.getNewStatus());
        Map<String, Object> data = Map.of(
            "orderId", event.getOrderId(),
            "userName", event.getUserName(),
            "previousStatus", event.getPreviousStatus(),
            "newStatus", event.getNewStatus()
        );
        String body = templateEngine.render("order-status-changed", data);
        emailService.sendEmail(event.getUserEmail(), "Order Status Update #" + event.getOrderId(),
                body, "ORDER_STATUS_CHANGED");
    }

    @Async("notificationTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePaymentCompleted(PaymentCompletedEvent event) {
        log.info("Handling PaymentCompletedEvent: paymentId={}, orderId={}", event.getPaymentId(), event.getOrderId());
        Map<String, Object> data = Map.of(
            "orderId", event.getOrderId(),
            "userName", event.getUserName(),
            "amount", event.getAmount(),
            "transactionId", event.getTransactionId()
        );
        String body = templateEngine.render("payment-success", data);
        emailService.sendEmail(event.getUserEmail(), "Payment Successful #" + event.getOrderId(),
                body, "PAYMENT_SUCCESS");
    }

    @Async("notificationTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePaymentFailed(PaymentFailedEvent event) {
        log.info("Handling PaymentFailedEvent: paymentId={}, orderId={}", event.getPaymentId(), event.getOrderId());
        Map<String, Object> data = Map.of(
            "orderId", event.getOrderId(),
            "userName", event.getUserName(),
            "amount", event.getAmount(),
            "failureReason", event.getFailureReason()
        );
        String body = templateEngine.render("payment-failed", data);
        emailService.sendEmail(event.getUserEmail(), "Payment Failed #" + event.getOrderId(),
                body, "PAYMENT_FAILED");
    }

    @Async("notificationTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePaymentRefunded(PaymentRefundedEvent event) {
        log.info("Handling PaymentRefundedEvent: paymentId={}, orderId={}", event.getPaymentId(), event.getOrderId());
        Map<String, Object> data = Map.of(
            "orderId", event.getOrderId(),
            "userName", event.getUserName(),
            "amount", event.getAmount(),
            "transactionId", event.getTransactionId()
        );
        String body = templateEngine.render("refund-notification", data);
        emailService.sendEmail(event.getUserEmail(), "Refund Processed #" + event.getOrderId(),
                body, "REFUND_NOTIFICATION");
    }
}
