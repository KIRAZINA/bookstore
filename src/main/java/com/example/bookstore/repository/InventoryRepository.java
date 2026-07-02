package com.example.bookstore.repository;

import com.example.bookstore.model.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    Optional<Inventory> findByBookId(Long bookId);

    @Query("SELECT i FROM Inventory i WHERE i.availableQuantity <= :threshold ORDER BY i.availableQuantity ASC")
    List<Inventory> findLowStock(int threshold);

    @Query("SELECT i FROM Inventory i WHERE i.availableQuantity = 0")
    List<Inventory> findOutOfStock();

    boolean existsByBookId(Long bookId);
}
