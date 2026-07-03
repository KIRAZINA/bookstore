package com.example.bookstore.service;

import com.example.bookstore.config.InventoryProperties;
import com.example.bookstore.model.Inventory;
import com.example.bookstore.model.InventoryStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InventoryValidatorTest {

    private InventoryProperties properties;
    private InventoryValidator validator;

    @BeforeEach
    void setUp() {
        properties = new InventoryProperties();
        InventoryProperties.Thresholds thresholds = new InventoryProperties.Thresholds();
        thresholds.setCritical(5);
        thresholds.setLow(20);
        properties.setThresholds(thresholds);
        validator = new InventoryValidator(properties);
    }

    private Inventory inventory(int available, int reserved) {
        Inventory inv = new Inventory(1L, available);
        if (reserved > 0) {
            inv.reserve(reserved);
        }
        return inv;
    }

    @Nested
    @DisplayName("validateReservation")
    class ValidateReservation {
        @Test
        @DisplayName("Accepts valid reservation")
        void valid() {
            Inventory inv = inventory(10, 0);
            assertDoesNotThrow(() -> validator.validateReservation(inv, 3));
        }

        @Test
        @DisplayName("Throws when inventory is null")
        void nullInventory() {
            assertThrows(IllegalArgumentException.class,
                    () -> validator.validateReservation(null, 1));
        }

        @Test
        @DisplayName("Throws when quantity is zero")
        void zeroQuantity() {
            Inventory inv = inventory(10, 0);
            assertThrows(IllegalArgumentException.class,
                    () -> validator.validateReservation(inv, 0));
        }

        @Test
        @DisplayName("Throws when quantity is negative")
        void negativeQuantity() {
            Inventory inv = inventory(10, 0);
            assertThrows(IllegalArgumentException.class,
                    () -> validator.validateReservation(inv, -1));
        }

        @Test
        @DisplayName("Throws when insufficient stock")
        void insufficientStock() {
            Inventory inv = inventory(5, 0);
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> validator.validateReservation(inv, 10));
            assertTrue(ex.getMessage().contains("Insufficient stock"));
        }

        @Test
        @DisplayName("Accepts exact available quantity")
        void exactQuantity() {
            Inventory inv = inventory(10, 0);
            assertDoesNotThrow(() -> validator.validateReservation(inv, 10));
        }
    }

    @Nested
    @DisplayName("validateRelease")
    class ValidateRelease {
        @Test
        @DisplayName("Accepts valid release")
        void valid() {
            Inventory inv = inventory(10, 5);
            assertDoesNotThrow(() -> validator.validateRelease(inv, 3));
        }

        @Test
        @DisplayName("Throws when inventory is null")
        void nullInventory() {
            assertThrows(IllegalArgumentException.class,
                    () -> validator.validateRelease(null, 1));
        }

        @Test
        @DisplayName("Throws when quantity is zero")
        void zeroQuantity() {
            Inventory inv = inventory(10, 5);
            assertThrows(IllegalArgumentException.class,
                    () -> validator.validateRelease(inv, 0));
        }

        @Test
        @DisplayName("Throws when release exceeds reserved")
        void exceedsReserved() {
            Inventory inv = inventory(10, 3);
            assertThrows(IllegalStateException.class,
                    () -> validator.validateRelease(inv, 10));
        }
    }

    @Nested
    @DisplayName("validateFinalization")
    class ValidateFinalization {
        @Test
        @DisplayName("Accepts valid finalization")
        void valid() {
            Inventory inv = inventory(10, 5);
            assertDoesNotThrow(() -> validator.validateFinalization(inv, 3));
        }

        @Test
        @DisplayName("Throws when inventory is null")
        void nullInventory() {
            assertThrows(IllegalArgumentException.class,
                    () -> validator.validateFinalization(null, 1));
        }

        @Test
        @DisplayName("Throws when quantity is zero")
        void zeroQuantity() {
            Inventory inv = inventory(10, 5);
            assertThrows(IllegalArgumentException.class,
                    () -> validator.validateFinalization(inv, 0));
        }

        @Test
        @DisplayName("Throws when finalization exceeds reserved")
        void exceedsReserved() {
            Inventory inv = inventory(10, 3);
            assertThrows(IllegalStateException.class,
                    () -> validator.validateFinalization(inv, 10));
        }
    }

    @Nested
    @DisplayName("determineStatus")
    class DetermineStatus {
        @Test
        @DisplayName("OUT_OF_STOCK when zero")
        void outOfStock() {
            assertEquals(InventoryStatus.OUT_OF_STOCK, validator.determineStatus(0));
        }

        @Test
        @DisplayName("OUT_OF_STOCK when negative")
        void outOfStockNegative() {
            assertEquals(InventoryStatus.OUT_OF_STOCK, validator.determineStatus(-1));
        }

        @Test
        @DisplayName("CRITICAL_STOCK at critical threshold")
        void criticalStock() {
            assertEquals(InventoryStatus.CRITICAL_STOCK, validator.determineStatus(5));
        }

        @Test
        @DisplayName("LOW_STOCK at low threshold")
        void lowStock() {
            assertEquals(InventoryStatus.LOW_STOCK, validator.determineStatus(20));
        }

        @Test
        @DisplayName("LOW_STOCK between critical and low")
        void lowStockBetween() {
            assertEquals(InventoryStatus.LOW_STOCK, validator.determineStatus(10));
        }

        @Test
        @DisplayName("AVAILABLE above low threshold")
        void available() {
            assertEquals(InventoryStatus.AVAILABLE, validator.determineStatus(100));
        }
    }

    @Nested
    @DisplayName("validateAdjustment")
    class ValidateAdjustment {
        @Test
        @DisplayName("Accepts non-negative quantity")
        void valid() {
            assertDoesNotThrow(() -> validator.validateAdjustment(10));
        }

        @Test
        @DisplayName("Accepts zero")
        void zero() {
            assertDoesNotThrow(() -> validator.validateAdjustment(0));
        }

        @Test
        @DisplayName("Throws when negative")
        void negative() {
            assertThrows(IllegalArgumentException.class,
                    () -> validator.validateAdjustment(-1));
        }
    }
}
