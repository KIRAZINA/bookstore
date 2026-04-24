package com.example.bookstore.model;

public enum OrderStatus {
    PENDING,      // Order created, payment pending
    CONFIRMED,    // Payment confirmed, processing
    SHIPPED,      // Items shipped
    DELIVERED,    // Items delivered
    CANCELLED     // Order cancelled
}
