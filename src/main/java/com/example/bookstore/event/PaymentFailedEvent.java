package com.example.bookstore.event;

import java.math.BigDecimal;

public class PaymentFailedEvent extends BaseEvent {
    private final Long paymentId;
    private final Long orderId;
    private final Long userId;
    private final String userName;
    private final String userEmail;
    private final BigDecimal amount;
    private final String failureReason;

    public PaymentFailedEvent(Long paymentId, Long orderId, Long userId, String userName,
                              String userEmail, BigDecimal amount, String failureReason) {
        this.paymentId = paymentId;
        this.orderId = orderId;
        this.userId = userId;
        this.userName = userName;
        this.userEmail = userEmail;
        this.amount = amount;
        this.failureReason = failureReason;
    }

    public Long getPaymentId() { return paymentId; }
    public Long getOrderId() { return orderId; }
    public Long getUserId() { return userId; }
    public String getUserName() { return userName; }
    public String getUserEmail() { return userEmail; }
    public BigDecimal getAmount() { return amount; }
    public String getFailureReason() { return failureReason; }
}
