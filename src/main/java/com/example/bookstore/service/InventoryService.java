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
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final InventoryRepository inventoryRepository;
    private final InventoryHistoryRepository historyRepository;
    private final InventoryValidator validator;
    private final BusinessMetrics businessMetrics;
    private final DomainEventPublisher domainEventPublisher;

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

    public int getReservedQuantity(Long bookId) {
        return inventoryRepository.findByBookId(bookId)
                .map(Inventory::getReservedQuantity)
                .orElse(0);
    }

    public InventoryStatus getInventoryStatus(Long bookId) {
        int available = getAvailableQuantity(bookId);
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
                quantity, orderId, null, changedBy, "Order reservation");

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
        Inventory inventory = getInventoryOrThrow(bookId);
        validator.validateRelease(inventory, quantity);

        int previousAvailable = inventory.getAvailableQuantity();
        int previousReserved = inventory.getReservedQuantity();
        inventory.release(quantity);
        Inventory saved = inventoryRepository.save(inventory);

        recordHistory(saved.getId(), bookId, InventoryEventType.RELEASED,
                previousAvailable, saved.getAvailableQuantity(),
                previousReserved, saved.getReservedQuantity(),
                quantity, orderId, null, changedBy, reason);

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
                quantity, orderId, null, "system", "Payment completed");

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
        Inventory inventory = getInventoryOrThrow(bookId);

        int previousAvailable = inventory.getAvailableQuantity();
        int previousReserved = inventory.getReservedQuantity();
        inventory.restore(quantity);
        Inventory saved = inventoryRepository.save(inventory);

        recordHistory(saved.getId(), bookId, InventoryEventType.RESTORED,
                previousAvailable, saved.getAvailableQuantity(),
                previousReserved, saved.getReservedQuantity(),
                quantity, orderId, null, changedBy, reason);

        businessMetrics.recordInventoryRestore();
        log.info("Restored {} units of book {} for order {}: {}", quantity, bookId, orderId, reason);
        return saved;
    }

    @Transactional
    @Retryable(
        retryFor = OptimisticLockingFailureException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 100, multiplier = 2)
    )
    protected Inventory initializeInventory(Long bookId, int initialQuantity) {
        if (inventoryRepository.existsByBookId(bookId)) {
            throw new IllegalStateException("Inventory already exists for book: " + bookId);
        }
        Inventory inventory = new Inventory(bookId, initialQuantity);
        Inventory saved = inventoryRepository.save(inventory);

        recordHistory(saved.getId(), bookId, InventoryEventType.CREATED,
                null, saved.getAvailableQuantity(),
                null, saved.getReservedQuantity(),
                initialQuantity, null, null, "system", "Initial inventory creation");

        log.info("Created inventory for book {} with {} units", bookId, initialQuantity);
        return saved;
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

    public List<InventoryHistory> getHistoryForOrder(Long orderId) {
        return historyRepository.findByOrderIdOrderByCreatedAtDesc(orderId);
    }

    public void releaseAllReservationsForOrder(Map<Long, Integer> orderItems, Long orderId, String reason, String changedBy) {
        for (Map.Entry<Long, Integer> entry : orderItems.entrySet()) {
            releaseReservation(entry.getKey(), entry.getValue(), orderId, reason, changedBy);
        }
        domainEventPublisher.publish(
                new InventoryReleasedEvent(orderId, orderItems, reason),
                EventTypes.AGGREGATE_INVENTORY, String.valueOf(orderId), EventTypes.INVENTORY_RELEASED);
    }

    public void finalizeAllReservationsForOrder(Map<Long, Integer> orderItems, Long orderId) {
        for (Map.Entry<Long, Integer> entry : orderItems.entrySet()) {
            finalizeReservation(entry.getKey(), entry.getValue(), orderId);
        }
        domainEventPublisher.publish(
                new InventoryFinalizedEvent(orderId, orderItems),
                EventTypes.AGGREGATE_INVENTORY, String.valueOf(orderId), EventTypes.INVENTORY_FINALIZED);
    }

    public void restoreAllStockForOrder(Map<Long, Integer> orderItems, Long orderId, String reason, String changedBy) {
        for (Map.Entry<Long, Integer> entry : orderItems.entrySet()) {
            restoreStock(entry.getKey(), entry.getValue(), orderId, reason, changedBy);
        }
    }

    @Transactional
    @Retryable(
        retryFor = { CannotAcquireLockException.class, PessimisticLockingFailureException.class,
                     LockTimeoutException.class, OptimisticLockingFailureException.class },
        maxAttempts = 3,
        backoff = @Backoff(delay = 100, multiplier = 2)
    )
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
                    entry.getValue(), orderId, null, changedBy, "Order reservation (batch)");
        }
        domainEventPublisher.publish(
                new InventoryReservedEvent(orderId, Map.copyOf(bookQuantities)),
                EventTypes.AGGREGATE_INVENTORY, String.valueOf(orderId), EventTypes.INVENTORY_RESERVED);
    }

    private void recordHistory(Long inventoryId, Long bookId, InventoryEventType eventType,
                               Integer prevAvail, Integer newAvail,
                               Integer prevRes, Integer newRes,
                               int qtyChange, Long orderId, Long paymentId,
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
        history.setPaymentId(paymentId);
        history.setChangedBy(changedBy);
        history.setReason(reason);
        historyRepository.save(history);
    }
}
