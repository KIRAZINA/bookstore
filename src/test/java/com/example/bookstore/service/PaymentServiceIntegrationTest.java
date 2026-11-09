package com.example.bookstore.service;

import com.example.bookstore.base.AbstractIntegrationTest;
import com.example.bookstore.config.SecurityUtils;
import com.example.bookstore.exception.InvalidPaymentStateException;
import com.example.bookstore.model.*;
import com.example.bookstore.repository.*;
import com.example.bookstore.service.InventoryAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * This class is intentionally NOT class-level {@code @Transactional}.
 *
 * <p>{@link PaymentService} fences its phases with {@code REQUIRES_NEW}, which opens
 * a new transaction that can only observe data already committed by the test. Under a
 * class-level test transaction the fixture rows are never committed, so the phase
 * fence cannot see them. Each test therefore commits its own fixture data, and
 * {@link #cleanUp()} removes it before the next test.
 */
class PaymentServiceIntegrationTest extends AbstractIntegrationTest {

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

    @MockitoBean
    private SecurityUtils securityUtils;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryAdminService inventoryAdminService;

    /**
     * Without a class-level test transaction, data written by a test is committed and
     * would otherwise leak into the next test. Cleanup runs in safe
     * foreign-key order: payments, then orders (cascading order history/items), then
     * inventory, books, and users (cascading carts).
     */
    @BeforeEach
    void cleanUp() {
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
        inventoryRepository.deleteAll();
        bookRepository.deleteAll();
        userRepository.deleteAll();
    }

    private AppUser createUser() {
        AppUser user = new AppUser();
        user.setUsername("pay-it-" + UUID.randomUUID());
        user.setPassword("password");
        user.setEmail("pay-it@example.com");
        user.setRole(UserRole.ROLE_USER);
        return userRepository.save(user);
    }

    private Book createBook(String title, BigDecimal price, int stock) {
        Book book = new Book();
        book.setTitle(title);
        book.setAuthor("Author");
        book.setPrice(price);
        book.setCategory("Testing");
        book.setStock(stock);
        Book saved = bookRepository.save(book);
        inventoryAdminService.initializeInventory(saved.getId(), stock);
        return saved;
    }

    private Order createOrder(AppUser user, Book book, int quantity) {
        when(securityUtils.getCurrentUser()).thenReturn(user);
        cartService.addToCart(book.getId(), quantity);
        return orderService.createOrder();
    }

    @Test
    @DisplayName("Payment is created alongside order with PENDING status")
    void paymentCreatedWithOrder() {
        AppUser user = createUser();
        Book book = createBook("Payment Order Book", new BigDecimal("25.00"), 10);
        Order order = createOrder(user, book, 2);

        assertNotNull(order.getId());
        Payment payment = paymentService.getPaymentByOrderId(order.getId());
        assertNotNull(payment);
        assertEquals(PaymentStatus.PENDING, payment.getPaymentStatus());
        // Without a test transaction the payment is reloaded from a DECIMAL(19,4)
        // column, so compare numerically rather than by scale.
        assertEquals(0, new BigDecimal("50.00").compareTo(payment.getAmount()));
        assertEquals("DUMMY", payment.getPaymentProvider());
        assertNotNull(payment.getCreatedAt());
    }

    @Test
    @DisplayName("Process payment transitions payment to COMPLETED and order to PAID")
    void processPayment_CompletesPaymentAndTransitionsOrder() {
        AppUser user = createUser();
        Book book = createBook("Process Payment Book", new BigDecimal("30.00"), 5);
        Order order = createOrder(user, book, 1);

        Payment processed = paymentService.processPayment(order.getId(), "card");

        assertEquals(PaymentStatus.COMPLETED, processed.getPaymentStatus());
        assertNotNull(processed.getTransactionId());
        assertTrue(processed.getTransactionId().startsWith("TXN-"));
        assertEquals("card", processed.getPaymentMethod());
        assertNotNull(processed.getCompletedAt());

        Order updatedOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertEquals(OrderStatus.PAID, updatedOrder.getStatus());
    }

    @Test
    @DisplayName("Payment history records each transition")
    void paymentHistoryRecordsTransitions() {
        AppUser user = createUser();
        Book book = createBook("History Payment Book", new BigDecimal("40.00"), 5);
        Order order = createOrder(user, book, 1);

        Payment payment = paymentService.getPaymentByOrderId(order.getId());
        List<PaymentHistory> historyBefore = paymentService.getPaymentHistory(payment.getId());
        assertEquals(0, historyBefore.size());

        paymentService.processPayment(order.getId(), "card");

        List<PaymentHistory> historyAfter = paymentService.getPaymentHistory(payment.getId());
        assertEquals(2, historyAfter.size());
        assertEquals(PaymentStatus.PROCESSING, historyAfter.get(0).getNewStatus());
        assertEquals(PaymentStatus.COMPLETED, historyAfter.get(1).getNewStatus());
    }

    @Test
    @DisplayName("Cancel pending payment releases reservation and cancels order")
    void cancelPendingPayment_releasesReservationAndCancelsOrder() {
        AppUser user = createUser();
        Book book = createBook("Cancel Payment Book", new BigDecimal("20.00"), 5);
        Order order = createOrder(user, book, 1);

        Inventory invBefore = inventoryRepository.findByBookId(book.getId()).orElseThrow();
        int availableBefore = invBefore.getAvailableQuantity();
        int reservedBefore = invBefore.getReservedQuantity();

        when(securityUtils.getCurrentUser()).thenReturn(user);
        Payment cancelled = paymentService.cancelPayment(order.getId());

        assertEquals(PaymentStatus.CANCELLED, cancelled.getPaymentStatus());

        Order reloadedOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertEquals(OrderStatus.CANCELLED, reloadedOrder.getStatus());

        Inventory invAfter = inventoryRepository.findByBookId(book.getId()).orElseThrow();
        assertEquals(availableBefore + 1, invAfter.getAvailableQuantity());
        assertEquals(reservedBefore - 1, invAfter.getReservedQuantity());

        List<OrderStatusHistory> history = orderService.getOrderHistory(order.getId());
        assertTrue(history.stream().anyMatch(h -> h.getNewStatus() == OrderStatus.CANCELLED));
    }

    @Test
    @DisplayName("Cancel payment after order cancel is cleanup without duplicate inventory change")
    void cancelPaymentAfterOrderCancel_isCleanupWithoutDuplicateInventoryChange() {
        AppUser user = createUser();
        Book book = createBook("Cancel Payment Cleanup Book", new BigDecimal("20.00"), 5);
        Order order = createOrder(user, book, 1);

        when(securityUtils.getCurrentUser()).thenReturn(user);
        orderService.cancelOwnOrder(order.getId());

        Inventory invBefore = inventoryRepository.findByBookId(book.getId()).orElseThrow();
        int availableBefore = invBefore.getAvailableQuantity();
        int reservedBefore = invBefore.getReservedQuantity();
        int orderHistoryBefore = orderService.getOrderHistory(order.getId()).size();
        Payment payment = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        int paymentHistoryBefore = paymentService.getPaymentHistory(payment.getId()).size();

        Payment cancelled = paymentService.cancelPayment(order.getId());
        assertEquals(PaymentStatus.CANCELLED, cancelled.getPaymentStatus());

        Order reloadedOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertEquals(OrderStatus.CANCELLED, reloadedOrder.getStatus());

        Inventory invAfter = inventoryRepository.findByBookId(book.getId()).orElseThrow();
        assertEquals(availableBefore, invAfter.getAvailableQuantity());
        assertEquals(reservedBefore, invAfter.getReservedQuantity());

        assertEquals(orderHistoryBefore, orderService.getOrderHistory(order.getId()).size());
    }

    @Test
    @DisplayName("Cancel payment on paid order is rejected without side effects")
    void cancelPaymentOnPaidOrder_isRejectedWithoutSideEffects() {
        AppUser user = createUser();
        Book book = createBook("Cancel Paid Payment Book", new BigDecimal("20.00"), 5);
        Order order = createOrder(user, book, 1);

        when(securityUtils.getCurrentUser()).thenReturn(user);
        paymentService.processPayment(order.getId(), "card");

        Inventory invBefore = inventoryRepository.findByBookId(book.getId()).orElseThrow();
        int availableBefore = invBefore.getAvailableQuantity();
        int reservedBefore = invBefore.getReservedQuantity();
        int orderHistoryBefore = orderService.getOrderHistory(order.getId()).size();
        Payment payment = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        int paymentHistoryBefore = paymentService.getPaymentHistory(payment.getId()).size();

        assertThrows(InvalidPaymentStateException.class,
                () -> paymentService.cancelPayment(order.getId()));

        Order reloadedOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertEquals(OrderStatus.PAID, reloadedOrder.getStatus());
        Payment reloadedPayment = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        assertEquals(PaymentStatus.COMPLETED, reloadedPayment.getPaymentStatus());

        Inventory invAfter = inventoryRepository.findByBookId(book.getId()).orElseThrow();
        assertEquals(availableBefore, invAfter.getAvailableQuantity());
        assertEquals(reservedBefore, invAfter.getReservedQuantity());

        assertEquals(orderHistoryBefore, orderService.getOrderHistory(order.getId()).size());
        assertEquals(paymentHistoryBefore, paymentService.getPaymentHistory(reloadedPayment.getId()).size());
    }

    @Test
    @DisplayName("Cannot process already completed payment")
    void cannotProcessCompletedPayment() {
        AppUser user = createUser();
        Book book = createBook("Double Pay Book", new BigDecimal("15.00"), 5);
        Order order = createOrder(user, book, 1);

        paymentService.processPayment(order.getId(), "card");

        assertThrows(InvalidPaymentStateException.class,
                () -> paymentService.processPayment(order.getId(), "card"));
    }

    @Test
    @DisplayName("Refund completed payment for PAID order cancels order and records history")
    void refundCompletedPayment_forPaidOrder_cancelsOrderAndRecordsHistory() {
        AppUser user = createUser();
        Book book = createBook("Refund Payment Book", new BigDecimal("50.00"), 5);
        Order order = createOrder(user, book, 1);

        Payment processed = paymentService.processPayment(order.getId(), "card");
        assertNotNull(processed.getId());

        Payment refunded = paymentService.refundPayment(processed.getId());
        assertEquals(PaymentStatus.REFUNDED, refunded.getPaymentStatus());

        Order cancelledOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertEquals(OrderStatus.CANCELLED, cancelledOrder.getStatus());

        List<PaymentHistory> payHistory = paymentService.getPaymentHistory(refunded.getId());
        assertTrue(payHistory.stream().anyMatch(h -> h.getNewStatus() == PaymentStatus.REFUNDED));

        List<OrderStatusHistory> orderHistory = orderService.getOrderHistory(order.getId());
        assertTrue(orderHistory.stream().anyMatch(h -> h.getNewStatus() == OrderStatus.CANCELLED));
    }

    @Test
    @DisplayName("Payment history by order ID returns correct history")
    void paymentHistoryByOrderId() {
        AppUser user = createUser();
        Book book = createBook("History By Order Book", new BigDecimal("35.00"), 5);
        Order order = createOrder(user, book, 1);

        List<PaymentHistory> history = paymentService.getPaymentHistoryByOrderId(order.getId());
        assertTrue(history.isEmpty());
    }

    @Test
    @DisplayName("All payment endpoints work end-to-end")
    void fullPaymentLifecycle() {
        AppUser user = createUser();
        Book book = createBook("Lifecycle Book", new BigDecimal("100.00"), 3);
        Order order = createOrder(user, book, 1);

        Payment payment = paymentService.getPaymentByOrderId(order.getId());
        assertEquals(PaymentStatus.PENDING, payment.getPaymentStatus());

        payment = paymentService.processPayment(order.getId(), "credit_card");
        assertEquals(PaymentStatus.COMPLETED, payment.getPaymentStatus());

        Order paidOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertEquals(OrderStatus.PAID, paidOrder.getStatus());

        Payment refunded = paymentService.refundPayment(payment.getId());
        assertEquals(PaymentStatus.REFUNDED, refunded.getPaymentStatus());

        Order cancelledOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertEquals(OrderStatus.CANCELLED, cancelledOrder.getStatus());

        List<PaymentHistory> history = paymentService.getPaymentHistory(payment.getId());
        assertEquals(4, history.size());
        assertEquals(PaymentStatus.PROCESSING, history.get(0).getNewStatus());
        assertEquals(PaymentStatus.COMPLETED, history.get(1).getNewStatus());
        assertEquals(PaymentStatus.REFUND_PROCESSING, history.get(2).getNewStatus());
        assertEquals(PaymentStatus.REFUNDED, history.get(3).getNewStatus());
    }
}
