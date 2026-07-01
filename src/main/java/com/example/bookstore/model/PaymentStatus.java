package com.example.bookstore.model;

import java.util.Map;
import java.util.Set;

public enum PaymentStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED,
    REFUNDED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> TRANSITIONS = Map.of(
        PENDING,    Set.of(PROCESSING, CANCELLED),
        PROCESSING, Set.of(COMPLETED, FAILED),
        COMPLETED,  Set.of(REFUNDED),
        FAILED,     Set.of(PENDING),
        CANCELLED,  Set.of(),
        REFUNDED,   Set.of()
    );

    public boolean canTransitionTo(PaymentStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED || this == REFUNDED;
    }
}
