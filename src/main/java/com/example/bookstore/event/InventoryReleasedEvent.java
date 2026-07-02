package com.example.bookstore.event;

import java.util.Map;

public class InventoryReleasedEvent extends BaseEvent {
    private final Long orderId;
    private final Map<Long, Integer> releases;
    private final String reason;

    public InventoryReleasedEvent(Long orderId, Map<Long, Integer> releases, String reason) {
        this.orderId = orderId;
        this.releases = Map.copyOf(releases);
        this.reason = reason;
    }

    public Long getOrderId() { return orderId; }
    public Map<Long, Integer> getReleases() { return releases; }
    public String getReason() { return reason; }
}
