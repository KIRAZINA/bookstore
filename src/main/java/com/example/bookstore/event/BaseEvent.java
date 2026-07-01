package com.example.bookstore.event;

import java.time.Instant;
import java.util.UUID;

public abstract class BaseEvent {
    private final UUID eventId;
    private final Instant timestamp;

    protected BaseEvent() {
        this.eventId = UUID.randomUUID();
        this.timestamp = Instant.now();
    }

    public UUID getEventId() { return eventId; }
    public Instant getTimestamp() { return timestamp; }
}
