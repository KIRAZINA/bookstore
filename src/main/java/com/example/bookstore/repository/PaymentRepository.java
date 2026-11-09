package com.example.bookstore.repository;

import com.example.bookstore.model.Payment;
import com.example.bookstore.model.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByOrderId(Long orderId);

    @Query("select p.order.id from Payment p where p.id = :id")
    Optional<Long> findOrderIdByPaymentId(@Param("id") Long id);

    /**
     * Resolves the payment id for an order without loading the Payment entity into
     * the caller's persistence context. Loading the entity there could cache a stale
     * copy across the REQUIRES_NEW phase fences.
     */
    @Query("select p.id from Payment p where p.order.id = :orderId")
    Optional<Long> findPaymentIdByOrderId(@Param("orderId") Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") Long id);

    Page<Payment> findByPaymentStatus(PaymentStatus status, Pageable pageable);
}
