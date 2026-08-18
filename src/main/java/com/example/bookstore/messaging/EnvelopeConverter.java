package com.example.bookstore.messaging;

import com.example.bookstore.event.BaseEvent;
import com.example.bookstore.event.EventOutbox;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class EnvelopeConverter {

    private static final Logger log = LoggerFactory.getLogger(EnvelopeConverter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private EnvelopeConverter() {}

    public static EventOutbox toOutbox(BaseEvent event, String aggregateType, String aggregateId,
                                        String eventType, String producer,
                                        String correlationId, String traceId) {
        String payload = serializePayload(event);
        return new EventOutbox(
                event.getEventId(),
                aggregateType,
                aggregateId,
                eventType,
                1,
                correlationId,
                traceId,
                producer,
                payload,
                null
        );
    }

    public static DomainEventEnvelope toEnvelope(EventOutbox outbox) {
        return new DomainEventEnvelope(
                outbox.getId(),
                outbox.getAggregateType(),
                outbox.getAggregateId(),
                outbox.getEventType(),
                outbox.getEventVersion(),
                outbox.getCreatedAt(),
                outbox.getCorrelationId(),
                outbox.getTraceId(),
                outbox.getProducer(),
                deserializePayload(outbox.getPayload()),
                Map.of()
        );
    }

    public static String serializePayload(Object event) {
        try {
            Map<String, Object> map = toMap(event);
            return MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event payload: {}", event.getClass().getSimpleName(), e);
            throw new IllegalStateException("Event serialization failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> deserializePayload(String json) {
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize event payload", e);
            return Map.of();
        }
    }

    private static Map<String, Object> toMap(Object obj) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (obj instanceof Record record) {
            RecordComponent[] components = record.getClass().getRecordComponents();
            for (RecordComponent comp : components) {
                try {
                    Object value = comp.getAccessor().invoke(record);
                    if (value instanceof UUID uuid) {
                        map.put(comp.getName(), uuid.toString());
                    } else if (value instanceof Enum<?> e) {
                        map.put(comp.getName(), e.name());
                    } else {
                        map.put(comp.getName(), value);
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    map.put(comp.getName(), null);
                }
            }
        } else {
            try {
                String json = MAPPER.writeValueAsString(obj);
                return MAPPER.readValue(json, Map.class);
            } catch (Exception e) {
                log.warn("Failed to convert event to map: {}", obj.getClass().getSimpleName(), e);
            }
        }
        return map;
    }

    public static String toJson(DomainEventEnvelope envelope) {
        try {
            return MAPPER.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize envelope", e);
        }
    }

    public static DomainEventEnvelope fromJson(String json) {
        try {
            return MAPPER.readValue(json, DomainEventEnvelope.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize envelope", e);
        }
    }
}
