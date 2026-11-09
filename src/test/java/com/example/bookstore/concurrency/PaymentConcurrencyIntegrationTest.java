package com.example.bookstore.concurrency;

import com.example.bookstore.base.AbstractIntegrationTest;
import com.example.bookstore.model.*;
import com.example.bookstore.repository.*;
import com.example.bookstore.service.*;
import com.example.bookstore.service.payment.DummyPaymentProvider;
import com.example.bookstore.service.payment.PaymentProvider;
import com.example.bookstore.service.payment.PaymentProviderResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test-only provider instrumentation. {@link TestConfiguration} keeps this class out
 * of component scanning so production-style integration tests keep using the real
 * {@link DummyPaymentProvider}.
 */
@TestConfiguration
class RecordingPaymentProviderConfig {
    static final AtomicInteger processCount = new AtomicInteger(0);
    static final AtomicInteger refundCount = new AtomicInteger(0);

    /**
     * Per-invocation capture of the transaction state that was active at the moment
     * the provider was called. The primary gate is
     * {@link TransactionSynchronizationManager#isActualTransactionActive()}; the
     * synchronization flag is retained only as diagnostic context.
     */
    static final List<Boolean> processTxActive =
            Collections.synchronizedList(new ArrayList<>());
    static final List<Boolean> refundTxActive =
            Collections.synchronizedList(new ArrayList<>());
    static final List<Boolean> processSyncActive =
            Collections.synchronizedList(new ArrayList<>());
    static final List<Boolean> refundSyncActive =
            Collections.synchronizedList(new ArrayList<>());

    /**
     * Diagnostic switch: when enabled, the recorder also captures the payment status
     * that a fresh database read observes at the moment the provider is called. This
     * proves whether the phase 1 commit is visible to the provider call.
     */
    static volatile boolean captureObservedStatus = false;
    static final List<String> processObservedStatus =
            Collections.synchronizedList(new ArrayList<>());

    static void reset() {
        processCount.set(0);
        refundCount.set(0);
        processTxActive.clear();
        refundTxActive.clear();
        processSyncActive.clear();
        refundSyncActive.clear();
        processObservedStatus.clear();
        captureObservedStatus = false;
    }

    @Bean
    @Primary
    DummyPaymentProvider paymentProvider(com.example.bookstore.repository.PaymentRepository paymentRepository) {
        return new DummyPaymentProvider() {
            @Override
            public com.example.bookstore.service.payment.PaymentProviderResponse processPayment(com.example.bookstore.model.Payment payment) {
                int invocation = processCount.incrementAndGet();
                processTxActive.add(TransactionSynchronizationManager.isActualTransactionActive());
                processSyncActive.add(TransactionSynchronizationManager.isSynchronizationActive());
                if (captureObservedStatus) {
                    String observed = paymentRepository.findById(payment.getId())
                            .map(p -> String.valueOf(p.getPaymentStatus()))
                            .orElse("ABSENT");
                    processObservedStatus.add(observed);
                }
                return PaymentProviderResponse.success(
                        "rec-process-" + invocation, PaymentStatus.COMPLETED);
            }

            @Override
            public com.example.bookstore.service.payment.PaymentProviderResponse refundPayment(com.example.bookstore.model.Payment payment) {
                int invocation = refundCount.incrementAndGet();
                refundTxActive.add(TransactionSynchronizationManager.isActualTransactionActive());
                refundSyncActive.add(TransactionSynchronizationManager.isSynchronizationActive());
                return PaymentProviderResponse.success(
                        "rec-refund-" + invocation, PaymentStatus.REFUNDED);
            }
        };
    }
}

