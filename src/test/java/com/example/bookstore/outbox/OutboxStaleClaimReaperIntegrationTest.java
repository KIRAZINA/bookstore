package com.example.bookstore.outbox;

import com.example.bookstore.base.AbstractIntegrationTest;
import com.example.bookstore.config.KafkaTopicConfig;
import com.example.bookstore.event.EventOutbox;
import com.example.bookstore.event.OutboxStatus;
import com.example.bookstore.event.publisher.OutboxPublisherScheduler;
import com.example.bookstore.event.repository.EventOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Evidence for the stale-claim reaper and the zombie-worker overwrite fence. The stale
 * threshold is left at its production default of 10 minutes so the test also pins that
 * value: SMTP can hang well past the 5-second Kafka timeout, so a shorter default would
 * be unsafe.
 */
@EmbeddedKafka(
        topics = {
                KafkaTopicConfig.TOPIC_ORDERS,
                KafkaTopicConfig.TOPIC_PAYMENTS,
                KafkaTopicConfig.TOPIC_INVENTORY,
                KafkaTopicConfig.TOPIC_NOTIFICATIONS,
                KafkaTopicConfig.TOPIC_DLT
        },
        partitions = 1,
        controlledShutdown = true,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@TestPropertySource(properties = {
        "app.outbox.poll-interval=3600000",
        "app.outbox.reap-interval-ms=3600000"
})
class OutboxStaleClaimReaperIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxPublisherScheduler outboxPublisherScheduler;

    @Autowired
    private EventOutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
    }

    /**
     * Hibernate binds Instant parameters as UTC wall-clock into a plain TIMESTAMP column,
     * so test fixtures must use the same encoding for the reaper comparison to be exact.
     */
    private static Timestamp ts(Instant value) {
        return Timestamp.valueOf(value.atZone(ZoneOffset.UTC).toLocalDateTime());
    }

    /** @Modifying queries require an active transaction when invoked directly. */
    private int inTransaction(Supplier<Integer> write) {
        return new TransactionTemplate(transactionManager).execute(status -> write.get());
    }

    private UUID seedClaimed(String aggregateId, Instant updatedAt, UUID token) {
        UUID id = UUID.randomUUID();
        outboxRepository.save(new EventOutbox(id, "ORDER", aggregateId, "ORDER_CREATED", 1,
                null, null, null, "bookstore", "{\"orderId\":1}", null));
        jdbcTemplate.update(
                "UPDATE event_outbox SET status='CLAIMED', claim_token=?, updated_at=? WHERE id=?",
                token, ts(updatedAt), id);
        return id;
    }

    // GREEN_INVARIANT: the reaper needs the V10 updated_at/claim_token columns and cannot
    // be expressed against pre-migration HEAD; added after the migration landed.

    @Test
    @Timeout(60)
    @DisplayName("R-CLAIM-2a (GREEN_INVARIANT): the reaper reverts only stale CLAIMED rows")
    void reaperRevertsOnlyStaleClaims() {
        Instant stale = Instant.now().minus(11, ChronoUnit.MINUTES);
        Instant fresh = Instant.now().minus(30, ChronoUnit.SECONDS);
        List<UUID> staleIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            staleIds.add(seedClaimed("stale-" + i, stale, UUID.randomUUID()));
        }
        List<UUID> freshIds = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            freshIds.add(seedClaimed("fresh-" + i, fresh, UUID.randomUUID()));
        }

        outboxPublisherScheduler.reapStaleClaims();

        for (UUID id : staleIds) {
            EventOutbox row = outboxRepository.findById(id).orElseThrow();
            assertEquals(OutboxStatus.PENDING, row.getStatus(), "a stale claim must be reverted");
            assertNull(row.getClaimToken(), "a reverted claim must clear its token");
            assertNotNull(row.getUpdatedAt());
            assertRefreshed(row.getUpdatedAt(), stale, "the reaper must refresh updated_at");
        }
        for (UUID id : freshIds) {
            EventOutbox row = outboxRepository.findById(id).orElseThrow();
            assertEquals(OutboxStatus.CLAIMED, row.getStatus(), "a fresh claim must be left alone");
            assertNotNull(row.getClaimToken(), "a fresh claim must keep its token");
        }
    }

    private static void assertRefreshed(Instant actual, Instant formerStale, String message) {
        assertNotNull(actual, message);
        org.junit.jupiter.api.Assertions.assertTrue(actual.isAfter(formerStale), message);
    }

    @Test
    @Timeout(60)
    @DisplayName("R-CLAIM-2b (GREEN_INVARIANT): a zombie worker cannot overwrite a re-claimed row")
    void zombieWorkerCannotOverwriteReclaimedRow() {
        UUID id = UUID.randomUUID();
        UUID tokenA = UUID.randomUUID();
        UUID tokenB = UUID.randomUUID();
        outboxRepository.save(new EventOutbox(id, "ORDER", "zombie", "ORDER_CREATED", 1,
                null, null, null, "bookstore", "{\"orderId\":1}", null));
        jdbcTemplate.update(
                "UPDATE event_outbox SET status='CLAIMED', claim_token=?, updated_at=? WHERE id=?",
                tokenA, ts(Instant.now()), id);
        // Reaper reverts the hung worker's claim (older than the 10-minute default).
        jdbcTemplate.update(
                "UPDATE event_outbox SET status='PENDING', claim_token=NULL, updated_at=? WHERE id=?",
                ts(Instant.now().minus(11, ChronoUnit.MINUTES)), id);
        // Worker B re-claims the row with a fresh token.
        jdbcTemplate.update(
                "UPDATE event_outbox SET status='CLAIMED', claim_token=?, updated_at=? WHERE id=?",
                tokenB, ts(Instant.now()), id);

        int updated = inTransaction(() -> outboxRepository.markFailedFenced(id, tokenA, 5, Instant.now()));

        assertEquals(0, updated, "the zombie's stale failure mark must update zero rows");
        EventOutbox row = outboxRepository.findById(id).orElseThrow();
        assertEquals(OutboxStatus.CLAIMED, row.getStatus(), "worker B's claim must remain intact");
        assertEquals(tokenB, row.getClaimToken(), "worker B's token must not be overwritten");
        assertEquals(0, row.getRetryCount(), "the zombie must not bump the retry count");
        assertNull(row.getPublishedAt());
    }
}
