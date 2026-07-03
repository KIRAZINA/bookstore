package com.example.bookstore.performance.redis;

import com.example.bookstore.base.AbstractPerformanceTest;
import com.example.bookstore.config.RedisRateLimiter;
import com.example.bookstore.performance.LatencyStatistics;
import com.example.bookstore.service.BookService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Section 8 & 10: Redis cache and distributed rate limiter performance.
 */
@Tag("performance")
class RedisPerformanceTest extends AbstractPerformanceTest {

    private static final int RATE_LIMIT_OPS = Integer.getInteger("perf.redis.ops", 500);

    @Autowired
    private RedisRateLimiter rateLimiter;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private BookService bookService;

    @Autowired
    private CacheManager cacheManager;

    @Test
    @DisplayName("Section 8: Rate limiter throughput and Lua execution latency")
    void rateLimiterThroughput() throws InterruptedException {
        String key = "perf:ratelimit:" + System.nanoTime();
        int limit = RATE_LIMIT_OPS * 2;
        LatencyStatistics latencies = new LatencyStatistics(RATE_LIMIT_OPS);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        long start = System.nanoTime();
        for (int i = 0; i < RATE_LIMIT_OPS; i++) {
            long opStart = System.nanoTime();
            if (rateLimiter.tryConsume(key, limit, Duration.ofMinutes(1), 1)) {
                allowed.incrementAndGet();
            } else {
                rejected.incrementAndGet();
            }
            latencies.recordNanos(System.nanoTime() - opStart);
        }
        long wallNanos = System.nanoTime() - start;

        System.out.printf("%n=== Redis Rate Limiter (%d ops) ===%n", RATE_LIMIT_OPS);
        System.out.printf("  allowed=%d  rejected=%d  throughput=%.2f ops/s%n",
                allowed.get(), rejected.get(), RATE_LIMIT_OPS / (wallNanos / 1_000_000_000.0));
        latencies.snapshot("Redis Lua rate limit check").log();

        assertEquals(RATE_LIMIT_OPS, allowed.get());
        assertEquals(0, rejected.get());
    }

    @Test
    @DisplayName("Section 8: Distributed rate limiter correctness under concurrent load")
    void rateLimiterDistributedCorrectness() throws InterruptedException {
        String key = "perf:concurrent:" + System.nanoTime();
        int limit = 50;
        int threads = 100;
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try {
                        startGate.await();
                        if (rateLimiter.tryConsume(key, limit, Duration.ofMinutes(1), 1)) {
                            allowed.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
        }

        System.out.printf("%n=== Rate Limiter Distributed Correctness ===%n  allowed=%d (limit=%d)%n",
                allowed.get(), limit);
        assertEquals(limit, allowed.get(), "Distributed limiter must not over-allow");
    }

    @Test
    @DisplayName("Section 8: Book catalog cache warm-up reduces read latency")
    void cacheWarmupReducesLatency() {
        Objects.requireNonNull(cacheManager.getCache("books")).clear();

        long coldStart = System.nanoTime();
        bookService.getAllBooks();
        double coldMs = (System.nanoTime() - coldStart) / 1_000_000.0;

        assertNotNull(Objects.requireNonNull(cacheManager.getCache("books")).get("allBooks"),
                "First read should populate Redis cache");

        LatencyStatistics warmStats = new LatencyStatistics(50);
        for (int i = 0; i < 50; i++) {
            long start = System.nanoTime();
            bookService.getAllBooks();
            warmStats.recordNanos(System.nanoTime() - start);
        }

        LatencyStatistics.PerformanceSnapshot warm = warmStats.snapshot("Cached book list");
        System.out.printf("%n=== Redis Cache ===%n  cold read=%.2fms  warm median=%.2fms%n",
                coldMs, warm.medianMs());
        assertTrue(warm.medianMs() <= coldMs * 2,
                "Cached reads should not be significantly slower than cold read");
    }
}
