package com.example.bookstore.performance.database;

import com.example.bookstore.base.AbstractPerformanceTest;
import com.example.bookstore.model.Book;
import com.example.bookstore.performance.LatencyStatistics;
import com.example.bookstore.repository.BookRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Section 9: Database connection pool and query performance characteristics.
 */
@Tag("performance")
class DatabasePerformanceTest extends AbstractPerformanceTest {

    private static final int BATCH_SIZE = Integer.getInteger("perf.db.batch", 100);

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        bookRepository.deleteAll();
    }

    @Test
    @DisplayName("Section 9: Connection pool utilization under sustained reads")
    void connectionPoolUnderSustainedReads() {
        seedBooks(BATCH_SIZE);
        LatencyStatistics latencies = new LatencyStatistics(BATCH_SIZE);

        for (int i = 0; i < BATCH_SIZE; i++) {
            long start = System.nanoTime();
            bookRepository.findAll();
            latencies.recordNanos(System.nanoTime() - start);
        }

        logPoolState("after sustained reads");
        latencies.snapshot("Book findAll (JPA)").log();
    }

    @Test
    @DisplayName("Section 9: Batch insert and indexed lookup performance")
    void batchInsertAndLookup() {
        List<Book> books = new ArrayList<>();
        for (int i = 0; i < BATCH_SIZE; i++) {
            Book book = new Book();
            book.setTitle("DB Perf Book " + i);
            book.setAuthor("Author " + i);
            book.setPrice(new BigDecimal("12.50"));
            book.setCategory(i % 2 == 0 ? "Fiction" : "SciFi");
            book.setStock(10);
            books.add(book);
        }

        long insertStart = System.nanoTime();
        bookRepository.saveAll(books);
        double insertMs = (System.nanoTime() - insertStart) / 1_000_000.0;

        LatencyStatistics lookupStats = new LatencyStatistics(50);
        for (int i = 0; i < 50; i++) {
            long start = System.nanoTime();
            jdbcTemplate.queryForList(
                    "SELECT id, title FROM book WHERE category = ? LIMIT 20",
                    "Fiction"
            );
            lookupStats.recordNanos(System.nanoTime() - start);
        }

        System.out.printf("%n=== Database Batch ===%n  batch insert %d rows=%.2fms%n", BATCH_SIZE, insertMs);
        lookupStats.snapshot("Indexed category lookup").log();
        logPoolState("after batch operations");

        assertTrue(insertMs > 0);
    }

    private void seedBooks(int count) {
        List<Book> books = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Book book = new Book();
            book.setTitle("Seed " + i);
            book.setAuthor("Author");
            book.setPrice(new BigDecimal("9.99"));
            book.setCategory("Seed");
            book.setStock(5);
            books.add(book);
        }
        bookRepository.saveAll(books);
    }

    private void logPoolState(String phase) {
        if (dataSource instanceof HikariDataSource hikari) {
            var pool = hikari.getHikariPoolMXBean();
            System.out.printf("--- HikariCP (%s) --- active=%d idle=%d total=%d awaiting=%d%n",
                    phase, pool.getActiveConnections(), pool.getIdleConnections(),
                    pool.getTotalConnections(), pool.getThreadsAwaitingConnection());
        }
    }
}
