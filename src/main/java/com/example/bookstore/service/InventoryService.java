package com.example.bookstore.service;

import com.example.bookstore.event.EventTypes;
import com.example.bookstore.event.InventoryFinalizedEvent;
import com.example.bookstore.event.InventoryReleasedEvent;
import com.example.bookstore.event.InventoryReservedEvent;
import com.example.bookstore.event.publisher.DomainEventPublisher;
import com.example.bookstore.metrics.BusinessMetrics;
import com.example.bookstore.model.Inventory;
import com.example.bookstore.model.InventoryEventType;
import com.example.bookstore.model.InventoryHistory;
import com.example.bookstore.model.InventoryStatus;
import com.example.bookstore.repository.InventoryHistoryRepository;
import com.example.bookstore.repository.InventoryRepository;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final InventoryRepository inventoryRepository;
    private final InventoryHistoryRepository historyRepository;
    private final InventoryValidator validator;
    private final BusinessMetrics businessMetrics;
    private final DomainEventPublisher domainEventPublisher;

    @Autowired(required = false)
    private CacheManager cacheManager;

    /**
     * Self-reference resolved through the Spring proxy. A direct call to
     * {@link #getAvailableQuantity(Long)} would bypass the {@code @Cacheable}
     * advice because it would not pass through the proxy.
     */
    @Autowired
    @Lazy
    private InventoryService self;

    public InventoryService(InventoryRepository inventoryRepository,
                            InventoryHistoryRepository historyRepository,
                            InventoryValidator validator,
                            BusinessMetrics businessMetrics,
                            DomainEventPublisher domainEventPublisher) {
        this.inventoryRepository = inventoryRepository;
        this.historyRepository = historyRepository;
        this.validator = validator;
        this.businessMetrics = businessMetrics;
        this.domainEventPublisher = domainEventPublisher;
    }

    @Cacheable(cacheNames = "inventory", key = "#bookId")
    public Optional<Inventory> getInventory(Long bookId) {
        return inventoryRepository.findByBookId(bookId);
    }

    public Inventory getInventoryOrThrow(Long bookId) {
        return inventoryRepository.findByBookId(bookId)
                .orElseThrow(() -> new EntityNotFoundException("Inventory not found for book: " + bookId));
    }

    @Cacheable(cacheNames = "inventoryAvailable", key = "#bookId")
    public int getAvailableQuantity(Long bookId) {
        return inventoryRepository.findByBookId(bookId)
                .map(Inventory::getAvailableQuantity)
                .orElse(0);
    }

    /**
     * Availability lookup for list endpoints. Reuses cached values where present so
     * list and single-book responses always agree, and batch-fetches only cache
     * misses to avoid one query per book.
     */
    public Map<Long, Integer> getAvailableQuantities(Collection<Long> bookIds) {
        if (bookIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> result = new java.util.HashMap<>();
        List<Long> misses = new java.util.ArrayList<>();
        Cache cache = cacheManager != null ? cacheManager.getCache("inventoryAvailable") : null;
        for (Long id : bookIds) {
            Cache.ValueWrapper entry = cache != null ? cache.get(id) : null;
            Object cached = entry != null ? entry.get() : null;
            if (cached instanceof Integer value) {
                result.put(id, value);
            } else {
                misses.add(id);
            }
        }
        if (!misses.isEmpty()) {
            Map<Long, Integer> fetched = inventoryRepository.findByBookIdIn(misses).stream()
                    .collect(Collectors.toMap(Inventory::getBookId, Inventory::getAvailableQuantity));
            for (Long id : misses) {
                result.put(id, fetched.getOrDefault(id, 0));
            }
        }
        return result;
    }

    public int getReservedQuantity(Long bookId) {
        return inventoryRepository.findByBookId(bookId)
                .map(Inventory::getReservedQuantity)
                .orElse(0);
    }

    public InventoryStatus getInventoryStatus(Long bookId) {
        // Route through the proxy so the shared inventoryAvailable cache is used
        // instead of a self-invocation that bypasses the cache advice.
        InventoryService target = self != null ? self : this;
        int available = target.getAvailableQuantity(bookId);
        return validator.determineStatus(available);
    }

    @WithSpan("InventoryService.reserve")
    @Transactional
    @Retryable(
        retryFor = OptimisticLockingFailureException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 100, multiplier = 2)
    )
    @CacheEvict(cacheNames = {"inventory", "inventoryAvailable"}, key = "#bookId")
    public Inventory reserve(Long bookId, int quantity, Long orderId, String changedBy) {
        Inventory inventory = getInventoryOrThrow(bookId);
        validator.validateReservation(inventory, quantity);

        int previousAvailable = inventory.getAvailableQuantity();
        int previousReserved = inventory.getReservedQuantity();
        inventory.reserve(quantity);
        Inventory saved = inventoryRepository.save(inventory);

        recordHistory(saved.getId(), bookId, InventoryEventType.RESERVED,
                previousAvailable, saved.getAvailableQuantity(),
                previousReserved, saved.getReservedQuantity(),
                quantity, orderId, changedBy, "Order reservation");

        businessMetrics.recordInventoryReservation();
        log.info("Reserved {} units of book {} for order {}", quantity, bookId, orderId);
        return saved;
    }

    @WithSpan("InventoryService.releaseReservation")
    @Transactional
    @Retryable(
        retryFor = OptimisticLockingFailureException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 100, multiplier = 2)
    )
    @CacheEvict(cacheNames = {"inventory", "inventoryAvailable"}, key = "#bookId")
    public Inventory releaseReservation(Long bookId, int quantity, Long orderId, String reason, String changedBy) {
        if (orderId != null && historyRepository.existsByBookIdAndOrderIdAndEventType(
                bookId, orderId, InventoryEventType.RELEASED)) {
            log.warn("Reservation for book {} already released for order {}; skipping duplicate release",
                    bookId, orderId);
            return getInventoryOrThrow(bookId);
        }

        Inventory inventory = getInventoryOrThrow(bookId);
        validator.validateRelease(inventory, quantity);

        int previousAvailable = inventory.getAvailableQuantity();
        int previousReserved = inventory.getReservedQuantity();
        inventory.release(quantity);
        Inventory saved = inventoryRepository.save(inventory);

        recordHistory(saved.getId(), bookId, InventoryEventType.RELEASED,
                previousAvailable, saved.getAvailableQuantity(),
                previousReserved, saved.getReservedQuantity(),
                quantity, orderId, changedBy, reason);

        businessMetrics.recordInventoryRelease();
        log.info("Released {} units of book {} for order {}: {}", quantity, bookId, orderId, reason);
        return saved;
    }

    @WithSpan("InventoryService.finalizeReservation")
    @Transactional
    @Retryable(
        retryFor = OptimisticLockingFailureException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 100, multiplier = 2)
    )
    @CacheEvict(cacheNames = {"inventory", "inventoryAvailable"}, key = "#bookId")
    public Inventory finalizeReservation(Long bookId, int quantity, Long orderId) {
        Inventory inventory = getInventoryOrThrow(bookId);
        validator.validateFinalization(inventory, quantity);

        int previousAvailable = inventory.getAvailableQuantity();
        int previousReserved = inventory.getReservedQuantity();
        inventory.finalizeReservation(quantity);
        Inventory saved = inventoryRepository.save(inventory);

        recordHistory(saved.getId(), bookId, InventoryEventType.FINALIZED,
                previousAvailable, saved.getAvailableQuantity(),
                previousReserved, saved.getReservedQuantity(),
                quantity, orderId, "system", "Payment completed");

        businessMetrics.recordInventoryFinalization();
        businessMetrics.recordBooksSold(quantity);
        log.info("Finalized {} units of book {} for order {}", quantity, bookId, orderId);
        return saved;
    }

    @WithSpan("InventoryService.restoreStock")
    @Transactional
    @Retryable(
        retryFor = OptimisticLockingFailureException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 100, multiplier = 2)
    )
    @CacheEvict(cacheNames = {"inventory", "inventoryAvailable"}, key = "#bookId")
    public Inventory restoreStock(Long bookId, int quantity, Long orderId, String reason, String changedBy) {
        if (orderId != null && historyRepository.existsByBookIdAndOrderIdAndEventType(
                bookId, orderId, InventoryEventType.RESTORED)) {
            log.warn("Stock for book {} already restored for order {}; skipping duplicate restore", bookId, orderId);
            return getInventoryOrThrow(bookId);
        }

        Inventory inventory = getInventoryOrThrow(bookId);

        int previousAvailable = inventory.getAvailableQuantity();
        int previousReserved = inventory.getReservedQuantity();
        inventory.restore(quantity);
        Inventory saved = inventoryRepository.save(inventory);

        recordHistory(saved.getId(), bookId, InventoryEventType.RESTORED,
                previousAvailable, saved.getAvailableQuantity(),
                previousReserved, saved.getReservedQuantity(),
                quantity, orderId, changedBy, reason);

        businessMetrics.recordInventoryRestore();
        log.info("Restored {} units of book {} for order {}: {}", quantity, bookId, orderId, reason);
        return saved;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    @Retryable(
        retryFor = OptimisticLockingFailureException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 100, multiplier = 2)
    )
    @CacheEvict(cacheNames = {"inventory", "inventoryAvailable"}, key = "#bookId")
    protected Inventory initializeInventory(Long bookId, int initialQuantity) {
        if (inventoryRepository.existsByBookId(bookId)) {
            throw new IllegalStateException("Inventory already exists for book: " + bookId);
        }
        Inventory inventory = new Inventory(bookId, initialQuantity);
        Inventory saved = inventoryRepository.save(inventory);

        recordHistory(saved.getId(), bookId, InventoryEventType.CREATED,
                null, saved.getAvailableQuantity(),
                null, saved.getReservedQuantity(),
                initialQuantity, null, "system", "Initial inventory creation");

        log.info("Created inventory for book {} with {} units", bookId, initialQuantity);
        evictInventoryCacheEntries(bookId);
        return saved;
    }

    private void evictInventoryCaches() {
        if (cacheManager == null) {
            return;
        }
        Cache inventoryCache = cacheManager.getCache("inventory");
        if (inventoryCache != null) {
            inventoryCache.clear();
        }
        Cache availabilityCache = cacheManager.getCache("inventoryAvailable");
        if (availabilityCache != null) {
            availabilityCache.clear();
        }
    }

    private void evictInventoryCacheEntries(Long bookId) {
        if (cacheManager == null) {
            return;
        }
        Cache inventoryCache = cacheManager.getCache("inventory");
        if (inventoryCache != null) {
            inventoryCache.evict(bookId);
        }
        Cache availabilityCache = cacheManager.getCache("inventoryAvailable");
        if (availabilityCache != null) {
            availabilityCache.evict(bookId);
        }
    }

    public List<Inventory> findLowStock(int threshold) {
        return inventoryRepository.findLowStock(threshold);
    }

    public List<Inventory> findOutOfStock() {
        return inventoryRepository.findOutOfStock();
    }

    public List<InventoryHistory> getHistoryForBook(Long bookId) {
        return historyRepository.findByBookIdOrderByCreatedAtDesc(bookId);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    @Retryable(
        retryFor = OptimisticLockingFailureException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 100, multiplier = 2)
    )
    @CacheEvict(cacheNames = {"inventory", "inventoryAvailable"}, allEntries = true)
    public void releaseAllReservationsForOrder(Map<Long, Integer> orderItems, Long orderId, String reason, String changedBy) {
        for (Map.Entry<Long, Integer> entry : orderItems.entrySet()) {
            Long bookId = entry.getKey();
            int quantity = entry.getValue();
            if (orderId != null && historyRepository.existsByBookIdAndOrderIdAndEventType(
                    bookId, orderId, InventoryEventType.RELEASED)) {
                log.warn("Reservation for book {} already released for order {}; skipping duplicate release",
                        bookId, orderId);
                continue;
            }

            Inventory inventory = getInventoryOrThrow(bookId);
            validator.validateRelease(inventory, quantity);

            int previousAvailable = inventory.getAvailableQuantity();
            int previousReserved = inventory.getReservedQuantity();
            inventory.release(quantity);
            Inventory saved = inventoryRepository.save(inventory);

            recordHistory(saved.getId(), bookId, InventoryEventType.RELEASED,
                    previousAvailable, saved.getAvailableQuantity(),
                    previousReserved, saved.getReservedQuantity(),
                    quantity, orderId, changedBy, reason);

            businessMetrics.recordInventoryRelease();
            log.info("Released {} units of book {} for order {}: {}", quantity, bookId, orderId, reason);
        }
        domainEventPublisher.publish(
                new InventoryReleasedEvent(orderId, orderItems, reason),
                EventTypes.AGGREGATE_INVENTORY, String.valueOf(orderId), EventTypes.INVENTORY_RELEASED);
        evictInventoryCaches();
    }

    @Transactional(propagation = Propagation.REQUIRED)
    @Retryable(
        retryFor = OptimisticLockingFailureException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 100, multiplier = 2)
    )
    @CacheEvict(cacheNames = {"inventory", "inventoryAvailable"}, allEntries = true)
    public void finalizeAllReservationsForOrder(Map<Long, Integer> orderItems, Long orderId) {
        for (Map.Entry<Long, Integer> entry : orderItems.entrySet()) {
            Long bookId = entry.getKey();
            int quantity = entry.getValue();
            Inventory inventory = getInventoryOrThrow(bookId);
            validator.validateFinalization(inventory, quantity);

            int previousAvailable = inventory.getAvailableQuantity();
            int previousReserved = inventory.getReservedQuantity();
            inventory.finalizeReservation(quantity);
            Inventory saved = inventoryRepository.save(inventory);

            recordHistory(saved.getId(), bookId, InventoryEventType.FINALIZED,
                    previousAvailable, saved.getAvailableQuantity(),
                    previousReserved, saved.getReservedQuantity(),
                    quantity, orderId, "system", "Payment completed");

            businessMetrics.recordInventoryFinalization();
            businessMetrics.recordBooksSold(quantity);
            log.info("Finalized {} units of book {} for order {}", quantity, bookId, orderId);
        }
        domainEventPublisher.publish(
                new InventoryFinalizedEvent(orderId, orderItems),
                EventTypes.AGGREGATE_INVENTORY, String.valueOf(orderId), EventTypes.INVENTORY_FINALIZED);
        evictInventoryCaches();
    }

    @Transactional(propagation = Propagation.REQUIRED)
    @Retryable(
        retryFor = OptimisticLockingFailureException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 100, multiplier = 2)
    )
    @CacheEvict(cacheNames = {"inventory", "inventoryAvailable"}, allEntries = true)
    public void restoreAllStockForOrder(Map<Long, Integer> orderItems, Long orderId, String reason, String changedBy) {
        for (Map.Entry<Long, Integer> entry : orderItems.entrySet()) {
            Long bookId = entry.getKey();
            int quantity = entry.getValue();
            if (orderId != null && historyRepository.existsByBookIdAndOrderIdAndEventType(
                    bookId, orderId, InventoryEventType.RESTORED)) {
                log.warn("Stock for book {} already restored for order {}; skipping duplicate restore", bookId, orderId);
                continue;
            }

            Inventory inventory = getInventoryOrThrow(bookId);

            int previousAvailable = inventory.getAvailableQuantity();
            int previousReserved = inventory.getReservedQuantity();
            inventory.restore(quantity);
            Inventory saved = inventoryRepository.save(inventory);

            recordHistory(saved.getId(), bookId, InventoryEventType.RESTORED,
                    previousAvailable, saved.getAvailableQuantity(),
                    previousReserved, saved.getReservedQuantity(),
                    quantity, orderId, changedBy, reason);

            businessMetrics.recordInventoryRestore();
            log.info("Restored {} units of book {} for order {}: {}", quantity, bookId, orderId, reason);
        }
        evictInventoryCaches();
    }

    @Transactional(propagation = Propagation.REQUIRED)
    @Retryable(
        retryFor = { CannotAcquireLockException.class, PessimisticLockingFailureException.class,
                     LockTimeoutException.class, OptimisticLockingFailureException.class },
        maxAttempts = 3,
        backoff = @Backoff(delay = 100, multiplier = 2)
    )
    // Batch reservation: the transaction and cache eviction are placed on this
    // proxy-visible method so they cannot be bypassed by self-invocation.
    @CacheEvict(cacheNames = {"inventory", "inventoryAvailable"}, allEntries = true)
    public void reserveAll(Long orderId, Map<Long, Integer> bookQuantities, String changedBy) {
        List<Map.Entry<Long, Integer>> sorted = bookQuantities.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
        for (Map.Entry<Long, Integer> entry : sorted) {
            Inventory inventory = inventoryRepository.findByBookIdWithLock(entry.getKey())
                    .orElseThrow(() -> new EntityNotFoundException("Inventory not found for book: " + entry.getKey()));
            validator.validateReservation(inventory, entry.getValue());

            int previousAvailable = inventory.getAvailableQuantity();
            int previousReserved = inventory.getReservedQuantity();
            inventory.reserve(entry.getValue());
            Inventory saved = inventoryRepository.save(inventory);

            recordHistory(saved.getId(), entry.getKey(), InventoryEventType.RESERVED,
                    previousAvailable, saved.getAvailableQuantity(),
                    previousReserved, saved.getReservedQuantity(),
                    entry.getValue(), orderId, changedBy, "Order reservation (batch)");
        }
        domainEventPublisher.publish(
                new InventoryReservedEvent(orderId, Map.copyOf(bookQuantities)),
                EventTypes.AGGREGATE_INVENTORY, String.valueOf(orderId), EventTypes.INVENTORY_RESERVED);
        evictInventoryCaches();
    }

    private void recordHistory(Long inventoryId, Long bookId, InventoryEventType eventType,
                               Integer prevAvail, Integer newAvail,
                               Integer prevRes, Integer newRes,
                               int qtyChange, Long orderId,
                               String changedBy, String reason) {
        InventoryHistory history = new InventoryHistory();
        history.setInventoryId(inventoryId);
        history.setBookId(bookId);
        history.setEventType(eventType);
        history.setPreviousAvailable(prevAvail);
        history.setNewAvailable(newAvail);
        history.setPreviousReserved(prevRes);
        history.setNewReserved(newRes);
        history.setQuantityChange(qtyChange);
        history.setOrderId(orderId);
        history.setChangedBy(changedBy);
        history.setReason(reason);
        historyRepository.save(history);
    }
}
