package com.example.bookstore.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

@Entity
@Table(name = "inventory")
@Getter
@Setter
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "book_id", nullable = false, unique = true)
    private Long bookId;

    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity;

    @Column(name = "last_updated", nullable = false)
    private LocalDateTime lastUpdated;

    @Version
    private Long version;

    /** No-arg constructor required by JPA. */
    public Inventory() {
    }

    public Inventory(Long bookId, int availableQuantity) {
        this.bookId = bookId;
        this.availableQuantity = availableQuantity;
        this.reservedQuantity = 0;
        this.lastUpdated = LocalDateTime.now();
    }

    public int getTotalQuantity() {
        return availableQuantity + reservedQuantity;
    }

    public void reserve(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Reservation quantity must be positive");
        }
        if (availableQuantity < quantity) {
            throw new IllegalStateException(
                "Insufficient available inventory. Requested: " + quantity +
                ", Available: " + availableQuantity);
        }
        this.availableQuantity -= quantity;
        this.reservedQuantity += quantity;
        this.lastUpdated = LocalDateTime.now();
    }

    public void release(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Release quantity must be positive");
        }
        if (reservedQuantity < quantity) {
            throw new IllegalStateException(
                "Cannot release " + quantity + " units. Only " + reservedQuantity + " reserved.");
        }
        this.reservedQuantity -= quantity;
        this.availableQuantity += quantity;
        this.lastUpdated = LocalDateTime.now();
    }

    public void finalizeReservation(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Finalize quantity must be positive");
        }
        if (reservedQuantity < quantity) {
            throw new IllegalStateException(
                "Cannot finalize " + quantity + " units. Only " + reservedQuantity + " reserved.");
        }
        this.reservedQuantity -= quantity;
        this.lastUpdated = LocalDateTime.now();
    }

    public void adjustAvailable(int newAvailableQuantity) {
        if (newAvailableQuantity < 0) {
            throw new IllegalArgumentException("Available quantity cannot be negative");
        }
        this.availableQuantity = newAvailableQuantity;
        this.lastUpdated = LocalDateTime.now();
    }

    public void restore(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Restore quantity must be positive");
        }
        this.availableQuantity += quantity;
        this.lastUpdated = LocalDateTime.now();
    }
}
