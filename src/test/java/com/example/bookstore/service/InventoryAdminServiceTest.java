package com.example.bookstore.service;

import com.example.bookstore.config.SecurityUtils;
import com.example.bookstore.event.InventoryAdjustedEvent;
import com.example.bookstore.event.publisher.DomainEventPublisher;
import com.example.bookstore.metrics.BusinessMetrics;
import com.example.bookstore.model.AppUser;
import com.example.bookstore.model.Inventory;
import com.example.bookstore.model.InventoryHistory;
import com.example.bookstore.model.InventoryStatus;
import com.example.bookstore.repository.InventoryHistoryRepository;
import com.example.bookstore.repository.InventoryRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryAdminServiceTest {

    @Mock
    private InventoryRepository inventoryRepository;
    @Mock
    private InventoryHistoryRepository historyRepository;
    @Mock
    private InventoryValidator validator;
    @Mock
    private SecurityUtils securityUtils;
    @Mock
    private DomainEventPublisher domainEventPublisher;
    @Mock
    private InventoryService inventoryService;

    private InventoryAdminService adminService;
    private Inventory inventory;

    @BeforeEach
    void setUp() {
        adminService = new InventoryAdminService(inventoryRepository, historyRepository,
                validator, securityUtils, domainEventPublisher, inventoryService);
        inventory = new Inventory(1L, 100);
    }

    @Nested
    @DisplayName("getAllInventory")
    class GetAll {
        @Test
        @DisplayName("Returns all inventory")
        void all() {
            when(inventoryRepository.findAll()).thenReturn(List.of(inventory));
            assertEquals(1, adminService.getAllInventory().size());
        }
    }

    @Nested
    @DisplayName("getInventoryByBookId")
    class GetByBookId {
        @Test
        @DisplayName("Returns inventory when found")
        void found() {
            when(inventoryRepository.findByBookId(1L)).thenReturn(Optional.of(inventory));
            assertEquals(1L, adminService.getInventoryByBookId(1L).getBookId());
        }

        @Test
        @DisplayName("Throws when not found")
        void notFound() {
            when(inventoryRepository.findByBookId(99L)).thenReturn(Optional.empty());
            assertThrows(EntityNotFoundException.class, () -> adminService.getInventoryByBookId(99L));
        }
    }

    @Nested
    @DisplayName("adjustInventory")
    class AdjustInventory {
        @Test
        @DisplayName("Adjusts inventory and publishes event")
        void adjust() {
            AppUser admin = new AppUser();
            admin.setUsername("admin");
            when(securityUtils.getCurrentUser()).thenReturn(admin);
            when(inventoryRepository.findByBookId(1L)).thenReturn(Optional.of(inventory));
            when(inventoryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            Inventory result = adminService.adjustInventory(1L, 50, "Stock correction");

            assertEquals(50, result.getAvailableQuantity());
            verify(validator).validateAdjustment(50);
            verify(historyRepository).save(any(InventoryHistory.class));
            verify(domainEventPublisher).publish(any(InventoryAdjustedEvent.class),
                    anyString(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("initializeInventory")
    class Initialize {
        @Test
        @DisplayName("Delegates to inventory service")
        void delegates() {
            when(inventoryService.initializeInventory(1L, 50)).thenReturn(inventory);
            assertEquals(inventory, adminService.initializeInventory(1L, 50));
        }
    }

    @Nested
    @DisplayName("getHistoryForBook")
    class GetHistory {
        @Test
        @DisplayName("Returns paginated history")
        void paginated() {
            Page<InventoryHistory> page = new PageImpl<>(List.of(new InventoryHistory()));
            when(historyRepository.findByBookId(eq(1L), any(PageRequest.class))).thenReturn(page);
            assertEquals(1, adminService.getHistoryForBook(1L, 0, 10).getContent().size());
        }
    }

    @Nested
    @DisplayName("getInventoryByStatus")
    class GetByStatus {
        @Test
        @DisplayName("Delegates OUT_OF_STOCK")
        void outOfStock() {
            when(inventoryRepository.findOutOfStock()).thenReturn(List.of(inventory));
            assertEquals(1, adminService.getInventoryByStatus(InventoryStatus.OUT_OF_STOCK).size());
        }

        @Test
        @DisplayName("Filters by CRITICAL_STOCK")
        void criticalStock() {
            when(inventoryRepository.findAll()).thenReturn(List.of(inventory));
            when(validator.determineStatus(100)).thenReturn(InventoryStatus.AVAILABLE);
            assertTrue(adminService.getInventoryByStatus(InventoryStatus.CRITICAL_STOCK).isEmpty());
        }

        @Test
        @DisplayName("Delegates LOW_STOCK")
        void lowStock() {
            when(inventoryRepository.findLowStock(Integer.MAX_VALUE)).thenReturn(List.of(inventory));
            assertEquals(1, adminService.getInventoryByStatus(InventoryStatus.LOW_STOCK).size());
        }

        @Test
        @DisplayName("Filters by AVAILABLE")
        void available() {
            when(inventoryRepository.findAll()).thenReturn(List.of(inventory));
            when(validator.determineStatus(100)).thenReturn(InventoryStatus.AVAILABLE);
            assertEquals(1, adminService.getInventoryByStatus(InventoryStatus.AVAILABLE).size());
        }
    }
}
