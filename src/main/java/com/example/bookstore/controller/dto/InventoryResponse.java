package com.example.bookstore.controller.dto;

import com.example.bookstore.model.InventoryStatus;

public class InventoryResponse {
    private Long bookId;
    private String bookTitle;
    private int availableQuantity;
    private int reservedQuantity;
    private int totalQuantity;
    private InventoryStatus status;

    /** No-arg constructor required for JSON deserialization. */
    public InventoryResponse() {}

    public InventoryResponse(Long bookId, String bookTitle, int availableQuantity,
                             int reservedQuantity, int totalQuantity, InventoryStatus status) {
        this.bookId = bookId;
        this.bookTitle = bookTitle;
        this.availableQuantity = availableQuantity;
        this.reservedQuantity = reservedQuantity;
        this.totalQuantity = totalQuantity;
        this.status = status;
    }

    public Long getBookId() { return bookId; }
    public void setBookId(Long bookId) { this.bookId = bookId; }
    public String getBookTitle() { return bookTitle; }
    public void setBookTitle(String bookTitle) { this.bookTitle = bookTitle; }
    public int getAvailableQuantity() { return availableQuantity; }
    public void setAvailableQuantity(int availableQuantity) { this.availableQuantity = availableQuantity; }
    public int getReservedQuantity() { return reservedQuantity; }
    public void setReservedQuantity(int reservedQuantity) { this.reservedQuantity = reservedQuantity; }
    public int getTotalQuantity() { return totalQuantity; }
    public void setTotalQuantity(int totalQuantity) { this.totalQuantity = totalQuantity; }
    public InventoryStatus getStatus() { return status; }
    public void setStatus(InventoryStatus status) { this.status = status; }
}
