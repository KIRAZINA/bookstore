package com.example.bookstore.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;

@Configuration
public class RedisRateLimiterConfig {

    @Bean
    public RedisRateLimiter redisRateLimiter(ObjectProvider<RedisTemplate<String, String>> redisTemplateProvider) {
        return new RedisRateLimiter(redisTemplateProvider.getIfAvailable());
    }
}