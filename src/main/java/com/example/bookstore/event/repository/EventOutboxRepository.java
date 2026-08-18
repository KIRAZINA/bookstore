package com.example.bookstore.event.repository;

import com.example.bookstore.event.EventOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface EventOutboxRepository extends JpaRepository<EventOutbox, UUID> {

    @Query("SELECT e FROM EventOutbox e WHERE e.status = 'PENDING' AND e.retryCount < :maxRetries ORDER BY e.createdAt ASC")
    List<EventOutbox> findPendingEvents(int maxRetries);

    @Query("SELECT e FROM EventOutbox e WHERE e.status = 'PENDING' AND e.retryCount < :maxRetries AND e.createdAt < :before ORDER BY e.createdAt ASC")
    List<EventOutbox> findPendingEventsOlderThan(Instant before, int maxRetries);

    @Modifying
    @Query("UPDATE EventOutbox e SET e.status = 'PUBLISHED', e.publishedAt = :now WHERE e.id = :id AND e.status = 'PENDING'")
    int markPublished(UUID id, Instant now);

    @Modifying
    @Query("UPDATE EventOutbox e SET e.status = 'FAILED', e.publishedAt = :now WHERE e.id = :id")
    int markFailed(UUID id, Instant now);

    @Modifying
    @Query("UPDATE EventOutbox e SET e.retryCount = e.retryCount + 1 WHERE e.id = :id")
    int incrementRetry(UUID id);

    @Modifying
    @Query("DELETE FROM EventOutbox e WHERE e.status = 'PUBLISHED' AND e.publishedAt < :before")
    int deletePublishedOlderThan(Instant before);
}
