package com.example.bookstore.controller.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
public class CartItemResponse {
    private Long id;
    private Long bookId;
    private String bookTitle;
    private String bookAuthor;
    private Integer quantity;
    private BigDecimal priceAtAdd;
    private LocalDateTime addedAt;
    private BigDecimal subtotal;

    /** No-arg constructor required for JSON deserialization. */
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
}