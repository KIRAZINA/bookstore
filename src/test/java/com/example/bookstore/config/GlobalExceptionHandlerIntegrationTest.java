package com.example.bookstore.config;

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

class GlobalExceptionHandlerIntegrationTest extends AbstractControllerTest {

    @Autowired
    private BookRepository bookRepository;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        bookRepository.deleteAll();
        adminToken = registerAdminAndGetToken("err-admin-" + System.nanoTime());
    }

    @Test
    @DisplayName("Validation error returns 400 with field errors")
    void validationError_ReturnsBadRequest() throws Exception {
        String invalidRequest = """
                {"title": "", "author": "", "price": -1, "stock": -5}
                """;

        mockMvc.perform(post("/api/v1/books")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(invalidRequest))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Missing request body returns 400")
    void missingBody_ReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/books")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Resource not found returns appropriate error")
    void deleteNonExistentBook_ReturnsError() throws Exception {
        mockMvc.perform(delete("/api/v1/books/99999")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Unauthenticated requests to protected endpoints return 403")
    void unauthenticatedRequest_Returns401() throws Exception {
        mockMvc.perform(get("/api/v1/cart"))
                .andExpect(status().isForbidden());
    }
}
