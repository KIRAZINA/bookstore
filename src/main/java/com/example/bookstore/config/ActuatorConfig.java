package com.example.bookstore.config;

import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.util.Map;

@Configuration
public class ActuatorConfig {

    @Bean
    public HealthIndicator redisHealthIndicator(ObjectProvider<RedisConnectionFactory> connectionFactoryProvider) {
        RedisConnectionFactory connectionFactory = connectionFactoryProvider.getIfAvailable();
        if (connectionFactory == null) {
            return () -> Health.up().withDetail("service", "Redis").withDetail("enabled", false).build();
        }
        return () -> {
            try {
                RedisConnection connection = connectionFactory.getConnection();
                connection.ping();
                connection.close();
                return Health.up().withDetail("service", "Redis").build();
            } catch (Exception e) {
                return Health.down(e).withDetail("service", "Redis").build();
            }
        };
    }

    @Bean
    public InfoContributor bookstoreInfoContributor() {
        return builder -> {
            builder.withDetail("application", Map.of(
                    "name", "Bookstore API",
                    "description", "REST API for an online bookstore",
                    "stage", "Stage 7 — Production Observability",
                    "java", Runtime.version().toString(),
                    "processors", Runtime.getRuntime().availableProcessors(),
                    "maxMemory", Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MB"
            ));
        };
    }
}
