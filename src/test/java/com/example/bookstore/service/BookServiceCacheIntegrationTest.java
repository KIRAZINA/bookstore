package com.example.bookstore.service;

import com.example.bookstore.model.Book;
import com.example.bookstore.repository.BookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.cache.type=simple",
        "app.cache.enabled=true"
})
@EnableAutoConfiguration(exclude = DataRedisAutoConfiguration.class)
@ActiveProfiles("test")
@DisplayName("BookService Cache Integration Tests")
class BookServiceCacheIntegrationTest {

    @Autowired
    private BookService bookService;

    @Autowired
    private CacheManager cacheManager;

    @MockitoSpyBean
    private BookRepository bookRepository;

    private Book testBook;

    @BeforeEach
    void setUp() {
        cacheManager.getCacheNames().forEach(name ->
                Objects.requireNonNull(cacheManager.getCache(name)).clear());

        testBook = new Book();
        testBook.setTitle("Cache Test Book");
        testBook.setAuthor("Cache Author");
        testBook.setPrice(new BigDecimal("15.99"));
        testBook.setStock(20);
        testBook.setCategory("TestCategory");

        bookRepository.deleteAll();
    }

    @Nested
    @DisplayName("getAllBooks caching")
    class GetAllBooksCachingTests {

        @Test
        @DisplayName("First call should hit database (cache miss)")
        void getAllBooks_CacheMiss() {
            bookRepository.save(testBook);

            bookService.getAllBooks();

            verify(bookRepository, times(1)).findAll();
        }

        @Test
        @DisplayName("Subsequent calls should use cache (cache hit)")
        void getAllBooks_CacheHit() {
            bookRepository.save(testBook);

            bookService.getAllBooks();
            bookService.getAllBooks();
            bookService.getAllBooks();

            verify(bookRepository, times(1)).findAll();
        }

        @Test
        @DisplayName("After eviction, next call should hit database again")
        void getAllBooks_AfterEviction_CacheMiss() {
            bookRepository.save(testBook);

            bookService.getAllBooks();
            bookService.getAllBooks();

            Book newBook = new Book();
            newBook.setTitle("New");
            newBook.setAuthor("Author");
            newBook.setPrice(new BigDecimal("9.99"));
            newBook.setCategory("Cat");
            newBook.setStock(5);
            bookService.addBook(newBook);

            bookService.getAllBooks();

            verify(bookRepository, atLeast(2)).findAll();
        }
    }

    @Nested
    @DisplayName("getBooksByCategory caching")
    class GetBooksByCategoryCachingTests {

        @Test
        @DisplayName("Same category query should be cached")
        void getBooksByCategory_CacheHit() {
            bookRepository.save(testBook);

            bookService.getBooksByCategory("TestCategory");
            bookService.getBooksByCategory("TestCategory");

            verify(bookRepository, times(1)).findByCategory("TestCategory");
        }

        @Test
        @DisplayName("Different category queries should use separate cache entries")
        void getBooksByCategory_DifferentCategories() {
            bookRepository.save(testBook);

            bookService.getBooksByCategory("TestCategory");
            bookService.getBooksByCategory("OtherCategory");

            verify(bookRepository, times(1)).findByCategory("TestCategory");
            verify(bookRepository, times(1)).findByCategory("OtherCategory");
        }
    }

    @Nested
    @DisplayName("searchBooks caching")
    class SearchBooksCachingTests {

        @Test
        @DisplayName("Same search query should be cached")
        void searchBooks_CacheHit() {
            bookRepository.save(testBook);

            bookService.searchBooks("Cache");
            bookService.searchBooks("Cache");

            verify(bookRepository, times(1)).findByTitleOrAuthor("Cache");
        }

        @Test
        @DisplayName("Different search queries should use separate cache entries")
        void searchBooks_DifferentQueries() {
            bookRepository.save(testBook);

            bookService.searchBooks("Cache");
            bookService.searchBooks("Other");

            verify(bookRepository, times(1)).findByTitleOrAuthor("Cache");
            verify(bookRepository, times(1)).findByTitleOrAuthor("Other");
        }
    }

    @Nested
    @DisplayName("Cache invalidation on writes")
    class CacheInvalidationTests {

        @Test
        @DisplayName("Adding a book should evict books cache")
        void addBook_EvictsBooksCache() {
            bookRepository.save(testBook);

            bookService.getAllBooks();
            verify(bookRepository, times(1)).findAll();

            Book newBook = new Book();
            newBook.setTitle("New");
            newBook.setAuthor("Author");
            newBook.setPrice(new BigDecimal("9.99"));
            newBook.setCategory("Cat");
            newBook.setStock(5);
            bookService.addBook(newBook);

            bookService.getAllBooks();
            verify(bookRepository, times(2)).findAll();
        }

        @Test
        @DisplayName("Deleting a book should evict books and bookById caches")
        void deleteBook_EvictsCaches() {
            Book saved = bookRepository.save(testBook);

            bookService.getAllBooks();
            reset(bookRepository);

            bookService.deleteBook(saved.getId());

            bookService.getAllBooks();
            verify(bookRepository, times(1)).findAll();
        }

        @Test
        @DisplayName("Updating stock should evict books and bookById caches")
        void updateStock_EvictsCaches() {
            Book saved = bookRepository.save(testBook);

            bookService.getBooksByCategory("TestCategory");
            verify(bookRepository, times(1)).findByCategory("TestCategory");

            bookService.updateStock(saved.getId(), 99);

            bookService.getBooksByCategory("TestCategory");
            verify(bookRepository, times(2)).findByCategory("TestCategory");
        }
    }

    @Nested
    @DisplayName("Cache region isolation")
    class CacheRegionIsolationTests {

        @Test
        @DisplayName("Cache regions should be properly named")
        void cacheRegions_ShouldExist() {
            assertTrue(cacheManager.getCacheNames().contains("books"));
            assertTrue(cacheManager.getCacheNames().contains("bookById"));
        }

        @Test
        @DisplayName("getAllBooks should populate books cache")
        void getAllBooks_PopulatesBooksCache() {
            bookRepository.save(testBook);

            bookService.getAllBooks();

            var cached = cacheManager.getCache("books");
            assertNotNull(cached);
            var wrapper = cached.get("allBooks");
            assertNotNull(wrapper);
            Object cachedValue = wrapper.get();
            assertNotNull(cachedValue);
            @SuppressWarnings("unchecked")
            List<Book> cachedBooks = (List<Book>) cachedValue;
            assertFalse(cachedBooks.isEmpty());
        }
    }
}
