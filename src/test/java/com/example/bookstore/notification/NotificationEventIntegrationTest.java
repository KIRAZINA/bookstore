package com.example.bookstore.notification;

import com.example.bookstore.base.AbstractIntegrationTest;
import com.example.bookstore.model.*;
import com.example.bookstore.notification.model.EmailLog;
import com.example.bookstore.notification.model.EmailStatus;
import com.example.bookstore.notification.repository.EmailLogRepository;
import com.example.bookstore.repository.BookRepository;
import com.example.bookstore.repository.InventoryRepository;
import com.example.bookstore.repository.UserRepository;
import com.example.bookstore.service.CartService;
import com.example.bookstore.service.OrderService;
import com.example.bookstore.service.PaymentService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class NotificationEventIntegrationTest extends AbstractIntegrationTest {

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
    private EmailLogRepository emailLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private AppUser user;
    private Book book;

    @BeforeEach
    void setUp() {
        emailLogRepository.deleteAll();
        bookRepository.deleteAll();
        userRepository.deleteAll();

        user = new AppUser();
        user.setUsername("notif-user-" + System.nanoTime());
        user.setPassword(passwordEncoder.encode("password"));
        user.setEmail("notif@test.com");
        user.setRole(UserRole.ROLE_USER);
        user = userRepository.save(user);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));

        book = new Book();
        book.setTitle("Notification Book");
        book.setAuthor("Author");
        book.setPrice(new BigDecimal("15.00"));
        book.setCategory("Test");
        book.setStock(10);
        book = bookRepository.save(book);

        inventoryRepository.save(new Inventory(book.getId(), 10));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Order creation triggers email log entry via event")
    void createOrder_TriggersEmailLog() {
        cartService.addToCart(book.getId(), 1);

        Order order = orderService.createOrder();

        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> {
                    List<EmailLog> logs = emailLogRepository.findAll();
                    return logs.stream().anyMatch(log -> "ORDER_CONFIRMATION".equals(log.getEmailType()));
                });

        List<EmailLog> logs = emailLogRepository.findAll();
        EmailLog orderEmail = logs.stream()
                .filter(log -> "ORDER_CONFIRMATION".equals(log.getEmailType()))
                .findFirst()
                .orElse(null);
        assertNotNull(orderEmail);
        assertEquals(user.getEmail(), orderEmail.getRecipient());
        assertTrue(orderEmail.getSubject().contains(String.valueOf(order.getId())));
    }

    @Test
    @DisplayName("Payment completion triggers email log entry via event")
    void processPayment_TriggersEmailLog() {
        cartService.addToCart(book.getId(), 1);
        Order order = orderService.createOrder();

        paymentService.processPayment(order.getId(), "CREDIT_CARD");

        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> {
                    List<EmailLog> logs = emailLogRepository.findAll();
                    return logs.stream().anyMatch(log -> "PAYMENT_SUCCESS".equals(log.getEmailType()));
                });
    }

    @Test
    @DisplayName("Order status change triggers email log entry")
    void orderStatusChange_TriggersEmailLog() {
        cartService.addToCart(book.getId(), 1);
        Order order = orderService.createOrder();

        orderService.updateOrderStatus(order.getId(), OrderStatus.PAID, "Test payment");

        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> {
                    List<EmailLog> logs = emailLogRepository.findAll();
                    return logs.stream().anyMatch(log -> "ORDER_STATUS_CHANGED".equals(log.getEmailType()));
                });
    }
}
