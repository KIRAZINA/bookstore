package com.example.bookstore.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Positive;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "order_items")
@Getter
@Setter
@ToString
@EqualsAndHashCode(of = "id")
public class OrderItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    @ManyToOne
    @JoinColumn(name = "order_id", nullable = false)
    @ToString.Exclude
    private Order order;

    @Column(nullable = false)
    @Positive(message = "Quantity must be positive")
    private int quantity;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal subtotal;

    public void recalculateSubtotal() {
        if (unitPrice != null && quantity > 0) {
            subtotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
        }
    }

    @PrePersist
    public void prePersist() {
        recalculateSubtotal();
    }
}