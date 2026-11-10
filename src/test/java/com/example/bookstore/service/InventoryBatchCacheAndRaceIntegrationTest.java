package com.example.bookstore.service;

import com.example.bookstore.base.AbstractIntegrationTest;
import com.example.bookstore.model.AppUser;
import com.example.bookstore.model.Book;
import com.example.bookstore.model.Inventory;
import com.example.bookstore.model.InventoryEventType;
import com.example.bookstore.model.Order;
import com.example.bookstore.model.Payment;
import com.example.bookstore.model.PaymentStatus;
import com.example.bookstore.model.UserRole;
import com.example.bookstore.repository.BookRepository;
import com.example.bookstore.repository.InventoryHistoryRepository;
import com.example.bookstore.repository.InventoryRepository;
import com.example.bookstore.repository.OrderRepository;
import com.example.bookstore.repository.PaymentRepository;
import com.example.bookstore.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration evidence for inventory batch transactions, cache eviction, and
 * idempotent terminal history rows.
 */
class InventoryBatchCacheAndRaceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryHistoryRepository inventoryHistoryRepository;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private CartService cartService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AppUser user;
    private Book book;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
        inventoryHistoryRepository.deleteAll();
        orderRepository.deleteAll();
        inventoryRepository.deleteAll();
        bookRepository.deleteAll();
        userRepository.deleteAll();
        clearInventoryCaches();

        user = new AppUser();
        user.setUsername("inventory-batch-" + System.nanoTime());
        user.setPassword(passwordEncoder.encode("password"));
        user.setEmail("inventory-batch@test.com");
        user.setRole(UserRole.ROLE_USER);
        user = userRepository.save(user);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));

        book = new Book();
        book.setTitle("Inventory Batch Test Book");
        book.setAuthor("Author");
        book.setPrice(new BigDecimal("10.00"));
        book.setCategory("Test");
        book.setStock(10);
        book = bookRepository.save(book);

        inventoryRepository.save(new Inventory(book.getId(), 10));
    }

    private void clearInventoryCaches() {
        for (String cacheName : List.of("inventory", "inventoryAvailable")) {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
            }
        }
    }

    private TransactionTemplate callerTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        return template;
    }

    private long countHistoryRows(long orderId, InventoryEventType eventType) {
        return inventoryHistoryRepository.findByOrderIdAndEventType(orderId, eventType).stream()
                .filter(history -> book.getId().equals(history.getBookId()))
                .count();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Inventory history has no duplicate terminal rows and the idempotency index exists")
    void duplicatePrecheck_NoDuplicateTerminalHistoryRows() {
        List<Map<String, Object>> duplicates = jdbcTemplate.queryForList(
                "SELECT book_id, order_id, event_type, COUNT(*) AS c "
                        + "FROM inventory_history GROUP BY book_id, order_id, event_type HAVING COUNT(*) > 1");
        assertTrue(duplicates.isEmpty(), "Duplicate inventory history rows found: " + duplicates);

        Integer indexCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = current_schema() "
                        + "AND indexname = 'uq_inventory_history_idempotency'",
                Integer.class);
        assertEquals(1, indexCount, "idempotency index must exist after migration");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Batch reserve evicts the inventory availability cache")
    void batchReserve_EvictsAvailabilityCache() {
        clearInventoryCaches();
        assertEquals(10, inventoryService.getAvailableQuantity(book.getId()),
                "initial cache population should see 10 available");

        inventoryService.reserveAll(1001L, Map.of(book.getId(), 3), "batch-reserve-test");

        assertEquals(7, inventoryService.getAvailableQuantity(book.getId()),
                "batch reserve must evict the stale availability cache");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Batch release evicts the inventory availability cache")
    void batchRelease_EvictsAvailabilityCache() {
        inventoryService.reserveAll(1002L, Map.of(book.getId(), 3), "batch-release-setup");
        clearInventoryCaches();
        assertEquals(7, inventoryService.getAvailableQuantity(book.getId()),
                "cache should observe the reserved availability before release");

        callerTransaction().executeWithoutResult(status ->
                inventoryService.releaseAllReservationsForOrder(
                        Map.of(book.getId(), 3), 1002L, "batch-release-test", "test"));

        assertEquals(10, inventoryService.getAvailableQuantity(book.getId()),
                "batch release must evict the stale availability cache");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("Concurrent batch releases restore stock exactly once")
    void concurrentBatchRelease_RestoresExactlyOnce() throws Exception {
        long orderId = 2001L;
        int quantity = 5;
        inventoryService.reserveAll(orderId, Map.of(book.getId(), quantity), "race-setup");
        clearInventoryCaches();

        int threadCount = 10;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });

        try {
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        callerTransaction().executeWithoutResult(status ->
                                inventoryService.releaseAllReservationsForOrder(
                                        Map.of(book.getId(), quantity), orderId,
                                        "concurrent-release", "race-test"));
                    } catch (Exception ignored) {
                        // Losing racers may fail with lock or unique-constraint exceptions.
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS),
                    "concurrent releases did not finish within the timeout");
        } finally {
            executor.shutdownNow();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        Inventory inventory = inventoryRepository.findByBookId(book.getId()).orElseThrow();
        assertEquals(10, inventory.getAvailableQuantity(),
                "stock must be restored exactly once");
        assertEquals(0, inventory.getReservedQuantity(),
                "reserved quantity must be zero after release");
        assertEquals(1, countHistoryRows(orderId, InventoryEventType.RELEASED),
                "exactly one RELEASED history row must exist");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("Coordinated refund restores stock exactly once")
    void coordinatedRefund_RestoresStockExactlyOnce() {
        cartService.addToCart(book.getId(), 2);
        Order order = orderService.createOrder();

        Payment processed = paymentService.processPayment(order.getId(), "CREDIT_CARD");
        Payment refunded = paymentService.refundPayment(processed.getId());

        assertEquals(PaymentStatus.REFUNDED, refunded.getPaymentStatus());

        Inventory inventory = inventoryRepository.findByBookId(book.getId()).orElseThrow();
        assertEquals(10, inventory.getAvailableQuantity(),
                "refund must restore the reserved quantity exactly once");
        assertEquals(0, inventory.getReservedQuantity(),
                "refund must clear the reserved quantity");
        assertEquals(1, countHistoryRows(order.getId(), InventoryEventType.RESTORED),
                "exactly one RESTORED history row must exist");
    }
}
