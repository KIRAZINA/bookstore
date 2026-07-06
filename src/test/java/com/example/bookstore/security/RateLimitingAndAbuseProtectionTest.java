package com.example.bookstore.security;

import com.example.bookstore.base.AbstractControllerTest;
import com.example.bookstore.model.Book;
import com.example.bookstore.repository.BookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Rate Limiting & Abuse Protection Tests")
class RateLimitingAndAbuseProtectionTest extends AbstractControllerTest {

    @Autowired
    private BookRepository bookRepository;

    @BeforeEach
    void setUp() {
        bookRepository.deleteAll();
        Book book = new Book();
        book.setTitle("Rate Limit Test Book");
        book.setAuthor("Test Author");
        book.setPrice(new BigDecimal("19.99"));
        book.setCategory("Test");
        book.setStock(10);
        bookRepository.save(book);
    }

    @Test
    @DisplayName("Burst requests are allowed initially")
    void burstRequests_AllowedInitially() throws Exception {
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/api/v1/books"))
                    .andExpect(status().isOk());
        }
    }
}
