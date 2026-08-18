package com.example.bookstore.event;

import com.example.bookstore.event.repository.EventOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Component
@ConditionalOnProperty(value = "app.outbox.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanupScheduler.class);

    private final EventOutboxRepository outboxRepository;
    private final Duration retention;

    public OutboxCleanupScheduler(EventOutboxRepository outboxRepository,
                                   @Value("${app.outbox.cleanup.retention:7d}") Duration retention) {
        this.outboxRepository = outboxRepository;
        this.retention = retention;
    }

    @Scheduled(cron = "${app.outbox.cleanup.cron:0 0 3 * * ?}")
    @Transactional
    public void cleanupPublishedEvents() {
        Instant cutoff = Instant.now().minus(retention);
        int deleted = outboxRepository.deletePublishedOlderThan(cutoff);
        if (deleted > 0) {
            log.info("Cleaned up {} PUBLISHED outbox events older than {}", deleted, retention);
        }
    }
}
