package com.example.bookstore.outbox;

import com.example.bookstore.base.AbstractIntegrationTest;
import com.example.bookstore.config.KafkaTopicConfig;
import com.example.bookstore.event.EventOutbox;
import com.example.bookstore.event.OutboxStatus;
import com.example.bookstore.event.publisher.OutboxPublisherScheduler;
import com.example.bookstore.event.repository.EventOutboxRepository;
import com.example.bookstore.event.repository.ProcessedEventRepository;
import com.example.bookstore.messaging.DomainEventEnvelope;
import com.example.bookstore.messaging.EnvelopeConverter;
import com.example.bookstore.messaging.consumer.NotificationEventConsumer;
import com.example.bookstore.notification.email.EmailSender;
import com.example.bookstore.notification.model.EmailLog;
import com.example.bookstore.notification.model.EmailStatus;
import com.example.bookstore.notification.repository.EmailLogRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Evidence for D-OUTBOX-2: the notification consumer persists an email intent in the
 * outbox inside the dedup transaction, and a worker delivers it after commit.
 *
 * <p>The third test is a specification of the residual that remains: if SMTP succeeds
 * but the row cannot be marked as published, the next tick re-sends the email
 * (at-least-once delivery without a distributed transaction).</p>
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
@TestPropertySource(properties = {
        "app.notifications.delivery-mode=kafka",
        "app.outbox.poll-interval=3600000"
})
@Import(EmailOutboxDuplicateResidualIntegrationTest.OutboxTestConfig.class)
class EmailOutboxDuplicateResidualIntegrationTest extends AbstractIntegrationTest {

    private static final String RECIPIENT = "outbox-test@example.com";
    private static final String EMAIL_EVENT_TYPE = "NOTIFICATION_REQUESTED";

    @TestConfiguration
    static class OutboxTestConfig {

        @Bean
        @Primary
        RecordingEmailSender recordingEmailSender() {
            return new RecordingEmailSender();
        }

        @Bean
        @Primary
        OutboxFaultInjector outboxFaultInjector() {
            return new OutboxFaultInjector();
        }

        /**
         * Delegating repository that can force a single failure of {@code markPublished}
         * so the at-least-once residual can be reproduced deterministically. Every other
         * call is forwarded to the real Spring Data repository.
         */
        @Bean
        @Primary
        EventOutboxRepository faultInjectingOutboxRepository(ApplicationContext context,
                                                            OutboxFaultInjector injector) {
            return (EventOutboxRepository) Proxy.newProxyInstance(
                    EventOutboxRepository.class.getClassLoader(),
                    new Class<?>[]{EventOutboxRepository.class},
                    (proxy, method, args) -> {
                        if (injector.failMarkPublished.get()
                                && "markPublished".equals(method.getName())) {
                            throw new IllegalStateException("forced markPublished failure");
                        }
                        EventOutboxRepository delegate = context.getBean(
                                "eventOutboxRepository", EventOutboxRepository.class);
                        try {
                            return method.invoke(delegate, args);
                        } catch (InvocationTargetException e) {
                            throw e.getTargetException();
                        }
                    });
        }
    }

    static class OutboxFaultInjector {
        private final AtomicBoolean failMarkPublished = new AtomicBoolean(false);

        void reset() {
            failMarkPublished.set(false);
        }
    }

    static class RecordingEmailSender implements EmailSender {

        private final AtomicInteger sends = new AtomicInteger();
        private final List<Boolean> transactionActiveFlags =
                Collections.synchronizedList(new ArrayList<>());
        private final List<String> recipients =
                Collections.synchronizedList(new ArrayList<>());

        @Override
        public void send(String to, String subject, String body) {
            sends.incrementAndGet();
            recipients.add(to);
            transactionActiveFlags.add(
                    TransactionSynchronizationManager.isActualTransactionActive());
        }

        void reset() {
            sends.set(0);
            transactionActiveFlags.clear();
            recipients.clear();
        }
    }

    @Autowired
    private NotificationEventConsumer notificationEventConsumer;

    @Autowired
    private OutboxPublisherScheduler outboxPublisherScheduler;

    @Autowired
    private EventOutboxRepository outboxRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private EmailLogRepository emailLogRepository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private RecordingEmailSender recordingEmailSender;

