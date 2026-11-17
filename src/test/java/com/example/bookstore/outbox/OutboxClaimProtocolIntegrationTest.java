package com.example.bookstore.outbox;

import com.example.bookstore.base.AbstractIntegrationTest;
import com.example.bookstore.config.KafkaTopicConfig;
import com.example.bookstore.event.EventOutbox;
import com.example.bookstore.event.OutboxStatus;
import com.example.bookstore.event.publisher.OutboxPublisherScheduler;
import com.example.bookstore.event.repository.EventOutboxRepository;
import io.micrometer.observation.Observation;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Evidence for the two-phase outbox claim protocol: only one worker may deliver a given
 * row, the pickup is bounded by a batch size, and every Phase-3 mark is fenced by the
 * claim token so a zombie worker cannot overwrite a re-claimed row.
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
        "app.outbox.reap-interval-ms=3600000",
        "app.outbox.batch-size=10"
})
@Import(OutboxClaimProtocolIntegrationTest.RecordingKafkaConfig.class)
class OutboxClaimProtocolIntegrationTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class RecordingKafkaConfig {

        @Bean
        @Primary
        RecordingKafkaTemplate recordingKafkaTemplate(ProducerFactory<String, String> producerFactory) {
            return new RecordingKafkaTemplate(producerFactory);
        }
    }

    /**
     * Records every producer call. The optional barrier makes the concurrency test
     * deterministic: both workers must reach their first send before either can mark a
     * row, which is exactly the window in which an unclaimed pickup double-delivers.
     */
    static class RecordingKafkaTemplate extends KafkaTemplate<String, String> {

        private final AtomicInteger sends = new AtomicInteger();
        private final List<String> tuples = Collections.synchronizedList(new ArrayList<>());
        private volatile CountDownLatch barrier;

        RecordingKafkaTemplate(ProducerFactory<String, String> producerFactory) {
            super(producerFactory);
        }

        @Override
        protected CompletableFuture<SendResult<String, String>> doSend(
                ProducerRecord<String, String> producerRecord, Observation observation) {
            sends.incrementAndGet();
            tuples.add(producerRecord.topic() + "|" + producerRecord.key());

            CountDownLatch latch = barrier;
            if (latch != null) {
                latch.countDown();
                try {
                    latch.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.doSend(producerRecord, observation);
        }

        void armSendBarrier(int parties) {
            barrier = new CountDownLatch(parties);
        }

        void reset() {
            barrier = null;
            sends.set(0);
            tuples.clear();
        }
    }

    @Autowired
    private OutboxPublisherScheduler outboxPublisherScheduler;

    @Autowired
    private EventOutboxRepository outboxRepository;

    @Autowired
    private RecordingKafkaTemplate recordingKafkaTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        recordingKafkaTemplate.reset();
    }

    private EventOutbox pendingRow(String aggregateId) {
        return new EventOutbox(
                UUID.randomUUID(),
                "ORDER",
                aggregateId,
                "ORDER_CREATED",
                1,
                "corr-" + aggregateId,
                null,
                null,
                "bookstore",
                "{\"orderId\":1}",
                null);
    }

    private long countByStatus(OutboxStatus status) {
        return outboxRepository.findAll().stream()
                .filter(row -> row.getStatus() == status)
                .count();
    }

    /** Hibernate binds Instant as UTC wall-clock into a plain TIMESTAMP column. */
    private static Timestamp ts(Instant value) {
        return Timestamp.valueOf(value.atZone(ZoneOffset.UTC).toLocalDateTime());
    }

    /** @Modifying queries require an active transaction when invoked directly. */
    private int inTransaction(java.util.function.Supplier<Integer> write) {
        return new TransactionTemplate(transactionManager).execute(status -> write.get());
    }

    @Test
    @Timeout(120)
    @DisplayName("R-CLAIM-1a: two concurrent schedulers do not double-deliver")
    void concurrentSchedulersDoNotDoubleDeliver() throws Exception {
        List<EventOutbox> rows = IntStream.range(0, 20)
                .mapToObj(i -> pendingRow("k" + i))
                .toList();
        outboxRepository.saveAll(rows);

        recordingKafkaTemplate.armSendBarrier(2);
        CyclicBarrier startGate = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> {
                startGate.await();
                outboxPublisherScheduler.publishPendingEvents();
                return null;
            });
            Future<?> second = pool.submit(() -> {
                startGate.await();
                outboxPublisherScheduler.publishPendingEvents();
                return null;
            });

            first.get(90, TimeUnit.SECONDS);
            second.get(90, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(20, recordingKafkaTemplate.sends.get(),
                "each outbox row must be delivered exactly once across two workers");
        assertEquals(20, recordingKafkaTemplate.tuples.stream().distinct().count(),
                "no (topic, key) pair may be delivered twice");
        assertEquals(20, countByStatus(OutboxStatus.PUBLISHED),
                "every claimed row must end PUBLISHED");
        assertEquals(0, countByStatus(OutboxStatus.PENDING),
                "no row may be left behind by the claim protocol");
        for (EventOutbox row : outboxRepository.findAll()) {
            assertEquals(OutboxStatus.PUBLISHED, row.getStatus());
            assertNull(row.getClaimToken(), "a completed row must have its fence cleared");
            assertNotNull(row.getPublishedAt(), "a completed row must carry a publication timestamp");
        }
    }

    @Test
    @Timeout(120)
    @DisplayName("R-CLAIM-1b: one tick never claims more than the configured batch size")
    void batchLimitEnforced() {
        List<EventOutbox> rows = IntStream.range(0, 500)
                .mapToObj(i -> pendingRow("b" + i))
                .toList();
        outboxRepository.saveAll(rows);

        outboxPublisherScheduler.publishPendingEvents();

        assertEquals(10, countByStatus(OutboxStatus.PUBLISHED),
                "one tick must process exactly the configured batch size");
        assertEquals(490, countByStatus(OutboxStatus.PENDING),
                "rows beyond the batch limit must stay pending for the next tick");
        assertEquals(0, countByStatus(OutboxStatus.CLAIMED),
                "successful marks must leave no CLAIMED rows");
        assertEquals(10, recordingKafkaTemplate.sends.get(),
                "one send per claimed row, no more");
    }

    // GREEN_INVARIANT: the fence predicate is only expressible after V10 adds claim_token;
    // this test cannot compile or run against the pre-migration HEAD, so it was added after
    // the migration landed rather than claimed as red evidence.

    @Test
    @Timeout(30)
    @DisplayName("R-CLAIM-1c (GREEN_INVARIANT): a stale token is rejected by the fenced success mark")
    void fencedMarkRejectsStaleToken() {
        UUID id = UUID.randomUUID();
        UUID tokenA = UUID.randomUUID();
        UUID tokenB = UUID.randomUUID();
        outboxRepository.save(new EventOutbox(id, "ORDER", "fence-a", "ORDER_CREATED", 1,
                null, null, null, "bookstore", "{\"orderId\":1}", null));
        jdbcTemplate.update(
                "UPDATE event_outbox SET status='CLAIMED', claim_token=?, updated_at=? WHERE id=?",
                tokenA, ts(Instant.now()), id);
        jdbcTemplate.update(
                "UPDATE event_outbox SET claim_token=? WHERE id=?", tokenB, id);

        int updated = inTransaction(() -> outboxRepository.markPublishedFenced(id, tokenA, Instant.now()));

        assertEquals(0, updated, "a mark carrying the old token must update zero rows");
        EventOutbox row = outboxRepository.findById(id).orElseThrow();
        assertEquals(OutboxStatus.CLAIMED, row.getStatus(), "the row must stay with its new owner");
        assertEquals(tokenB, row.getClaimToken(), "the new token must not be cleared by the zombie");
        assertNull(row.getPublishedAt(), "no publication may be recorded by the fenced-out worker");
    }

    @Test
    @Timeout(30)
    @DisplayName("R-CLAIM-1d (GREEN_INVARIANT): a matching token publishes and clears the fence")
    void fencedMarkSucceedsAndClearsToken() {
        UUID id = UUID.randomUUID();
        UUID tokenA = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        outboxRepository.save(new EventOutbox(id, "ORDER", "fence-b", "ORDER_CREATED", 1,
                null, null, null, "bookstore", "{\"orderId\":1}", null));
        jdbcTemplate.update(
                "UPDATE event_outbox SET status='CLAIMED', claim_token=?, updated_at=? WHERE id=?",
                tokenA, ts(Instant.now()), id);

        int updated = inTransaction(() -> outboxRepository.markPublishedFenced(id, tokenA, now));

        assertEquals(1, updated, "a mark carrying the current token must update exactly one row");
        EventOutbox row = outboxRepository.findById(id).orElseThrow();
        assertEquals(OutboxStatus.PUBLISHED, row.getStatus());
        assertNull(row.getClaimToken(), "the token must be cleared on success");
        assertEquals(now, row.getPublishedAt());
        assertEquals(now, row.getUpdatedAt());
    }

    @Test
    @Timeout(30)
    @DisplayName("R-CLAIM-3a: the production constructor rejects a null transaction manager")
    void constructorRejectsNullTransactionManager() {
        assertThrows(NullPointerException.class,
                () -> new OutboxPublisherScheduler(outboxRepository, kafkaTemplate, null, 5, null),
                "the production constructor must fail fast on missing transaction management");
    }
}
