package com.example.bookstore.chaos;

import com.example.bookstore.config.RedisRateLimiter;
import com.example.bookstore.model.Book;
import com.example.bookstore.repository.BookRepository;
import com.example.bookstore.service.BookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Redis chaos tests — fail-open behavior and recovery")
class RedisChaosTest {

    private static final String UNREACHABLE_HOST = "192.0.2.1";

    @DynamicPropertySource
    static void configureUnavailableRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> UNREACHABLE_HOST);
        registry.add("spring.data.redis.port", () -> "6379");
        registry.add("spring.cache.type", () -> "redis");
        registry.add("app.cache.enabled", () -> "true");
    }

    @Autowired
    private BookService bookService;

    @Autowired
    private BookRepository bookRepository;

    @Autowired(required = false)
    private RedisRateLimiter redisRateLimiter;

    @BeforeEach
    void setUp() {
        bookRepository.deleteAll();
    }

    @Test
    @DisplayName("Rate limiter should fail open when Redis is unavailable")
    void rateLimiter_ShouldFailOpenWhenRedisDown() {
        if (redisRateLimiter == null) {
            return;
        }
        String key = "test:chaos:rate-limit-" + System.nanoTime();
        boolean allowed = redisRateLimiter.tryConsume(key, 10, Duration.ofMinutes(1), 1);
        assertTrue(allowed);
    }

    @Test
    @DisplayName("Book service should work normally when Redis is unavailable")
    void bookService_ShouldWorkWhenRedisUnavailable() {
        Book book = new Book();
        book.setTitle("Chaos Book");
        book.setAuthor("Author");
        book.setPrice(new BigDecimal("12.99"));
        book.setCategory("Test");
        book.setStock(50);
        Book saved = bookRepository.save(book);

        List<Book> allBooks = bookService.getAllBooks();
        assertNotNull(allBooks);
        assertFalse(allBooks.isEmpty());
        assertEquals(saved.getId(), allBooks.getFirst().getId());
    }
}