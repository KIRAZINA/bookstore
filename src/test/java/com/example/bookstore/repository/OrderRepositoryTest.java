package com.example.bookstore.repository;

import com.example.bookstore.base.AbstractIntegrationTest;
import com.example.bookstore.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@Transactional
class OrderRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private AppUser user;
    private Order order;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        bookRepository.deleteAll();
        userRepository.deleteAll();

        user = new AppUser();
        user.setUsername("repo-test-user");
        user.setPassword(passwordEncoder.encode("password"));
        user.setEmail("repo@test.com");
        user.setRole(UserRole.ROLE_USER);
        user = userRepository.save(user);

        Book book = new Book();
        book.setTitle("Order Repo Book");
        book.setAuthor("Author");
        book.setPrice(new BigDecimal("10.00"));
        book.setCategory("Test");
        book.setStock(5);
        book = bookRepository.save(book);

        order = new Order();
        order.setUser(user);
        order.setStatus(OrderStatus.PENDING);
        order.setTotalPrice(new BigDecimal("10.00"));

        OrderItem item = new OrderItem();
        item.setBook(book);
        item.setQuantity(1);
        item.setUnitPrice(new BigDecimal("10.00"));
        item.setSubtotal(new BigDecimal("10.00"));
        order.addItem(item);

        order = orderRepository.save(order);
    }

    @Test
    @DisplayName("Should find orders by user ID with pagination")
    void findByUserId_ReturnsUserOrders() {
        Page<Order> page = orderRepository.findByUserId(user.getId(), PageRequest.of(0, 10));

        assertEquals(1, page.getContent().size());
        assertEquals(order.getId(), page.getContent().getFirst().getId());
    }

    @Test
    @DisplayName("Should find orders by status with pagination")
    void findByStatus_ReturnsOrdersWithStatus() {
        Page<Order> pending = orderRepository.findByStatus(OrderStatus.PENDING, PageRequest.of(0, 10));
        assertEquals(1, pending.getContent().size());

        Page<Order> delivered = orderRepository.findByStatus(OrderStatus.DELIVERED, PageRequest.of(0, 10));
        assertTrue(delivered.getContent().isEmpty());
    }

    @Test
    @DisplayName("Should find order by ID and user ID")
    void findByIdAndUserId_ReturnsOrder() {
        Optional<Order> found = orderRepository.findByIdAndUserId(order.getId(), user.getId());

        assertTrue(found.isPresent());
        assertEquals(order.getId(), found.get().getId());
    }

    @Test
    @DisplayName("Should return empty when order ID and user ID do not match")
    void findByIdAndUserId_WrongUser_ReturnsEmpty() {
        Optional<Order> found = orderRepository.findByIdAndUserId(order.getId(), 999L);

        assertFalse(found.isPresent());
    }
}
