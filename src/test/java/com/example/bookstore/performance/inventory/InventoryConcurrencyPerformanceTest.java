package com.example.bookstore.performance.inventory;

import com.example.bookstore.base.AbstractPerformanceTest;
import com.example.bookstore.model.Book;
import com.example.bookstore.model.Inventory;
import com.example.bookstore.model.UserRole;
import com.example.bookstore.performance.LatencyStatistics;
import com.example.bookstore.repository.BookRepository;
import com.example.bookstore.repository.InventoryRepository;
import com.example.bookstore.repository.OrderRepository;
import com.example.bookstore.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Section 6: Inventory concurrency performance under high contention.
 */
@Tag("performance")
class InventoryConcurrencyPerformanceTest extends AbstractPerformanceTest {

    private static final int STOCK = 100;
    private static final int THREAD_COUNT = 200;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private OrderRepository orderRepository;

    private Book book;

    @BeforeEach
    void setUp() {
        inventoryRepository.deleteAll();
        orderRepository.deleteAll();
        bookRepository.deleteAll();

        var user = createTestUser("inv-perf-" + System.nanoTime(), UserRole.ROLE_USER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));

        book = new Book();
        book.setTitle("Contention Book");
        book.setAuthor("Author");
        book.setPrice(new BigDecimal("15.00"));
        book.setCategory("Performance");
        book.setStock(STOCK);
        book = bookRepository.save(book);

        inventoryRepository.save(new Inventory(book.getId(), STOCK));
    }

    @Test
    @DisplayName("Section 6: Concurrent reservations — throughput and overselling prevention")
    void concurrentReservationsThroughputAndCorrectness() throws InterruptedException {
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger retries = new AtomicInteger();
        LatencyStatistics latencies = new LatencyStatistics(THREAD_COUNT);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREAD_COUNT);

        try (ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT)) {
            for (int i = 0; i < THREAD_COUNT; i++) {
                final long orderId = i + 1;
                executor.submit(() -> {
                    try {
                        startGate.await();
                        long start = System.nanoTime();
                        int attempts = 0;
                        while (attempts < 3) {
                            attempts++;
                            try {
                                inventoryService.reserve(book.getId(), 1, orderId, "perf-user");
                                successes.incrementAndGet();
                                latencies.recordNanos(System.nanoTime() - start);
                                return;
                            } catch (Exception e) {
                                retries.incrementAndGet();
                            }
                        }
                        failures.incrementAndGet();
                        latencies.recordNanos(System.nanoTime() - start);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            long wallStart = System.nanoTime();
            startGate.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS));
            long wallNanos = System.nanoTime() - wallStart;

            Inventory finalState = inventoryRepository.findByBookId(book.getId()).orElseThrow();
            assertEquals(0, finalState.getAvailableQuantity());
            assertEquals(STOCK, finalState.getReservedQuantity());
            assertEquals(STOCK, successes.get());
            assertEquals(THREAD_COUNT - STOCK, failures.get());

            double throughput = successes.get() / (wallNanos / 1_000_000_000.0);
            System.out.printf("%n=== Inventory Concurrency (%d threads, %d stock) ===%n", THREAD_COUNT, STOCK);
            System.out.printf("  successes=%d  failures=%d  retries=%d%n", successes.get(), failures.get(), retries.get());
            System.out.printf("  reservation throughput=%.2f ops/s%n", throughput);
            latencies.snapshot("Inventory reservation (contended)").log();
        }
    }
}
