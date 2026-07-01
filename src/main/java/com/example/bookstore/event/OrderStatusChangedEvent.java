package com.example.bookstore.event;

import com.example.bookstore.model.OrderStatus;

public class OrderStatusChangedEvent extends BaseEvent {
    private final Long orderId;
    private final Long userId;
    private final String userName;
    private final String userEmail;
    private final OrderStatus previousStatus;
    private final OrderStatus newStatus;

    public OrderStatusChangedEvent(Long orderId, Long userId, String userName, String userEmail,
                                   OrderStatus previousStatus, OrderStatus newStatus) {
        this.orderId = orderId;
        this.userId = userId;
        this.userName = userName;
        this.userEmail = userEmail;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
    }

    public Long getOrderId() { return orderId; }
    public Long getUserId() { return userId; }
    public String getUserName() { return userName; }
    public String getUserEmail() { return userEmail; }
    public OrderStatus getPreviousStatus() { return previousStatus; }
    public OrderStatus getNewStatus() { return newStatus; }
}
