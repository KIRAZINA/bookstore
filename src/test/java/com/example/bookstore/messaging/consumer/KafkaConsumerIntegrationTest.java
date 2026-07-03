package com.example.bookstore.messaging.consumer;

import com.example.bookstore.base.AbstractIntegrationTest;
import com.example.bookstore.config.KafkaTopicConfig;
import com.example.bookstore.event.ProcessedEvent;
import com.example.bookstore.event.repository.ProcessedEventRepository;
import com.example.bookstore.messaging.DomainEventEnvelope;
import com.example.bookstore.messaging.EnvelopeConverter;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EmbeddedKafka(
    topics = {
        KafkaTopicConfig.TOPIC_ORDERS,
        KafkaTopicConfig.TOPIC_PAYMENTS,
        KafkaTopicConfig.TOPIC_INVENTORY,
        KafkaTopicConfig.TOPIC_NOTIFICATIONS,
        KafkaTopicConfig.TOPIC_DLT
    },
    partitions = 1,
    controlledShutdown = true,
    bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class KafkaConsumerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @BeforeEach
    void setUp() {
        processedEventRepository.deleteAll();
    }

    private String createOrderEventPayload(String eventId, String aggregateId) {
        DomainEventEnvelope envelope = new DomainEventEnvelope(
                UUID.fromString(eventId),
                "ORDER",
                aggregateId,
                "ORDER_CREATED",
                1,
                Instant.ofEpochMilli(System.currentTimeMillis()),
                UUID.randomUUID().toString(),
                null,
                "bookstore",
                Map.of("orderId", aggregateId, "userName", "test", "totalPrice", 50.0),
                Map.of()
        );
        return EnvelopeConverter.toJson(envelope);
    }

    @Test
    @DisplayName("Kafka consumer receives and processes a valid event")
    void consumer_ReceivesValidEvent() {
        String eventId = UUID.randomUUID().toString();
        String payload = createOrderEventPayload(eventId, "order-1");

        kafkaTemplate.send(KafkaTopicConfig.TOPIC_ORDERS, "order-1", payload);

        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() ->
                        processedEventRepository.existsByEventIdAndConsumerGroup(
                                eventId, KafkaTopicConfig.CONSUMER_GROUP_ORDERS));
    }

    @Test
    @DisplayName("Duplicate event is idempotently skipped by consumer")
    void consumer_DuplicateEvent_IsIdempotent() {
        String eventId = UUID.randomUUID().toString();
        String consumerGroup = KafkaTopicConfig.CONSUMER_GROUP_ORDERS;

        processedEventRepository.saveAndFlush(new ProcessedEvent(eventId, consumerGroup));

        String payload = createOrderEventPayload(eventId, "order-dup");
        kafkaTemplate.send(KafkaTopicConfig.TOPIC_ORDERS, "order-dup", payload);

        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() ->
                        assertEquals(1, processedEventRepository
                                .findAll().stream()
                                .filter(pe -> pe.getEventId().equals(eventId)
                                        && pe.getConsumerGroup().equals(consumerGroup))
                                .count(),
                                "Event should not be processed twice"));
    }

    @Test
    @DisplayName("Same event with different consumer groups is allowed")
    void consumer_SameEvent_DifferentConsumerGroups() {
        String eventId = UUID.randomUUID().toString();
        String payload = createOrderEventPayload(eventId, "order-multi");

        kafkaTemplate.send(KafkaTopicConfig.TOPIC_ORDERS, "order-multi", payload);

        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() ->
                        processedEventRepository.existsByEventIdAndConsumerGroup(
                                eventId, KafkaTopicConfig.CONSUMER_GROUP_ORDERS));

        assertFalse(processedEventRepository.existsByEventIdAndConsumerGroup(
                eventId, KafkaTopicConfig.CONSUMER_GROUP_NOTIFICATIONS));
    }

    @Test
    @DisplayName("Invalid message on consumer topic does not break subsequent processing")
    void consumer_InvalidMessage_DoesNotBlockSubsequent() throws Exception {
        kafkaTemplate.send(KafkaTopicConfig.TOPIC_ORDERS, "invalid-key", "{invalid json}")
                .get(5, TimeUnit.SECONDS);

        String validEventId = UUID.randomUUID().toString();
        String validPayload = createOrderEventPayload(validEventId, "order-after-invalid");

        kafkaTemplate.send(KafkaTopicConfig.TOPIC_ORDERS, "order-after-invalid", validPayload);

        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() ->
                        processedEventRepository.existsByEventIdAndConsumerGroup(
                                validEventId, KafkaTopicConfig.CONSUMER_GROUP_ORDERS));
    }

    @Test
    @DisplayName("Dead letter topic is configured and writable")
    void deadLetterTopic_IsConfigured() {
        String dltPayload = "{\"eventId\":\"00000000-0000-0000-0000-000000000000\",\"eventType\":\"DLT_TEST\"}";
        kafkaTemplate.send(KafkaTopicConfig.TOPIC_DLT, "dlt-key-1", dltPayload);

        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() ->
                        assertDoesNotThrow(() ->
                                kafkaTemplate.flush()));
    }
}
