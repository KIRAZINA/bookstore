package com.example.bookstore.controller;

import com.example.bookstore.model.Cart;
import com.example.bookstore.service.CartService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cart")
public class CartController {
    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Cart> getCart() {
        return ResponseEntity.ok(cartService.getCart());
    }

    @PostMapping("/add")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Cart> addToCart(@RequestParam Long bookId, @RequestParam int quantity) {
        return ResponseEntity.ok(cartService.addToCart(bookId, quantity));
    }

    @PutMapping("/update")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Cart> updateCartItem(@RequestParam Long bookId, @RequestParam int quantity) {
        return ResponseEntity.ok(cartService.updateCartItem(bookId, quantity));
    }

    @DeleteMapping("/remove")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Cart> removeFromCart(@RequestParam Long bookId) {
        return ResponseEntity.ok(cartService.removeFromCart(bookId));
    }

    @DeleteMapping("/clear")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Void> clearCart() {
        cartService.clearCart();
        return ResponseEntity.noContent().build();
    }
}
