package com.example.bookstore.controller.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class OrderItemResponse {
    private Long id;
    private Long bookId;
    private String bookTitle;
    private String bookAuthor;
    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal subtotal;

    /** No-arg constructor required for JSON deserialization. */
    public OrderItemResponse() {}

    public OrderItemResponse(Long id, Long bookId, String bookTitle, String bookAuthor,
                            Integer quantity, BigDecimal unitPrice, BigDecimal subtotal) {
        this.id = id;
        this.bookId = bookId;
        this.bookTitle = bookTitle;
        this.bookAuthor = bookAuthor;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.subtotal = subtotal;
    }
}