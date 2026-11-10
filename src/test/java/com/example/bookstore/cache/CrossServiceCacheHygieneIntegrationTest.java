package com.example.bookstore.cache;

import com.example.bookstore.base.AbstractIntegrationTest;
import com.example.bookstore.model.Book;
import com.example.bookstore.model.Inventory;
import com.example.bookstore.repository.BookRepository;
import com.example.bookstore.repository.InventoryHistoryRepository;
import com.example.bookstore.repository.InventoryRepository;
import com.example.bookstore.repository.OrderRepository;
import com.example.bookstore.repository.PaymentRepository;
import com.example.bookstore.service.BookService;
import com.example.bookstore.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-service cache hygiene evidence for the {@code inventoryAvailable} cache.
 *
 * <p>{@code InventoryService} owns the cache, but {@code BookService}, cart,
 * order and controller reads consume it indirectly. These tests verify that a
 * batch write in the owning service is visible to a cross-service reader and
 * that the reader still uses the cache rather than silently bypassing it.
 *
 * <p>Spring Data Redis 4 performs cache eviction through an asynchronous cache
 * writer, so invalidation is awaited with a bounded condition poll rather than
 * assumed to be visible synchronously.
 */
class CrossServiceCacheHygieneIntegrationTest extends AbstractIntegrationTest {

    private static final long CACHE_TIMEOUT_MILLIS = 2_000L;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private BookService bookService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryHistoryRepository inventoryHistoryRepository;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private CacheManager cacheManager;

    private Book book;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
        inventoryHistoryRepository.deleteAll();
        orderRepository.deleteAll();
        inventoryRepository.deleteAll();
        bookRepository.deleteAll();
        clearInventoryCaches();

        book = new Book();
        book.setTitle("Cross Service Cache Book");
        book.setAuthor("Author");
        book.setPrice(new BigDecimal("10.00"));
        book.setCategory("Test");
        book.setStock(10);
        book = bookRepository.save(book);

        inventoryRepository.saveAndFlush(new Inventory(book.getId(), 10));
    }

    private void clearInventoryCaches() {
        for (String cacheName : List.of("inventory", "inventoryAvailable")) {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
            }
        }
    }

    private void assertAvailabilityEventually(int expected) throws InterruptedException {
        assertTrue(awaitAvailabilityCacheEntry(false),
                "the mutation must evict the availability cache entry");
        assertEquals(expected, bookService.getAvailableQuantity(book.getId()),
                "a cross-service reader must observe the mutation, not a stale cache value");
    }

    private boolean awaitAvailabilityCacheEntry(boolean present) throws InterruptedException {
        Cache availabilityCache = cacheManager.getCache("inventoryAvailable");
        assertNotNull(availabilityCache, "inventoryAvailable cache must be configured");
        long deadline = System.currentTimeMillis() + CACHE_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if ((availabilityCache.get(book.getId()) != null) == present) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    @Test
    @Timeout(20)
    @DisplayName("Batch reserve is visible to a BookService availability read")
    void batchReserve_thenBookServiceRead_seesFreshState() throws InterruptedException {
        assertEquals(10, bookService.getAvailableQuantity(book.getId()),
                "book service should warm its view from the availability cache");

        inventoryService.reserveAll(5001L, Map.of(book.getId(), 3), "cross-service-reserve");

        assertAvailabilityEventually(7);
    }

    @Test
    @Timeout(20)
    @DisplayName("Batch release is visible to a BookService availability read")
    void batchRelease_thenBookServiceRead_seesFreshState() throws InterruptedException {
        inventoryService.reserveAll(5002L, Map.of(book.getId(), 4), "cross-service-setup");
        clearInventoryCaches();
        assertEquals(6, bookService.getAvailableQuantity(book.getId()),
                "book service should warm its view from the availability cache");

        inventoryService.releaseAllReservationsForOrder(
                Map.of(book.getId(), 4), 5002L, "cross-service-release", "test");

        assertAvailabilityEventually(10);
    }

    @Test
    @Timeout(20)
    @DisplayName("Batch restore is visible to a BookService availability read")
    void batchRestore_thenBookServiceRead_seesFreshState() throws InterruptedException {
        inventoryService.reserveAll(5003L, Map.of(book.getId(), 2), "cross-service-setup");
        inventoryService.finalizeAllReservationsForOrder(Map.of(book.getId(), 2), 5003L);
        clearInventoryCaches();
        assertEquals(8, bookService.getAvailableQuantity(book.getId()),
                "book service should warm its view from the availability cache");

        inventoryService.restoreAllStockForOrder(
                Map.of(book.getId(), 2), 5003L, "cross-service-restore", "test");

        assertEquals(10, inventoryRepository.findByBookId(book.getId()).orElseThrow().getAvailableQuantity(),
                "the batch restore must persist the restored availability");
        assertAvailabilityEventually(10);
    }

    @Test
    @Timeout(20)
    @DisplayName("Cross-service reads still use the cache, and a batch write invalidates it")
    void crossServiceRead_usesCache_andSeesFreshStateAfterBatchWrite() throws InterruptedException {
        clearInventoryCaches();
        assertEquals(10, bookService.getAvailableQuantity(book.getId()),
                "initial cross-service read should see the database value");
        assertTrue(awaitAvailabilityCacheEntry(true),
                "the cross-service read must populate the availability cache");

        // Simulate an out-of-band database change that performs no eviction. The
        // cached value must still be served, proving reads are not bypassing Redis.
        Inventory inventory = inventoryRepository.findByBookId(book.getId()).orElseThrow();
        inventory.adjustAvailable(5);
        inventoryRepository.saveAndFlush(inventory);

        assertEquals(10, bookService.getAvailableQuantity(book.getId()),
                "a second cross-service read must be served from the cache");

        inventoryService.reserveAll(5004L, Map.of(book.getId(), 3), "cross-service-invalidate");

        assertAvailabilityEventually(2);
    }
}
