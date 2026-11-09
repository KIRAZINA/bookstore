package com.example.bookstore.concurrency;

import com.example.bookstore.base.AbstractIntegrationTest;
import com.example.bookstore.model.AppUser;
import com.example.bookstore.model.Book;
import com.example.bookstore.model.Inventory;
import com.example.bookstore.model.Order;
import com.example.bookstore.model.OrderStatus;
import com.example.bookstore.model.OrderStatusHistory;
import com.example.bookstore.model.Payment;
import com.example.bookstore.model.PaymentHistory;
import com.example.bookstore.model.PaymentStatus;
import com.example.bookstore.model.UserRole;
import com.example.bookstore.repository.BookRepository;
import com.example.bookstore.repository.InventoryRepository;
import com.example.bookstore.repository.OrderRepository;
import com.example.bookstore.repository.PaymentRepository;
import com.example.bookstore.repository.UserRepository;
import com.example.bookstore.service.CartService;
import com.example.bookstore.service.OrderService;
import com.example.bookstore.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ambient transaction boundary evidence for payment provider calls.
 *
 * <p>This class is deliberately NOT annotated with {@code @Transactional}. Each test
 * commits its own fixture data so the REQUIRES_NEW phase fences inside
 * {@link PaymentService} can observe committed rows, and so the only ambient
 * transaction in play is the one a test explicitly opens.
 *
 * <p>Provider instrumentation is shared with {@link PaymentConcurrencyIntegrationTest}
 * through {@link RecordingPaymentProviderConfig}, which records the transaction state
 * observed at each provider invocation.
 */
