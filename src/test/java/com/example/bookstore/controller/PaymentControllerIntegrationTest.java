package com.example.bookstore.controller;

import com.example.bookstore.base.AbstractControllerTest;
import com.example.bookstore.model.*;
import com.example.bookstore.repository.BookRepository;
import com.example.bookstore.repository.InventoryRepository;
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

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PaymentControllerIntegrationTest extends AbstractControllerTest {

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private CartService cartService;

    @Autowired
    private OrderService orderService;

    private String token;
    private AppUser user;
    private Long orderId;

    @BeforeEach
    void setUp() throws Exception {
        bookRepository.deleteAll();
        String username = "pay-user-" + System.nanoTime();
        user = createTestUser(username, UserRole.ROLE_USER);
        token = loginAndGetToken(username);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));

        Book book = new Book();
        book.setTitle("Payment Book");
        book.setAuthor("Author");
        book.setPrice(new BigDecimal("30.00"));
        book.setCategory("Test");
        book.setStock(10);
        book = bookRepository.save(book);

        inventoryRepository.save(new Inventory(book.getId(), 10));

        cartService.addToCart(book.getId(), 2);
        Order order = orderService.createOrder();
        orderId = order.getId();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("GET /api/payments/{orderId} returns payment details")
    void getPayment_ReturnsPayment() throws Exception {
        mockMvc.perform(get("/api/v1/payments/" + orderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$.amount").value(60.0));
    }

    @Test
    @DisplayName("POST /api/payments/{orderId}/process processes payment")
    void processPayment_Success() throws Exception {
        String request = """
                {"paymentMethod": "CREDIT_CARD"}
                """;

        mockMvc.perform(post("/api/v1/payments/" + orderId + "/process")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.transactionId").isNotEmpty());
    }

    @Test
    @DisplayName("POST /api/payments/{orderId}/cancel cancels pending payment")
    void cancelPayment_Success() throws Exception {
        mockMvc.perform(post("/api/v1/payments/" + orderId + "/cancel")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("CANCELLED"));
    }

    @Test
    @DisplayName("GET /api/payments/{orderId}/history returns payment history")
    void getPaymentHistory_ReturnsHistory() throws Exception {
        String request = """
                {"paymentMethod": "CREDIT_CARD"}
                """;
        mockMvc.perform(post("/api/v1/payments/" + orderId + "/process")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(request));

        mockMvc.perform(get("/api/v1/payments/" + orderId + "/history")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))));
    }

    @Test
    @DisplayName("Payment endpoints reject unauthenticated requests")
    void paymentEndpoints_Unauthenticated_Returns401() throws Exception {
        mockMvc.perform(get("/api/v1/payments/" + orderId))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/payments/" + orderId + "/process")
                        .contentType("application/json")
                        .content("{\"paymentMethod\": \"CARD\"}"))
                .andExpect(status().isForbidden());
    }
}
