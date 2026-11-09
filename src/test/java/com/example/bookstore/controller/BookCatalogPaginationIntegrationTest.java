package com.example.bookstore.controller;

import com.example.bookstore.base.AbstractControllerTest;
import com.example.bookstore.model.Book;
import com.example.bookstore.model.Inventory;
import com.example.bookstore.repository.BookRepository;
import com.example.bookstore.repository.InventoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BookCatalogPaginationIntegrationTest extends AbstractControllerTest {

    @Autowired
    private BookRepository bookRepository;
    @Autowired
    private InventoryRepository inventoryRepository;

    @BeforeEach
    void setUp() {
        bookRepository.deleteAll();
        inventoryRepository.deleteAll();
        for (int i = 1; i <= 30; i++) {
            Book b = new Book();
            b.setTitle("Book " + i);
            b.setAuthor("Author");
            b.setPrice(new BigDecimal("10.00"));
            b.setCategory(i % 2 == 0 ? "Fiction" : "Tech");
            b.setStock(5);
            b = bookRepository.save(b);
            inventoryRepository.save(new Inventory(b.getId(), 5));
        }
    }

    @Test
    void getAllBooks_withoutParams_returnsBoundedArray() throws Exception {
        mockMvc.perform(get("/api/v1/books"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Page", "0"))
                .andExpect(header().string("X-Page-Size", "20"))
                .andExpect(header().string("X-Truncated", "true"))
                .andExpect(jsonPath("$.length()").value(20));
    }

    @Test
    void getAllBooks_withOversizedRequest_clampsToMaxSize() throws Exception {
        mockMvc.perform(get("/api/v1/books?page=0&size=1000"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Page-Size", "100"))
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.lessThanOrEqualTo(100)));
    }

    @Test
    void getAllBooks_secondPage_returnsNextBoundedSlice() throws Exception {
        mockMvc.perform(get("/api/v1/books?page=1&size=10"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Page", "1"))
                .andExpect(jsonPath("$.length()").value(10));
    }

    @Test
    void getBooksByCategory_returnsBoundedArray() throws Exception {
        mockMvc.perform(get("/api/v1/books/category?category=Fiction&size=5"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Truncated", "true"))
                .andExpect(jsonPath("$.length()").value(5));
    }

    @Test
    void searchBooks_returnsBoundedArray() throws Exception {
        mockMvc.perform(get("/api/v1/books/search?search=Book"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Truncated", "true"))
                .andExpect(jsonPath("$.length()").value(20));
    }

    @Test
    void searchBooks_withLongInput_isRejected() throws Exception {
        String longTerm = "a".repeat(129);
        mockMvc.perform(get("/api/v1/books/search?search=" + longTerm))
                .andExpect(status().isBadRequest());
    }

    @Test
    void negativePage_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/books?page=-1&size=10"))
                .andExpect(status().isBadRequest());
    }
}