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
        return Collections.unmodifiableList(items);
    }

    public void setItems(List<CartItem> items) {
        this.items = new ArrayList<>(items);
    }

    // В Cart.java
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