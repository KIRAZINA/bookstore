package com.example.bookstore.chaos;

import com.example.bookstore.model.Book;
import com.example.bookstore.model.Inventory;
import com.example.bookstore.repository.BookRepository;
import com.example.bookstore.repository.InventoryRepository;
import com.example.bookstore.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
@ActiveProfiles("test")
@DisplayName("Inventory chaos tests — concurrency, overselling, consistency")
class InventoryChaosTest {

    private static final int STOCK = 10;
    private static final int THREADS = 20;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private BookRepository bookRepository;

    private Book testBook;

    @BeforeEach
    void setUp() {
        inventoryRepository.deleteAll();
        bookRepository.deleteAll();

        testBook = new Book();
        testBook.setTitle("Inventory Chaos Book");
        testBook.setAuthor("Author");
        testBook.setPrice(new BigDecimal("19.99"));
        testBook.setCategory("Testing");
        testBook.setStock(STOCK);
        testBook = bookRepository.save(testBook);

        inventoryRepository.save(new Inventory(testBook.getId(), STOCK));
    }

    @Test
    @DisplayName("Should prevent overselling under high concurrent load")
    void shouldPreventOversellingUnderConcurrency() throws InterruptedException {
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREADS);

        try (ExecutorService executor = Executors.newFixedThreadPool(THREADS)) {
            for (int i = 0; i < THREADS; i++) {
                final long orderId = i + 1;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        inventoryService.reserve(testBook.getId(), 1, orderId, "user-" + orderId);
                        successes.incrementAndGet();
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }
            startLatch.countDown();
            doneLatch.await();
        }

        Inventory finalInventory = inventoryRepository.findByBookId(testBook.getId()).orElseThrow();

        assertEquals(STOCK, successes.get());
        assertEquals(THREADS - STOCK, failures.get());
        assertEquals(0, finalInventory.getAvailableQuantity());
        assertEquals(STOCK, finalInventory.getReservedQuantity());
    }
}
