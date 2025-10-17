package com.example.bookstore.controller;

import com.example.bookstore.model.Cart;
import com.example.bookstore.service.CartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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

    @Operation(summary = "Get current user's cart")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cart retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @GetMapping
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Cart> getCart() {
        return ResponseEntity.ok(cartService.getCart());
    }

    @Operation(summary = "Add book to cart")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Book added to cart"),
            @ApiResponse(responseCode = "400", description = "Invalid book ID or quantity"),
            @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @PostMapping("/add")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Cart> addToCart(@RequestParam Long bookId, @RequestParam int quantity) {
        return ResponseEntity.ok(cartService.addToCart(bookId, quantity));
    }

    @Operation(summary = "Update book quantity in cart")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cart updated successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid book ID or quantity"),
            @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @PutMapping("/update")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Cart> updateCartItem(@RequestParam Long bookId, @RequestParam int quantity) {
        return ResponseEntity.ok(cartService.updateCartItem(bookId, quantity));
    }

    @Operation(summary = "Remove book from cart")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Book removed from cart"),
            @ApiResponse(responseCode = "400", description = "Invalid book ID"),
            @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @DeleteMapping("/remove")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Cart> removeFromCart(@RequestParam Long bookId) {
        return ResponseEntity.ok(cartService.removeFromCart(bookId));
    }

    @Operation(summary = "Clear cart")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Cart cleared successfully"),
            @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @DeleteMapping("/clear")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Void> clearCart() {
        cartService.clearCart();
        return ResponseEntity.noContent().build();
    }
}