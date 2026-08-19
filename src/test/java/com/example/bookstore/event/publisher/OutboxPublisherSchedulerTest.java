package com.example.bookstore.event.publisher;

import com.example.bookstore.event.EventOutbox;
import com.example.bookstore.event.repository.EventOutboxRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherSchedulerTest {

    @Mock
    private EventOutboxRepository outboxRepository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxPublisherScheduler scheduler;
    private SendResult<String, String> successResult;

    @BeforeEach
    void setUp() {
        successResult = mock(SendResult.class);
        lenient().when(successResult.getRecordMetadata())
                .thenReturn(mock(org.apache.kafka.clients.producer.RecordMetadata.class));
        scheduler = new OutboxPublisherScheduler(outboxRepository, kafkaTemplate, 5);
    }

    private EventOutbox anOutbox(String eventType) {
        return anOutbox(eventType, null, null);
    }

    private EventOutbox anOutbox(String eventType, String traceId, String spanId) {
        return new EventOutbox(UUID.randomUUID(), "order", "1", eventType,
                1, "corr-1", traceId, spanId, "bookstore", "{\"orderId\":1}", null);
    }

    private CompletableFuture<SendResult<String, String>> successFuture() {
        return CompletableFuture.completedFuture(successResult);
    }

    @Nested
    @DisplayName("publishPendingEvents")
    class PublishPendingEvents {

        @Test
        @DisplayName("Does nothing when no pending events")
        void noPendingEvents() {
            when(outboxRepository.findPendingEvents(5)).thenReturn(List.of());

            scheduler.publishPendingEvents();

            verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        }

        @Test
        @DisplayName("Publishes pending events and marks as published")
        void publishesSuccessfully() {
            EventOutbox outbox = anOutbox("ORDER_CREATED");
            when(outboxRepository.findPendingEvents(5)).thenReturn(List.of(outbox));
            when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(successFuture());

            scheduler.publishPendingEvents();

            ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
            verify(kafkaTemplate).send(captor.capture());
            ProducerRecord<String, String> record = captor.getValue();
            assertEquals("orders.events", record.topic());
            assertEquals("1", record.key());
            assertNotNull(record.value());
            verify(outboxRepository).markPublished(eq(outbox.getId()), any(Instant.class));
        }

        @Test
        @DisplayName("Adds W3C traceparent header when trace context is available")
        void addsTraceParentHeader() {
            EventOutbox outbox = anOutbox("ORDER_CREATED", "0123456789abcdef0123456789abcdef", "0123456789abcdef");
            when(outboxRepository.findPendingEvents(5)).thenReturn(List.of(outbox));
            when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(successFuture());

            scheduler.publishPendingEvents();

            ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
            verify(kafkaTemplate).send(captor.capture());
            org.apache.kafka.common.header.Header header = captor.getValue().headers().lastHeader("traceparent");
            assertNotNull(header);
            String traceParent = new String(header.value(), StandardCharsets.UTF_8);
            assertEquals("00-0123456789abcdef0123456789abcdef-0123456789abcdef-01", traceParent);
        }

        @Test
        @DisplayName("Skips traceparent header when trace context is incomplete")
        void skipsTraceParentWhenMissing() {
            EventOutbox outbox = anOutbox("ORDER_CREATED", "trace-only", null);
            when(outboxRepository.findPendingEvents(5)).thenReturn(List.of(outbox));
            when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(successFuture());

            scheduler.publishPendingEvents();

            ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
            verify(kafkaTemplate).send(captor.capture());
            assertNull(captor.getValue().headers().lastHeader("traceparent"));
        }

        @Test
        @DisplayName("Increments retry on transient failure")
        void retriesOnFailure() {
            EventOutbox outbox = anOutbox("ORDER_CREATED");
            when(outboxRepository.findPendingEvents(5)).thenReturn(List.of(outbox));

            CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new ExecutionException(new RuntimeException("Kafka unavailable")));
            when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failedFuture);

            scheduler.publishPendingEvents();

            verify(outboxRepository).incrementRetry(outbox.getId());
        }

        @Test
        @DisplayName("Marks as failed after max retries exceeded")
        void marksFailed() {
            EventOutbox outbox = anOutbox("ORDER_CREATED");
            outbox.incrementRetry();
            outbox.incrementRetry();
            outbox.incrementRetry();
            outbox.incrementRetry();
            assertEquals(4, outbox.getRetryCount());
            when(outboxRepository.findPendingEvents(5)).thenReturn(List.of(outbox));

            CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new ExecutionException(new RuntimeException("Kafka unavailable")));
            when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failedFuture);

            scheduler.publishPendingEvents();

            verify(outboxRepository).markFailed(eq(outbox.getId()), any(Instant.class));
        }

        @Test
        @DisplayName("Resolves topic by event type prefix")
        void resolvesTopic() {
            EventOutbox orderOutbox = anOutbox("ORDER_CREATED");
            EventOutbox paymentOutbox = anOutbox("PAYMENT_COMPLETED");
            EventOutbox inventoryOutbox = anOutbox("INVENTORY_RESERVED");
            EventOutbox notificationOutbox = anOutbox("NOTIFICATION_SEND");

            when(outboxRepository.findPendingEvents(5)).thenReturn(
                    List.of(orderOutbox, paymentOutbox, inventoryOutbox, notificationOutbox));
            when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(successFuture());

            scheduler.publishPendingEvents();

            ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
            verify(kafkaTemplate, times(4)).send(captor.capture());
            List<String> topics = captor.getAllValues().stream().map(ProducerRecord::topic).toList();
            assertTrue(topics.containsAll(List.of(
                    "orders.events", "payments.events", "inventory.events", "notifications.events")));
        }

        @Test
        @DisplayName("Handles InterruptedException by breaking")
        void interrupted() {
            EventOutbox outbox = anOutbox("ORDER_CREATED");
            when(outboxRepository.findPendingEvents(5)).thenReturn(List.of(outbox, anOutbox("ORDER_CREATED")));

            CompletableFuture<SendResult<String, String>> interruptedFuture = new CompletableFuture<>();
            interruptedFuture.completeExceptionally(new InterruptedException("Interrupted"));
            when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(interruptedFuture);

            scheduler.publishPendingEvents();

            verify(outboxRepository, never()).markPublished(any(), any());
        }
    }
}