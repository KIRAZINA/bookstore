package com.example.bookstore.security;

import com.example.bookstore.base.AbstractControllerTest;
import com.example.bookstore.model.AppUser;
import com.example.bookstore.model.Book;
import com.example.bookstore.repository.BookRepository;
import com.example.bookstore.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("Authorization Security Tests")
class AuthorizationSecurityTest extends AbstractControllerTest {

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private UserRepository userRepository;

    private String user1Token;
    private String user2Token;
    private String adminToken;
    private Long testBookId;

    @BeforeEach
    void setUp() throws Exception {
        userRepository.deleteAll();
        bookRepository.deleteAll();

        user1Token = registerUserAndGetToken("authz-user1-" + System.nanoTime());
        user2Token = registerUserAndGetToken("authz-user2-" + System.nanoTime());
        adminToken = registerAdminAndGetToken("authz-admin-" + System.nanoTime());

        Book book = new Book();
        book.setTitle("Authz Test Book");
        book.setAuthor("Authz Author");
        book.setPrice(new BigDecimal("29.99"));
        book.setCategory("Test");
        book.setStock(50);
        testBookId = bookRepository.save(book).getId();
    }

    @Nested
    @DisplayName("Role-Based Access Control")
    class RbacTests {
        @Test
        @DisplayName("USER cannot access ADMIN-only endpoints")
        void userCannotAccessAdminEndpoints() throws Exception {
            mockMvc.perform(post("/api/v1/books")
                            .header("Authorization", "Bearer " + user1Token)
                            .contentType("application/json")
                            .content("""
                                    {"title": "New Book", "author": "Author", "price": 10, "stock": 10}
                                    """))
                    .andExpect(status().isForbidden());

            mockMvc.perform(put("/api/v1/books/" + testBookId)
                            .header("Authorization", "Bearer " + user1Token)
                            .contentType("application/json")
                            .content("""
                                    {"title": "Updated"}
                                    """))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("ADMIN can access all admin endpoints")
        void adminCanAccessAdminEndpoints() throws Exception {
            mockMvc.perform(post("/api/v1/books")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType("application/json")
                            .content("""
                                    {"title": "Admin Created", "author": "Admin", "price": 20, "stock": 10, "category": "Test"}
                                    """))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("USER can access user-only endpoints")
        void userCanAccessUserEndpoints() throws Exception {
            mockMvc.perform(get("/api/v1/cart")
                            .header("Authorization", "Bearer " + user1Token))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("Privilege Escalation Attempts")
    class PrivilegeEscalationTests {
        @Test
        @DisplayName("Vertical privilege escalation fails (USER cannot become ADMIN)")
        void verticalEscalationFails() throws Exception {
            mockMvc.perform(get("/api/v1/admin/inventory")
                            .header("Authorization", "Bearer " + user1Token))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Anonymous cannot escalate privileges")
        void anonymousEscalationFails() throws Exception {
            mockMvc.perform(post("/api/v1/books")
                            .contentType("application/json")
                            .content("""
                                    {"title": "Malicious", "author": "Hacker", "price": 1, "stock": 1}
                                    """))
                    .andExpect(status().isForbidden());
        }
    }
}
