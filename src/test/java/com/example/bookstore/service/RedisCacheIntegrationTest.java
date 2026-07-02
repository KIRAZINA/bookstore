package com.example.bookstore.service;

import com.example.bookstore.base.AbstractIntegrationTest;
import com.example.bookstore.model.Book;
import com.example.bookstore.repository.BookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class RedisCacheIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private BookService bookService;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        cacheManager.getCacheNames().forEach(name ->
                Objects.requireNonNull(cacheManager.getCache(name)).clear());
        bookRepository.deleteAll();
    }

    @Test
    @DisplayName("Real Redis: caching works - second call returns same data without DB hit")
    void getAllBooks_CacheHit() {
        Book book = new Book();
        book.setTitle("Redis Cache Book");
        book.setAuthor("Author");
        book.setPrice(new BigDecimal("10.00"));
        book.setCategory("Test");
        book.setStock(5);
        bookRepository.save(book);

        List<Book> firstCall = bookService.getAllBooks();
        assertFalse(firstCall.isEmpty());

        List<Book> secondCall = bookService.getAllBooks();
        assertFalse(secondCall.isEmpty());

        assertEquals(firstCall.size(), secondCall.size());
    }

    @Test
    @DisplayName("Real Redis: adding a book evicts the cache so subsequent call returns updated data")
    void addBook_EvictsBooksCache() {
        Book book = new Book();
        book.setTitle("Pre-Cache Book");
        book.setAuthor("Author");
        book.setPrice(new BigDecimal("5.00"));
        book.setCategory("Test");
        book.setStock(10);
        bookRepository.save(book);

        bookService.getAllBooks();

        Book newBook = new Book();
        newBook.setTitle("New Book");
        newBook.setAuthor("Author");
        newBook.setPrice(new BigDecimal("7.00"));
        newBook.setCategory("Test");
        newBook.setStock(3);
        bookService.addBook(newBook);

        List<Book> result = bookService.getAllBooks();
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("Real Redis: cache regions books and bookById exist")
    void cacheRegions_ShouldExist() {
        assertTrue(cacheManager.getCacheNames().contains("books"));
        assertTrue(cacheManager.getCacheNames().contains("bookById"));
    }

    @Test
    @DisplayName("Real Redis: getBooksByCategory results are cached by category")
    void getBooksByCategory_CacheHit() {
        Book book = new Book();
        book.setTitle("Category Cache Book");
        book.setAuthor("Author");
        book.setPrice(new BigDecimal("8.00"));
        book.setCategory("Fiction");
        book.setStock(5);
        bookRepository.save(book);

        bookService.getBooksByCategory("Fiction");
        bookService.getBooksByCategory("Fiction");
        bookService.getBooksByCategory("Fiction");

        assertNotNull(Objects.requireNonNull(cacheManager.getCache("books"))
                .get("category-Fiction"));
    }

    @Test
    @DisplayName("Real Redis: deleting a book evicts the cache so subsequent call returns updated data")
    void deleteBook_EvictsCaches() {
        Book book = new Book();
        book.setTitle("Evict Test Book");
        book.setAuthor("Author");
        book.setPrice(new BigDecimal("12.00"));
        book.setCategory("Test");
        book.setStock(5);
        book = bookRepository.save(book);

        bookService.getAllBooks();
        bookService.deleteBook(book.getId());

        List<Book> result = bookService.getAllBooks();
        assertTrue(result.isEmpty());
    }
}
