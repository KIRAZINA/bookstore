package com.example.bookstore.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InventoryTest {

    private Inventory createInventory(int available) {
        return new Inventory(1L, available);
    }

    @Nested
    @DisplayName("Construction")
    class Construction {
        @Test
        @DisplayName("Creates inventory with initial quantity")
        void createsWithInitialQuantity() {
            Inventory inv = new Inventory(1L, 50);
            assertEquals(1L, inv.getBookId());
            assertEquals(50, inv.getAvailableQuantity());
            assertEquals(0, inv.getReservedQuantity());
            assertEquals(50, inv.getTotalQuantity());
            assertNotNull(inv.getLastUpdated());
            assertNull(inv.getVersion());
        }

        @Test
        @DisplayName("Default constructor sets no fields")
        void defaultConstructor() {
            Inventory inv = new Inventory();
            assertNull(inv.getBookId());
            assertEquals(0, inv.getAvailableQuantity());
            assertEquals(0, inv.getReservedQuantity());
        }
    }

    @Nested
    @DisplayName("reserve")
    class Reserve {
        @Test
        @DisplayName("Reserves available quantity")
        void reserveSuccess() {
            Inventory inv = createInventory(10);
            inv.reserve(3);
            assertEquals(7, inv.getAvailableQuantity());
            assertEquals(3, inv.getReservedQuantity());
        }

        @Test
        @DisplayName("Throws when quantity is zero")
        void reserveZero_Throws() {
            Inventory inv = createInventory(10);
            assertThrows(IllegalArgumentException.class, () -> inv.reserve(0));
        }

        @Test
        @DisplayName("Throws when quantity is negative")
        void reserveNegative_Throws() {
            Inventory inv = createInventory(10);
            assertThrows(IllegalArgumentException.class, () -> inv.reserve(-1));
        }

        @Test
        @DisplayName("Throws when insufficient available quantity")
        void reserveInsufficient_Throws() {
            Inventory inv = createInventory(5);
            assertThrows(IllegalStateException.class, () -> inv.reserve(10));
        }

        @Test
        @DisplayName("Can reserve all available quantity")
        void reserveAll() {
            Inventory inv = createInventory(10);
            inv.reserve(10);
            assertEquals(0, inv.getAvailableQuantity());
            assertEquals(10, inv.getReservedQuantity());
        }
    }

    @Nested
    @DisplayName("release")
    class Release {
        @Test
        @DisplayName("Releases reserved quantity back to available")
        void releaseSuccess() {
            Inventory inv = createInventory(10);
            inv.reserve(5);
            inv.release(3);
            assertEquals(8, inv.getAvailableQuantity());
            assertEquals(2, inv.getReservedQuantity());
        }

        @Test
        @DisplayName("Throws when quantity exceeds reserved")
        void releaseExcessive_Throws() {
            Inventory inv = createInventory(10);
            inv.reserve(5);
            assertThrows(IllegalStateException.class, () -> inv.release(10));
        }

        @Test
        @DisplayName("Throws when quantity is zero")
        void releaseZero_Throws() {
            Inventory inv = createInventory(10);
            assertThrows(IllegalArgumentException.class, () -> inv.release(0));
        }

        @Test
        @DisplayName("Throws when no reservation exists")
        void releaseFromEmpty_Throws() {
            Inventory inv = createInventory(10);
            assertThrows(IllegalStateException.class, () -> inv.release(1));
        }
    }

    @Nested
    @DisplayName("finalizeReservation")
    class Finalize {
        @Test
        @DisplayName("Finalizes reservation without restoring availability")
        void finalizeSuccess() {
            Inventory inv = createInventory(10);
            inv.reserve(5);
            inv.finalizeReservation(3);
            assertEquals(5, inv.getAvailableQuantity());
            assertEquals(2, inv.getReservedQuantity());
        }

        @Test
        @DisplayName("Throws when quantity exceeds reserved")
        void finalizeExcessive_Throws() {
            Inventory inv = createInventory(10);
            inv.reserve(3);
            assertThrows(IllegalStateException.class, () -> inv.finalizeReservation(5));
        }

        @Test
        @DisplayName("Throws when quantity is zero")
        void finalizeZero_Throws() {
            Inventory inv = createInventory(10);
            assertThrows(IllegalArgumentException.class, () -> inv.finalizeReservation(0));
        }
    }

    @Nested
    @DisplayName("restore")
    class Restore {
        @Test
        @DisplayName("Restores quantity to available")
        void restoreSuccess() {
            Inventory inv = createInventory(10);
            inv.restore(5);
            assertEquals(15, inv.getAvailableQuantity());
            assertNotNull(inv.getLastUpdated());
        }

        @Test
        @DisplayName("Throws when quantity is zero")
        void restoreZero_Throws() {
            Inventory inv = createInventory(10);
            assertThrows(IllegalArgumentException.class, () -> inv.restore(0));
        }

        @Test
        @DisplayName("Throws when quantity is negative")
        void restoreNegative_Throws() {
            Inventory inv = createInventory(10);
            assertThrows(IllegalArgumentException.class, () -> inv.restore(-1));
        }
    }

    @Nested
    @DisplayName("adjustAvailable")
    class Adjust {
        @Test
        @DisplayName("Sets available quantity directly")
        void adjustSuccess() {
            Inventory inv = createInventory(10);
            inv.adjustAvailable(25);
            assertEquals(25, inv.getAvailableQuantity());
        }

        @Test
        @DisplayName("Throws when negative")
        void adjustNegative_Throws() {
            Inventory inv = createInventory(10);
            assertThrows(IllegalArgumentException.class, () -> inv.adjustAvailable(-1));
        }

        @Test
        @DisplayName("Can set to zero")
        void adjustZero() {
            Inventory inv = createInventory(10);
            inv.adjustAvailable(0);
            assertEquals(0, inv.getAvailableQuantity());
        }
    }

    @Nested
    @DisplayName("getTotalQuantity")
    class TotalQuantity {
        @Test
        @DisplayName("Sums available and reserved")
        void totalQuantity() {
            Inventory inv = new Inventory(1L, 20);
            inv.reserve(8);
            assertEquals(20, inv.getTotalQuantity());
        }
    }
}
