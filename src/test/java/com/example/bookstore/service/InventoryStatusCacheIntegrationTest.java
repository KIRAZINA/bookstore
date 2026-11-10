package com.example.bookstore.service;

import com.example.bookstore.base.AbstractIntegrationTest;
import com.example.bookstore.model.Book;
import com.example.bookstore.model.Inventory;
import com.example.bookstore.model.InventoryStatus;
import com.example.bookstore.repository.BookRepository;
import com.example.bookstore.repository.InventoryHistoryRepository;
import com.example.bookstore.repository.InventoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Evidence that {@link InventoryService#getInventoryStatus(Long)} routes through
 * the same {@code inventoryAvailable} cache as every other availability reader.
 *
 * <p>Static analysis marks this method as a self-invocation cache bypass: it calls
 * {@code getAvailableQuantity} on {@code this}, so the {@code @Cacheable} advice
 * never runs. These tests warm the cache through the public availability read and
 * then prove that {@code getInventoryStatus} honours it, while a real eviction
 * still exposes fresh data.
 *
 * <p>Spring Data Redis 4 performs cache eviction through an asynchronous cache
 * writer, so cache-state changes are awaited with a bounded condition poll rather
 * than assumed to be visible synchronously.
 */
class InventoryStatusCacheIntegrationTest extends AbstractIntegrationTest {

    private static final long CACHE_TIMEOUT_MILLIS = 2_000L;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryHistoryRepository inventoryHistoryRepository;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private CacheManager cacheManager;

    private Book book;

    @BeforeEach
    void setUp() {
        inventoryHistoryRepository.deleteAll();
        inventoryRepository.deleteAll();
        bookRepository.deleteAll();
        clearInventoryCaches();

        book = new Book();
        book.setTitle("Inventory Status Cache Book");
        book.setAuthor("Author");
        book.setPrice(new BigDecimal("12.00"));
        book.setCategory("Test");
        book.setStock(20);
        book = bookRepository.save(book);

        inventoryRepository.saveAndFlush(new Inventory(book.getId(), 20));
    }

    private void clearInventoryCaches() {
        Cache inventoryCache = cacheManager.getCache("inventory");
        if (inventoryCache != null) {
            inventoryCache.clear();
        }
        Cache availabilityCache = cacheManager.getCache("inventoryAvailable");
        if (availabilityCache != null) {
            availabilityCache.clear();
        }
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
    @DisplayName("getInventoryStatus reads through a warm availability cache")
    void getInventoryStatus_readsThroughWarmAvailabilityCache() throws InterruptedException {
        assertEquals(20, inventoryService.getAvailableQuantity(book.getId()),
                "the public availability read must warm the cache");
        assertTrue(awaitAvailabilityCacheEntry(true),
                "the warm availability read must become visible in the cache");

        // Out-of-band database change without eviction: the cache must still win.
        Inventory inventory = inventoryRepository.findByBookId(book.getId()).orElseThrow();
        inventory.adjustAvailable(0);
        inventoryRepository.saveAndFlush(inventory);

        assertEquals(InventoryStatus.AVAILABLE, inventoryService.getInventoryStatus(book.getId()),
                "getInventoryStatus must read the warm availability cache instead of bypassing it");
    }

    @Test
    @Timeout(20)
    @DisplayName("getInventoryStatus reflects a real mutation after cache eviction")
    void getInventoryStatus_reflectsFreshValue_afterEviction() throws InterruptedException {
        assertEquals(20, inventoryService.getAvailableQuantity(book.getId()),
                "the public availability read must warm the cache");
        assertTrue(awaitAvailabilityCacheEntry(true),
                "the warm availability read must become visible in the cache");
        assertEquals(InventoryStatus.AVAILABLE, inventoryService.getInventoryStatus(book.getId()));

        inventoryService.reserve(book.getId(), 20, 6001L, "status-eviction-test");

        assertTrue(awaitAvailabilityCacheEntry(false),
                "the single-item mutation must evict the availability cache entry");
        assertEquals(InventoryStatus.OUT_OF_STOCK, inventoryService.getInventoryStatus(book.getId()),
                "after eviction the status read must observe the reserved availability");
    }
}
