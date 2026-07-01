package com.example.bookstore.repository;

import com.example.bookstore.model.PaymentHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentHistoryRepository extends JpaRepository<PaymentHistory, Long> {
    List<PaymentHistory> findByPaymentIdOrderByCreatedAtAsc(Long paymentId);
}
