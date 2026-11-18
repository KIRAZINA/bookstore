package com.example.bookstore.service;

import com.example.bookstore.base.AbstractIntegrationTest;
import com.example.bookstore.config.SecurityUtils;
import com.example.bookstore.model.AppUser;
import com.example.bookstore.model.Book;
import com.example.bookstore.model.Order;
import com.example.bookstore.model.Payment;
import com.example.bookstore.model.PaymentStatus;
import com.example.bookstore.model.UserRole;
import com.example.bookstore.repository.BookRepository;
import com.example.bookstore.repository.InventoryRepository;
import com.example.bookstore.repository.OrderRepository;
import com.example.bookstore.repository.PaymentRepository;
import com.example.bookstore.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Persistence evidence: V12 columns exist with the specified types on a real
 * Postgres, and the refund success path writes the three refund fields while preserving
 * the original {@code transaction_id}.
 *
 * <p>Like {@link PaymentServiceIntegrationTest}, this class is intentionally not
 * class-level {@code @Transactional}: the refund path's {@code REQUIRES_NEW} fences need
 * committed fixture rows.
 */
class PaymentRefundIntegrityIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private CartService cartService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private InventoryAdminService inventoryAdminService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private SecurityUtils securityUtils;

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
        user.setUsername("refund-int-" + UUID.randomUUID());
        user.setPassword("password");
        user.setEmail("refund-int@example.com");
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
    @DisplayName("V12 columns exist on payment with the specified types and are nullable")
    void v12ColumnsExistWithExpectedTypes() {
        assertColumn("refunded_amount", "numeric");
        assertColumn("refunded_at", "timestamp without time zone");
        assertColumn("refund_transaction_id", "character varying");
    }

    private void assertColumn(String name, String expectedType) {
        Map<String, Object> column = jdbcTemplate.queryForMap(
                "SELECT data_type, is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'payment' AND column_name = ?",
                name);
        assertEquals(expectedType, column.get("data_type"), "unexpected type for " + name);
        assertEquals("YES", column.get("is_nullable"), name + " must be nullable");
    }

    @Test
    @DisplayName("Successful refund persists refund fields and keeps the original transaction id")
    void successfulRefund_persistsRefundFieldsAndPreservesOriginalTransactionId() {
        AppUser user = createUser();
        Book book = createBook("Refund Integrity Book", new BigDecimal("50.00"), 5);
        Order order = createOrder(user, book, 1);

        Payment processed = paymentService.processPayment(order.getId(), "card");
        assertEquals(PaymentStatus.COMPLETED, processed.getPaymentStatus());
        String originalTransactionId = processed.getTransactionId();
        assertNotNull(originalTransactionId);

        Payment refunded = paymentService.refundPayment(processed.getId());
        assertEquals(PaymentStatus.REFUNDED, refunded.getPaymentStatus());
        assertEquals(originalTransactionId, refunded.getTransactionId());
        assertEquals(0, new BigDecimal("50.00").compareTo(refunded.getRefundedAmount()));
        assertNotNull(refunded.getRefundedAt());
        assertNotNull(refunded.getRefundTransactionId());
        assertTrue(refunded.getRefundTransactionId().startsWith(originalTransactionId + "-REF-"),
                "refund transaction id must be the provider's distinct refund id");

        // Re-read through the repository to prove the values are persisted, not just
        // held on the entity returned by the phase-fence orchestration.
        Payment persisted = paymentRepository.findById(processed.getId()).orElseThrow();
        assertEquals(originalTransactionId, persisted.getTransactionId());
        assertEquals(0, new BigDecimal("50.00").compareTo(persisted.getRefundedAmount()));
        assertNotNull(persisted.getRefundedAt());
        assertEquals(refunded.getRefundTransactionId(), persisted.getRefundTransactionId());
    }
}
