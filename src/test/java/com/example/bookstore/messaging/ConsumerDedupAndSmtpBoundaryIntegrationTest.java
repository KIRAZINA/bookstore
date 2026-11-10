package com.example.bookstore.messaging;

import com.example.bookstore.base.AbstractIntegrationTest;
import com.example.bookstore.config.KafkaTopicConfig;
import com.example.bookstore.event.repository.ProcessedEventRepository;
import com.example.bookstore.messaging.consumer.AbstractIdempotentConsumer;
import com.example.bookstore.messaging.consumer.InventoryEventConsumer;
import com.example.bookstore.messaging.consumer.PaymentEventConsumer;
import com.example.bookstore.messaging.metrics.MessagingMetrics;
import com.example.bookstore.metrics.BusinessMetrics;
import com.example.bookstore.notification.email.EmailSender;
import com.example.bookstore.notification.email.EmailService;
import com.example.bookstore.notification.repository.EmailLogRepository;
import com.example.bookstore.service.AuditService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration evidence for consumer dedup atomicity and the SMTP transaction boundary.
 *
 * <p>These tests use the real Testcontainers PostgreSQL and Redis instances supplied by
 * {@link AbstractIntegrationTest}. Kafka is not required because the dedup and SMTP
 * invariants are exercised through the same consumer entry points that Kafka invokes.</p>
 */
