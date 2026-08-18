package com.example.bookstore.service;

import com.example.bookstore.config.SecurityUtils;
import com.example.bookstore.event.EventTypes;
import com.example.bookstore.event.InventoryAdjustedEvent;
import com.example.bookstore.event.publisher.DomainEventPublisher;
import com.example.bookstore.model.Inventory;
import com.example.bookstore.model.InventoryEventType;
import com.example.bookstore.model.InventoryHistory;
import com.example.bookstore.model.InventoryStatus;
import com.example.bookstore.repository.InventoryHistoryRepository;
import com.example.bookstore.repository.InventoryRepository;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class InventoryAdminService {

    private static final Logger log = LoggerFactory.getLogger(InventoryAdminService.class);

    private final InventoryRepository inventoryRepository;
    private final InventoryHistoryRepository historyRepository;
    private final InventoryValidator validator;
    private final SecurityUtils securityUtils;
    private final DomainEventPublisher domainEventPublisher;
    private final InventoryService inventoryService;

    public InventoryAdminService(InventoryRepository inventoryRepository,
                                 InventoryHistoryRepository historyRepository,
                                 InventoryValidator validator,
                                 SecurityUtils securityUtils,
                                 DomainEventPublisher domainEventPublisher,
                                 InventoryService inventoryService) {
        this.inventoryRepository = inventoryRepository;
        this.historyRepository = historyRepository;
        this.validator = validator;
        this.securityUtils = securityUtils;
        this.domainEventPublisher = domainEventPublisher;
        this.inventoryService = inventoryService;
    }

    public List<Inventory> getAllInventory() {
        return inventoryRepository.findAll();
    }

    public Inventory getInventoryByBookId(Long bookId) {
        return inventoryRepository.findByBookId(bookId)
                .orElseThrow(() -> new EntityNotFoundException("Inventory not found for book: " + bookId));
    }

    @WithSpan("InventoryAdminService.adjustInventory")
    @Transactional
    @CacheEvict(cacheNames = {"inventory", "inventoryAvailable"}, key = "#bookId")
    public Inventory adjustInventory(Long bookId, int newAvailableQuantity, String reason) {
        Inventory inventory = getInventoryByBookId(bookId);
        String adminUsername = securityUtils.getCurrentUser().getUsername();

        validator.validateAdjustment(newAvailableQuantity);
        int previousAvailable = inventory.getAvailableQuantity();

        inventory.adjustAvailable(newAvailableQuantity);
        Inventory saved = inventoryRepository.save(inventory);

        InventoryHistory history = new InventoryHistory();
        history.setInventoryId(saved.getId());
        history.setBookId(bookId);
        history.setEventType(InventoryEventType.ADJUSTED);
        history.setPreviousAvailable(previousAvailable);
        history.setNewAvailable(newAvailableQuantity);
        history.setPreviousReserved(saved.getReservedQuantity());
        history.setNewReserved(saved.getReservedQuantity());
        history.setQuantityChange(newAvailableQuantity - previousAvailable);
        history.setChangedBy(adminUsername);
        history.setReason(reason);
        history.setCreatedAt(LocalDateTime.now());
        historyRepository.save(history);

        domainEventPublisher.publish(
                new InventoryAdjustedEvent(bookId, previousAvailable, newAvailableQuantity, reason, adminUsername),
                EventTypes.AGGREGATE_INVENTORY, String.valueOf(bookId), EventTypes.INVENTORY_ADJUSTED);

        log.info("Inventory adjusted for book {}: {} -> {} by {} (reason: {})",
                bookId, previousAvailable, newAvailableQuantity, adminUsername, reason);
        return saved;
    }

    @Transactional
    public Inventory initializeInventory(Long bookId, int initialQuantity) {
        return inventoryService.initializeInventory(bookId, initialQuantity);
    }

    public List<Inventory> getLowStock(int threshold) {
        return inventoryService.findLowStock(threshold);
    }

    public List<Inventory> getOutOfStock() {
        return inventoryService.findOutOfStock();
    }

    public Page<InventoryHistory> getHistoryForBook(Long bookId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return historyRepository.findByBookId(bookId, pageable);
    }

    public List<InventoryHistory> getHistoryForOrder(Long orderId) {
        return inventoryService.getHistoryForOrder(orderId);
    }

    public List<Inventory> getInventoryByStatus(InventoryStatus status) {
        switch (status) {
            case OUT_OF_STOCK -> {
                return inventoryRepository.findOutOfStock();
            }
            case CRITICAL_STOCK -> {
                return inventoryRepository.findAll().stream()
                        .filter(i -> validator.determineStatus(i.getAvailableQuantity()) == InventoryStatus.CRITICAL_STOCK)
                        .collect(Collectors.toList());
            }
            case LOW_STOCK -> {
                return inventoryRepository.findLowStock(Integer.MAX_VALUE);
            }
            default -> {
                return inventoryRepository.findAll().stream()
                        .filter(i -> validator.determineStatus(i.getAvailableQuantity()) == InventoryStatus.AVAILABLE)
                        .collect(Collectors.toList());
            }
        }
    }
}
