package com.example.bookstore.event.repository;

import com.example.bookstore.event.EventOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface EventOutboxRepository extends JpaRepository<EventOutbox, UUID> {

    /**
     * Phase 1 (CLAIM): atomically claim up to {@code batchSize} PENDING rows that are
     * within the retry budget. {@code FOR UPDATE SKIP LOCKED} makes concurrent claimers
     * pick disjoint sets; RETURNING maps the claimed entities in a single round trip.
     * Deliberately not {@code @Modifying} — the RETURNING result set is the query result.
     */
    @Query(value = """
        WITH claimed AS (
            SELECT id FROM event_outbox
             WHERE status = 'PENDING'
               AND retry_count < :maxRetries
             ORDER BY created_at ASC
             LIMIT :batchSize
             FOR UPDATE SKIP LOCKED
        )
        UPDATE event_outbox e
           SET status = 'CLAIMED',
               claim_token = gen_random_uuid(),
               updated_at = :now
          FROM claimed
         WHERE e.id = claimed.id
        RETURNING e.id, e.aggregate_type, e.aggregate_id, e.event_type,
                  e.event_version, e.correlation_id, e.trace_id, e.span_id,
                  e.producer, e.payload, e.metadata, e.status, e.retry_count,
                  e.created_at, e.published_at, e.updated_at, e.claim_token
        """, nativeQuery = true)
    List<EventOutbox> claimBatch(@Param("batchSize") int batchSize,
                                 @Param("maxRetries") int maxRetries,
                                 @Param("now") Instant now);

    @Query("SELECT e FROM EventOutbox e WHERE e.status = 'PENDING' AND e.retryCount < :maxRetries AND e.createdAt < :before ORDER BY e.createdAt ASC")
    List<EventOutbox> findPendingEventsOlderThan(Instant before, int maxRetries);

    /**
     * Fenced success mark (canonical name). Returns the number of rows updated; a zero
     * means the claim token no longer matches and the caller must not touch the row.
     */
    @Modifying
    @Query("""
        UPDATE EventOutbox e
           SET e.status = 'PUBLISHED',
               e.publishedAt = :now,
               e.updatedAt = :now,
               e.claimToken = null
         WHERE e.id = :id
           AND e.status = 'CLAIMED'
           AND e.claimToken = :token
        """)
    int markPublishedFenced(@Param("id") UUID id,
                            @Param("token") UUID token,
                            @Param("now") Instant now);

    /**
     * Fenced success mark. Kept under the historic {@code markPublished} name because a
     * 4B.4 regression test injects a mark failure by intercepting that method name; the
     * production scheduler calls this alias so the fault-injection contract still holds.
     * The predicate is identical to {@link #markPublishedFenced(UUID, UUID, Instant)} —
     * no unfenced status write remains.
     */
    @Modifying
    @Query("""
        UPDATE EventOutbox e
           SET e.status = 'PUBLISHED',
               e.publishedAt = :now,
               e.updatedAt = :now,
               e.claimToken = null
         WHERE e.id = :id
           AND e.status = 'CLAIMED'
           AND e.claimToken = :token
        """)
    int markPublished(@Param("id") UUID id,
                      @Param("token") UUID token,
                      @Param("now") Instant now);

    /** Fenced failure mark: retry while under the budget, otherwise terminal FAILED. */
    @Modifying
    @Query("""
        UPDATE EventOutbox e
           SET e.status = CASE WHEN e.retryCount + 1 >= :maxRetries THEN 'FAILED' ELSE 'PENDING' END,
               e.retryCount = e.retryCount + 1,
               e.updatedAt = :now,
               e.claimToken = null
         WHERE e.id = :id
           AND e.status = 'CLAIMED'
           AND e.claimToken = :token
        """)
    int markFailedFenced(@Param("id") UUID id,
                         @Param("token") UUID token,
                         @Param("maxRetries") int maxRetries,
                         @Param("now") Instant now);

    /** Reaper: revert every CLAIMED row whose claim is older than the stale threshold. */
    @Modifying
    @Query("""
        UPDATE EventOutbox e
           SET e.status = 'PENDING',
               e.claimToken = null,
               e.updatedAt = :now
         WHERE e.status = 'CLAIMED'
           AND e.updatedAt < :threshold
        """)
    int revertStaleClaims(@Param("threshold") Instant threshold,
                          @Param("now") Instant now);

    @Modifying
    @Query("DELETE FROM EventOutbox e WHERE e.status = 'PUBLISHED' AND e.publishedAt < :before")
    int deletePublishedOlderThan(Instant before);
}
