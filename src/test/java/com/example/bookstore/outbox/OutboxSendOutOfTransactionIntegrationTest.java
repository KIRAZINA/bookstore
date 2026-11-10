package com.example.bookstore.outbox;

import com.example.bookstore.base.AbstractIntegrationTest;
import com.example.bookstore.config.KafkaTopicConfig;
import com.example.bookstore.event.BaseEvent;
import com.example.bookstore.event.EventOutbox;
import com.example.bookstore.event.OutboxStatus;
import com.example.bookstore.event.publisher.OutboxEventPublisher;
import com.example.bookstore.event.publisher.OutboxPublisherScheduler;
import com.example.bookstore.event.repository.EventOutboxRepository;
import io.micrometer.observation.Observation;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Evidence for D-OUTBOX-1: the outbox must persist the intent inside the caller
 * transaction and deliver it to Kafka only from the scheduler, outside any
 * database transaction.
 *
 * <p>The recording template captures
 * {@link TransactionSynchronizationManager#isActualTransactionActive()} at the exact
 * moment the Kafka producer is invoked, which is the property under test rather than a
 * proxy observation of the scheduler method.</p>
 */
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
@TestPropertySource(properties = "app.outbox.poll-interval=3600000")
@Import(OutboxSendOutOfTransactionIntegrationTest.RecordingKafkaConfig.class)
class OutboxSendOutOfTransactionIntegrationTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class RecordingKafkaConfig {

        @Bean
        @Primary
        RecordingKafkaTemplate recordingKafkaTemplate(ProducerFactory<String, String> producerFactory) {
            return new RecordingKafkaTemplate(producerFactory);
        }
    }

    /**
     * Records the transaction state observed by the producer call. Extends the real
     * template so delivery still goes to the embedded broker; nothing is stubbed.
     */
    static class RecordingKafkaTemplate extends KafkaTemplate<String, String> {

        private final AtomicInteger sends = new AtomicInteger();
        private final List<Boolean> transactionActiveFlags =
                Collections.synchronizedList(new ArrayList<>());

        RecordingKafkaTemplate(ProducerFactory<String, String> producerFactory) {
            super(producerFactory);
        }

        @Override
        protected CompletableFuture<SendResult<String, String>> doSend(
                ProducerRecord<String, String> producerRecord, Observation observation) {
            sends.incrementAndGet();
            transactionActiveFlags.add(
                    TransactionSynchronizationManager.isActualTransactionActive());
            return super.doSend(producerRecord, observation);
        }

        void reset() {
            sends.set(0);
            transactionActiveFlags.clear();
        }
    }

    /**
     * A local event type keeps the test independent of the domain listeners; the
     * outbox row is what this test exercises.
     */
    static class OutboxTxTestEvent extends BaseEvent {
        private final String marker = "outbox-tx-boundary";

        public String getMarker() {
            return marker;
        }
    }

    @Autowired
    private OutboxEventPublisher outboxEventPublisher;

    @Autowired
    private OutboxPublisherScheduler outboxPublisherScheduler;

    @Autowired
    private EventOutboxRepository outboxRepository;

    @Autowired
    private RecordingKafkaTemplate recordingKafkaTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        recordingKafkaTemplate.reset();
    }

    private TransactionTemplate callerTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        return template;
    }

    @Test
    @Timeout(20)
    @DisplayName("R-OUTBOX-1a: the Kafka send executes outside any database transaction")
    void kafkaSendHappensOutsideTransaction() {
        callerTransaction().executeWithoutResult(status -> outboxEventPublisher.publish(
                new OutboxTxTestEvent(), "NOTIFICATION", "9001", "NOTIFICATION_SENT"));

        assertEquals(0, recordingKafkaTemplate.sends.get(),
                "publish() must only persist the outbox row; delivery belongs to the scheduler");

        outboxPublisherScheduler.publishPendingEvents();

        assertEquals(1, recordingKafkaTemplate.sends.get(),
                "the scheduler must deliver the pending row to Kafka");
        assertFalse(recordingKafkaTemplate.transactionActiveFlags.isEmpty(),
                "the producer must have been invoked");
        assertTrue(recordingKafkaTemplate.transactionActiveFlags.stream()
                        .noneMatch(Boolean::booleanValue),
                "the Kafka send must not observe an active database transaction: "
                        + recordingKafkaTemplate.transactionActiveFlags);
    }

    @Test
    @Timeout(20)
    @DisplayName("R-OUTBOX-1b: publish commits a PENDING row and sends nothing")
    void publishCommitsRowEvenIfSchedulerNotYetRun() {
        callerTransaction().executeWithoutResult(status -> outboxEventPublisher.publish(
                new OutboxTxTestEvent(), "NOTIFICATION", "9002", "NOTIFICATION_SENT"));

        List<EventOutbox> rows = outboxRepository.findAll();
        assertEquals(1, rows.size(), "exactly one outbox row must be committed");
        assertEquals(OutboxStatus.PENDING, rows.get(0).getStatus(),
                "the row must stay PENDING until the scheduler delivers it");
        assertNull(rows.get(0).getPublishedAt(),
                "a pending row must not carry a publication timestamp");
        assertEquals(0, recordingKafkaTemplate.sends.get(),
                "committing the row must not trigger an inline Kafka send");
    }
}
