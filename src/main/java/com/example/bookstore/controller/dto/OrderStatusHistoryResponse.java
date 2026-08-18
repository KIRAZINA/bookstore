package com.example.bookstore.controller.dto;

import com.example.bookstore.model.OrderStatus;
import java.time.LocalDateTime;

public class OrderStatusHistoryResponse {

    private Long id;
    private OrderStatus previousStatus;
    private OrderStatus newStatus;
    private String changedBy;
    private String comment;
    private LocalDateTime createdAt;

    /** No-arg constructor required for JSON deserialization. */
    public OrderStatusHistoryResponse() {}

    public OrderStatusHistoryResponse(Long id, OrderStatus previousStatus, OrderStatus newStatus,
                                      String changedBy, String comment, LocalDateTime createdAt) {
        this.id = id;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.changedBy = changedBy;
        this.comment = comment;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public OrderStatus getPreviousStatus() { return previousStatus; }
    public void setPreviousStatus(OrderStatus previousStatus) { this.previousStatus = previousStatus; }
    public OrderStatus getNewStatus() { return newStatus; }
    public void setNewStatus(OrderStatus newStatus) { this.newStatus = newStatus; }
    public String getChangedBy() { return changedBy; }
    public void setChangedBy(String changedBy) { this.changedBy = changedBy; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
