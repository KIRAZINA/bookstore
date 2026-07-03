package com.example.bookstore.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InventoryStatusTest {

    @Test
    @DisplayName("OUT_OF_STOCK is defined")
    void outOfStock() {
        assertEquals("OUT_OF_STOCK", InventoryStatus.OUT_OF_STOCK.name());
    }

    @Test
    @DisplayName("CRITICAL_STOCK is defined")
    void criticalStock() {
        assertEquals("CRITICAL_STOCK", InventoryStatus.CRITICAL_STOCK.name());
    }

    @Test
    @DisplayName("LOW_STOCK is defined")
    void lowStock() {
        assertEquals("LOW_STOCK", InventoryStatus.LOW_STOCK.name());
    }

    @Test
    @DisplayName("AVAILABLE is defined")
    void available() {
        assertEquals("AVAILABLE", InventoryStatus.AVAILABLE.name());
    }

    @Test
    @DisplayName("All values are present")
    void allValues() {
        InventoryStatus[] values = InventoryStatus.values();
        assertEquals(4, values.length);
    }
}
