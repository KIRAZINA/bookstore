package com.example.bookstore.controller.dto;

import java.math.BigDecimal;
import java.util.List;

public class CartResponse {
    private Long id;
    private Long userId;
    private List<CartItemResponse> items;
    private Integer totalItems;
    private BigDecimal totalPrice;

    /** No-arg constructor required for JSON deserialization. */
    public CartResponse() {}

    public CartResponse(Long id, Long userId, List<CartItemResponse> items) {
        this.id = id;
        this.userId = userId;
        this.items = items;
        this.totalItems = items != null ? items.stream().mapToInt(CartItemResponse::getQuantity).sum() : 0;
        this.totalPrice = items != null ? items.stream()
            .map(CartItemResponse::getSubtotal)
            .reduce(BigDecimal.ZERO, BigDecimal::add) : BigDecimal.ZERO;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public List<CartItemResponse> getItems() { return items; }
    public void setItems(List<CartItemResponse> items) { this.items = items; }
    public Integer getTotalItems() { return totalItems; }
    public void setTotalItems(Integer totalItems) { this.totalItems = totalItems; }
    public BigDecimal getTotalPrice() { return totalPrice; }
    public void setTotalPrice(BigDecimal totalPrice) { this.totalPrice = totalPrice; }
}
