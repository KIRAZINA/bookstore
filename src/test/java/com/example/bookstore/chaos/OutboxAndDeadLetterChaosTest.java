package com.example.bookstore.chaos;

import com.example.bookstore.config.DeadLetterService;
import com.example.bookstore.event.BaseEvent;
import com.example.bookstore.event.EventOutbox;
import com.example.bookstore.event.OutboxStatus;
import com.example.bookstore.event.publisher.OutboxEventPublisher;
import com.example.bookstore.event.publisher.OutboxPublisherScheduler;
import com.example.bookstore.event.repository.EventOutboxRepository;
import com.example.bookstore.event.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Outbox & Dead Letter Queue chaos tests — event persistence and recovery")
class OutboxAndDeadLetterChaosTest extends AbstractChaosTest {

    @Autowired
    private EventOutboxRepository eventOutboxRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private OutboxEventPublisher outboxEventPublisher;

    @Autowired
    private OutboxPublisherScheduler outboxPublisherScheduler;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired(required = false)
    private DeadLetterService deadLetterService;

    @BeforeEach
    void setUp() {
        eventOutboxRepository.deleteAll();
        processedEventRepository.deleteAll();
    }

    @Test
    @DisplayName("Should persist events to outbox table transactionally")
    void shouldPersistEventsToOutboxTransactionally() {
        String eventId = UUID.randomUUID().toString();
        TestEvent event = new TestEvent("test-transaction-data");

        transactionTemplate.executeWithoutResult(status -> {
            outboxEventPublisher.publish(event, "test-agg", eventId, "TRANSACTIONAL_TEST");
        });

        EventOutbox outboxEntry = eventOutboxRepository.findAll().getFirst();

        assertNotNull(outboxEntry);
        assertEquals("TRANSACTIONAL_TEST", outboxEntry.getEventType());
        assertEquals(OutboxStatus.PENDING, outboxEntry.getStatus());
    }

    @Test
    @DisplayName("Should have pending outbox entries before publisher runs")
    void shouldHavePendingOutboxEntriesBeforePublish() {
        for (int i = 0; i < 3; i++) {
            final int index = i;
            String eventId = UUID.randomUUID().toString();
            TestEvent event = new TestEvent("data-" + index);
            transactionTemplate.executeWithoutResult(status -> {
                outboxEventPublisher.publish(event, "agg", eventId, "EVENT_" + index);
            });
        }

        assertEquals(3, eventOutboxRepository.count());
        assertTrue(eventOutboxRepository.findAll().stream()
                .allMatch(e -> e.getStatus() == OutboxStatus.PENDING));
    }

    static class TestEvent extends BaseEvent {
        private final String data;

        TestEvent(String data) {
            this.data = data;
        }

        public String getData() {
            return data;
        }
    }
}
