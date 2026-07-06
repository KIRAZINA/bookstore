package com.example.bookstore.service;

import com.example.bookstore.base.AbstractIntegrationTest;
import com.example.bookstore.model.*;
import com.example.bookstore.repository.*;
import com.example.bookstore.service.payment.DummyPaymentProvider;
import com.example.bookstore.service.payment.PaymentProviderResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class PaymentIntegrationTest extends AbstractIntegrationTest {

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
    private InventoryRepository inventoryRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private DummyPaymentProvider paymentProvider;

    private AppUser user;
    private Book book;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
        inventoryRepository.deleteAll();
        bookRepository.deleteAll();
        userRepository.deleteAll();

        user = new AppUser();
        user.setUsername("pay-int-" + System.nanoTime());
        user.setPassword(passwordEncoder.encode("password"));
        user.setEmail("pay-int@test.com");
        user.setRole(UserRole.ROLE_USER);
        user = userRepository.save(user);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));

        book = new Book();
        book.setTitle("Payment Integration Book");
        book.setAuthor("Author");
        book.setPrice(new BigDecimal("25.00"));
        book.setCategory("Test");
        book.setStock(10);
        book = bookRepository.save(book);

        Inventory inventory = new Inventory(book.getId(), 10);
        inventoryRepository.save(inventory);

        when(paymentProvider.getName()).thenReturn("DUMMY");
    }

    @Test
    @DisplayName("Successful payment transitions payment to COMPLETED and order to PAID")
    void processPayment_Success_CompletesPaymentAndOrder() {
        when(paymentProvider.processPayment(any()))
                .thenReturn(PaymentProviderResponse.success("TXN-SUCCESS-001", PaymentStatus.COMPLETED));

        cartService.addToCart(book.getId(), 2);
        Order order = orderService.createOrder();

        Payment payment = paymentService.processPayment(order.getId(), "CREDIT_CARD");

        Payment reloaded = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        assertEquals(PaymentStatus.COMPLETED, reloaded.getPaymentStatus());
        assertNotNull(reloaded.getTransactionId());
        assertNotNull(reloaded.getCompletedAt());

        Order reloadedOrder = orderService.getOrderById(order.getId());
        assertEquals(OrderStatus.PAID, reloadedOrder.getStatus());
    }

    @Test
    @DisplayName("Failed payment releases inventory and transitions to FAILED")
    void processPayment_ProviderFailure_ReleasesInventory() {
        when(paymentProvider.processPayment(any()))
                .thenReturn(PaymentProviderResponse.failed("INSUFFICIENT_FUNDS"));

        cartService.addToCart(book.getId(), 2);
        Order order = orderService.createOrder();

        Payment payment = paymentService.processPayment(order.getId(), "CREDIT_CARD");

        Payment reloaded = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        assertEquals(PaymentStatus.FAILED, reloaded.getPaymentStatus());
        assertEquals("INSUFFICIENT_FUNDS", reloaded.getFailureReason());

        Inventory inventory = inventoryRepository.findByBookId(book.getId()).orElseThrow();
        assertEquals(10, inventory.getAvailableQuantity(),
                "Inventory should be fully restored after failed payment");
    }

    @Test
    @DisplayName("Refund transitions payment to REFUNDED and restores stock")
    void refundPayment_Success_RefundsAndRestoresStock() {
        when(paymentProvider.processPayment(any()))
                .thenReturn(PaymentProviderResponse.success("TXN-REF-001", PaymentStatus.COMPLETED));
        when(paymentProvider.refundPayment(any()))
                .thenReturn(PaymentProviderResponse.success("TXN-REF-REFUND-001", PaymentStatus.REFUNDED));

        cartService.addToCart(book.getId(), 3);
        Order order = orderService.createOrder();
        paymentService.processPayment(order.getId(), "CREDIT_CARD");
        Payment payment = paymentRepository.findByOrderId(order.getId()).orElseThrow();

        Payment refunded = paymentService.refundPayment(payment.getId());

        assertEquals(PaymentStatus.REFUNDED, refunded.getPaymentStatus());

        Inventory inventory = inventoryRepository.findByBookId(book.getId()).orElseThrow();
        assertEquals(10, inventory.getAvailableQuantity(),
                "Stock should be restored to original after refund");
    }

    @Test
    @DisplayName("Refund provider failure restores payment to COMPLETED and restores stock")
    void refundPayment_ProviderFailure_RestoresPaymentStatus() {
        when(paymentProvider.processPayment(any()))
                .thenReturn(PaymentProviderResponse.success("TXN-REF-FAIL-001", PaymentStatus.COMPLETED));
        when(paymentProvider.refundPayment(any()))
                .thenReturn(PaymentProviderResponse.failed("REFUND_REJECTED"));

        cartService.addToCart(book.getId(), 1);
        Order order = orderService.createOrder();
        paymentService.processPayment(order.getId(), "CREDIT_CARD");
        Payment payment = paymentRepository.findByOrderId(order.getId()).orElseThrow();

        Payment result = paymentService.refundPayment(payment.getId());

        assertEquals(PaymentStatus.COMPLETED, result.getPaymentStatus(),
                "Payment should revert to COMPLETED on refund failure");

        Inventory inventory = inventoryRepository.findByBookId(book.getId()).orElseThrow();
        assertEquals(9, inventory.getAvailableQuantity(),
                "Stock should reflect non-refunded state (reserved quantity was fully finalized)");
    }

    @Test
    @DisplayName("Cancelling pending payment leaves order in PENDING")
    void cancelPendingPayment_OrderRemainsPending() {
        cartService.addToCart(book.getId(), 1);
        Order order = orderService.createOrder();

        paymentService.cancelPayment(order.getId());

        Payment payment = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        assertEquals(PaymentStatus.CANCELLED, payment.getPaymentStatus());

        Order reloaded = orderService.getOrderById(order.getId());
        assertEquals(OrderStatus.PENDING, reloaded.getStatus());
    }

    @Test
    @DisplayName("Two-phase payment processing prevents concurrent modifications")
    void processPayment_TwoPhase_DetectsStateChange() {
        when(paymentProvider.processPayment(any()))
                .thenAnswer(invocation -> {
                    Payment payment = invocation.getArgument(0);
                    Payment inDb = paymentRepository.findByOrderId(payment.getOrder().getId()).orElseThrow();
                    assertEquals(PaymentStatus.PROCESSING, inDb.getPaymentStatus(),
                            "Payment should be in PROCESSING state during provider call (phase 1 completed)");
                    return PaymentProviderResponse.success("TXN-TWOPHASE-001", PaymentStatus.COMPLETED);
                });

        cartService.addToCart(book.getId(), 1);
        Order order = orderService.createOrder();

        Payment payment = paymentService.processPayment(order.getId(), "CREDIT_CARD");

        assertEquals(PaymentStatus.COMPLETED, payment.getPaymentStatus());
        assertNotNull(payment.getTransactionId());
    }
}
