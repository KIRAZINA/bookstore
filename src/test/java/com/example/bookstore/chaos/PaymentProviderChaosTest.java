package com.example.bookstore.chaos;

import com.example.bookstore.base.AbstractIntegrationTest;
import com.example.bookstore.config.SecurityUtils;
import com.example.bookstore.model.*;
import com.example.bookstore.repository.*;
import com.example.bookstore.service.*;
import com.example.bookstore.service.payment.DummyPaymentProvider;
import com.example.bookstore.service.payment.PaymentProviderResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("Payment provider chaos tests — failure handling and idempotency")
class PaymentProviderChaosTest extends AbstractIntegrationTest {

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
    private InventoryRepository inventoryRepository;

    @Autowired
    private OrderRepository orderRepository;

    @MockitoBean
    private SecurityUtils securityUtils;

    @MockitoBean
    private DummyPaymentProvider paymentProvider;

    private AppUser testUser;
    private Book testBook;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        bookRepository.deleteAll();
        inventoryRepository.deleteAll();
        userRepository.deleteAll();

        testUser = new AppUser();
        testUser.setUsername("pay-chaos-" + UUID.randomUUID());
        testUser.setPassword("password");
        testUser.setEmail("pay-chaos@example.com");
        testUser.setRole(UserRole.ROLE_USER);
        testUser = userRepository.save(testUser);

        testBook = new Book();
        testBook.setTitle("Payment Chaos Book");
        testBook.setAuthor("Author");
        testBook.setPrice(new BigDecimal("19.99"));
        testBook.setCategory("Testing");
        testBook.setStock(100);
        testBook = bookRepository.save(testBook);

        Inventory inventory = new Inventory(testBook.getId(), testBook.getStock());
        inventoryRepository.save(inventory);

        when(paymentProvider.getName()).thenReturn("DUMMY");
        when(securityUtils.getCurrentUser()).thenReturn(testUser);
    }

    private Order createTestOrder() {
        cartService.addToCart(testBook.getId(), 1);
        return orderService.createOrder();
    }

    @Test
    @DisplayName("Should handle payment provider timeout gracefully")
    void shouldHandlePaymentTimeoutGracefully() {
        when(paymentProvider.processPayment(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("Payment provider timeout"));

        Order order = createTestOrder();
        assertThrows(RuntimeException.class, () -> {
            paymentService.processPayment(order.getId(), "CREDIT_CARD");
        });
    }

    @Test
    @DisplayName("Should handle payment provider failure gracefully")
    void shouldHandlePaymentFailureGracefully() {
        when(paymentProvider.processPayment(org.mockito.ArgumentMatchers.any()))
                .thenReturn(PaymentProviderResponse.failed("PAYMENT_DECLINED"));

        Order order = createTestOrder();
        Payment payment = paymentService.processPayment(order.getId(), "CREDIT_CARD");

        assertNotNull(payment);
        assertEquals(PaymentStatus.FAILED, payment.getPaymentStatus());
    }

    @Test
    @DisplayName("Should maintain inventory consistency even if payment fails")
    void shouldMaintainInventoryConsistencyWhenPaymentFails() {
        Book bookBefore = bookRepository.findById(testBook.getId()).orElseThrow();
        int initialStock = bookBefore.getStock();

        when(paymentProvider.processPayment(org.mockito.ArgumentMatchers.any()))
                .thenReturn(PaymentProviderResponse.failed("PAYMENT_DECLINED"));

        Order order = createTestOrder();
        assertDoesNotThrow(() -> {
            paymentService.processPayment(order.getId(), "CREDIT_CARD");
        });

        Book bookAfter = bookRepository.findById(testBook.getId()).orElseThrow();
        assertEquals(initialStock, bookAfter.getStock());
    }
}
