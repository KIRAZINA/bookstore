package com.example.bookstore.service;

import com.example.bookstore.config.SecurityUtils;
import com.example.bookstore.model.AppUser;
import com.example.bookstore.model.Book;
import com.example.bookstore.model.Cart;
import com.example.bookstore.model.CartItem;
import com.example.bookstore.repository.BookRepository;
import com.example.bookstore.repository.CartRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class CartService {
    private final CartRepository cartRepository;
    private final BookRepository bookRepository;
    private final SecurityUtils securityUtils;
    private final InventoryService inventoryService;

    public CartService(CartRepository cartRepository,
                       BookRepository bookRepository,
                       SecurityUtils securityUtils,
                       InventoryService inventoryService) {
        this.cartRepository = cartRepository;
        this.bookRepository = bookRepository;
        this.securityUtils = securityUtils;
        this.inventoryService = inventoryService;
    }

    public Cart getCart() {
        AppUser user = securityUtils.getCurrentUser();
        return cartRepository.findByUserId(user.getId())
                .orElseGet(() -> {
                    Cart cart = new Cart();
                    cart.setUser(user);
                    return cartRepository.save(cart);
                });
    }

    @Transactional
    public Cart addToCart(Long bookId, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        Cart cart = getCart();
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("Book not found"));
        int available = inventoryService.getAvailableQuantity(bookId);
        if (available < quantity) {
            throw new IllegalStateException(
                "Not enough stock for book: " + book.getTitle() +
                ". Available: " + available + ", Requested: " + quantity);
        }

        Optional<CartItem> existingItem = cart.getItems().stream()
                .filter(item -> item.getBook().getId().equals(bookId))
                .findFirst();
        if (existingItem.isPresent()) {
            CartItem item = existingItem.get();
            item.setQuantity(item.getQuantity() + quantity);
        } else {
            CartItem item = new CartItem();
            item.setBook(book);
            item.setQuantity(quantity);
            item.setPriceAtAdd(book.getPrice());
            cart.addItem(item);
        }
        return cartRepository.save(cart);
    }

    @Transactional
    public Cart updateCartItem(Long bookId, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        Cart cart = getCart();
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("Book not found"));
        int available = inventoryService.getAvailableQuantity(bookId);
        if (available < quantity) {
            throw new IllegalStateException(
                "Not enough stock for book: " + book.getTitle() +
                ". Available: " + available + ", Requested: " + quantity);
        }

        CartItem item = cart.getItems().stream()
                .filter(i -> i.getBook().getId().equals(bookId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Book not in cart"));
        item.setQuantity(quantity);
        return cartRepository.save(cart);
    }

    @Transactional
    public Cart removeFromCart(Long bookId) {
        Cart cart = getCart();
        cart.getItems().stream()
                .filter(item -> item.getBook().getId().equals(bookId))
                .findFirst()
                .ifPresent(cart::removeItem);
        return cartRepository.save(cart);
    }

    @Transactional
    public void clearCart() {
        Cart cart = getCart();
        cart.clearItems();
        cartRepository.save(cart);
    }
}
