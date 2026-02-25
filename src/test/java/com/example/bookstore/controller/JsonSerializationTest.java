package com.example.bookstore.controller;

import com.example.bookstore.controller.dto.LoginRequest;
import com.example.bookstore.controller.dto.RegisterRequest;
import com.example.bookstore.controller.dto.RegisterResponse;
import com.example.bookstore.model.Book;
import com.example.bookstore.model.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class JsonSerializationTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Nested
    @DisplayName("LoginRequest JSON")
    class LoginRequestSerialization {

        @Test
        @DisplayName("Серіалізація LoginRequest в JSON")
        void serialize() throws Exception {
            LoginRequest request = new LoginRequest();
            request.setUsername("testuser");
            request.setPassword("password123");

            String json = objectMapper.writeValueAsString(request);

            assertTrue(json.contains("\"username\":\"testuser\""));
            assertTrue(json.contains("\"password\":\"password123\""));
        }

        @Test
        @DisplayName("Десеріалізація JSON в LoginRequest")
        void deserialize() throws Exception {
            String json = "{\"username\":\"testuser\",\"password\":\"password123\"}";

            LoginRequest request = objectMapper.readValue(json, LoginRequest.class);

            assertEquals("testuser", request.getUsername());
            assertEquals("password123", request.getPassword());
        }

        @Test
        @DisplayName("Десеріалізація з null username")
        void deserialize_NullUsername() throws Exception {
            String json = "{\"username\":null,\"password\":\"pass\"}";

            LoginRequest request = objectMapper.readValue(json, LoginRequest.class);

            assertNull(request.getUsername());
        }

        @Test
        @DisplayName("Десеріалізація з порожнім username")
        void deserialize_EmptyUsername() throws Exception {
            String json = "{\"username\":\"\",\"password\":\"pass\"}";

            LoginRequest request = objectMapper.readValue(json, LoginRequest.class);

            assertEquals("", request.getUsername());
        }
    }

    @Nested
    @DisplayName("RegisterRequest JSON")
    class RegisterRequestSerialization {

        @Test
        @DisplayName("Серіалізація RegisterRequest в JSON")
        void serialize() throws Exception {
            RegisterRequest request = new RegisterRequest();
            request.setUsername("newuser");
            request.setPassword("password123");
            request.setEmail("test@example.com");

            String json = objectMapper.writeValueAsString(request);

            assertTrue(json.contains("\"username\":\"newuser\""));
            assertTrue(json.contains("\"password\":\"password123\""));
            assertTrue(json.contains("\"email\":\"test@example.com\""));
        }

        @Test
        @DisplayName("Десеріалізація JSON в RegisterRequest")
        void deserialize() throws Exception {
            String json = "{\"username\":\"user\",\"password\":\"pass123\",\"email\":\"mail@test.com\"}";

            RegisterRequest request = objectMapper.readValue(json, RegisterRequest.class);

            assertEquals("user", request.getUsername());
            assertEquals("pass123", request.getPassword());
            assertEquals("mail@test.com", request.getEmail());
        }

        @Test
        @DisplayName("Десеріалізація без email")
        void deserialize_NoEmail() throws Exception {
            String json = "{\"username\":\"user\",\"password\":\"pass123\"}";

            RegisterRequest request = objectMapper.readValue(json, RegisterRequest.class);

            assertEquals("user", request.getUsername());
            assertNull(request.getEmail());
        }
    }

    @Nested
    @DisplayName("RegisterResponse JSON")
    class RegisterResponseSerialization {

        @Test
        @DisplayName("Серіалізація RegisterResponse в JSON")
        void serialize() throws Exception {
            RegisterResponse response = new RegisterResponse(1L, "user", "test@example.com", UserRole.ROLE_USER);

            String json = objectMapper.writeValueAsString(response);

            assertTrue(json.contains("\"id\":1"));
            assertTrue(json.contains("\"username\":\"user\""));
            assertTrue(json.contains("\"email\":\"test@example.com\""));
            assertTrue(json.contains("\"role\":\"ROLE_USER\""));
        }

        @Test
        @DisplayName("Десеріалізація JSON в RegisterResponse")
        void deserialize() throws Exception {
            String json = "{\"id\":5,\"username\":\"test\",\"email\":\"test@test.com\",\"role\":\"ROLE_ADMIN\"}";

            RegisterResponse response = objectMapper.readValue(json, RegisterResponse.class);

            assertEquals(5L, response.getId());
            assertEquals("test", response.getUsername());
            assertEquals("test@test.com", response.getEmail());
            assertEquals(UserRole.ROLE_ADMIN, response.getRole());
        }

        @ParameterizedTest
        @ValueSource(strings = {"ROLE_USER", "ROLE_ADMIN"})
        @DisplayName("Десеріалізація різних ролей")
        void deserialize_DifferentRoles(String role) throws Exception {
            String json = "{\"id\":1,\"username\":\"u\",\"email\":\"e@t.com\",\"role\":\"" + role + "\"}";

            RegisterResponse response = objectMapper.readValue(json, RegisterResponse.class);

            assertEquals(UserRole.valueOf(role), response.getRole());
        }
    }

    @Nested
    @DisplayName("Book JSON")
    class BookSerialization {

        @Test
        @DisplayName("Серіалізація Book в JSON")
        void serialize() throws Exception {
            Book book = new Book();
            book.setId(1L);
            book.setTitle("Java Book");
            book.setAuthor("John Doe");
            book.setPrice(29.99);
            book.setStock(10);
            book.setCategory("Programming");

            String json = objectMapper.writeValueAsString(book);

            assertTrue(json.contains("\"id\":1"));
            assertTrue(json.contains("\"title\":\"Java Book\""));
            assertTrue(json.contains("\"author\":\"John Doe\""));
            assertTrue(json.contains("\"price\":29.99"));
            assertTrue(json.contains("\"stock\":10"));
            assertTrue(json.contains("\"category\":\"Programming\""));
        }

        @Test
        @DisplayName("Десеріалізація JSON в Book")
        void deserialize() throws Exception {
            String json = "{\"id\":2,\"title\":\"Test Book\",\"author\":\"Author\",\"price\":19.99,\"stock\":5,\"category\":\"Fiction\"}";

            Book book = objectMapper.readValue(json, Book.class);

            assertEquals(2L, book.getId());
            assertEquals("Test Book", book.getTitle());
            assertEquals("Author", book.getAuthor());
            assertEquals(19.99, book.getPrice());
            assertEquals(5, book.getStock());
            assertEquals("Fiction", book.getCategory());
        }

        @Test
        @DisplayName("Десеріалізація Book без категорії")
        void deserialize_NoCategory() throws Exception {
            String json = "{\"id\":1,\"title\":\"B\",\"author\":\"A\",\"price\":10.0,\"stock\":1}";

            Book book = objectMapper.readValue(json, Book.class);

            assertNull(book.getCategory());
        }

        @ParameterizedTest
        @ValueSource(doubles = {0.01, 100.0, 999.99})
        @DisplayName("Десеріалізація з різними цінами")
        void deserialize_DifferentPrices(double price) throws Exception {
            String json = "{\"id\":1,\"title\":\"B\",\"author\":\"A\",\"price\":" + price + ",\"stock\":1}";

            Book book = objectMapper.readValue(json, Book.class);

            assertEquals(price, book.getPrice());
        }
    }

    @Nested
    @DisplayName("UserRole JSON")
    class UserRoleSerialization {

        @Test
        @DisplayName("Серіалізація UserRole в JSON")
        void serialize() throws Exception {
            UserRole role = UserRole.ROLE_ADMIN;

            String json = objectMapper.writeValueAsString(role);

            assertEquals("\"ROLE_ADMIN\"", json);
        }

        @Test
        @DisplayName("Десеріалізація JSON в UserRole")
        void deserialize() throws Exception {
            String json = "\"ROLE_USER\"";

            UserRole role = objectMapper.readValue(json, UserRole.class);

            assertEquals(UserRole.ROLE_USER, role);
        }

        @ParameterizedTest
        @ValueSource(strings = {"ROLE_USER", "ROLE_ADMIN"})
        @DisplayName("Десеріалізація всіх значень enum")
        void deserialize_AllValues(String roleName) throws Exception {
            String json = "\"" + roleName + "\"";

            UserRole role = objectMapper.readValue(json, UserRole.class);

            assertEquals(UserRole.valueOf(roleName), role);
        }
    }
}
