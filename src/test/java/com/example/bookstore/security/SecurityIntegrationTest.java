package com.example.bookstore.security;

import com.example.bookstore.base.AbstractControllerTest;
import com.example.bookstore.model.Book;
import com.example.bookstore.repository.BookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SecurityIntegrationTest extends AbstractControllerTest {

    @Autowired
    private BookRepository bookRepository;

    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() throws Exception {
        bookRepository.deleteAll();
        adminToken = registerAdminAndGetToken("sec-admin-" + System.nanoTime());
        userToken = registerUserAndGetToken("sec-user-" + System.nanoTime());
    }

    @Test
    @DisplayName("Public GET /api/books is accessible without authentication")
    void publicEndpoint_NoAuth_ReturnsOk() throws Exception {
        mockMvc.perform(get("/api/books"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Admin-only POST /api/books requires admin role")
    void adminEndpoint_UserRole_ReturnsForbidden() throws Exception {
        String request = """
                {"title": "Book", "author": "Auth", "price": 10.00, "stock": 5}
                """;

        mockMvc.perform(post("/api/books")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType("application/json")
                        .content(request))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Admin-only POST /api/books works with admin role")
    void adminEndpoint_AdminRole_ReturnsCreated() throws Exception {
        String request = """
                {"title": "Admin Book", "author": "Admin", "price": 20.00, "category": "Test", "stock": 3}
                """;

        mockMvc.perform(post("/api/books")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(request))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Cart endpoints require USER role")
    void cartEndpoint_NoAuth_ReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/cart"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Invalid JWT token returns 401")
    void invalidToken_ReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/cart")
                        .header("Authorization", "Bearer invalid.token.here"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Order endpoints require USER role")
    void orderEndpoint_UserRole_ReturnsOk() throws Exception {
        mockMvc.perform(get("/api/orders")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());
    }
}
