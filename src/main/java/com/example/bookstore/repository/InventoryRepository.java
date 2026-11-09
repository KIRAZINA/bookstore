package com.example.bookstore.repository;

import com.example.bookstore.model.Inventory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    Optional<Inventory> findByBookId(Long bookId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Inventory i WHERE i.bookId = :bookId")
    Optional<Inventory> findByBookIdWithLock(Long bookId);

    @Query("SELECT i FROM Inventory i WHERE i.availableQuantity <= :threshold ORDER BY i.availableQuantity ASC")
    List<Inventory> findLowStock(int threshold);

    @Query("SELECT i FROM Inventory i WHERE i.availableQuantity = 0")
    List<Inventory> findOutOfStock();

    List<Inventory> findByBookIdIn(Collection<Long> bookIds);

    boolean existsByBookId(Long bookId);
}
