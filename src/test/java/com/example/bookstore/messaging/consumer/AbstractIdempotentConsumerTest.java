package com.example.bookstore.messaging.consumer;

import com.example.bookstore.event.ProcessedEvent;
import com.example.bookstore.event.repository.ProcessedEventRepository;
import com.example.bookstore.messaging.DomainEventEnvelope;
import com.example.bookstore.messaging.metrics.MessagingMetrics;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AbstractIdempotentConsumerTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private MessagingMetrics messagingMetrics;

    private SpyConsumer consumer;
    private String validJson;

    @BeforeEach
    void setUp() {
        SpyConsumer.reset();
        consumer = new SpyConsumer(processedEventRepository, messagingMetrics);
        UUID eventId = UUID.randomUUID();
        validJson = """
                {"eventId":"%s","aggregateType":"order","aggregateId":"1","eventType":"ORDER_CREATED","eventVersion":1,"timestamp":"2025-01-01T00:00:00Z","correlationId":"corr-1","producer":"bookstore","payload":{"orderId":1},"metadata":{}}
                """.formatted(eventId);
    }

    private RecordHeaders headersWithTraceParent() {
        RecordHeaders headers = new RecordHeaders();
        headers.add("traceparent", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"
                .getBytes(StandardCharsets.UTF_8));
        return headers;
    }

    @Nested
    @DisplayName("processEvent")
    class ProcessEvent {
        @Test
        @DisplayName("Saves processed event and delegates to doProcess")
        void success() {
            when(processedEventRepository.saveAndFlush(any(ProcessedEvent.class)))
                    .thenReturn(mock(ProcessedEvent.class));

            consumer.processEvent(validJson, new RecordHeaders());

            assertEquals(1, SpyConsumer.processedCount);
            verify(messagingMetrics).recordEventConsumed();
        }

        @Test
        @DisplayName("Skips processing on duplicate event (DataIntegrityViolationException)")
        void duplicateEvent() {
            when(processedEventRepository.saveAndFlush(any(ProcessedEvent.class)))
                    .thenThrow(new DataIntegrityViolationException("Duplicate key"));

            consumer.processEvent(validJson, new RecordHeaders());

            assertEquals(0, SpyConsumer.processedCount);
            verify(messagingMetrics, never()).recordEventConsumed();
        }

        @Test
        @DisplayName("Re-throws exception from doProcess and records failure")
        void doProcessFails() {
            when(processedEventRepository.saveAndFlush(any(ProcessedEvent.class)))
                    .thenReturn(mock(ProcessedEvent.class));
            consumer.throwOnProcess = true;

            assertThrows(RuntimeException.class,
                    () -> consumer.processEvent(validJson, new RecordHeaders()));

            verify(messagingMetrics).recordEventFailed();
        }

        @Test
        @DisplayName("Records processing duration in finally block")
        void recordsDuration() {
            when(processedEventRepository.saveAndFlush(any(ProcessedEvent.class)))
                    .thenReturn(mock(ProcessedEvent.class));

            consumer.processEvent(validJson, new RecordHeaders());

            verify(messagingMetrics).recordEventProcessingDuration(anyLong());
        }

        @Test
        @DisplayName("Records duration even when processing fails")
        void recordsDurationOnFailure() {
            when(processedEventRepository.saveAndFlush(any(ProcessedEvent.class)))
                    .thenReturn(mock(ProcessedEvent.class));
            consumer.throwOnProcess = true;

            assertThrows(RuntimeException.class,
                    () -> consumer.processEvent(validJson, new RecordHeaders()));

            verify(messagingMetrics).recordEventProcessingDuration(anyLong());
        }

        @Test
        @DisplayName("Continues trace from traceparent header when no span is current")
        void continuesTraceFromHeader() {
            when(processedEventRepository.saveAndFlush(any(ProcessedEvent.class)))
                    .thenReturn(mock(ProcessedEvent.class));

            consumer.processEvent(validJson, headersWithTraceParent());

            assertEquals("0123456789abcdef0123456789abcdef", SpyConsumer.capturedTraceId);
        }

        @Test
        @DisplayName("Does not override an already active local span")
        void doesNotOverrideActiveSpan() {
            when(processedEventRepository.saveAndFlush(any(ProcessedEvent.class)))
                    .thenReturn(mock(ProcessedEvent.class));

            io.opentelemetry.api.trace.Span localSpan = io.opentelemetry.api.trace.Span.wrap(
                    io.opentelemetry.api.trace.SpanContext.create(
                            "fedcba9876543210fedcba9876543210", "0123456789abcdef",
                            io.opentelemetry.api.trace.TraceFlags.getSampled(),
                            io.opentelemetry.api.trace.TraceState.getDefault()));
            try (Scope scope = io.opentelemetry.context.Context.current()
                    .with(localSpan).makeCurrent()) {
                consumer.processEvent(validJson, headersWithTraceParent());
            }

            assertEquals("fedcba9876543210fedcba9876543210", SpyConsumer.capturedTraceId);
        }
    }

    static class SpyConsumer extends AbstractIdempotentConsumer {
        static int processedCount = 0;
        static String capturedTraceId = null;
        boolean throwOnProcess = false;

        SpyConsumer(ProcessedEventRepository repo, MessagingMetrics metrics) {
            super(repo, metrics, "test-group");
        }

        static void reset() {
            processedCount = 0;
            capturedTraceId = null;
        }

        @Override
        protected void doProcess(DomainEventEnvelope envelope) {
            processedCount++;
            Span current = Span.current();
            if (current != null && current.getSpanContext().isValid()) {
                capturedTraceId = current.getSpanContext().getTraceId();
            }
            if (throwOnProcess) {
                throw new RuntimeException("Processing error");
            }
        }
    }
}