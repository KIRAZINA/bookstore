package com.example.bookstore.controller;

import com.example.bookstore.base.AbstractControllerTest;
import com.example.bookstore.model.Book;
import com.example.bookstore.repository.BookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CartControllerIntegrationTest extends AbstractControllerTest {

    @Autowired
    private BookRepository bookRepository;

    private String token;
    private Book availableBook;

    @BeforeEach
    void setUp() throws Exception {
        bookRepository.deleteAll();
        token = registerUserAndGetToken("cart-user-" + System.nanoTime());

        availableBook = new Book();
        availableBook.setTitle("Cart Test Book");
        availableBook.setAuthor("Author");
        availableBook.setPrice(new BigDecimal("9.99"));
        availableBook.setCategory("Test");
        availableBook.setStock(10);
        availableBook = bookRepository.save(availableBook);
    }

    @Test
    @DisplayName("GET /api/cart returns empty cart for new user")
    void getCart_ReturnsEmptyCart() throws Exception {
        mockMvc.perform(get("/api/cart")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    @DisplayName("POST /api/cart/items adds item to cart")
    void addToCart_AddsItem() throws Exception {
        String request = """
                {"bookId": %d, "quantity": 2}
                """.formatted(availableBook.getId());

        mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].bookId").value(availableBook.getId()))
                .andExpect(jsonPath("$.items[0].quantity").value(2));
    }

    @Test
    @DisplayName("PUT /api/cart/items/{bookId} updates item quantity")
    void updateCartItem_UpdatesQuantity() throws Exception {
        String addRequest = """
                {"bookId": %d, "quantity": 1}
                """.formatted(availableBook.getId());

        mockMvc.perform(post("/api/cart/items")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(addRequest));

        String updateRequest = """
                {"quantity": 5}
                """;

        mockMvc.perform(put("/api/cart/items/" + availableBook.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(updateRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(5));
    }

    @Test
    @DisplayName("DELETE /api/cart/items/{bookId} removes item")
    void removeFromCart_RemovesItem() throws Exception {
        String addRequest = """
                {"bookId": %d, "quantity": 1}
                """.formatted(availableBook.getId());

        mockMvc.perform(post("/api/cart/items")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(addRequest));

        mockMvc.perform(delete("/api/cart/items/" + availableBook.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    @DisplayName("DELETE /api/cart clears all items")
    void clearCart_RemovesAllItems() throws Exception {
        String addRequest = """
                {"bookId": %d, "quantity": 1}
                """.formatted(availableBook.getId());

        mockMvc.perform(post("/api/cart/items")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(addRequest));

        mockMvc.perform(delete("/api/cart")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }
}
