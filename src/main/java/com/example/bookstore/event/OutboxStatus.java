package com.example.bookstore.event;

public enum OutboxStatus {
    PENDING,
    CLAIMED,
    PUBLISHED,
    FAILED
}
