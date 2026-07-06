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

class BookControllerIntegrationTest extends AbstractControllerTest {

    @Autowired
    private BookRepository bookRepository;

    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() throws Exception {
        bookRepository.deleteAll();
        adminToken = registerAdminAndGetToken("book-admin-" + System.nanoTime());
        userToken = registerUserAndGetToken("book-user-" + System.nanoTime());
    }

    @Test
    @DisplayName("GET /api/books returns all books")
    void getAllBooks_ReturnsBooks() throws Exception {
        Book book = new Book();
        book.setTitle("REST Book");
        book.setAuthor("REST Author");
        book.setPrice(new BigDecimal("14.99"));
        book.setCategory("REST");
        book.setStock(7);
        bookRepository.save(book);

        mockMvc.perform(get("/api/v1/books"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title").value("REST Book"))
                .andExpect(jsonPath("$[0].author").value("REST Author"))
                .andExpect(jsonPath("$[0].price").value(14.99));
    }

    @Test
    @DisplayName("GET /api/books/category filters by category")
    void getBooksByCategory_Filters() throws Exception {
        Book fiction = new Book();
        fiction.setTitle("Fiction Book");
        fiction.setAuthor("Author");
        fiction.setPrice(new BigDecimal("10.00"));
        fiction.setCategory("Fiction");
        fiction.setStock(5);
        bookRepository.save(fiction);

        Book science = new Book();
        science.setTitle("Science Book");
        science.setAuthor("Author");
        science.setPrice(new BigDecimal("20.00"));
        science.setCategory("Science");
        science.setStock(3);
        bookRepository.save(science);

        mockMvc.perform(get("/api/v1/books/category").param("category", "Fiction"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title").value("Fiction Book"));

        mockMvc.perform(get("/api/v1/books/category").param("category", "NonExistent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("POST /api/books with admin creates book")
    void addBook_AsAdmin_CreatesBook() throws Exception {
        String request = """
                {"title": "New Book", "author": "New Author", "price": 25.00, "category": "New", "stock": 10}
                """;

        mockMvc.perform(post("/api/v1/books")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("New Book"))
                .andExpect(jsonPath("$.id").isNumber());
    }

    @Test
    @DisplayName("POST /api/books without admin returns 403")
    void addBook_AsUser_ReturnsForbidden() throws Exception {
        String request = """
                {"title": "New Book", "author": "Author", "price": 10.00, "category": "Test", "stock": 5}
                """;

        mockMvc.perform(post("/api/v1/books")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType("application/json")
                        .content(request))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/books with invalid data returns 400")
    void addBook_InvalidData_ReturnsBadRequest() throws Exception {
        String request = """
                {"title": "", "author": "", "price": -1, "stock": -5}
                """;

        mockMvc.perform(post("/api/v1/books")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(request))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("DELETE /api/books/{id} with admin removes book")
    void deleteBook_AsAdmin_RemovesBook() throws Exception {
        Book book = new Book();
        book.setTitle("To Delete");
        book.setAuthor("Author");
        book.setPrice(new BigDecimal("5.00"));
        book.setCategory("Temp");
        book.setStock(1);
        book = bookRepository.save(book);

        mockMvc.perform(delete("/api/v1/books/" + book.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
    }
}
