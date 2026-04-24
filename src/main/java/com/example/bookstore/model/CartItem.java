package com.example.bookstore.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Positive;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "cart_items")
@Getter
@Setter
@ToString
@EqualsAndHashCode(of = "id")
public class CartItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    @ManyToOne
    @JoinColumn(name = "cart_id")
    @ToString.Exclude
    private Cart cart;

    @Column(nullable = false)
    @Positive(message = "Quantity must be positive")
    private int quantity;

    @Column(name = "price_at_add", nullable = false, precision = 19, scale = 4)
    private BigDecimal priceAtAdd;

    @Column(name = "added_at", nullable = false)
    private LocalDateTime addedAt;

    @PrePersist
    public void prePersist() {
        if (priceAtAdd == null && book != null) {
            priceAtAdd = book.getPrice();
        }
        if (addedAt == null) {
            addedAt = LocalDateTime.now();
        }
    }
}