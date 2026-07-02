package com.example.bookstore.repository;

import com.example.bookstore.base.AbstractIntegrationTest;
import com.example.bookstore.model.Book;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@Transactional
class BookRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private BookRepository bookRepository;

    private Book savedBook;

    @BeforeEach
    void setUp() {
        bookRepository.deleteAll();
        Book book = new Book();
        book.setTitle("Test Title");
        book.setAuthor("Test Author");
        book.setPrice(new BigDecimal("15.99"));
        book.setCategory("Fiction");
        book.setStock(10);
        savedBook = bookRepository.save(book);
    }

    @Test
    @DisplayName("Should find books by category")
    void findByCategory_ReturnsMatchingBooks() {
        Book another = new Book();
        another.setTitle("Another");
        another.setAuthor("Author");
        another.setPrice(new BigDecimal("9.99"));
        another.setCategory("Fiction");
        another.setStock(5);
        bookRepository.save(another);

        Book different = new Book();
        different.setTitle("Non-Fiction");
        different.setAuthor("Author");
        different.setPrice(new BigDecimal("12.99"));
        different.setCategory("Science");
        different.setStock(3);
        bookRepository.save(different);

        List<Book> fictionBooks = bookRepository.findByCategory("Fiction");

        assertEquals(2, fictionBooks.size());
        assertTrue(fictionBooks.stream().allMatch(b -> "Fiction".equals(b.getCategory())));
    }

    @Test
    @DisplayName("Should find books by title or author search")
    void findByTitleOrAuthor_ReturnsMatchingBooks() {
        Book another = new Book();
        another.setTitle("Java Programming");
        another.setAuthor("John Doe");
        another.setPrice(new BigDecimal("29.99"));
        another.setCategory("Tech");
        another.setStock(5);
        bookRepository.save(another);

        List<Book> titleResults = bookRepository.findByTitleOrAuthor("Java");
        assertEquals(1, titleResults.size());
        assertEquals("Java Programming", titleResults.getFirst().getTitle());

        List<Book> authorResults = bookRepository.findByTitleOrAuthor("Doe");
        assertEquals(1, authorResults.size());
        assertEquals("John Doe", authorResults.getFirst().getAuthor());
    }

    @Test
    @DisplayName("Should return empty list when no books match search")
    void findByTitleOrAuthor_NoMatch_ReturnsEmpty() {
        List<Book> results = bookRepository.findByTitleOrAuthor("NonExistentXYZ");
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("Should save and retrieve book with all fields")
    void saveAndFindById_AllFieldsPreserved() {
        Optional<Book> found = bookRepository.findById(savedBook.getId());

        assertTrue(found.isPresent());
        assertEquals("Test Title", found.get().getTitle());
        assertEquals("Test Author", found.get().getAuthor());
        assertEquals(0, new BigDecimal("15.99").compareTo(found.get().getPrice()));
        assertEquals("Fiction", found.get().getCategory());
        assertEquals(10, found.get().getStock());
        assertNotNull(found.get().getVersion());
    }

    @Test
    @DisplayName("Should delete book and not find it")
    void deleteBook_RemovesFromDatabase() {
        bookRepository.deleteById(savedBook.getId());

        assertFalse(bookRepository.findById(savedBook.getId()).isPresent());
    }
}
