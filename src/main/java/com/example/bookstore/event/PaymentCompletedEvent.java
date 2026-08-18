package com.example.bookstore.event;

import java.math.BigDecimal;

public class PaymentCompletedEvent extends PaymentResultEvent {

    public PaymentCompletedEvent(Long paymentId, Long orderId, Long userId, String userName,
                                 String userEmail, BigDecimal amount, String transactionId) {
        super(paymentId, orderId, userId, userName, userEmail, amount, transactionId);
    }
}