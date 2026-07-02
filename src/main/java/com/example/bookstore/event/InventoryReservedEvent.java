package com.example.bookstore.event;

import java.util.Map;

public class InventoryReservedEvent extends BaseEvent {
    private final Long orderId;
    private final Map<Long, Integer> reservations;

    public InventoryReservedEvent(Long orderId, Map<Long, Integer> reservations) {
        this.orderId = orderId;
        this.reservations = Map.copyOf(reservations);
    }

    public Long getOrderId() { return orderId; }
    public Map<Long, Integer> getReservations() { return reservations; }
}
