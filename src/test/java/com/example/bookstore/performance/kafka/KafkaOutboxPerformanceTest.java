package com.example.bookstore.performance.kafka;

import com.example.bookstore.base.AbstractPerformanceTest;
import com.example.bookstore.event.BaseEvent;
import com.example.bookstore.event.OutboxStatus;
import com.example.bookstore.event.publisher.OutboxEventPublisher;
import com.example.bookstore.event.publisher.OutboxPublisherScheduler;
import com.example.bookstore.event.repository.EventOutboxRepository;
import com.example.bookstore.event.repository.ProcessedEventRepository;
import com.example.bookstore.performance.LatencyStatistics;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Section 7: Kafka outbox publishing throughput and latency.
 */
@Tag("performance")
class KafkaOutboxPerformanceTest extends AbstractPerformanceTest {

    private static final int EVENT_COUNT = Integer.getInteger("perf.kafka.events", 100);

    @Autowired
    private OutboxEventPublisher outboxEventPublisher;

    @Autowired
    private OutboxPublisherScheduler outboxPublisherScheduler;

    @Autowired
    private EventOutboxRepository outboxRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        processedEventRepository.deleteAll();
    }

    @Test
    @DisplayName("Section 7: Outbox publish throughput and drain latency")
    void outboxPublishThroughput() {
        LatencyStatistics publishLatencies = new LatencyStatistics(EVENT_COUNT);

        long publishStart = System.nanoTime();
        for (int i = 0; i < EVENT_COUNT; i++) {
            final int index = i;
            long start = System.nanoTime();
            transactionTemplate.executeWithoutResult(status ->
                    outboxEventPublisher.publish(
                            new PerfEvent("payload-" + index),
                            "PERF",
                            "agg-" + index,
                            "PERF_EVENT"
                    )
            );
            publishLatencies.recordNanos(System.nanoTime() - start);
        }
        long publishNanos = System.nanoTime() - publishStart;

        Awaitility.await()
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> outboxRepository.count() >= EVENT_COUNT);

        long drainStart = System.nanoTime();
        Awaitility.await()
                .atMost(60, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> {
                    outboxPublisherScheduler.publishPendingEvents();
                    long pending = outboxRepository.findAll().stream()
                            .filter(e -> e.getStatus() == OutboxStatus.PENDING)
                            .count();
                    return pending == 0;
                });
        long drainNanos = System.nanoTime() - drainStart;

        double publishThroughput = EVENT_COUNT / (publishNanos / 1_000_000_000.0);
        double drainThroughput = EVENT_COUNT / (drainNanos / 1_000_000_000.0);

        System.out.printf("%n=== Kafka Outbox (%d events) ===%n", EVENT_COUNT);
        System.out.printf("  publish throughput=%.2f events/s%n", publishThroughput);
        System.out.printf("  drain throughput=%.2f events/s%n", drainThroughput);
        publishLatencies.snapshot("Outbox publish (transactional)").log();

        assertTrue(outboxRepository.count() >= EVENT_COUNT);
    }

    private static final class PerfEvent extends BaseEvent {
        private final String data;

        PerfEvent(String data) {
            this.data = data;
        }

        public String getData() {
            return data;
        }
    }
}
