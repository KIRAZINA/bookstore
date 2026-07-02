package com.example.bookstore.event;

import java.util.Map;

public class InventoryFinalizedEvent extends BaseEvent {
    private final Long orderId;
    private final Map<Long, Integer> finalizations;

    public InventoryFinalizedEvent(Long orderId, Map<Long, Integer> finalizations) {
        this.orderId = orderId;
        this.finalizations = Map.copyOf(finalizations);
    }

    public Long getOrderId() { return orderId; }
    public Map<Long, Integer> getFinalizations() { return finalizations; }
}
