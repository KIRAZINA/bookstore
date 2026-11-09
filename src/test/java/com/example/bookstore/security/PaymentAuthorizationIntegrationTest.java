package com.example.bookstore.security;

import com.example.bookstore.base.AbstractControllerTest;
import com.example.bookstore.model.*;
import com.example.bookstore.repository.BookRepository;
import com.example.bookstore.repository.InventoryRepository;
import com.example.bookstore.repository.PaymentHistoryRepository;
import com.example.bookstore.repository.PaymentRepository;
import com.example.bookstore.service.CartService;
import com.example.bookstore.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PaymentAuthorizationIntegrationTest extends AbstractControllerTest {

    @Autowired
    private BookRepository bookRepository;
    @Autowired
    private InventoryRepository inventoryRepository;
    @Autowired
    private CartService cartService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private PaymentHistoryRepository paymentHistoryRepository;

    private AppUser owner;
    private AppUser attacker;
    private String ownerToken;
    private String attackerToken;
    private Long orderId;

    @BeforeEach
    void setUp() throws Exception {
        bookRepository.deleteAll();
        inventoryRepository.deleteAll();
        paymentHistoryRepository.deleteAll();
        paymentRepository.deleteAll();

        owner = createTestUser("owner-" + System.nanoTime(), UserRole.ROLE_USER);
        attacker = createTestUser("attacker-" + System.nanoTime(), UserRole.ROLE_USER);
        ownerToken = loginAndGetToken(owner.getUsername());
        attackerToken = loginAndGetToken(attacker.getUsername());

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(owner, null, owner.getAuthorities()));

        Book book = new Book();
        book.setTitle("PayAuth Book");
        book.setAuthor("Author");
        book.setPrice(new BigDecimal("30.00"));
        book.setCategory("Test");
        book.setStock(10);
        book = bookRepository.save(book);

        inventoryRepository.save(new Inventory(book.getId(), 10));

        cartService.addToCart(book.getId(), 2);
        Order order = orderService.createOrder();
        orderId = order.getId();

        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Long createPendingOrder(AppUser user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        try {
            Book book = new Book();
            book.setTitle("PayAuth Book " + UUID.randomUUID());
            book.setAuthor("Author");
            book.setPrice(new BigDecimal("10.00"));
            book.setCategory("Test");
            book.setStock(5);
            book = bookRepository.save(book);
            inventoryRepository.save(new Inventory(book.getId(), 5));
            cartService.addToCart(book.getId(), 1);
            Order order = orderService.createOrder();
            return order.getId();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("attacker cannot get owner payment")
    void attackerCannotGetOwnersPayment() throws Exception {
        mockMvc.perform(get("/api/v1/payments/" + orderId)
                        .header("Authorization", "Bearer " + attackerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("attacker cannot process owner payment")
    void attackerCannotProcessOwnersPayment() throws Exception {
        Payment before = paymentRepository.findByOrderId(orderId).orElseThrow();
        int historyBefore = paymentHistoryRepository.findByPaymentIdOrderByCreatedAtAsc(before.getId()).size();

        mockMvc.perform(post("/api/v1/payments/" + orderId + "/process")
                        .header("Authorization", "Bearer " + attackerToken)
                        .contentType("application/json")
                        .content("{\"paymentMethod\":\"CREDIT_CARD\"}"))
                .andExpect(status().isForbidden());

        Payment after = paymentRepository.findByOrderId(orderId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(before.getPaymentStatus(), after.getPaymentStatus());
        int historyAfter = paymentHistoryRepository.findByPaymentIdOrderByCreatedAtAsc(after.getId()).size();
        org.junit.jupiter.api.Assertions.assertEquals(historyBefore, historyAfter);
    }

    @Test
    @DisplayName("attacker cannot cancel owner payment")
    void attackerCannotCancelOwnersPayment() throws Exception {
        Payment before = paymentRepository.findByOrderId(orderId).orElseThrow();
        int historyBefore = paymentHistoryRepository.findByPaymentIdOrderByCreatedAtAsc(before.getId()).size();

        mockMvc.perform(post("/api/v1/payments/" + orderId + "/cancel")
                        .header("Authorization", "Bearer " + attackerToken))
                .andExpect(status().isForbidden());

        Payment after = paymentRepository.findByOrderId(orderId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(before.getPaymentStatus(), after.getPaymentStatus());
        int historyAfter = paymentHistoryRepository.findByPaymentIdOrderByCreatedAtAsc(after.getId()).size();
        org.junit.jupiter.api.Assertions.assertEquals(historyBefore, historyAfter);
    }

    @Test
    @DisplayName("attacker cannot get owner payment history")
    void attackerCannotGetOwnersPaymentHistory() throws Exception {
        mockMvc.perform(get("/api/v1/payments/" + orderId + "/history")
                        .header("Authorization", "Bearer " + attackerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("owner can get own payment")
    void ownerCanGetOwnPayment() throws Exception {
        mockMvc.perform(get("/api/v1/payments/" + orderId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"));
    }

    @Test
    @DisplayName("owner can get own payment history")
    void ownerCanGetOwnPaymentHistory() throws Exception {
        mockMvc.perform(get("/api/v1/payments/" + orderId + "/history")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("owner can process own payment")
    void ownerCanProcessOwnPayment() throws Exception {
        mockMvc.perform(post("/api/v1/payments/" + orderId + "/process")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType("application/json")
                        .content("{\"paymentMethod\":\"CREDIT_CARD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("COMPLETED"));
    }

    @Test
    @DisplayName("owner can cancel own pending payment")
    void ownerCanCancelOwnPayment() throws Exception {
        Long cancelOrderId = createPendingOrder(owner);
        mockMvc.perform(post("/api/v1/payments/" + cancelOrderId + "/cancel")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("CANCELLED"));
    }
}
