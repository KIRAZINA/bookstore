package com.example.bookstore.service;

import com.example.bookstore.base.AbstractControllerTest;
import com.example.bookstore.controller.dto.BookRequest;
import com.example.bookstore.model.Book;
import com.example.bookstore.repository.BookRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BookMutationCacheEvictionCommandShapeTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RedisConnectionFactory redisConnectionFactory;
    @Autowired
    private BookRepository bookRepository;

    private long keysCalls(RedisConnection conn) {
        try {
            java.util.Properties info = conn.info("commandstats");
            String raw = info.toString();
            for (String line : raw.split(",")) {
                if (line.contains("cmdstat_keys")) {
                    // line like cmdstat_keys=calls=1,...
                    int idx = line.indexOf("calls=");
                    if (idx >= 0) {
                        String rest = line.substring(idx + 6);
                        int end = rest.indexOf(",");
                        if (end < 0) end = rest.length();
                        // also handle trailing }
                        String num = rest.substring(0, end).replaceAll("[^0-9]", "");
                        if (!num.isEmpty()) return Long.parseLong(num);
                    }
                }
            }
        } catch (Exception e) {
        }
        return -1;
    }

    @Test
    void addBook_doesNotUseKeysCommand() throws Exception {
        String username = "admin-" + System.nanoTime();
        createTestUser(username, com.example.bookstore.model.UserRole.ROLE_ADMIN);
        String token = loginAndGetToken(username);

        RedisConnection conn = redisConnectionFactory.getConnection();
        long before = keysCalls(conn);

        String body = """
                {"title":"CmdShape Book","author":"A","price":10.00,"category":"Test"}
                """;
        mockMvc.perform(post("/api/v1/books")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        long after = keysCalls(conn);
        conn.close();
        org.junit.jupiter.api.Assertions.assertTrue(after - before == 0,
                "expected cmdstat_keys increment 0 but was " + (after - before));
    }
}