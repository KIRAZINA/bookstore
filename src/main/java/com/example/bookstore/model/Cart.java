package com.example.bookstore.model;

import jakarta.persistence.*;
import lombok.*;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@Setter
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Cart {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @OneToOne
    @JoinColumn(name = "user_id")
    @ToString.Exclude
    private AppUser user;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "cart_id")
    private List<CartItem> items = new ArrayList<>();

    public List<CartItem> getItems() {
        return new ArrayList<>(items);
    }

    public void setItems(List<CartItem> items) {
        this.items = new ArrayList<>(items);
    }

    // Custom getUser and setUser methods removed - using standard Lombok getters/setters
    // Fields id and user already have generated getter/setter from @Getter @Setter
}