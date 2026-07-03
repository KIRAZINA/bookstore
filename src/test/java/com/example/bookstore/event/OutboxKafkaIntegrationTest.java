package com.example.bookstore.event;

import com.example.bookstore.base.AbstractIntegrationTest;
import com.example.bookstore.event.publisher.OutboxEventPublisher;
import com.example.bookstore.event.repository.EventOutboxRepository;
import com.example.bookstore.event.repository.ProcessedEventRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EmbeddedKafka(
    topics = { "test.events" },
    partitions = 1,
    controlledShutdown = true,
    bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class OutboxKafkaIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxEventPublisher outboxEventPublisher;

    @Autowired
    private EventOutboxRepository outboxRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        processedEventRepository.deleteAll();
    }

    @Test
    @DisplayName("Event published via outbox appears in outbox table with PENDING status")
    void outbox_storesEventOnPublish() {
        TestEvent event = new TestEvent("test-data");

        transactionTemplate.executeWithoutResult(status ->
            outboxEventPublisher.publish(event, "TEST", "agg-1", "TEST_EVENT")
        );

        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> outboxRepository.count() > 0);

        EventOutbox stored = outboxRepository.findAll().iterator().next();
        assertEquals("TEST_EVENT", stored.getEventType());
        assertEquals("PENDING", stored.getStatus().name());
        assertEquals("agg-1", stored.getAggregateId());
        assertNotNull(stored.getPayload());
    }

    @Test
    @DisplayName("Outbox event stores metadata correctly")
    void outbox_storesMetadataCorrectly() {
        TestEvent event = new TestEvent("{\"orderId\":42}");

        transactionTemplate.executeWithoutResult(status ->
            outboxEventPublisher.publish(event, "ORDER", "order-42", "ORDER_CREATED")
        );

        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> outboxRepository.count() > 0);

        EventOutbox stored = outboxRepository.findAll().iterator().next();
        assertEquals("ORDER", stored.getAggregateType());
        assertEquals("order-42", stored.getAggregateId());
        assertEquals(0, stored.getRetryCount());
        assertNotNull(stored.getCreatedAt());
    }

    @Test
    @DisplayName("Processed event unique constraint prevents duplicate processing")
    void processedEvent_uniqueConstraint_preventsDuplicates() {
        String eventId = UUID.randomUUID().toString();
        String consumerGroup = "test-consumer";

        processedEventRepository.saveAndFlush(new ProcessedEvent(eventId, consumerGroup));

        assertTrue(processedEventRepository.existsByEventIdAndConsumerGroup(eventId, consumerGroup));

        assertThrows(DataIntegrityViolationException.class,
                () -> processedEventRepository.saveAndFlush(new ProcessedEvent(eventId, consumerGroup)));
    }

    @Test
    @DisplayName("Processed event allows same eventId for different consumer groups")
    void processedEvent_differentConsumerGroup_allowed() {
        String eventId = UUID.randomUUID().toString();

        processedEventRepository.saveAndFlush(new ProcessedEvent(eventId, "consumer-a"));
        processedEventRepository.saveAndFlush(new ProcessedEvent(eventId, "consumer-b"));

        assertEquals(2, processedEventRepository.count());
    }

    public static class TestEvent extends BaseEvent {
        private final String data;

        public TestEvent(String data) {
            this.data = data;
        }

        public String getData() { return data; }
    }
}
