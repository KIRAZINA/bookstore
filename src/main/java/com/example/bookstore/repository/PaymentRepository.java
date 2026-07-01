package com.example.bookstore.repository;

import com.example.bookstore.model.Payment;
import com.example.bookstore.model.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByOrderId(Long orderId);
    Page<Payment> findByPaymentStatus(PaymentStatus status, Pageable pageable);
}
