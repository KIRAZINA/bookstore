package com.example.bookstore.controller.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class CartItemResponse {
    private Long id;
    private Long bookId;
    private String bookTitle;
    private String bookAuthor;
    private Integer quantity;
    private BigDecimal priceAtAdd;
    private LocalDateTime addedAt;
    private BigDecimal subtotal;

    public CartItemResponse() {}

    public CartItemResponse(Long id, Long bookId, String bookTitle, String bookAuthor, 
                          Integer quantity, BigDecimal priceAtAdd, LocalDateTime addedAt) {
        this.id = id;
        this.bookId = bookId;
        this.bookTitle = bookTitle;
        this.bookAuthor = bookAuthor;
        this.quantity = quantity;
        this.priceAtAdd = priceAtAdd;
        this.addedAt = addedAt;
        if (priceAtAdd != null && quantity != null) {
            this.subtotal = priceAtAdd.multiply(BigDecimal.valueOf(quantity));
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getBookId() { return bookId; }
    public void setBookId(Long bookId) { this.bookId = bookId; }
    public String getBookTitle() { return bookTitle; }
    public void setBookTitle(String bookTitle) { this.bookTitle = bookTitle; }
    public String getBookAuthor() { return bookAuthor; }
    public void setBookAuthor(String bookAuthor) { this.bookAuthor = bookAuthor; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public BigDecimal getPriceAtAdd() { return priceAtAdd; }
    public void setPriceAtAdd(BigDecimal priceAtAdd) { this.priceAtAdd = priceAtAdd; }
    public LocalDateTime getAddedAt() { return addedAt; }
    public void setAddedAt(LocalDateTime addedAt) { this.addedAt = addedAt; }
    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }
}
