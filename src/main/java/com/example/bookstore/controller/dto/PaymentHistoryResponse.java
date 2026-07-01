package com.example.bookstore.controller.dto;

import com.example.bookstore.model.PaymentStatus;

import java.time.LocalDateTime;

public class PaymentHistoryResponse {
    private Long id;
    private PaymentStatus previousStatus;
    private PaymentStatus newStatus;
    private String changedBy;
    private String reason;
    private LocalDateTime createdAt;

    public PaymentHistoryResponse(Long id, PaymentStatus previousStatus, PaymentStatus newStatus,
                                  String changedBy, String reason, LocalDateTime createdAt) {
        this.id = id;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.changedBy = changedBy;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public PaymentStatus getPreviousStatus() { return previousStatus; }
    public PaymentStatus getNewStatus() { return newStatus; }
    public String getChangedBy() { return changedBy; }
    public String getReason() { return reason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
