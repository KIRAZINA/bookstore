package com.example.bookstore.event;

import java.math.BigDecimal;

public class PaymentRefundedEvent extends PaymentResultEvent {

    public PaymentRefundedEvent(Long paymentId, Long orderId, Long userId, String userName,
                                String userEmail, BigDecimal amount, String transactionId) {
        super(paymentId, orderId, userId, userName, userEmail, amount, transactionId);
    }
}