package com.example.bookstore.event;

import java.math.BigDecimal;

public class OrderCreatedEvent extends BaseEvent {
    private final Long orderId;
    private final Long userId;
    private final String userName;
    private final String userEmail;
    private final BigDecimal totalPrice;

    public OrderCreatedEvent(Long orderId, Long userId, String userName, String userEmail, BigDecimal totalPrice) {
        this.orderId = orderId;
        this.userId = userId;
        this.userName = userName;
        this.userEmail = userEmail;
        this.totalPrice = totalPrice;
    }

    public Long getOrderId() { return orderId; }
    public Long getUserId() { return userId; }
    public String getUserName() { return userName; }
    public String getUserEmail() { return userEmail; }
    public BigDecimal getTotalPrice() { return totalPrice; }
}
