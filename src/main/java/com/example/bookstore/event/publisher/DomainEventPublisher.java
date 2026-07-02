package com.example.bookstore.event.publisher;

import com.example.bookstore.event.BaseEvent;

public interface DomainEventPublisher {
    void publish(BaseEvent event, String aggregateType, String aggregateId, String eventType);
}
