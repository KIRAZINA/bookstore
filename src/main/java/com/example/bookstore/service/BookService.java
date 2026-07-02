package com.example.bookstore.service;

import com.example.bookstore.model.Book;
import com.example.bookstore.repository.BookRepository;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;

@Service
public class BookService {
    private final BookRepository bookRepository;
    private final CacheManager cacheManager;
    private final StringRedisTemplate redisTemplate;

    private static final String BOOKS_PREFIX = "books::";
    private static final String BOOK_BY_ID_PREFIX = "bookById::";

    public BookService(BookRepository bookRepository, CacheManager cacheManager, ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.bookRepository = bookRepository;
        this.cacheManager = cacheManager;
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
    }

    public Book addBook(Book book) {
        Book saved = bookRepository.save(book);
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

    public void deleteBook(Long id) {
        bookRepository.deleteById(id);
        evictBooksCache();
        evictBookByIdCache();
    }

    public Book updateStock(Long bookId, int stock) {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("Book not found"));
        if (stock < 0) {
            throw new IllegalArgumentException("Stock cannot be negative");
        }
        book.setStock(stock);
        Book saved = bookRepository.save(book);
        evictBooksCache();
        evictBookByIdCache();
        return saved;
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