@Import(ConsumerDedupAndSmtpBoundaryIntegrationTest.RecordingEmailConfig.class)
class ConsumerDedupAndSmtpBoundaryIntegrationTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class RecordingEmailConfig {
        @Bean
        @Primary
        RecordingEmailSender recordingEmailSender() {
            return new RecordingEmailSender();
        }
    }

    static class RecordingEmailSender implements EmailSender {
        final AtomicInteger sends = new AtomicInteger();
        final List<Boolean> transactionActiveFlags =
                Collections.synchronizedList(new ArrayList<>());

        @Override
        public void send(String to, String subject, String body) {
            sends.incrementAndGet();
            transactionActiveFlags.add(
                    TransactionSynchronizationManager.isActualTransactionActive());
        }

        void reset() {
            sends.set(0);
            transactionActiveFlags.clear();
        }
    }

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private AuditService auditService;

    @Autowired
    private MessagingMetrics messagingMetrics;

    @Autowired
    private BusinessMetrics businessMetrics;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EmailService emailService;

    @Autowired
    private RecordingEmailSender recordingEmailSender;

    @BeforeEach
    void setUp() {
        processedEventRepository.deleteAll();
        recordingEmailSender.reset();
    }

    private TransactionTemplate callerTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        return template;
    }

    private String envelopeJson(String eventId, String eventType) {
        DomainEventEnvelope envelope = new DomainEventEnvelope(
                UUID.fromString(eventId),
                eventType.startsWith("PAYMENT") ? "payment" : "order",
                "1",
                eventType,
                1,
                Instant.now(),
                UUID.randomUUID().toString(),
                null,
                "bookstore",
                Map.of("orderId", "1"),
                Map.of());
        return EnvelopeConverter.toJson(envelope);
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("R1: a processing failure rolls back the dedup row and a retry reprocesses")
    void failureAfterDedup_DoesNotCommitDedupRow_AndRetryProcesses() {
        String eventId = UUID.randomUUID().toString();
        String consumerGroup = "dedup-test-" + System.nanoTime();
        FailingConsumer consumer = new FailingConsumer(
                processedEventRepository, messagingMetrics, consumerGroup, transactionManager);

        String message = envelopeJson(eventId, "ORDER_CREATED");

        assertThrows(IllegalStateException.class,
                () -> consumer.consume(message, new RecordHeaders()));

        assertFalse(processedEventRepository.existsByEventIdAndConsumerGroup(eventId, consumerGroup),
                "dedup row must not survive a failed processing transaction");

        consumer.consume(message, new RecordHeaders());

        assertTrue(processedEventRepository.existsByEventIdAndConsumerGroup(eventId, consumerGroup),
                "dedup row must commit after successful reprocessing");
        assertEquals(2, consumer.calls.get(), "the retry must invoke doProcess again");
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("R2a: SMTP executes outside the caller transaction")
    void smtp_RunsOutsideCallerTransaction() {
        callerTransaction().executeWithoutResult(status ->
                emailService.sendEmail("smtp-boundary@example.com", "Subject", "Body", "TEST"));

        assertEquals(1, recordingEmailSender.sends.get());
        assertFalse(recordingEmailSender.transactionActiveFlags.isEmpty());
        assertTrue(recordingEmailSender.transactionActiveFlags.stream()
                        .noneMatch(Boolean::booleanValue),
                "SMTP must not observe an active transaction: "
                        + recordingEmailSender.transactionActiveFlags);
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("R2b: doProcess executes inside the dedup transaction")
    void doProcess_RunsInsideDedupTransaction() {
        String eventId = UUID.randomUUID().toString();
        String consumerGroup = "tx-test-" + System.nanoTime();
        TransactionRecordingConsumer consumer = new TransactionRecordingConsumer(
                processedEventRepository, messagingMetrics, consumerGroup, transactionManager);

        consumer.consume(envelopeJson(eventId, "ORDER_CREATED"), new RecordHeaders());

        assertEquals(Boolean.TRUE, consumer.transactionActiveInsideDoProcess,
                "doProcess must run inside the dedup TransactionTemplate transaction");
        assertTrue(processedEventRepository.existsByEventIdAndConsumerGroup(eventId, consumerGroup));
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("R3: a log-save failure after SMTP does not resend or throw")
    void logSaveFailure_DoesNotResendOrThrow() {
        RecordingEmailSender sender = new RecordingEmailSender();
        EmailLogRepository failingRepository = failingSaveRepository();
        EmailService service = new EmailService(
                sender, failingRepository, businessMetrics, transactionManager);

        assertDoesNotThrow(() ->
                service.sendEmail("log-failure@example.com", "Subject", "Body", "TEST"));

        assertEquals(1, sender.sends.get(),
                "SMTP must be called exactly once even when the email log save fails");
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("R3b: log-save failure leaves the consumer dedup row committed")
    void logSaveFailure_ConsumerDedupCommits() {
        RecordingEmailSender sender = new RecordingEmailSender();
        EmailService service = new EmailService(
                sender, failingSaveRepository(), businessMetrics, transactionManager);

        String eventId = UUID.randomUUID().toString();
        String consumerGroup = "email-log-test-" + System.nanoTime();
        EmailSendingConsumer consumer = new EmailSendingConsumer(
                processedEventRepository, messagingMetrics, consumerGroup, transactionManager, service);

        consumer.consume(envelopeJson(eventId, "ORDER_CREATED"), new RecordHeaders());

        assertEquals(1, sender.sends.get(), "SMTP must be attempted exactly once");
        assertTrue(processedEventRepository.existsByEventIdAndConsumerGroup(eventId, consumerGroup),
                "consumer dedup row must commit when only the email log save fails");
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("R4: inventory consumer commits its dedup row atomically")
    void inventoryConsumer_CommitsDedupRow() {
        InventoryEventConsumer consumer = new InventoryEventConsumer(
                processedEventRepository, messagingMetrics, auditService, transactionManager);

        String eventId = UUID.randomUUID().toString();
        String message = envelopeJson(eventId, "INVENTORY_RESERVED");
        consumer.onInventoryEvent(new ConsumerRecord<>(
                KafkaTopicConfig.TOPIC_INVENTORY, 0, 0L, "key", message));

        assertTrue(processedEventRepository.existsByEventIdAndConsumerGroup(
                        eventId, KafkaTopicConfig.CONSUMER_GROUP_INVENTORY),
                "inventory consumer dedup row must be committed");
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("R5: payment consumer commits its dedup row atomically")
    void paymentConsumer_CommitsDedupRow() {
        PaymentEventConsumer consumer = new PaymentEventConsumer(
                processedEventRepository, messagingMetrics, auditService, transactionManager);

        String eventId = UUID.randomUUID().toString();
        String message = envelopeJson(eventId, "PAYMENT_COMPLETED");
        consumer.onPaymentEvent(new ConsumerRecord<>(
                KafkaTopicConfig.TOPIC_PAYMENTS, 0, 0L, "key", message));

        assertTrue(processedEventRepository.existsByEventIdAndConsumerGroup(
                        eventId, KafkaTopicConfig.CONSUMER_GROUP_PAYMENTS),
                "payment consumer dedup row must be committed");
    }

    private static EmailLogRepository failingSaveRepository() {
        return (EmailLogRepository) Proxy.newProxyInstance(
                EmailLogRepository.class.getClassLoader(),
                new Class<?>[]{EmailLogRepository.class},
                (proxy, method, args) -> {
                    if ("save".equals(method.getName())) {
                        throw new IllegalStateException("forced email log save failure");
                    }
                    return null;
                });
    }

    static class FailingConsumer extends AbstractIdempotentConsumer {
        private final AtomicInteger calls = new AtomicInteger();

        FailingConsumer(ProcessedEventRepository repository,
                        MessagingMetrics metrics,
                        String consumerGroup,
                        PlatformTransactionManager transactionManager) {
            super(repository, metrics, consumerGroup, transactionManager);
        }

        void consume(String message, Headers headers) {
            processEvent(message, headers);
        }

        @Override
        protected void doProcess(DomainEventEnvelope envelope) {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("forced consumer processing failure");
            }
        }
    }

    static class TransactionRecordingConsumer extends AbstractIdempotentConsumer {
        volatile Boolean transactionActiveInsideDoProcess;

        TransactionRecordingConsumer(ProcessedEventRepository repository,
                                     MessagingMetrics metrics,
                                     String consumerGroup,
                                     PlatformTransactionManager transactionManager) {
            super(repository, metrics, consumerGroup, transactionManager);
        }

        void consume(String message, Headers headers) {
            processEvent(message, headers);
        }

        @Override
        protected void doProcess(DomainEventEnvelope envelope) {
            transactionActiveInsideDoProcess =
                    TransactionSynchronizationManager.isActualTransactionActive();
        }
    }

    static class EmailSendingConsumer extends AbstractIdempotentConsumer {
        private final EmailService emailService;

        EmailSendingConsumer(ProcessedEventRepository repository,
                             MessagingMetrics metrics,
                             String consumerGroup,
                             PlatformTransactionManager transactionManager,
                             EmailService emailService) {
            super(repository, metrics, consumerGroup, transactionManager);
            this.emailService = emailService;
        }

        void consume(String message, Headers headers) {
            processEvent(message, headers);
        }

        @Override
        protected void doProcess(DomainEventEnvelope envelope) {
            emailService.sendEmail("consumer@example.com", "Subject", "Body", "ORDER_CONFIRMATION");
        }
    }
}
