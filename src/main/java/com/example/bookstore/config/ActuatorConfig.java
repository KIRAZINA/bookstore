package com.example.bookstore.config;

import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.util.Map;

@Configuration
public class ActuatorConfig {

    @Bean
    @ConditionalOnBean(RedisConnectionFactory.class)
    public HealthIndicator redisHealthIndicator(RedisConnectionFactory connectionFactory) {
        return () -> {
            try {
                var connection = connectionFactory.getConnection();
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
