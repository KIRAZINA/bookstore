package com.example.bookstore.controller.dto;

import com.example.bookstore.model.InventoryEventType;

import java.time.LocalDateTime;

public class InventoryHistoryResponse {
    private Long id;
    private Long bookId;
    private InventoryEventType eventType;
    private Integer previousAvailable;
    private Integer newAvailable;
    private Integer previousReserved;
    private Integer newReserved;
    private int quantityChange;
    private Long orderId;
    private Long paymentId;
    private String changedBy;
    private String reason;
    private LocalDateTime createdAt;

    public InventoryHistoryResponse() {}

    public InventoryHistoryResponse(Long id, Long bookId, InventoryEventType eventType,
                                    Integer previousAvailable, Integer newAvailable,
                                    Integer previousReserved, Integer newReserved,
                                    int quantityChange, Long orderId, Long paymentId,
                                    String changedBy, String reason, LocalDateTime createdAt) {
        this.id = id;
        this.bookId = bookId;
        this.eventType = eventType;
        this.previousAvailable = previousAvailable;
        this.newAvailable = newAvailable;
        this.previousReserved = previousReserved;
        this.newReserved = newReserved;
        this.quantityChange = quantityChange;
        this.orderId = orderId;
        this.paymentId = paymentId;
        this.changedBy = changedBy;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getBookId() { return bookId; }
    public void setBookId(Long bookId) { this.bookId = bookId; }
    public InventoryEventType getEventType() { return eventType; }
    public void setEventType(InventoryEventType eventType) { this.eventType = eventType; }
    public Integer getPreviousAvailable() { return previousAvailable; }
    public void setPreviousAvailable(Integer previousAvailable) { this.previousAvailable = previousAvailable; }
    public Integer getNewAvailable() { return newAvailable; }
    public void setNewAvailable(Integer newAvailable) { this.newAvailable = newAvailable; }
    public Integer getPreviousReserved() { return previousReserved; }
    public void setPreviousReserved(Integer previousReserved) { this.previousReserved = previousReserved; }
    public Integer getNewReserved() { return newReserved; }
    public void setNewReserved(Integer newReserved) { this.newReserved = newReserved; }
    public int getQuantityChange() { return quantityChange; }
    public void setQuantityChange(int quantityChange) { this.quantityChange = quantityChange; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Long getPaymentId() { return paymentId; }
    public void setPaymentId(Long paymentId) { this.paymentId = paymentId; }
    public String getChangedBy() { return changedBy; }
    public void setChangedBy(String changedBy) { this.changedBy = changedBy; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
