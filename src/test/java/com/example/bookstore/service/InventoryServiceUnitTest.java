package com.example.bookstore.service;

import com.example.bookstore.event.InventoryReservedEvent;
import com.example.bookstore.event.publisher.DomainEventPublisher;
import com.example.bookstore.metrics.BusinessMetrics;
import com.example.bookstore.model.Inventory;
import com.example.bookstore.model.InventoryHistory;
import com.example.bookstore.repository.InventoryHistoryRepository;
import com.example.bookstore.repository.InventoryRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryServiceUnitTest {

    @Mock
    private InventoryRepository inventoryRepository;
    @Mock
    private InventoryHistoryRepository historyRepository;
    @Mock
    private InventoryValidator validator;
    @Mock
    private BusinessMetrics businessMetrics;
    @Mock
    private DomainEventPublisher domainEventPublisher;

    private InventoryService inventoryService;
    private Inventory inventory;

    @Captor
    private ArgumentCaptor<Inventory> inventoryCaptor;

    @BeforeEach
    void setUp() {
        inventoryService = new InventoryService(inventoryRepository, historyRepository,
                validator, businessMetrics, domainEventPublisher);
        inventory = new Inventory(1L, 50);
        inventory.reserve(10);
    }

    @Nested
    @DisplayName("getInventory")
    class GetInventory {
        @Test
        @DisplayName("Returns inventory when found")
        void found() {
            when(inventoryRepository.findByBookId(1L)).thenReturn(Optional.of(inventory));
            Optional<Inventory> result = inventoryService.getInventory(1L);
            assertTrue(result.isPresent());
            assertEquals(1L, result.get().getBookId());
        }

        @Test
        @DisplayName("Returns empty when not found")
        void notFound() {
            when(inventoryRepository.findByBookId(99L)).thenReturn(Optional.empty());
            assertTrue(inventoryService.getInventory(99L).isEmpty());
        }
    }

    @Nested
    @DisplayName("getInventoryOrThrow")
    class GetInventoryOrThrow {
        @Test
        @DisplayName("Returns inventory when found")
        void found() {
            when(inventoryRepository.findByBookId(1L)).thenReturn(Optional.of(inventory));
            assertEquals(1L, inventoryService.getInventoryOrThrow(1L).getBookId());
        }

        @Test
        @DisplayName("Throws when not found")
        void notFound() {
            when(inventoryRepository.findByBookId(99L)).thenReturn(Optional.empty());
            assertThrows(EntityNotFoundException.class, () -> inventoryService.getInventoryOrThrow(99L));
        }
    }

    @Nested
    @DisplayName("getAvailableQuantity")
    class GetAvailableQuantity {
        @Test
        @DisplayName("Returns available quantity")
        void found() {
            when(inventoryRepository.findByBookId(1L)).thenReturn(Optional.of(inventory));
            assertEquals(40, inventoryService.getAvailableQuantity(1L));
        }

        @Test
        @DisplayName("Returns zero when not found")
        void notFound() {
            when(inventoryRepository.findByBookId(99L)).thenReturn(Optional.empty());
            assertEquals(0, inventoryService.getAvailableQuantity(99L));
        }
    }

    @Nested
    @DisplayName("getReservedQuantity")
    class GetReservedQuantity {
        @Test
        @DisplayName("Returns reserved quantity")
        void found() {
            when(inventoryRepository.findByBookId(1L)).thenReturn(Optional.of(inventory));
            assertEquals(10, inventoryService.getReservedQuantity(1L));
        }

        @Test
        @DisplayName("Returns zero when not found")
        void notFound() {
            when(inventoryRepository.findByBookId(99L)).thenReturn(Optional.empty());
            assertEquals(0, inventoryService.getReservedQuantity(99L));
        }
    }

    @Nested
    @DisplayName("reserve")
    class Reserve {
        @Test
        @DisplayName("Reserves quantity and saves")
        void reserveSuccess() {
            when(inventoryRepository.findByBookId(1L)).thenReturn(Optional.of(inventory));
            when(inventoryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            Inventory result = inventoryService.reserve(1L, 5, 100L, "system");

            assertEquals(35, result.getAvailableQuantity());
            assertEquals(15, result.getReservedQuantity());
            verify(historyRepository).save(any(InventoryHistory.class));
            verify(businessMetrics).recordInventoryReservation();
        }
    }

    @Nested
    @DisplayName("releaseReservation")
    class ReleaseReservation {
        @Test
        @DisplayName("Releases quantity and saves")
        void releaseSuccess() {
            when(inventoryRepository.findByBookId(1L)).thenReturn(Optional.of(inventory));
            when(inventoryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            Inventory result = inventoryService.releaseReservation(1L, 3, 100L, "Cancelled", "system");

            assertEquals(43, result.getAvailableQuantity());
            assertEquals(7, result.getReservedQuantity());
            verify(historyRepository).save(any(InventoryHistory.class));
            verify(businessMetrics).recordInventoryRelease();
        }
    }

    @Nested
    @DisplayName("finalizeReservation")
    class FinalizeReservation {
        @Test
        @DisplayName("Finalizes and reduces reserved only")
        void finalizeSuccess() {
            when(inventoryRepository.findByBookId(1L)).thenReturn(Optional.of(inventory));
            when(inventoryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            Inventory result = inventoryService.finalizeReservation(1L, 5, 100L);

            assertEquals(40, result.getAvailableQuantity());
            assertEquals(5, result.getReservedQuantity());
            verify(historyRepository).save(any(InventoryHistory.class));
            verify(businessMetrics).recordInventoryFinalization();
            verify(businessMetrics).recordBooksSold(5);
        }
    }

    @Nested
    @DisplayName("restoreStock")
    class RestoreStock {
        @Test
        @DisplayName("Restores quantity to available")
        void restoreSuccess() {
            Inventory nonReserved = new Inventory(1L, 50);
            when(inventoryRepository.findByBookId(1L)).thenReturn(Optional.of(nonReserved));
            when(inventoryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            Inventory result = inventoryService.restoreStock(1L, 10, 100L, "Refund", "system");

            assertEquals(60, result.getAvailableQuantity());
            verify(historyRepository).save(any(InventoryHistory.class));
            verify(businessMetrics).recordInventoryRestore();
        }
    }

    @Nested
    @DisplayName("reserveAll")
    class ReserveAll {
        @Test
        @DisplayName("Reserves multiple books in sorted order")
        void reserveAllSuccess() {
            Inventory inv1 = new Inventory(1L, 20);
            Inventory inv2 = new Inventory(2L, 30);
            when(inventoryRepository.findByBookIdWithLock(1L)).thenReturn(Optional.of(inv1));
            when(inventoryRepository.findByBookIdWithLock(2L)).thenReturn(Optional.of(inv2));
            when(inventoryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            inventoryService.reserveAll(100L, Map.of(2L, 5, 1L, 3), "system");

            assertEquals(17, inv1.getAvailableQuantity());
            assertEquals(3, inv1.getReservedQuantity());
            assertEquals(25, inv2.getAvailableQuantity());
            assertEquals(5, inv2.getReservedQuantity());
            verify(domainEventPublisher).publish(any(InventoryReservedEvent.class),
                    anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("Throws when inventory not found for a book")
        void bookNotFound() {
            when(inventoryRepository.findByBookIdWithLock(1L)).thenReturn(Optional.empty());
            assertThrows(EntityNotFoundException.class,
                    () -> inventoryService.reserveAll(100L, Map.of(1L, 3), "system"));
        }
    }

    @Nested
    @DisplayName("releaseAllReservationsForOrder")
    class ReleaseAll {
        @Test
        @DisplayName("Releases all items and publishes event")
        void releaseAll() {
            Inventory inv = new Inventory(1L, 20);
            inv.reserve(5);
            when(inventoryRepository.findByBookId(1L)).thenReturn(Optional.of(inv));
            when(inventoryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            inventoryService.releaseAllReservationsForOrder(
                    Map.of(1L, 5), 100L, "Cancelled", "system");

            assertEquals(20, inv.getAvailableQuantity());
            assertEquals(0, inv.getReservedQuantity());
            verify(domainEventPublisher).publish(any(), anyString(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("finalizeAllReservationsForOrder")
    class FinalizeAll {
        @Test
        @DisplayName("Finalizes all items and publishes event")
        void finalizeAll() {
            Inventory inv = new Inventory(1L, 20);
            inv.reserve(5);
            when(inventoryRepository.findByBookId(1L)).thenReturn(Optional.of(inv));
            when(inventoryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            inventoryService.finalizeAllReservationsForOrder(Map.of(1L, 5), 100L);

            assertEquals(15, inv.getAvailableQuantity());
            assertEquals(0, inv.getReservedQuantity());
            verify(domainEventPublisher).publish(any(), anyString(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("findLowStock / findOutOfStock")
    class StockQueries {
        @Test
        @DisplayName("Delegates to repository")
        void lowStock() {
            when(inventoryRepository.findLowStock(10)).thenReturn(List.of(inventory));
            assertEquals(1, inventoryService.findLowStock(10).size());
        }

        @Test
        @DisplayName("Delegates out of stock to repository")
        void outOfStock() {
            when(inventoryRepository.findOutOfStock()).thenReturn(List.of());
            assertTrue(inventoryService.findOutOfStock().isEmpty());
        }
    }
}
