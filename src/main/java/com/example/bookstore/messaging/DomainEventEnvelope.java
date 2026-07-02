package com.example.bookstore.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record DomainEventEnvelope(
    UUID eventId,
    String aggregateType,
    String aggregateId,
    String eventType,
    int eventVersion,
    Instant timestamp,
    String correlationId,
    String traceId,
    String producer,
    Map<String, Object> payload,
    Map<String, Object> metadata
) {}
