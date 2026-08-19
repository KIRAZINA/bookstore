package com.example.bookstore.repository;

import com.example.bookstore.model.InventoryEventType;
import com.example.bookstore.model.InventoryHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InventoryHistoryRepository extends JpaRepository<InventoryHistory, Long> {

    List<InventoryHistory> findByBookIdOrderByCreatedAtDesc(Long bookId);

    List<InventoryHistory> findByInventoryIdOrderByCreatedAtDesc(Long inventoryId);

    List<InventoryHistory> findByOrderIdOrderByCreatedAtDesc(Long orderId);

    Page<InventoryHistory> findByBookId(Long bookId, Pageable pageable);

    List<InventoryHistory> findByEventType(InventoryEventType eventType);

    List<InventoryHistory> findByOrderIdAndEventType(Long orderId, InventoryEventType eventType);

    boolean existsByBookIdAndOrderIdAndEventType(Long bookId, Long orderId, InventoryEventType eventType);
}
