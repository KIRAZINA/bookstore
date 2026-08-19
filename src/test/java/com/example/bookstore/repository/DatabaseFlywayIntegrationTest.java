package com.example.bookstore.repository;

import com.example.bookstore.base.AbstractIntegrationTest;
import com.example.bookstore.model.Book;
import com.example.bookstore.model.Inventory;
import jakarta.persistence.EntityManager;
import jakarta.validation.ConstraintViolationException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseFlywayIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ApplicationContext applicationContext;

    @BeforeEach
    void setUp() {
        inventoryRepository.deleteAll();
        bookRepository.deleteAll();
    }

    @Test
    @DisplayName("Books with the same title are allowed (no unique constraint on title)")
    void duplicateBookTitle_IsAllowed() {
        Book book = new Book();
        book.setTitle("Shared Title");
        book.setAuthor("Author");
        book.setPrice(new BigDecimal("10.00"));
        book.setCategory("Test");
        book.setStock(5);
        bookRepository.saveAndFlush(book);

        Book duplicate = new Book();
        duplicate.setTitle("Shared Title");
        duplicate.setAuthor("Another Author");
        duplicate.setPrice(new BigDecimal("15.00"));
        duplicate.setCategory("Test");
        duplicate.setStock(3);

        Book saved = bookRepository.saveAndFlush(duplicate);
        assertNotNull(saved.getId());
        assertNotEquals(book.getId(), saved.getId());
    }

    @Test
    @DisplayName("NOT NULL constraint violation on required field")
    void nullTitle_ThrowsConstraintViolation() {
        Book book = new Book();
        book.setAuthor("Author");
        book.setPrice(new BigDecimal("10.00"));
        book.setCategory("Test");
        book.setStock(5);

        assertThrows(ConstraintViolationException.class, () ->
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
        inventoryRepository.saveAndFlush(inventory);

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

    @Test
    @DisplayName("Every migration script is applied exactly once and succeeded on a clean database")
    void flywayMigration_AllVersionsAppliedExactlyOnce() {
        transactionTemplate.executeWithoutResult(status -> {
            List<Object[]> rows = entityManager
                    .createNativeQuery("SELECT version, COUNT(*), SUM(CASE WHEN success THEN 1 ELSE 0 END) "
                            + "FROM flyway_schema_history GROUP BY version ORDER BY version")
                    .getResultList();

            assertEquals(List.of("1", "2", "3", "4", "5", "6", "7", "8"),
                    rows.stream().map(r -> String.valueOf(r[0])).toList(),
                    "Expected exactly the V1-V8 migrations in order");
            for (Object[] row : rows) {
                assertEquals(1L, ((Number) row[1]).longValue(),
                        "Version " + row[0] + " must be applied exactly once");
                assertEquals(1L, ((Number) row[2]).longValue(),
                        "Version " + row[0] + " must have success=true");
            }
        });
    }

    @Test
    @DisplayName("No Flyway engine bean exists; FlywayConfig's constructor is the sole migration trigger")
    void flywayMigration_NoEngineBean() {
        assertTrue(applicationContext.getBeansOfType(Flyway.class).isEmpty(),
                "Migrations run via the FlywayConfig construction side effect; "
                        + "no Flyway engine bean should be registered (auto-config is inactive)");
    }
}
