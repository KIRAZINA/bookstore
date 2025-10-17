package com.example.bookstore.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Positive;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;

@Entity
@Table(name = "orders")
@Getter
@Setter
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private AppUser user;

    @Column(name = "created_at", nullable = false)
    @CreatedDate
    private LocalDateTime createdAt;

    @Column(name = "total_price", nullable = false)
    @Positive(message = "Total price must be positive")
    private double totalPrice;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "order_id")
    private List<OrderItem> items = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public void addItem(OrderItem item) {
        items.add(item);
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public void setItems(List<OrderItem> items) {
        this.items = new ArrayList<>(items);
    }

    public AppUser getUser() {
        if (user == null) return null;
        AppUser copy = new AppUser();
        copy.setId(user.getId());
        copy.setUsername(user.getUsername());
        copy.setEmail(user.getEmail());

        return copy;
    }

    public void setUser(AppUser user) {
        if (user == null) {
            this.user = null;
            return;
        }
        this.user = new AppUser();
        this.user.setId(user.getId());
        this.user.setUsername(user.getUsername());
        this.user.setEmail(user.getEmail());
    }

}