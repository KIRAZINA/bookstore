package com.example.bookstore.service;

import com.example.bookstore.model.Book;
import com.example.bookstore.repository.BookRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;

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
    private static final Logger log = LoggerFactory.getLogger(BookService.class);

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

    @Cacheable(cacheNames = "bookById", key = "#id")
    public Book getBookById(Long id) {
        return bookRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Book not found: " + id));
    }

    @Cacheable(cacheNames = "books", key = "T(com.example.bookstore.service.BookService).searchCacheKey(#search)")
    public List<Book> searchBooks(String search) {
        if (search == null) search = "";
        if (search.trim().length() > 128) {
            throw new IllegalArgumentException("Search term too long");
        }
        return bookRepository.findByTitleOrAuthor(search.trim());
    }

    public List<Book> getAllBooks(int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, size), Sort.by("id").ascending());
        return bookRepository.findAll(pageable).getContent();
    }

    public List<Book> getBooksByCategory(String category, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, size), Sort.by("id").ascending());
        return bookRepository.findByCategory(category, pageable);
    }

    public List<Book> searchBooks(String search, int page, int size) {
        if (search == null) search = "";
        String normalized = search.trim();
        if (normalized.length() > 128) {
            throw new IllegalArgumentException("Search term too long");
        }
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, size), Sort.by("id").ascending());
        return bookRepository.findByTitleOrAuthor(normalized, pageable);
    }

    public static String searchCacheKey(String search) {
        String normalized = search == null ? "" : search.trim();
        if (normalized.length() > 128) {
            throw new IllegalArgumentException("Search term too long");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash search key", e);
        }
    }

    @Transactional
    public void deleteBook(Long id) {
        bookRepository.deleteById(id);
        evictBooksCache();
        evictBookByIdCache();
    }

    @Transactional
    public Book updateStock(Long bookId, int stock) {
        if (stock < 0) {
            throw new IllegalArgumentException("Stock cannot be negative");
        }
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("Book not found"));
        // The inventory table is the single source of truth for stock; Book.stock is @Deprecated
        // and must not be written as authoritative (see V6 migration comment).
        inventoryAdminService.adjustInventory(bookId, stock,
                "Admin stock update via BookService.updateStock");
        evictBooksCache();
        evictBookByIdCache();
        return book;
    }

    public int getAvailableQuantity(Long bookId) {
        return inventoryService.getAvailableQuantity(bookId);
    }

    private void evictBooksCache() {
        try {
            if (redisTemplate != null) {
                ScanOptions options = ScanOptions.scanOptions().match(BOOKS_PREFIX + "*").count(100).build();
                try (Cursor<String> cursor = redisTemplate.scan(options)) {
                    List<String> batch = new ArrayList<>();
                    while (cursor.hasNext()) {
                        batch.add(cursor.next());
                        if (batch.size() >= 100) {
                            redisTemplate.delete(batch);
                            batch.clear();
                        }
                    }
                    if (!batch.isEmpty()) {
                        redisTemplate.delete(batch);
                    }
                }
            } else {
                Objects.requireNonNull(cacheManager.getCache("books")).clear();
            }
        } catch (RuntimeException e) {
            log.warn("Failed to evict books cache: {}", e.getMessage());
        }
    }

    private void evictBookByIdCache() {
        try {
            if (redisTemplate != null) {
                ScanOptions options = ScanOptions.scanOptions().match(BOOK_BY_ID_PREFIX + "*").count(100).build();
                try (Cursor<String> cursor = redisTemplate.scan(options)) {
                    List<String> batch = new ArrayList<>();
                    while (cursor.hasNext()) {
                        batch.add(cursor.next());
                        if (batch.size() >= 100) {
                            redisTemplate.delete(batch);
                            batch.clear();
                        }
                    }
                    if (!batch.isEmpty()) {
                        redisTemplate.delete(batch);
                    }
                }
            } else {
                Objects.requireNonNull(cacheManager.getCache("bookById")).clear();
            }
        } catch (RuntimeException e) {
            log.warn("Failed to evict bookById cache: {}", e.getMessage());
        }
    }
}
