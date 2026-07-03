package com.example.bookstore.service;

import com.example.bookstore.base.AbstractIntegrationTest;
import com.example.bookstore.config.KafkaTopicConfig;
import com.example.bookstore.event.repository.ProcessedEventRepository;
import com.example.bookstore.model.*;
import com.example.bookstore.notification.model.EmailLog;
import com.example.bookstore.notification.repository.EmailLogRepository;
import com.example.bookstore.repository.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EmbeddedKafka(
    topics = {
        KafkaTopicConfig.TOPIC_ORDERS,
        KafkaTopicConfig.TOPIC_PAYMENTS,
        KafkaTopicConfig.TOPIC_INVENTORY,
        KafkaTopicConfig.TOPIC_NOTIFICATIONS
    },
    partitions = 1,
    controlledShutdown = true,
    bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class OrderWorkflowIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private CartService cartService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Autowired
    private EmailLogRepository emailLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private AppUser user;
    private Book book;

    @BeforeEach
    void setUp() {
        emailLogRepository.deleteAll();
        orderStatusHistoryRepository.deleteAll();
        orderRepository.deleteAll();
        inventoryRepository.deleteAll();
        bookRepository.deleteAll();
        userRepository.deleteAll();

        user = new AppUser();
        user.setUsername("wf-user-" + System.nanoTime());
        user.setPassword(passwordEncoder.encode("password"));
        user.setEmail("wf@test.com");
        user.setRole(UserRole.ROLE_USER);
        user = userRepository.save(user);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));

        book = new Book();
        book.setTitle("Workflow Book");
        book.setAuthor("Author");
        book.setPrice(new BigDecimal("20.00"));
        book.setCategory("Test");
        book.setStock(10);
        book = bookRepository.save(book);

        Inventory inventory = new Inventory(book.getId(), 10);
        inventoryRepository.save(inventory);
    }

    @Test
    @DisplayName("Complete order lifecycle: create -> inventory reserve -> payment -> email")
    void createOrder_FullLifecycle_EndToEnd() {
        cartService.addToCart(book.getId(), 2);

        Order order = orderService.createOrder();

        assertNotNull(order.getId());
        assertEquals(OrderStatus.PENDING, order.getStatus());

        Inventory prePayment = inventoryRepository.findByBookId(book.getId()).orElseThrow();
        assertEquals(8, prePayment.getAvailableQuantity(),
                "Inventory should be reduced after order creation");
        assertEquals(2, prePayment.getReservedQuantity(),
                "Inventory should show reserved quantity");

        Payment payment = paymentService.processPayment(order.getId(), "CREDIT_CARD");

        assertEquals(PaymentStatus.COMPLETED, payment.getPaymentStatus());
        assertNotNull(payment.getTransactionId());

        Order paidOrder = orderService.getOrderById(order.getId());
        assertEquals(OrderStatus.PAID, paidOrder.getStatus());

        Inventory finalized = inventoryRepository.findByBookId(book.getId()).orElseThrow();
        assertEquals(8, finalized.getAvailableQuantity(),
                "Finalized inventory should have available = initial - sold");
        assertEquals(0, finalized.getReservedQuantity(),
                "No reserved quantity after finalization");

        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> {
                    List<EmailLog> logs = emailLogRepository.findAll();
                    return logs.stream().anyMatch(log -> "ORDER_CONFIRMATION".equals(log.getEmailType()));
                });

        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> {
                    List<EmailLog> logs = emailLogRepository.findAll();
                    return logs.stream().anyMatch(log -> "PAYMENT_SUCCESS".equals(log.getEmailType()));
                });
    }

    @Test
    @DisplayName("Order cancellation releases reserved inventory")
    void cancelOrder_ReleasesInventory() {
        cartService.addToCart(book.getId(), 3);
        Order order = orderService.createOrder();

        Inventory afterCreate = inventoryRepository.findByBookId(book.getId()).orElseThrow();
        assertEquals(7, afterCreate.getAvailableQuantity());

        orderService.cancelOwnOrder(order.getId());

        Order cancelled = orderService.getOrderById(order.getId());
        assertEquals(OrderStatus.CANCELLED, cancelled.getStatus());

        Inventory released = inventoryRepository.findByBookId(book.getId()).orElseThrow();
        assertEquals(10, released.getAvailableQuantity(),
                "Inventory should be fully restored after cancellation");
        assertEquals(0, released.getReservedQuantity());
    }

    @Test
    @DisplayName("Admin can cancel paid order and inventory is released")
    void adminCancelsPaidOrder_ReleasesInventory() {
        cartService.addToCart(book.getId(), 1);
        Order order = orderService.createOrder();

        paymentService.processPayment(order.getId(), "CREDIT_CARD");

        orderService.adminCancelOrder(order.getId(), "Customer request");

        Order cancelled = orderService.getOrderById(order.getId());
        assertEquals(OrderStatus.CANCELLED, cancelled.getStatus());

        Inventory inventory = inventoryRepository.findByBookId(book.getId()).orElseThrow();
        assertEquals(10, inventory.getAvailableQuantity(),
                "Inventory should be restored after admin cancellation of paid order");
    }

    @Test
    @DisplayName("Order status transition history is recorded for each change")
    void orderTransitions_RecordHistory() {
        cartService.addToCart(book.getId(), 1);
        Order order = orderService.createOrder();

        paymentService.processPayment(order.getId(), "CREDIT_CARD");

        orderService.updateOrderStatus(order.getId(), OrderStatus.PROCESSING, "Processing started");
        orderService.updateOrderStatus(order.getId(), OrderStatus.SHIPPED, "Shipped");
        orderService.updateOrderStatus(order.getId(), OrderStatus.DELIVERED, "Delivered");

        List<OrderStatusHistory> history = orderStatusHistoryRepository
                .findByOrderIdOrderByCreatedAtAsc(order.getId());

        assertTrue(history.size() >= 3, "Should have at least 3 status transitions");
        assertEquals(OrderStatus.PAID, history.get(0).getNewStatus());
    }
}
