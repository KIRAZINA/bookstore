package com.example.bookstore.security;

import com.example.bookstore.base.AbstractIntegrationTest;
import com.example.bookstore.model.Book;
import com.example.bookstore.repository.BookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.Duration;

@DisplayName("Rate Limiting & Abuse Protection Tests")
class RateLimitingAndAbuseProtectionTest extends AbstractIntegrationTest {

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private WebTestClient webTestClient;

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
    void burstRequests_AllowedInitially() {
        for (int i = 0; i < 10; i++) {
            webTestClient.get().uri("/api/v1/books")
                    .exchange()
                    .expectStatus().isOk();
        }
    }
}
