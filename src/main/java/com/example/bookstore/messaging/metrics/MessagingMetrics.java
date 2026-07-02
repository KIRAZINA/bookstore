package com.example.bookstore.messaging.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class MessagingMetrics {

    private final Counter eventsPublished;
    private final Counter eventsConsumed;
    private final Counter eventsFailed;
    private final Counter eventsSentToDlt;
    private final Counter outboxPublished;
    private final Counter outboxFailed;
    private final Timer eventProcessingDuration;

    public MessagingMetrics(MeterRegistry registry) {
        this.eventsPublished = Counter.builder("bookstore.messaging.events.published")
                .description("Total domain events published to outbox")
                .register(registry);
        this.eventsConsumed = Counter.builder("bookstore.messaging.events.consumed")
                .description("Total domain events consumed by Kafka consumers")
                .register(registry);
        this.eventsFailed = Counter.builder("bookstore.messaging.events.failed")
                .description("Total consumer processing failures")
                .register(registry);
        this.eventsSentToDlt = Counter.builder("bookstore.messaging.events.dlt")
                .description("Total events sent to dead letter topic")
                .register(registry);
        this.outboxPublished = Counter.builder("bookstore.messaging.outbox.published")
                .description("Total outbox events successfully published to Kafka")
                .register(registry);
        this.outboxFailed = Counter.builder("bookstore.messaging.outbox.failed")
                .description("Total outbox events that permanently failed")
                .register(registry);
        this.eventProcessingDuration = Timer.builder("bookstore.messaging.event.processing.duration")
                .description("Event consumer processing duration")
                .register(registry);
    }

    public void recordEventPublished() {
        eventsPublished.increment();
    }

    public void recordEventConsumed() {
        eventsConsumed.increment();
    }

    public void recordEventFailed() {
        eventsFailed.increment();
    }

    public void recordEventSentToDlt() {
        eventsSentToDlt.increment();
    }

    public void recordOutboxPublished() {
        outboxPublished.increment();
    }

    public void recordOutboxFailed() {
        outboxFailed.increment();
    }

    public void recordEventProcessingDuration(long millis) {
        eventProcessingDuration.record(millis, TimeUnit.MILLISECONDS);
    }
}
