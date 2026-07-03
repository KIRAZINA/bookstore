package com.example.bookstore.chaos;

import com.example.bookstore.model.Book;
import com.example.bookstore.repository.BookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PostgreSQL chaos tests — failure handling and recovery")
class PostgresChaosTest extends AbstractChaosTest {

    @Autowired
    private BookRepository bookRepository;

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        bookRepository.deleteAll();
    }

    @Test
    @DisplayName("Should persist data when PostgreSQL is available")
    void shouldPersistBookWhenPostgresIsAvailable() {
        Book book = new Book();
        book.setTitle("Test Book");
        book.setAuthor("Test Author");
        book.setPrice(new BigDecimal("19.99"));
        book.setCategory("Fiction");
        book.setStock(100);

        Book saved = bookRepository.save(book);

        assertNotNull(saved);
        assertNotNull(saved.getId());
        assertEquals("Test Book", saved.getTitle());
    }

    @Test
    @DisplayName("Health endpoint shows database status")
    void healthEndpointShouldShowDatabaseStatus() {
        RestTemplate restTemplate = new RestTemplate();
        String healthUrl = "http://localhost:" + port + "/actuator/health";

        ResponseEntity<Map> response = restTemplate.getForEntity(healthUrl, Map.class);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
    }
}
