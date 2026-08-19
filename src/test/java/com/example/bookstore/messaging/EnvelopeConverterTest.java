package com.example.bookstore.messaging;

import com.example.bookstore.event.BaseEvent;
import com.example.bookstore.event.EventOutbox;
import com.example.bookstore.event.EventTypes;
import com.example.bookstore.event.OrderCreatedEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EnvelopeConverterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final OrderCreatedEvent sampleEvent = new OrderCreatedEvent(
            1L, 2L, "testuser", "test@example.com", new BigDecimal("99.99"));

    @Nested
    @DisplayName("toOutbox")
    class ToOutbox {
        @Test
        @DisplayName("Converts event to outbox entity")
        void convertsToOutbox() {
            EventOutbox outbox = EnvelopeConverter.toOutbox(
                    sampleEvent, EventTypes.AGGREGATE_ORDER, "1", EventTypes.ORDER_CREATED,
                    "bookstore", "corr-123", "trace-456", "span-789");

            assertNotNull(outbox);
            assertEquals(sampleEvent.getEventId(), outbox.getId());
            assertEquals(EventTypes.AGGREGATE_ORDER, outbox.getAggregateType());
            assertEquals("1", outbox.getAggregateId());
            assertEquals(EventTypes.ORDER_CREATED, outbox.getEventType());
            assertEquals(1, outbox.getEventVersion());
            assertEquals("corr-123", outbox.getCorrelationId());
            assertEquals("trace-456", outbox.getTraceId());
            assertEquals("span-789", outbox.getSpanId());
            assertEquals("bookstore", outbox.getProducer());
            assertNotNull(outbox.getPayload());
            assertNull(outbox.getMetadata());
        }

        @Test
        @DisplayName("Payload is valid JSON")
        void payloadIsValidJson() {
            EventOutbox outbox = EnvelopeConverter.toOutbox(
                    sampleEvent, EventTypes.AGGREGATE_ORDER, "1", EventTypes.ORDER_CREATED,
                    "bookstore", "corr-1", null, null);

            assertDoesNotThrow(() -> MAPPER.readTree(outbox.getPayload()));
        }
    }

    @Nested
    @DisplayName("toEnvelope")
    class ToEnvelope {
        @Test
        @DisplayName("Converts outbox to domain event envelope")
        void convertsToEnvelope() {
            EventOutbox outbox = EnvelopeConverter.toOutbox(
                    sampleEvent, EventTypes.AGGREGATE_ORDER, "1", EventTypes.ORDER_CREATED,
                    "bookstore", "corr-123", "trace-456", "span-789");

            DomainEventEnvelope envelope = EnvelopeConverter.toEnvelope(outbox);

            assertEquals(outbox.getId(), envelope.eventId());
            assertEquals(outbox.getAggregateType(), envelope.aggregateType());
            assertEquals(outbox.getAggregateId(), envelope.aggregateId());
            assertEquals(outbox.getEventType(), envelope.eventType());
            assertEquals(outbox.getEventVersion(), envelope.eventVersion());
            assertEquals(outbox.getCreatedAt(), envelope.timestamp());
            assertEquals(outbox.getCorrelationId(), envelope.correlationId());
            assertEquals(outbox.getTraceId(), envelope.traceId());
            assertEquals(outbox.getProducer(), envelope.producer());
            assertNotNull(envelope.payload());
            assertTrue(envelope.payload().containsKey("orderId"));
        }
    }

    @Nested
    @DisplayName("serializePayload / deserializePayload")
    class SerializationRoundTrip {
        @Test
        @DisplayName("Round-trips through JSON")
        void roundTrip() {
            String json = EnvelopeConverter.serializePayload(sampleEvent);
            Map<String, Object> deserialized = EnvelopeConverter.deserializePayload(json);

            assertEquals("1", deserialized.get("orderId").toString());
            assertEquals("testuser", deserialized.get("userName"));
            assertEquals("test@example.com", deserialized.get("userEmail"));
        }

        @Test
        @DisplayName("Returns empty map for invalid JSON")
        void invalidJsonReturnsEmpty() {
            Map<String, Object> result = EnvelopeConverter.deserializePayload("not-valid-json");
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("toJson / fromJson")
    class EnvelopeRoundTrip {
        @Test
        @DisplayName("Round-trips DomainEventEnvelope through JSON")
        void roundTrip() {
            Map<String, Object> payload = Map.of("orderId", 1);
            DomainEventEnvelope original = new DomainEventEnvelope(
                    UUID.randomUUID(), "order", "1", "ORDER_CREATED", 1,
                    Instant.now(), "corr-1", null, "bookstore",
                    payload, Map.of("key", "value"));

            String json = EnvelopeConverter.toJson(original);
            DomainEventEnvelope restored = EnvelopeConverter.fromJson(json);

            assertEquals(original.eventId(), restored.eventId());
            assertEquals(original.aggregateType(), restored.aggregateType());
            assertEquals(original.eventType(), restored.eventType());
            assertEquals(original.payload(), restored.payload());
            assertEquals(original.metadata(), restored.metadata());
            assertEquals(original.correlationId(), restored.correlationId());
        }
    }
}
