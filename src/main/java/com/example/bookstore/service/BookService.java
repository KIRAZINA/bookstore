package com.example.bookstore.service;

import com.example.bookstore.model.Book;
import com.example.bookstore.repository.BookRepository;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;

@Service
public class BookService {
    private final BookRepository bookRepository;
    private final CacheManager cacheManager;
    private final StringRedisTemplate redisTemplate;
    private final InventoryService inventoryService;
    private final InventoryAdminService inventoryAdminService;

    private static final String BOOKS_PREFIX = "books::";
    private static final String BOOK_BY_ID_PREFIX = "bookById::";

    public BookService(BookRepository bookRepository,
                       CacheManager cacheManager,
                       ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                       InventoryService inventoryService,
                       InventoryAdminService inventoryAdminService) {
        this.bookRepository = bookRepository;
        this.cacheManager = cacheManager;
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.inventoryService = inventoryService;
        this.inventoryAdminService = inventoryAdminService;
    }

    @Transactional
    public Book addBook(Book book) {
        Book saved = bookRepository.save(book);
        inventoryAdminService.initializeInventory(saved.getId(), 0);
        evictBooksCache();
        return saved;
    }

    @Cacheable(cacheNames = "books", key = "'allBooks'")
    public List<Book> getAllBooks() {
        return bookRepository.findAll();
    }

    @Cacheable(cacheNames = "books", key = "'category-' + #category")
    public List<Book> getBooksByCategory(String category) {
        return bookRepository.findByCategory(category);
    }

    @Cacheable(cacheNames = "books", key = "'search-' + #search")
    public List<Book> searchBooks(String search) {
        return bookRepository.findByTitleOrAuthor(search);
    }

    @Transactional
    public void deleteBook(Long id) {
        bookRepository.deleteById(id);
        evictBooksCache();
        evictBookByIdCache();
    }

    @Transactional
    public Book updateStock(Long bookId, int stock) {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("Book not found"));
        inventoryAdminService.adjustInventory(bookId, stock,
                "Admin stock update via BookService.updateStock");
        Book saved = bookRepository.findById(bookId).orElseThrow();
        evictBooksCache();
        evictBookByIdCache();
        return saved;
    }

    public int getAvailableQuantity(Long bookId) {
        return inventoryService.getAvailableQuantity(bookId);
    }

    private void evictBooksCache() {
        if (redisTemplate != null) {
            Set<String> keys = redisTemplate.keys(BOOKS_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } else {
            Objects.requireNonNull(cacheManager.getCache("books")).clear();
        }
    }

    private void evictBookByIdCache() {
        if (redisTemplate != null) {
            Set<String> keys = redisTemplate.keys(BOOK_BY_ID_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } else {
            Objects.requireNonNull(cacheManager.getCache("bookById")).clear();
        }
    }
}