@Import(RecordingPaymentProviderConfig.class)
class PaymentTransactionBoundaryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private CartService cartService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private BookRepository bookRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private InventoryRepository inventoryRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private PlatformTransactionManager transactionManager;

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
        user.setUsername("boundary-" + System.nanoTime());
        user.setPassword(passwordEncoder.encode("password"));
        user.setEmail("boundary@test.com");
        user.setRole(UserRole.ROLE_USER);
        user = userRepository.save(user);

        authAs(user);

        book = new Book();
        book.setTitle("Boundary Book");
        book.setAuthor("Author");
        book.setPrice(new BigDecimal("25.00"));
        book.setCategory("Test");
        book.setStock(10);
        book = bookRepository.save(book);

        inventoryRepository.save(new Inventory(book.getId(), 10));
    }

    private void authAs(AppUser principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private AppUser createAdmin() {
        AppUser admin = new AppUser();
        admin.setUsername("boundary-admin-" + System.nanoTime());
        admin.setPassword(passwordEncoder.encode("password"));
        admin.setEmail("boundary-admin@test.com");
        admin.setRole(UserRole.ROLE_ADMIN);
        return userRepository.save(admin);
    }

    /** Creates an order through the real service path so inventory is reserved. */
    private Order createCommittedOrder(int quantity) {
        cartService.addToCart(book.getId(), quantity);
        return orderService.createOrder();
    }

    private TransactionTemplate callerTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        return template;
    }

    private Inventory inventory() {
        return inventoryRepository.findByBookId(book.getId()).orElseThrow();
    }

    private long countOrderTransitions(Long orderId, OrderStatus status) {
        return orderService.getOrderHistory(orderId).stream()
                .map(OrderStatusHistory::getNewStatus)
                .filter(status::equals)
                .count();
    }

    private long countPaymentTransitions(Long paymentId, PaymentStatus status) {
        return paymentService.getPaymentHistory(paymentId).stream()
                .map(PaymentHistory::getNewStatus)
                .filter(status::equals)
                .count();
    }

    /**
     * Asserts the provider was invoked and that no provider invocation observed an
     * active database transaction. The primary gate is isActualTransactionActive();
     * the synchronization flag is reported only as diagnostic context.
     */
    private void assertProviderRanOutsideTransaction(List<Boolean> txFlags,
                                                     List<Boolean> syncFlags,
                                                     String label) {
        assertFalse(txFlags.isEmpty(), label + ": provider was never invoked");
        List<Boolean> txSnapshot = new ArrayList<>(txFlags);
        List<Boolean> syncSnapshot = new ArrayList<>(syncFlags);
        assertTrue(txSnapshot.stream().noneMatch(Boolean::booleanValue),
                label + ": provider observed an ACTIVE database transaction. "
                        + "isActualTransactionActive=" + txSnapshot
                        + ", isSynchronizationActive=" + syncSnapshot);
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void processPaymentCalledWithinCallerTransaction_invokesProviderWithNoActiveTransaction() {
        Order order = createCommittedOrder(2);
        RecordingPaymentProviderConfig.reset();
        RecordingPaymentProviderConfig.captureObservedStatus = true;

        // The only ambient transaction is this explicit caller transaction.
        try {
            callerTransaction().executeWithoutResult(status ->
                    paymentService.processPayment(order.getId(), "CREDIT_CARD"));
        } finally {
            RecordingPaymentProviderConfig.captureObservedStatus = false;
        }

        assertEquals(1, RecordingPaymentProviderConfig.processCount.get(),
                "provider must be invoked exactly once");
        assertProviderRanOutsideTransaction(
                RecordingPaymentProviderConfig.processTxActive,
                RecordingPaymentProviderConfig.processSyncActive,
                "processPayment");
        assertEquals(List.of("PROCESSING"),
                new ArrayList<>(RecordingPaymentProviderConfig.processObservedStatus),
                "phase 1 commit must be visible to the provider call");

        Payment payment = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        assertEquals(PaymentStatus.COMPLETED, payment.getPaymentStatus());
        assertEquals(OrderStatus.PAID,
                orderRepository.findById(order.getId()).orElseThrow().getStatus());
        assertEquals(1, countPaymentTransitions(payment.getId(), PaymentStatus.PROCESSING));
        assertEquals(1, countPaymentTransitions(payment.getId(), PaymentStatus.COMPLETED));
        assertEquals(1, countOrderTransitions(order.getId(), OrderStatus.PAID));
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void refundPaymentCalledWithinCallerTransaction_invokesProviderWithNoActiveTransaction() {
        Order order = createCommittedOrder(2);
        paymentService.processPayment(order.getId(), "CREDIT_CARD");
        Payment payment = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        RecordingPaymentProviderConfig.reset();

        callerTransaction().executeWithoutResult(status ->
                paymentService.refundPayment(payment.getId()));

        assertEquals(1, RecordingPaymentProviderConfig.refundCount.get(),
                "provider must be invoked exactly once");
        assertProviderRanOutsideTransaction(
                RecordingPaymentProviderConfig.refundTxActive,
                RecordingPaymentProviderConfig.refundSyncActive,
                "refundPayment");

        Payment refunded = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        assertEquals(PaymentStatus.REFUNDED, refunded.getPaymentStatus());
        assertEquals(OrderStatus.CANCELLED,
                orderRepository.findById(order.getId()).orElseThrow().getStatus());
        assertEquals(1, countPaymentTransitions(refunded.getId(), PaymentStatus.REFUND_PROCESSING));
        assertEquals(1, countPaymentTransitions(refunded.getId(), PaymentStatus.REFUNDED));
        assertEquals(1, countOrderTransitions(order.getId(), OrderStatus.CANCELLED));

        Inventory inv = inventory();
        assertEquals(10, inv.getAvailableQuantity(), "stock restored exactly once");
        assertEquals(0, inv.getReservedQuantity());
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void cancelOwnOrderPaidOrder_refundsOutsideTransactionAndStillCancelsOrder() {
        Order order = createCommittedOrder(2);
        paymentService.processPayment(order.getId(), "CREDIT_CARD");
        Payment payment = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        assertEquals(OrderStatus.PAID,
                orderRepository.findById(order.getId()).orElseThrow().getStatus());
        RecordingPaymentProviderConfig.reset();

        authAs(user);
        // cancelOwnOrder is @Transactional. The explicit outer transaction surfaces any
        // commit failure instead of swallowing UnexpectedRollbackException.
        assertDoesNotThrow(() -> callerTransaction().executeWithoutResult(status ->
                orderService.cancelOwnOrder(order.getId())));

        assertEquals(1, RecordingPaymentProviderConfig.refundCount.get(),
                "provider refund must be invoked exactly once");
        assertProviderRanOutsideTransaction(
                RecordingPaymentProviderConfig.refundTxActive,
                RecordingPaymentProviderConfig.refundSyncActive,
                "cancelOwnOrder -> refundPayment");

        assertEquals(OrderStatus.CANCELLED,
                orderRepository.findById(order.getId()).orElseThrow().getStatus());
        assertEquals(PaymentStatus.REFUNDED,
                paymentRepository.findByOrderId(order.getId()).orElseThrow().getPaymentStatus());
        assertEquals(1, countOrderTransitions(order.getId(), OrderStatus.CANCELLED),
                "order must record exactly one CANCELLED transition");
        assertEquals(1, countPaymentTransitions(payment.getId(), PaymentStatus.REFUNDED),
                "payment must record exactly one REFUNDED transition");

        Inventory inv = inventory();
        assertEquals(10, inv.getAvailableQuantity(), "stock restored exactly once");
        assertEquals(0, inv.getReservedQuantity());
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void adminCancelOrderPaidOrder_refundsOutsideTransactionAndStillCancelsOrder() {
        Order order = createCommittedOrder(2);
        paymentService.processPayment(order.getId(), "CREDIT_CARD");
        Payment payment = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        assertEquals(OrderStatus.PAID,
                orderRepository.findById(order.getId()).orElseThrow().getStatus());
        RecordingPaymentProviderConfig.reset();

        AppUser admin = createAdmin();
        authAs(admin);
        // adminCancelOrder is @Transactional. The explicit outer transaction surfaces any
        // commit failure instead of swallowing UnexpectedRollbackException.
        assertDoesNotThrow(() -> callerTransaction().executeWithoutResult(status ->
                orderService.adminCancelOrder(order.getId(), "Admin cancelled paid order")));

        assertEquals(1, RecordingPaymentProviderConfig.refundCount.get(),
                "provider refund must be invoked exactly once");
        assertProviderRanOutsideTransaction(
                RecordingPaymentProviderConfig.refundTxActive,
                RecordingPaymentProviderConfig.refundSyncActive,
                "adminCancelOrder -> refundPayment");

        assertEquals(OrderStatus.CANCELLED,
                orderRepository.findById(order.getId()).orElseThrow().getStatus());
        assertEquals(PaymentStatus.REFUNDED,
                paymentRepository.findByOrderId(order.getId()).orElseThrow().getPaymentStatus());
        assertEquals(1, countOrderTransitions(order.getId(), OrderStatus.CANCELLED),
                "order must record exactly one CANCELLED transition");
        assertEquals(1, countPaymentTransitions(payment.getId(), PaymentStatus.REFUNDED),
                "payment must record exactly one REFUNDED transition");

        Inventory inv = inventory();
        assertEquals(10, inv.getAvailableQuantity(), "stock restored exactly once");
        assertEquals(0, inv.getReservedQuantity());
    }
}
