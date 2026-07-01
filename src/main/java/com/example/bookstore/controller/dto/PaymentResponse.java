package com.example.bookstore.controller.dto;

import com.example.bookstore.model.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class PaymentResponse {
    private Long id;
    private Long orderId;
    private String paymentProvider;
    private String paymentMethod;
    private PaymentStatus paymentStatus;
    private BigDecimal amount;
    private String currency;
    private String transactionId;
    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;

    public PaymentResponse(Long id, Long orderId, String paymentProvider, String paymentMethod,
                           PaymentStatus paymentStatus, BigDecimal amount, String currency,
                           String transactionId, String failureReason,
                           LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime completedAt) {
        this.id = id;
        this.orderId = orderId;
        this.paymentProvider = paymentProvider;
        this.paymentMethod = paymentMethod;
        this.paymentStatus = paymentStatus;
        this.amount = amount;
        this.currency = currency;
        this.transactionId = transactionId;
        this.failureReason = failureReason;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.completedAt = completedAt;
    }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public String getPaymentProvider() { return paymentProvider; }
    public String getPaymentMethod() { return paymentMethod; }
    public PaymentStatus getPaymentStatus() { return paymentStatus; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getTransactionId() { return transactionId; }
    public String getFailureReason() { return failureReason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
}
