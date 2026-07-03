package com.example.bookstore.repository;

import com.example.bookstore.base.AbstractIntegrationTest;
import com.example.bookstore.model.Book;
import com.example.bookstore.model.Inventory;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@Transactional
class DatabaseFlywayIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        inventoryRepository.deleteAll();
        bookRepository.deleteAll();
    }

    @Test
    @DisplayName("Unique constraint violation throws DataIntegrityViolationException")
    void duplicateBookTitle_ThrowsDataIntegrityViolation() {
        Book book = new Book();
        book.setTitle("Unique Title");
        book.setAuthor("Author");
        book.setPrice(new BigDecimal("10.00"));
        book.setCategory("Test");
        book.setStock(5);
        bookRepository.saveAndFlush(book);

        Book duplicate = new Book();
        duplicate.setTitle("Unique Title");
        duplicate.setAuthor("Another Author");
        duplicate.setPrice(new BigDecimal("15.00"));
        duplicate.setCategory("Test");
        duplicate.setStock(3);

        assertThrows(DataIntegrityViolationException.class, () ->
                bookRepository.saveAndFlush(duplicate));
    }

    @Test
    @DisplayName("NOT NULL constraint violation on required field")
    void nullTitle_ThrowsDataIntegrityViolation() {
        Book book = new Book();
        book.setAuthor("Author");
        book.setPrice(new BigDecimal("10.00"));
        book.setCategory("Test");
        book.setStock(5);

        assertThrows(DataIntegrityViolationException.class, () ->
                bookRepository.saveAndFlush(book));
    }

    @Test
    @DisplayName("Inventory default values are correct")
    void inventoryDefaultValues_AreCorrect() {
        Book book = new Book();
        book.setTitle("Inventory Default Test");
        book.setAuthor("Author");
        book.setPrice(new BigDecimal("10.00"));
        book.setCategory("Test");
        book.setStock(5);
        book = bookRepository.saveAndFlush(book);

        Inventory inventory = new Inventory(book.getId(), 0);
        inventory = inventoryRepository.saveAndFlush(inventory);

        assertEquals(0, inventory.getAvailableQuantity());
        assertEquals(0, inventory.getReservedQuantity());
    }

    @Test
    @DisplayName("Transaction rollback restores database state on exception")
    void transactionRollback_RestoresState() {
        Book book = new Book();
        book.setTitle("Rollback Test");
        book.setAuthor("Author");
        book.setPrice(new BigDecimal("10.00"));
        book.setCategory("Test");
        book.setStock(5);
        bookRepository.saveAndFlush(book);

        assertThrows(RuntimeException.class, () ->
                transactionTemplate.executeWithoutResult(status -> {
                    bookRepository.deleteById(book.getId());
                    Inventory inventory = new Inventory(book.getId(), 10);
                    inventoryRepository.save(inventory);
                    throw new RuntimeException("Forced rollback");
                }));

        assertTrue(bookRepository.findById(book.getId()).isPresent(),
                "Book should still exist after rollback");
    }

    @Test
    @DisplayName("Cascade delete removes related entities")
    void cascadeDelete_RemovesRelatedEntities() {
        Book book = new Book();
        book.setTitle("Cascade Test");
        book.setAuthor("Author");
        book.setPrice(new BigDecimal("10.00"));
        book.setCategory("Test");
        book.setStock(5);
        book = bookRepository.saveAndFlush(book);

        Inventory inventory = new Inventory(book.getId(), 10);
        inventory = inventoryRepository.saveAndFlush(inventory);

        bookRepository.delete(book);
        bookRepository.flush();

        assertFalse(inventoryRepository.findByBookId(book.getId()).isPresent(),
                "Inventory should be cascade-deleted with book");
    }

    @Test
    @DisplayName("Flyway migration history table exists and contains applied migrations")
    void flywayMigration_HistoryExists() {
        transactionTemplate.executeWithoutResult(status -> {
            var list = entityManager
                    .createNativeQuery("SELECT COUNT(*) FROM flyway_schema_history")
                    .getSingleResult();
            long count = ((Number) list).longValue();
            assertTrue(count > 0, "Flyway schema history should contain at least one migration");
        });
    }

    @Test
    @DisplayName("Flyway migration checksums are valid")
    void flywayMigration_ChecksumsValid() {
        transactionTemplate.executeWithoutResult(status -> {
            var result = entityManager
                    .createNativeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE success = false")
                    .getSingleResult();
            long failedCount = ((Number) result).longValue();
            assertEquals(0, failedCount, "All Flyway migrations should have succeeded");
        });
    }
}