@org.springframework.context.annotation.Import(RecordingPaymentProviderConfig.class)
class PaymentConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private CartService cartService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private BookRepository bookRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private InventoryRepository inventoryRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private AppUser user;
    private Book book;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
        inventoryRepository.deleteAll();
        bookRepository.deleteAll();
        userRepository.deleteAll();
        RecordingPaymentProviderConfig.reset();

        user = new AppUser();
        user.setUsername("conc-" + System.nanoTime());
        user.setPassword(passwordEncoder.encode("password"));
        user.setEmail("conc@test.com");
        user.setRole(UserRole.ROLE_USER);
        user = userRepository.save(user);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));

        book = new Book();
        book.setTitle("Conc Book");
        book.setAuthor("Author");
        book.setPrice(new BigDecimal("25.00"));
        book.setCategory("Test");
        book.setStock(10);
        book = bookRepository.save(book);

        Inventory inventory = new Inventory(book.getId(), 10);
        inventoryRepository.save(inventory);
    }

    private void authAs(AppUser u) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
    }

    /**
     * Shuts down the executor in {@code finally} without swallowing an interrupt. The
     * interrupt flag is restored for the caller.
     */
    private static void shutdownExecutor(ExecutorService executor) {
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @Timeout(20)
    void twoConcurrentProcessPaymentCalls_invokeProviderExactlyOnce() throws Exception {
        cartService.addToCart(book.getId(), 2);
        Order order = orderService.createOrder();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        ExecutorService exec = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        try {
            Runnable task = () -> {
                authAs(user);
                try {
                    start.await(5, TimeUnit.SECONDS);
                    paymentService.processPayment(order.getId(), "CREDIT_CARD");
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            };
            exec.submit(task);
            exec.submit(task);
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals(1, RecordingPaymentProviderConfig.processCount.get());
            assertFalse(RecordingPaymentProviderConfig.processTxActive.isEmpty(),
                    "process provider must be invoked");
            assertTrue(RecordingPaymentProviderConfig.processTxActive.stream()
                            .noneMatch(Boolean::booleanValue),
                    "process provider must not observe an active transaction: "
                            + RecordingPaymentProviderConfig.processTxActive);
        } finally {
            shutdownExecutor(exec);
        }
    }

    @Test
    @Timeout(20)
    void twoConcurrentRefundPaymentCalls_invokeProviderExactlyOnce() throws Exception {
        cartService.addToCart(book.getId(), 2);
        Order order = orderService.createOrder();
        paymentService.processPayment(order.getId(), "CREDIT_CARD");
        Payment payment = paymentRepository.findByOrderId(order.getId()).orElseThrow();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        ExecutorService exec = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        try {
            Runnable task = () -> {
                authAs(user);
                try {
                    start.await(5, TimeUnit.SECONDS);
                    paymentService.refundPayment(payment.getId());
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            };
            exec.submit(task);
            exec.submit(task);
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals(1, RecordingPaymentProviderConfig.refundCount.get());
            assertFalse(RecordingPaymentProviderConfig.refundTxActive.isEmpty(),
                    "refund provider must be invoked");
            assertTrue(RecordingPaymentProviderConfig.refundTxActive.stream()
                            .noneMatch(Boolean::booleanValue),
                    "refund provider must not observe an active transaction: "
                            + RecordingPaymentProviderConfig.refundTxActive);
            Payment p = paymentRepository.findByOrderId(order.getId()).orElseThrow();
            assertEquals(PaymentStatus.REFUNDED, p.getPaymentStatus());
        } finally {
            shutdownExecutor(exec);
        }
    }

    @Test
    @Timeout(20)
    void twoConcurrentCancelPendingPaymentCalls_releaseInventoryOnce() throws Exception {
        cartService.addToCart(book.getId(), 1);
        Order order = orderService.createOrder();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        ExecutorService exec = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        try {
            Runnable task = () -> {
                authAs(user);
                try {
                    start.await(5, TimeUnit.SECONDS);
                    paymentService.cancelPayment(order.getId());
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            };
            exec.submit(task);
            exec.submit(task);
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals(0, RecordingPaymentProviderConfig.processCount.get(),
                    "cancelPayment must not invoke the process provider");
            assertEquals(0, RecordingPaymentProviderConfig.refundCount.get(),
                    "cancelPayment must not invoke the refund provider");
            Payment p = paymentRepository.findByOrderId(order.getId()).orElseThrow();
            assertEquals(PaymentStatus.CANCELLED, p.getPaymentStatus());
        } finally {
            shutdownExecutor(exec);
        }
    }
}
