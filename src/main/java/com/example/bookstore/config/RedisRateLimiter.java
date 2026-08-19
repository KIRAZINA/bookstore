package com.example.bookstore.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

public class RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final RedisScript<Boolean> slidingWindowScript;

    public RedisRateLimiter(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.slidingWindowScript = RedisScript.of("""
                local key = KEYS[1]
                local window = tonumber(ARGV[1])
                local limit = tonumber(ARGV[2])
                local now = tonumber(ARGV[3])
                local cost = tonumber(ARGV[4])

                redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
                local count = redis.call('ZCARD', key)
                if count + cost <= limit then
                    for i = 1, cost do
                        redis.call('ZADD', key, now, now .. ':' .. math.random())
                    end
                    redis.call('PEXPIRE', key, window)
                    return 1
                end
                return 0
                """, Boolean.class);
    }

    public boolean tryConsume(String key, int limit, Duration window, int cost) {
        try {
            Boolean allowed = redisTemplate.execute(
                    slidingWindowScript,
                    List.of("ratelimit:" + key),
                    String.valueOf(window.toMillis()),
                    String.valueOf(limit),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(cost)
            );
            return Boolean.TRUE.equals(allowed);
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            log.warn("Rate limiter Redis unavailable, allowing request (fail-open): key={}", key, e);
            return true;
        }
    }
}
