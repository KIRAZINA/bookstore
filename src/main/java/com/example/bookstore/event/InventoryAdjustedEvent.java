package com.example.bookstore.event;

public class InventoryAdjustedEvent extends BaseEvent {
    private final Long bookId;
    private final int previousQuantity;
    private final int newQuantity;
    private final String reason;
    private final String changedBy;

    public InventoryAdjustedEvent(Long bookId, int previousQuantity, int newQuantity,
                                  String reason, String changedBy) {
        this.bookId = bookId;
        this.previousQuantity = previousQuantity;
        this.newQuantity = newQuantity;
        this.reason = reason;
        this.changedBy = changedBy;
    }

    public Long getBookId() { return bookId; }
    public int getPreviousQuantity() { return previousQuantity; }
    public int getNewQuantity() { return newQuantity; }
    public String getReason() { return reason; }
    public String getChangedBy() { return changedBy; }
}
