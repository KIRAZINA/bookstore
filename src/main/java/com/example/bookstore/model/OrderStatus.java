package com.example.bookstore.model;

import java.util.Map;
import java.util.Set;

public enum OrderStatus {
    PENDING,
    PAID,
    PROCESSING,
    SHIPPED,
    DELIVERED,
    CANCELLED;

    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = Map.of(
        PENDING,    Set.of(PAID, CANCELLED),
        PAID,       Set.of(PROCESSING, CANCELLED),
        PROCESSING, Set.of(SHIPPED),
        SHIPPED,    Set.of(DELIVERED),
        DELIVERED,  Set.of(),
        CANCELLED,  Set.of()
    );

    public boolean canTransitionTo(OrderStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    public boolean isTerminal() {
        return this == DELIVERED || this == CANCELLED;
    }

    public boolean isCancelable() {
        return this == PENDING || this == PAID;
    }
}
