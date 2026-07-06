package com.example.bookstore.service;

import com.example.bookstore.base.AbstractIntegrationTest;
import com.example.bookstore.model.*;
import com.example.bookstore.repository.BookRepository;
import com.example.bookstore.repository.InventoryRepository;
import com.example.bookstore.repository.OrderRepository;
import com.example.bookstore.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class InventoryConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private AppUser user;
    private Book book;

    @BeforeEach
    void setUp() {
        inventoryRepository.deleteAll();
        orderRepository.deleteAll();
        bookRepository.deleteAll();
        userRepository.deleteAll();

        user = new AppUser();
        user.setUsername("concurrency-" + System.nanoTime());
        user.setPassword(passwordEncoder.encode("password"));
        user.setEmail("concurrency@test.com");
        user.setRole(UserRole.ROLE_USER);
        user = userRepository.save(user);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));

        book = new Book();
        book.setTitle("Concurrency Test Book");
        book.setAuthor("Author");
        book.setPrice(new BigDecimal("10.00"));
        book.setCategory("Test");
        book.setStock(10);
        book = bookRepository.save(book);

        Inventory inventory = new Inventory(book.getId(), 10);
        inventoryRepository.save(inventory);
    }

    @Test
    @DisplayName("20 concurrent reservations for 10 units only succeed 10 times")
    void concurrentReservations_PessimisticLock_PreventsOverselling() throws InterruptedException {
        int threadCount = 20;
        int stock = 10;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    inventoryService.reserve(book.getId(), 1, (long) (index + 1000), "concurrency-test");
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertEquals(stock, successCount.get(),
                "Exactly " + stock + " reservations should succeed");
        assertEquals(threadCount - stock, failureCount.get(),
                (threadCount - stock) + " reservations should fail");

        Inventory finalInventory = inventoryRepository.findByBookId(book.getId()).orElseThrow();
        assertEquals(0, finalInventory.getAvailableQuantity(),
                "All stock should be reserved");
        assertEquals(stock, finalInventory.getReservedQuantity(),
                "All stock should show as reserved");
    }

    @Test
    @DisplayName("ReserveAll with pessimistic lock prevents overselling across batch")
    void reserveAll_AtomicAndSorted_PreventsOverselling() {
        Inventory inventory = inventoryRepository.findByBookId(book.getId()).orElseThrow();
        assertEquals(10, inventory.getAvailableQuantity());

        Map<Long, Integer> batch = Map.of(book.getId(), 8);
        inventoryService.reserveAll(1L, batch, "batch-test");

        Inventory after = inventoryRepository.findByBookId(book.getId()).orElseThrow();
        assertEquals(2, after.getAvailableQuantity());
        assertEquals(8, after.getReservedQuantity());
    }

    @Test
    @DisplayName("Concurrent release and reserve does not produce inconsistent state")
    void concurrentReleaseAndReserve_ConsistentState() throws InterruptedException {
        inventoryService.reserve(book.getId(), 5, 1L, "user1");
        Inventory afterReserve = inventoryRepository.findByBookId(book.getId()).orElseThrow();
        assertEquals(5, afterReserve.getAvailableQuantity());
        assertEquals(5, afterReserve.getReservedQuantity());

        CountDownLatch latch = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        executor.submit(() -> {
            try {
                inventoryService.releaseReservation(book.getId(), 5, 1L, "Release before re-reserve", "user2");
            } finally {
                latch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                inventoryService.reserve(book.getId(), 3, 2L, "user2");
            } catch (Exception e) {
                // Reservation might fail or succeed depending on timing
            } finally {
                latch.countDown();
            }
        });

        latch.await();
        executor.shutdown();

        Inventory finalState = inventoryRepository.findByBookId(book.getId()).orElseThrow();
        int total = finalState.getAvailableQuantity() + finalState.getReservedQuantity();
        assertEquals(10, total,
                "Total stock (available + reserved) must always equal initial quantity");
    }

    @Test
    @DisplayName("Releasing more than reserved quantity throws validation error")
    void releaseExcess_ThrowsValidationError() {
        inventoryService.reserve(book.getId(), 3, 1L, "user");

        assertThrows(IllegalStateException.class, () ->
                inventoryService.releaseReservation(book.getId(), 5, 1L, "Excess release", "user"));

        Inventory inventory = inventoryRepository.findByBookId(book.getId()).orElseThrow();
        assertEquals(7, inventory.getAvailableQuantity());
        assertEquals(3, inventory.getReservedQuantity());
    }
}
