package com.example.bookstore.chaos;

import com.example.bookstore.event.BaseEvent;
import com.example.bookstore.event.EventOutbox;
import com.example.bookstore.event.OutboxStatus;
import com.example.bookstore.event.publisher.OutboxEventPublisher;
import com.example.bookstore.event.repository.EventOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Kafka chaos tests — outbox pattern and recovery")
class KafkaChaosTest extends AbstractChaosTest {

    @Autowired
    private EventOutboxRepository eventOutboxRepository;

    @Autowired
    private OutboxEventPublisher outboxEventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        eventOutboxRepository.deleteAll();
    }

    @Test
    @DisplayName("Should persist events to outbox table even if Kafka is unavailable")
    void shouldPersistEventsToOutboxWhenKafkaIsUnavailable() {
        String eventId = UUID.randomUUID().toString();
        TestEvent event = new TestEvent("test-data-" + eventId);

        transactionTemplate.executeWithoutResult(status -> {
            outboxEventPublisher.publish(event, "test-aggregate", eventId, "TEST_EVENT");
        });

        EventOutbox outbox = eventOutboxRepository.findAll().getFirst();

        assertNotNull(outbox);
        assertEquals("TEST_EVENT", outbox.getEventType());
        assertEquals(OutboxStatus.PENDING, outbox.getStatus());
    }

    @Test
    @DisplayName("Should have multiple outbox entries if multiple events published")
    void shouldPersistMultipleEventsToOutbox() {
        for (int i = 0; i < 5; i++) {
            final int index = i;
            String eventId = UUID.randomUUID().toString();
            TestEvent event = new TestEvent("data-" + index);
            transactionTemplate.executeWithoutResult(status -> {
                outboxEventPublisher.publish(event, "agg", eventId, "EVENT_" + index);
            });
        }

        assertEquals(5, eventOutboxRepository.count());
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
