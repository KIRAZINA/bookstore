package com.example.bookstore.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Runs Flyway migrations at startup.
 *
 * <p>This is the single migration entry point. Spring Boot's Flyway auto-configuration is
 * inactive here because Flyway 11.x no longer ships the {@code DatabaseFactory} class that
 * Spring Boot 4's {@code FlywayAutoConfiguration} condition requires, so no {@code Flyway}
 * bean is auto-created. As a result the schema is migrated exactly once, and deleting this
 * class would leave the schema uninitialized.
 *
 * <p>Enabled via {@code spring.flyway.enabled} (defaults in {@code application.yml}); the
 * {@code locations} and {@code baseline-on-migrate} values mirror that configuration.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", havingValue = "true", matchIfMissing = false)
public class FlywayConfig {

    public FlywayConfig(DataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load();
        flyway.migrate();
    }
}
