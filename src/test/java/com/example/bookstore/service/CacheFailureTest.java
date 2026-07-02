package com.example.bookstore.service;

import com.example.bookstore.model.Book;
import com.example.bookstore.repository.BookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Cache failure — graceful degradation when Redis is unavailable")
class CacheFailureTest {

    private static final String NON_ROUTABLE_HOST = "192.0.2.1";

    @DynamicPropertySource
    static void configureUnavailableRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> NON_ROUTABLE_HOST);
        registry.add("spring.data.redis.port", () -> "6379");
        registry.add("spring.cache.type", () -> "redis");
        registry.add("app.cache.enabled", () -> "true");
    }

    @Autowired
    private BookService bookService;

    @Autowired
    private BookRepository bookRepository;

    @BeforeEach
    void setUp() {
        bookRepository.deleteAll();
        Book book = new Book();
        book.setTitle("Redis Down Book");
        book.setAuthor("Author");
        book.setPrice(new BigDecimal("9.99"));
        book.setCategory("Test");
        book.setStock(10);
        bookRepository.save(book);
    }

    @Test
    @DisplayName("getAllBooks should still work when Redis is down")
    void getAllBooks_WhenRedisUnavailable_ShouldReturnFromDatabase() {
        List<Book> books = bookService.getAllBooks();

        assertNotNull(books);
        assertFalse(books.isEmpty());
        assertEquals("Redis Down Book", books.getFirst().getTitle());
    }

    @Test
    @DisplayName("getBooksByCategory should still work when Redis is down")
    void getBooksByCategory_WhenRedisUnavailable_ShouldReturnFromDatabase() {
        List<Book> books = bookService.getBooksByCategory("Test");

        assertNotNull(books);
        assertFalse(books.isEmpty());
    }

    @Test
    @DisplayName("searchBooks should still work when Redis is down")
    void searchBooks_WhenRedisUnavailable_ShouldReturnFromDatabase() {
        List<Book> books = bookService.searchBooks("Redis");

        assertNotNull(books);
        assertFalse(books.isEmpty());
    }

    @Test
    @DisplayName("addBook should still work when Redis is down")
    void addBook_WhenRedisUnavailable_ShouldSucceed() {
        Book newBook = new Book();
        newBook.setTitle("New");
        newBook.setAuthor("Author");
        newBook.setPrice(new BigDecimal("5.99"));
        newBook.setCategory("Cat");
        newBook.setStock(3);

        Book saved = bookService.addBook(newBook);

        assertNotNull(saved);
        assertNotNull(saved.getId());
    }

    @Test
    @DisplayName("deleteBook should still work when Redis is down")
    void deleteBook_WhenRedisUnavailable_ShouldSucceed() {
        Book toDelete = new Book();
        toDelete.setTitle("ToDelete");
        toDelete.setAuthor("Author");
        toDelete.setPrice(new BigDecimal("1.99"));
        toDelete.setCategory("C");
        toDelete.setStock(1);
        Book saved = bookRepository.save(toDelete);

        assertDoesNotThrow(() -> bookService.deleteBook(saved.getId()));
        assertFalse(bookRepository.findById(saved.getId()).isPresent());
    }

    @Test
    @DisplayName("updateStock should still work when Redis is down")
    void updateStock_WhenRedisUnavailable_ShouldSucceed() {
        Book stockBook = new Book();
        stockBook.setTitle("StockUpdate");
        stockBook.setAuthor("Author");
        stockBook.setPrice(new BigDecimal("2.99"));
        stockBook.setCategory("C");
        stockBook.setStock(1);
        Book saved = bookRepository.save(stockBook);

        Book updated = bookService.updateStock(saved.getId(), 100);

        assertEquals(100, updated.getStock());
    }
}
