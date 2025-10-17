package com.example.bookstore.repository;

import com.example.bookstore.model.AppUser;
import com.example.bookstore.model.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Page<Order> findByUser(AppUser user, Pageable pageable);  // Пагинация заказов пользователя
}