package com.example.bookstore.event;

import com.example.bookstore.event.repository.EventOutboxRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxCleanupTest {

    @Mock
    private EventOutboxRepository outboxRepository;

    @InjectMocks
    private OutboxCleanupScheduler cleanupScheduler;

    @Test
    @DisplayName("Deletes PUBLISHED events older than retention period")
    void cleanup_DeletesOldPublishedEvents() {
        cleanupScheduler = new OutboxCleanupScheduler(outboxRepository, Duration.ofDays(7));
        cleanupScheduler.cleanupPublishedEvents();

        verify(outboxRepository).deletePublishedOlderThan(any(Instant.class));
    }

    @Test
    @DisplayName("Does not throw when no events to clean")
    void cleanup_NoEvents_DoesNotThrow() {
        when(outboxRepository.deletePublishedOlderThan(any(Instant.class))).thenReturn(0);
        cleanupScheduler = new OutboxCleanupScheduler(outboxRepository, Duration.ofDays(7));
        cleanupScheduler.cleanupPublishedEvents();

        verify(outboxRepository).deletePublishedOlderThan(any(Instant.class));
    }

    @Test
    @DisplayName("Uses configured retention period")
    void cleanup_UsesConfiguredRetention() {
        Duration retention = Duration.ofDays(1);
        cleanupScheduler = new OutboxCleanupScheduler(outboxRepository, retention);
        cleanupScheduler.cleanupPublishedEvents();

        verify(outboxRepository).deletePublishedOlderThan(argThat(cutoff ->
                cutoff.isBefore(Instant.now()) && cutoff.isAfter(Instant.now().minus(Duration.ofDays(2)))));
    }
}