    @Autowired
    private OutboxFaultInjector outboxFaultInjector;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        emailLogRepository.deleteAll();
        processedEventRepository.deleteAll();
        outboxFaultInjector.reset();
        recordingEmailSender.reset();
    }

    private TransactionTemplate callerTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        return template;
    }

    private String orderCreatedEnvelope(String eventId, String orderId) {
        DomainEventEnvelope envelope = new DomainEventEnvelope(
                UUID.fromString(eventId),
                "ORDER",
                orderId,
                "ORDER_CREATED",
                1,
                Instant.now(),
                UUID.randomUUID().toString(),
                null,
                "bookstore",
                Map.of(
                        "orderId", orderId,
                        "userName", "Outbox Test",
                        "userEmail", RECIPIENT,
                        "totalPrice", 42.50),
                Map.of());
        return EnvelopeConverter.toJson(envelope);
    }

    private List<EventOutbox> emailOutboxRows() {
        return outboxRepository.findAll().stream()
                .filter(row -> EMAIL_EVENT_TYPE.equals(row.getEventType()))
                .toList();
    }

    private EventOutbox seedEmailIntent(String aggregateId) {
        EventOutbox row = new EventOutbox(
                UUID.randomUUID(),
                "NOTIFICATION",
                aggregateId,
                EMAIL_EVENT_TYPE,
                1,
                aggregateId,
                null,
                null,
                "bookstore",
                "{\"to\":\"" + RECIPIENT + "\",\"subject\":\"Residual\",\"body\":\"Body\","
                        + "\"emailType\":\"ORDER_CONFIRMATION\"}",
                null);
        callerTransaction().executeWithoutResult(status -> outboxRepository.save(row));
        return row;
    }

    @Test
    @Timeout(20)
    @DisplayName("R-OUTBOX-2a: a committed consumer transaction yields exactly one email after the tick")
    void smtpFiresExactlyOnceOnConsumerSuccess() {
        String eventId = UUID.randomUUID().toString();
        String orderId = "8101";

        kafkaTemplate.send(KafkaTopicConfig.TOPIC_ORDERS, orderId,
                orderCreatedEnvelope(eventId, orderId));

        Awaitility.await()
                .atMost(12, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .until(() -> processedEventRepository.existsByEventIdAndConsumerGroup(
                        eventId, KafkaTopicConfig.CONSUMER_GROUP_NOTIFICATIONS));
        Awaitility.await()
                .atMost(12, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .until(() -> emailOutboxRows().size() == 1);

        assertEquals(0, recordingEmailSender.sends.get(),
                "the consumer must persist the email intent instead of sending SMTP itself");
        assertEquals(OutboxStatus.PENDING, emailOutboxRows().get(0).getStatus());

        outboxPublisherScheduler.publishPendingEvents();

        assertEquals(1, recordingEmailSender.sends.get(),
                "exactly one email must be delivered by the scheduler");
        assertEquals(List.of(RECIPIENT), recordingEmailSender.recipients);
        assertTrue(recordingEmailSender.transactionActiveFlags.stream()
                        .noneMatch(Boolean::booleanValue),
                "outbox email delivery must run outside any database transaction");
        assertEquals(OutboxStatus.PUBLISHED, emailOutboxRows().get(0).getStatus(),
                "the email outbox row must be marked as published after delivery");

        List<EmailLog> logs = emailLogRepository.findAll();
        assertEquals(1, logs.size(), "exactly one email log must be written");
        assertEquals(EmailStatus.SENT, logs.get(0).getStatus());
        assertEquals(RECIPIENT, logs.get(0).getRecipient());
    }

    @Test
    @Timeout(20)
    @DisplayName("R-OUTBOX-2b: no email fires when the consumer transaction rolls back")
    void smtpDoesNotFireIfConsumerTxRollsBack() {
        String eventId = UUID.randomUUID().toString();
        String orderId = "8102";
        String message = orderCreatedEnvelope(eventId, orderId);

        assertThrows(IllegalStateException.class, () ->
                callerTransaction().executeWithoutResult(status -> {
                    notificationEventConsumer.onNotificationEvent(new ConsumerRecord<>(
                            KafkaTopicConfig.TOPIC_ORDERS, 0, 0L, orderId, message));
                    throw new IllegalStateException("forced commit failure after doProcess");
                }));

        assertFalse(processedEventRepository.existsByEventIdAndConsumerGroup(
                        eventId, KafkaTopicConfig.CONSUMER_GROUP_NOTIFICATIONS),
                "the dedup row must roll back with the failed consumer transaction");
        assertTrue(emailOutboxRows().isEmpty(),
                "the email intent must roll back with the consumer transaction");

        outboxPublisherScheduler.publishPendingEvents();

        assertEquals(0, recordingEmailSender.sends.get(),
                "no email may be delivered for a rolled-back consumer transaction");
        assertTrue(emailLogRepository.findAll().isEmpty(),
                "a rolled-back consumer transaction must not leave an email log");
    }

    @Test
    @Timeout(20)
    @DisplayName("R-OUTBOX-2c: SMTP success plus mark-published failure duplicates on retry (OPEN residual)")
    void smtpDuplicateResidual_Open() {
        seedEmailIntent("8103");
        assertEquals(1, emailOutboxRows().size());

        outboxFaultInjector.failMarkPublished.set(true);
        outboxPublisherScheduler.publishPendingEvents();

        assertEquals(1, recordingEmailSender.sends.get(),
                "the SMTP send happens before the publication marker is written");
        EventOutbox afterFailure = emailOutboxRows().get(0);
        assertEquals(OutboxStatus.PENDING, afterFailure.getStatus(),
                "the row must remain pending after the mark failure so it can be retried");
        assertTrue(afterFailure.getRetryCount() >= 1,
                "the failed publication attempt must be recorded");

        outboxFaultInjector.failMarkPublished.set(false);
        outboxPublisherScheduler.publishPendingEvents();

        assertEquals(2, recordingEmailSender.sends.get(),
                "the retry re-sends the email: at-least-once delivery without a distributed transaction");
        assertEquals(OutboxStatus.PUBLISHED, emailOutboxRows().get(0).getStatus());
    }
}
