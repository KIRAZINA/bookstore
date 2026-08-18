package com.example.bookstore.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "payment")
@Getter
@Setter
@NoArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "payment_provider", nullable = false)
    private String paymentProvider;

    @Column(name = "payment_method")
    private String paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false)
    private PaymentStatus paymentStatus;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(length = 3)
    private String currency = "USD";

    @Column(name = "transaction_id", unique = true)
    private String transactionId;

    @Column(name = "external_payment_id")
    private String externalPaymentId;

    @Column(name = "payment_reference")
    private String paymentReference;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Version
    private Long version;

    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<PaymentHistory> history = new ArrayList<>();

    public Payment(Order order, String paymentProvider, PaymentStatus paymentStatus, BigDecimal amount) {
        this.order = order;
        this.paymentProvider = paymentProvider;
        this.paymentStatus = paymentStatus;
        this.amount = amount;
    }

    @PrePersist
    public void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public PaymentHistory recordTransition(PaymentStatus newStatus, String changedBy, String reason) {
        if (!this.paymentStatus.canTransitionTo(newStatus)) {
            throw new IllegalStateException(
                "Cannot transition from " + this.paymentStatus + " to " + newStatus);
        }
        PaymentHistory historyEntry = new PaymentHistory();
        historyEntry.setPayment(this);
        historyEntry.setPreviousStatus(this.paymentStatus);
        historyEntry.setNewStatus(newStatus);
        historyEntry.setChangedBy(changedBy);
        historyEntry.setReason(reason);
        historyEntry.setCreatedAt(LocalDateTime.now());
        this.paymentStatus = newStatus;
        this.history.add(historyEntry);
        return historyEntry;
    }

    public List<PaymentHistory> getHistory() { return Collections.unmodifiableList(history); }
}
