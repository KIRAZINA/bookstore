package com.example.bookstore.service;

import com.example.bookstore.config.InventoryProperties;
import com.example.bookstore.model.Inventory;
import com.example.bookstore.model.InventoryStatus;
import org.springframework.stereotype.Component;

@Component
public class InventoryValidator {

    private final InventoryProperties properties;

    public InventoryValidator(InventoryProperties properties) {
        this.properties = properties;
    }

    public void validateReservation(Inventory inventory, int requestedQuantity) {
        if (inventory == null) {
            throw new IllegalArgumentException("Inventory not found for the specified book");
        }
        if (requestedQuantity <= 0) {
            throw new IllegalArgumentException("Reservation quantity must be positive, requested: " + requestedQuantity);
        }
        if (inventory.getAvailableQuantity() < requestedQuantity) {
            throw new IllegalStateException(
                "Insufficient stock. Requested: " + requestedQuantity +
                ", Available: " + inventory.getAvailableQuantity());
        }
    }

    public void validateRelease(Inventory inventory, int quantity) {
        if (inventory == null) {
            throw new IllegalArgumentException("Inventory not found");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Release quantity must be positive");
        }
        if (inventory.getReservedQuantity() < quantity) {
            throw new IllegalStateException(
                "Cannot release " + quantity + " units. Reserved: " + inventory.getReservedQuantity());
        }
    }

    public void validateFinalization(Inventory inventory, int quantity) {
        if (inventory == null) {
            throw new IllegalArgumentException("Inventory not found");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Finalization quantity must be positive");
        }
        if (inventory.getReservedQuantity() < quantity) {
            throw new IllegalStateException(
                "Cannot finalize " + quantity + " units. Reserved: " + inventory.getReservedQuantity());
        }
    }

    public void validateAdjustment(int newQuantity) {
        if (newQuantity < 0) {
            throw new IllegalArgumentException("Available quantity cannot be negative, attempted: " + newQuantity);
        }
    }

    public InventoryStatus determineStatus(int availableQuantity) {
        if (availableQuantity <= 0) {
            return InventoryStatus.OUT_OF_STOCK;
        }
        if (availableQuantity <= properties.getThresholds().getCritical()) {
            return InventoryStatus.CRITICAL_STOCK;
        }
        if (availableQuantity <= properties.getThresholds().getLow()) {
            return InventoryStatus.LOW_STOCK;
        }
        return InventoryStatus.AVAILABLE;
    }
}
