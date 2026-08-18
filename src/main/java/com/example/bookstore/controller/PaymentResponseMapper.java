package com.example.bookstore.controller;

import com.example.bookstore.controller.dto.PaymentHistoryResponse;
import com.example.bookstore.controller.dto.PaymentResponse;
import com.example.bookstore.model.Payment;
import com.example.bookstore.model.PaymentHistory;
import org.springframework.stereotype.Component;

@Component
public class PaymentResponseMapper {

    public PaymentResponse toResponse(Payment p) {
        return new PaymentResponse(
                p.getId(),
                p.getOrder().getId(),
                p.getPaymentProvider(),
                p.getPaymentMethod(),
                p.getPaymentStatus(),
                p.getAmount(),
                p.getCurrency(),
                p.getTransactionId(),
                p.getFailureReason(),
                p.getCreatedAt(),
                p.getUpdatedAt(),
                p.getCompletedAt()
        );
    }

    public PaymentHistoryResponse toHistoryResponse(PaymentHistory h) {
        return new PaymentHistoryResponse(
                h.getId(),
                h.getPreviousStatus(),
                h.getNewStatus(),
                h.getChangedBy(),
                h.getReason(),
                h.getCreatedAt()
        );
    }
}