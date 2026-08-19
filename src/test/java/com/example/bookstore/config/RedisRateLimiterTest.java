package com.example.bookstore.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
class RedisRateLimiterTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    private RedisRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new RedisRateLimiter(redisTemplate);
    }

    @Nested
    @DisplayName("tryConsume")
    class TryConsume {
        @Test
        @DisplayName("Returns true when Redis returns true")
        void allowed() {
            when(redisTemplate.execute(any(), anyList(), any(Object[].class)))
                    .thenReturn(true);

            assertTrue(rateLimiter.tryConsume("test-key", 10, Duration.ofMinutes(1), 1));
        }

        @Test
        @DisplayName("Returns false when Redis returns false")
        void denied() {
            when(redisTemplate.execute(any(), anyList(), any(Object[].class)))
                    .thenReturn(false);

            assertFalse(rateLimiter.tryConsume("test-key", 10, Duration.ofMinutes(1), 1));
        }

        @Test
        @DisplayName("Returns true (fail-open) when Redis connection fails")
        void redisDown() {
            when(redisTemplate.execute(any(), anyList(), any(Object[].class)))
                    .thenThrow(new RedisConnectionFailureException("Redis connection refused"));

            assertTrue(rateLimiter.tryConsume("test-key", 10, Duration.ofMinutes(1), 1));
        }

        @Test
        @DisplayName("Propagates non-Redis exceptions (does not mask logic bugs as fail-open)")
        void nonRedisExceptionPropagates() {
            when(redisTemplate.execute(any(), anyList(), any(Object[].class)))
                    .thenThrow(new IllegalStateException("Rate limiter script bug"));

            assertThrows(IllegalStateException.class,
                    () -> rateLimiter.tryConsume("test-key", 10, Duration.ofMinutes(1), 1));
        }

        @Test
        @DisplayName("Passes correct script arguments")
        void passesCorrectArgs() {
            when(redisTemplate.execute(any(), anyList(), any(Object[].class)))
                    .thenReturn(true);

            rateLimiter.tryConsume("user-1", 100, Duration.ofSeconds(30), 2);

            ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.captor();
            verify(redisTemplate).execute(any(), keysCaptor.capture(), any(Object[].class));

            assertEquals(List.of("ratelimit:user-1"), keysCaptor.getValue());
        }

        @Test
        @DisplayName("Uses key prefix")
        void usesKeyPrefix() {
            when(redisTemplate.execute(any(), anyList(), any(Object[].class)))
                    .thenReturn(true);

            rateLimiter.tryConsume("192.168.1.1:GET:/api/v1/books", 100, Duration.ofMinutes(1), 1);

            ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.captor();
            verify(redisTemplate).execute(any(), keysCaptor.capture(), any(Object[].class));
            assertTrue(keysCaptor.getValue().get(0).startsWith("ratelimit:"));
        }
    }
}
