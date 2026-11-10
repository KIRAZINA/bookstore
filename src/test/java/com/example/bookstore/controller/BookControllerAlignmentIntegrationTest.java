package com.example.bookstore.controller;

import com.example.bookstore.base.AbstractControllerTest;
import com.example.bookstore.model.Book;
import com.example.bookstore.model.Inventory;
import com.example.bookstore.repository.BookRepository;
import com.example.bookstore.repository.InventoryHistoryRepository;
import com.example.bookstore.repository.InventoryRepository;
import com.example.bookstore.repository.OrderRepository;
import com.example.bookstore.repository.PaymentRepository;
import com.example.bookstore.service.InventoryService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Evidence that the book list endpoint and the single-book endpoint use the
 * same availability read path and therefore return identical DTO values.
 *
 * <p>The list endpoint used to read {@code inventoryRepository.findByBookIdIn}
 * directly while the single-book endpoint used the cached
 * {@code InventoryService.getAvailableQuantity}. Whenever the cache was not
 * perfectly in sync, the two endpoints disagreed.
 */
class BookControllerAlignmentIntegrationTest extends AbstractControllerTest {

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryHistoryRepository inventoryHistoryRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private CacheManager cacheManager;

    private Book book;
    private String userToken;

    @BeforeEach
    void setUp() throws Exception {
        paymentRepository.deleteAll();
        inventoryHistoryRepository.deleteAll();
        orderRepository.deleteAll();
        inventoryRepository.deleteAll();
        bookRepository.deleteAll();
        clearCaches();

        book = new Book();
        book.setTitle("Alignment Test Book");
        book.setAuthor("Alignment Author");
        book.setPrice(new BigDecimal("14.99"));
        book.setCategory("Alignment");
        book.setStock(10);
        book = bookRepository.save(book);

        inventoryRepository.saveAndFlush(new Inventory(book.getId(), 10));
        userToken = registerUserAndGetToken("alignment-user-" + System.nanoTime());
    }

    private void clearCaches() {
        for (String cacheName : List.of("books", "bookById", "inventory", "inventoryAvailable")) {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
            }
        }
    }

    /**
     * Waits for the availability cache entry to appear/disappear. Spring Data
     * Redis 4 evicts asynchronously, so the test synchronises with that writer
     * with a bounded poll instead of assuming immediate visibility.
     */
    private boolean awaitAvailabilityCacheEntry(boolean present) throws InterruptedException {
        Cache availabilityCache = cacheManager.getCache("inventoryAvailable");
        assertNotNull(availabilityCache, "inventoryAvailable cache must be configured");
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            if ((availabilityCache.get(book.getId()) != null) == present) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private JsonNode getJson(String path) throws Exception {
        String body = mockMvc.perform(get(path)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode findBookById(JsonNode list, long id) {
        assertNotNull(list, "book list response must not be null");
        for (JsonNode node : list) {
            if (node.path("id").asLong() == id) {
                return node;
            }
        }
        return null;
    }

    @Test
    @Timeout(20)
    @DisplayName("List and single-book responses agree on every field when the availability cache is stale")
    void listAndSingleBook_agreeOnEveryField_whenAvailabilityCacheIsStale() throws Exception {
        // Warm the availability cache, then change the database out of band.
        // The list endpoint used to read the database directly and the single
        // endpoint the cache, so the two responses diverged.
        assertEquals(10, inventoryService.getAvailableQuantity(book.getId()),
                "availability cache should be warm before the database changes");
        assertTrue(awaitAvailabilityCacheEntry(true),
                "the warm availability read must become visible in the cache");

        Inventory inventory = inventoryRepository.findByBookId(book.getId()).orElseThrow();
        inventory.adjustAvailable(4);
        inventoryRepository.saveAndFlush(inventory);

        JsonNode listBook = findBookById(getJson("/api/v1/books"), book.getId());
        assertNotNull(listBook, "book must be present in the list response");
        JsonNode singleBook = getJson("/api/v1/books/" + book.getId());

        assertEquals(singleBook, listBook,
                "list and single-book responses must agree field-for-field");
    }

    @Test
    @Timeout(20)
    @DisplayName("List and single-book responses agree after an inventory mutation")
    void listAndSingleBook_agreeAfterInventoryMutation() throws Exception {
        inventoryService.reserve(book.getId(), 3, 7001L, "alignment-test");
        assertTrue(awaitAvailabilityCacheEntry(false),
                "the reservation must evict the availability cache entry");

        JsonNode listBook = findBookById(getJson("/api/v1/books"), book.getId());
        assertNotNull(listBook, "book must be present in the list response");
        JsonNode singleBook = getJson("/api/v1/books/" + book.getId());

        assertEquals(singleBook, listBook,
                "list and single-book responses must agree field-for-field");
        assertEquals(7, singleBook.path("availableQuantity").asInt(),
                "both paths must expose the post-reservation availability");
    }
}
