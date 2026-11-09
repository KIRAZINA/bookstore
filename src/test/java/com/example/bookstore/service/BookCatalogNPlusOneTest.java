package com.example.bookstore.service;

import com.example.bookstore.base.AbstractControllerTest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {
        "spring.cache.type=none",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
class BookCatalogNPlusOneTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private com.example.bookstore.repository.BookRepository bookRepository;
    @Autowired
    private com.example.bookstore.repository.InventoryRepository inventoryRepository;

    @org.junit.jupiter.api.BeforeEach
    void seed() {
        bookRepository.deleteAll();
        inventoryRepository.deleteAll();
        for (int i = 1; i <= 30; i++) {
            com.example.bookstore.model.Book b = new com.example.bookstore.model.Book();
            b.setTitle("NPlus Book " + i);
            b.setAuthor("Author");
            b.setPrice(new BigDecimal("10.00"));
            b.setCategory("Cat");
            b.setStock(5);
            b = bookRepository.save(b);
            inventoryRepository.save(new com.example.bookstore.model.Inventory(b.getId(), 5));
        }
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
    }

    @Test
    void catalogList_doesNotIssuePerRowInventoryQueries() throws Exception {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        long before = stats.getPrepareStatementCount();
        mockMvc.perform(get("/api/v1/books?page=0&size=20"))
                .andExpect(status().isOk());
        long after = stats.getPrepareStatementCount();
        long delta = after - before;
        org.junit.jupiter.api.Assertions.assertTrue(delta <= 3,
                "expected statement count delta <= 3 but was " + delta);
    }
}