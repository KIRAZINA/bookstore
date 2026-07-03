package com.example.bookstore.config;

import com.example.bookstore.base.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

class RedisRateLimiterIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private RedisRateLimiter rateLimiter;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Test
    @DisplayName("Request within limit is allowed")
    void tryConsume_WithinLimit_ReturnsTrue() {
        String key = "test:within-limit-" + System.nanoTime();

        assertTrue(rateLimiter.tryConsume(key, 10, Duration.ofMinutes(1), 1));
        assertTrue(rateLimiter.tryConsume(key, 10, Duration.ofMinutes(1), 1));
    }

    @Test
    @DisplayName("Request exceeding limit is denied")
    void tryConsume_ExceedsLimit_ReturnsFalse() {
        String key = "test:exceeds-limit-" + System.nanoTime();

        for (int i = 0; i < 5; i++) {
            assertTrue(rateLimiter.tryConsume(key, 5, Duration.ofMinutes(1), 1),
                    "Request " + (i + 1) + " should be allowed");
        }

        assertFalse(rateLimiter.tryConsume(key, 5, Duration.ofMinutes(1), 1),
                "6th request should be denied");
    }

    @Test
    @DisplayName("Limit resets after window expires")
    void tryConsume_WindowExpires_ResetsLimit() throws InterruptedException {
        String key = "test:window-reset-" + System.nanoTime();

        assertTrue(rateLimiter.tryConsume(key, 2, Duration.ofSeconds(2), 1));
        assertTrue(rateLimiter.tryConsume(key, 2, Duration.ofSeconds(2), 1));
        assertFalse(rateLimiter.tryConsume(key, 2, Duration.ofSeconds(2), 1));

        TimeUnit.SECONDS.sleep(3);

        assertTrue(rateLimiter.tryConsume(key, 2, Duration.ofSeconds(2), 1),
                "Limit should reset after window expires");
    }

    @Test
    @DisplayName("Different keys have independent rate limits")
    void tryConsume_MultipleKeys_IndependentLimits() {
        String keyA = "test:independent-a-" + System.nanoTime();
        String keyB = "test:independent-b-" + System.nanoTime();

        for (int i = 0; i < 5; i++) {
            assertTrue(rateLimiter.tryConsume(keyA, 5, Duration.ofMinutes(1), 1));
        }
        assertFalse(rateLimiter.tryConsume(keyA, 5, Duration.ofMinutes(1), 1));

        assertTrue(rateLimiter.tryConsume(keyB, 5, Duration.ofMinutes(1), 1),
                "Different key should have its own limit");
    }

    @Test
    @DisplayName("Cost greater than 1 consumes multiple capacity units")
    void tryConsume_MultipleCost_ConsumesCapacity() {
        String key = "test:multi-cost-" + System.nanoTime();

        assertTrue(rateLimiter.tryConsume(key, 5, Duration.ofMinutes(1), 3));
        assertTrue(rateLimiter.tryConsume(key, 5, Duration.ofMinutes(1), 2));
        assertFalse(rateLimiter.tryConsume(key, 5, Duration.ofMinutes(1), 1),
                "Should be denied after consuming all capacity");
    }

    @Test
    @DisplayName("Redis sorted set entries are cleaned up after TTL")
    void tryConsume_EntriesExpire_AfterTtl() throws InterruptedException {
        String key = "test:ttl-cleanup-" + System.nanoTime();

        rateLimiter.tryConsume(key, 100, Duration.ofSeconds(3), 1);

        TimeUnit.SECONDS.sleep(4);

        Long size = redisTemplate.opsForZSet().size("ratelimit:" + key);
        assertNotNull(size);
        assertEquals(0, size, "All expired entries should be cleaned up");
    }
}
