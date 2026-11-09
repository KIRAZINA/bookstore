package com.example.bookstore.service;

import com.example.bookstore.base.AbstractControllerTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BookSearchQueryTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void searchInputLongerThan128Rejected() throws Exception {
        String longTerm = "a".repeat(129);
        mockMvc.perform(get("/api/v1/books/search?search=" + longTerm))
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchResultsBounded() throws Exception {
        mockMvc.perform(get("/api/v1/books/search?search=Book&size=5"))
                .andExpect(status().isOk());
    }
}