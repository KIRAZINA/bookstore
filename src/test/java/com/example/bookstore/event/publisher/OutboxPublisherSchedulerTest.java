package com.example.bookstore.event.publisher;

import com.example.bookstore.event.EventOutbox;
import com.example.bookstore.event.OutboxStatus;
import com.example.bookstore.event.repository.EventOutboxRepository;
import com.example.bookstore.messaging.EnvelopeConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherSchedulerTest {

    @Mock
    private EventOutboxRepository outboxRepository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxPublisherScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new OutboxPublisherScheduler(outboxRepository, kafkaTemplate, 5);
    }

    private EventOutbox anOutbox(String eventType) {
        return new EventOutbox(UUID.randomUUID(), "order", "1", eventType,
                1, "corr-1", null, "bookstore", "{\"orderId\":1}", null);
    }

    @Nested
    @DisplayName("publishPendingEvents")
    class PublishPendingEvents {

        @Test
        @DisplayName("Does nothing when no pending events")
        void noPendingEvents() {
            when(outboxRepository.findPendingEvents(5)).thenReturn(List.of());

            scheduler.publishPendingEvents();

            verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("Publishes pending events and marks as published")
        void publishesSuccessfully() {
            EventOutbox outbox = anOutbox("ORDER_CREATED");
            when(outboxRepository.findPendingEvents(5)).thenReturn(List.of(outbox));

            SendResult<String, String> sendResult = mock(SendResult.class);
            when(sendResult.getRecordMetadata()).thenReturn(mock(org.apache.kafka.clients.producer.RecordMetadata.class));
            when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                    .thenAnswer(i -> {
                        java.util.concurrent.CompletableFuture<SendResult<String, String>> f = new java.util.concurrent.CompletableFuture<>();
                        f.complete(sendResult);
                        return f;
                    });

            scheduler.publishPendingEvents();

            verify(kafkaTemplate).send(eq("orders.events"), eq("1"), anyString());
            verify(outboxRepository).markPublished(eq(outbox.getId()), any(Instant.class));
        }

        @Test
        @DisplayName("Increments retry on transient failure")
        void retriesOnFailure() {
            EventOutbox outbox = anOutbox("ORDER_CREATED");
            when(outboxRepository.findPendingEvents(5)).thenReturn(List.of(outbox));

            java.util.concurrent.CompletableFuture<SendResult<String, String>> failedFuture = new java.util.concurrent.CompletableFuture<>();
            failedFuture.completeExceptionally(new ExecutionException(new RuntimeException("Kafka unavailable")));
            when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(failedFuture);

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

            java.util.concurrent.CompletableFuture<SendResult<String, String>> failedFuture = new java.util.concurrent.CompletableFuture<>();
            failedFuture.completeExceptionally(new ExecutionException(new RuntimeException("Kafka unavailable")));
            when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(failedFuture);

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

            java.util.concurrent.CompletableFuture<SendResult<String, String>> success = java.util.concurrent.CompletableFuture.completedFuture(mock(SendResult.class));
            when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(success);

            scheduler.publishPendingEvents();

            verify(kafkaTemplate).send(eq("orders.events"), anyString(), anyString());
            verify(kafkaTemplate).send(eq("payments.events"), anyString(), anyString());
            verify(kafkaTemplate).send(eq("inventory.events"), anyString(), anyString());
            verify(kafkaTemplate).send(eq("notifications.events"), anyString(), anyString());
        }

        @Test
        @DisplayName("Handles InterruptedException by breaking")
        void interrupted() {
            EventOutbox outbox = anOutbox("ORDER_CREATED");
            when(outboxRepository.findPendingEvents(5)).thenReturn(List.of(outbox, anOutbox("ORDER_CREATED")));

            java.util.concurrent.CompletableFuture<SendResult<String, String>> interruptedFuture = new java.util.concurrent.CompletableFuture<>();
            interruptedFuture.completeExceptionally(new InterruptedException("Interrupted"));
            when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(interruptedFuture);

            scheduler.publishPendingEvents();

            verify(outboxRepository, never()).markPublished(any(), any());
        }
    }
}
