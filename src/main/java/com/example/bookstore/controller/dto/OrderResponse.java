package com.example.bookstore.controller.dto;

import com.example.bookstore.model.OrderStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class OrderResponse {
    private Long id;
    private Long userId;
    private String username;
    private OrderStatus status;
    private LocalDateTime createdAt;
    private List<OrderItemResponse> items;
    private BigDecimal totalPrice;
    private Integer totalItems;

    /** No-arg constructor required for JSON deserialization. */
    public OrderResponse() {}

    public OrderResponse(Long id, Long userId, String username, OrderStatus status,
                        LocalDateTime createdAt, List<OrderItemResponse> items, BigDecimal totalPrice) {
        this.id = id;
        this.userId = userId;
        this.username = username;
        this.status = status;
        this.createdAt = createdAt;
        this.items = items;
        this.totalPrice = totalPrice;
        this.totalItems = items != null ? items.stream().mapToInt(OrderItemResponse::getQuantity).sum() : 0;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public List<OrderItemResponse> getItems() { return items; }
    public void setItems(List<OrderItemResponse> items) { this.items = items; }
    public BigDecimal getTotalPrice() { return totalPrice; }
    public void setTotalPrice(BigDecimal totalPrice) { this.totalPrice = totalPrice; }
    public Integer getTotalItems() { return totalItems; }
    public void setTotalItems(Integer totalItems) { this.totalItems = totalItems; }
}
