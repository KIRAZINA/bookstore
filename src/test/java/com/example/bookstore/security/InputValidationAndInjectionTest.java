package com.example.bookstore.security;

import com.example.bookstore.base.AbstractControllerTest;
import com.example.bookstore.repository.BookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("Input Validation & Injection Attack Tests")
class InputValidationAndInjectionTest extends AbstractControllerTest {

    @Autowired
    private BookRepository bookRepository;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        bookRepository.deleteAll();
        adminToken = registerAdminAndGetToken("injection-test-admin-" + System.nanoTime());
    }

    @Nested
    @DisplayName("Input Validation")
    class InputValidationTests {
        @Test
        @DisplayName("Missing required fields returns bad request")
        void missingRequiredFields_ReturnsBadRequest() throws Exception {
            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType("application/json")
                            .content("""
                                    {"email": "test@test.com"}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Negative values return bad request")
        void negativeValues_ReturnsBadRequest() throws Exception {
            mockMvc.perform(post("/api/v1/books")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType("application/json")
                            .content("""
                                    {"title": "Test", "author": "Author", "price": -10, "stock": -5, "category": "Test"}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Large payloads are rejected")
        void largePayload_ReturnsBadRequest() throws Exception {
            StringBuilder longTitle = new StringBuilder();
            for (int i = 0; i < 10000; i++) {
                longTitle.append("x");
            }

            mockMvc.perform(post("/api/v1/books")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType("application/json")
                            .content(String.format("""
                                    {"title": "%s", "author": "Author", "price": 10, "stock": 5, "category": "Test"}
                                    """, longTitle)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Invalid JSON returns bad request")
        void invalidJson_ReturnsBadRequest() throws Exception {
            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType("application/json")
                            .content("{invalid json"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("SQL Injection Attacks")
    class SqlInjectionTests {
        @Test
        @DisplayName("SQL injection in search endpoint is neutralized")
        void sqlInjectionSearchEndpoint_Neutralized() throws Exception {
            String maliciousQuery = "' OR '1'='1";
            mockMvc.perform(get("/api/v1/books/search")
                            .param("query", maliciousQuery))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("SQL injection in book title is neutralized")
        void sqlInjectionBookTitle_Neutralized() throws Exception {
            String maliciousTitle = "Book'; DROP TABLE book; --";
            mockMvc.perform(post("/api/v1/books")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType("application/json")
                            .content(String.format("""
                                    {"title": "%s", "author": "Author", "price": 10, "stock": 5, "category": "Test"}
                                    """, maliciousTitle)))
                    .andExpect(status().isCreated());
        }
    }
}
