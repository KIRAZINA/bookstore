package com.example.bookstore.controller;

import com.example.bookstore.controller.dto.AddToCartRequest;
import com.example.bookstore.controller.dto.CartItemResponse;
import com.example.bookstore.controller.dto.CartResponse;
import com.example.bookstore.controller.dto.UpdateCartItemRequest;
import com.example.bookstore.model.Cart;
import com.example.bookstore.model.CartItem;
import com.example.bookstore.service.CartService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/cart")
public class CartController {
    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<CartResponse> getCart() {
        return ResponseEntity.ok(toCartResponse(cartService.getCart()));
    }

    @PostMapping("/items")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<CartResponse> addToCart(@Valid @RequestBody AddToCartRequest request) {
        return ResponseEntity.ok(toCartResponse(cartService.addToCart(request.getBookId(), request.getQuantity())));
    }

    @PutMapping("/items/{bookId}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<CartResponse> updateCartItem(@PathVariable Long bookId, @Valid @RequestBody UpdateCartItemRequest request) {
        return ResponseEntity.ok(toCartResponse(cartService.updateCartItem(bookId, request.getQuantity())));
    }

    @DeleteMapping("/items/{bookId}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<CartResponse> removeFromCart(@PathVariable Long bookId) {
        return ResponseEntity.ok(toCartResponse(cartService.removeFromCart(bookId)));
    }

    @DeleteMapping
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Void> clearCart() {
        cartService.clearCart();
        return ResponseEntity.noContent().build();
    }

    private CartResponse toCartResponse(Cart cart) {
        List<CartItemResponse> items = cart.getItems().stream()
                .map(this::toCartItemResponse)
                .collect(Collectors.toList());
        return new CartResponse(cart.getId(), cart.getUser().getId(), items);
    }

    private CartItemResponse toCartItemResponse(CartItem item) {
        return new CartItemResponse(
                item.getId(),
                item.getBook().getId(),
                item.getBook().getTitle(),
                item.getBook().getAuthor(),
                item.getQuantity(),
                item.getPriceAtAdd(),
                item.getAddedAt()
        );
    }
}
