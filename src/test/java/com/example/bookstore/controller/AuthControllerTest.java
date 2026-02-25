package com.example.bookstore.controller;

import com.example.bookstore.controller.dto.LoginRequest;
import com.example.bookstore.controller.dto.RegisterRequest;
import com.example.bookstore.model.AppUser;
import com.example.bookstore.model.UserRole;
import com.example.bookstore.repository.UserRepository;
import com.example.bookstore.service.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @Nested
    @DisplayName("POST /api/auth/register")
    class RegisterTests {

        @Test
        @DisplayName("Успішна реєстрація нового користувача")
        void register_Success() throws Exception {
            RegisterRequest request = new RegisterRequest();
            request.setUsername("testuser");
            request.setPassword("password123");
            request.setEmail("test@example.com");

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").exists())
                    .andExpect(jsonPath("$.username").value("testuser"))
                    .andExpect(jsonPath("$.email").value("test@example.com"))
                    .andExpect(jsonPath("$.role").value("ROLE_USER"));

            assertTrue(userRepository.findByUsername("testuser").isPresent());
        }

        @Test
        @DisplayName("Реєстрація - користувач вже існує")
        void register_UserAlreadyExists() throws Exception {
            AppUser existingUser = new AppUser();
            existingUser.setUsername("existinguser");
            existingUser.setPassword(passwordEncoder.encode("password123"));
            existingUser.setEmail("existing@example.com");
            existingUser.setRole(UserRole.ROLE_USER);
            userRepository.save(existingUser);

            RegisterRequest request = new RegisterRequest();
            request.setUsername("existinguser");
            request.setPassword("password123");
            request.setEmail("new@example.com");

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("User with this username already exists"));
        }

        @Test
        @DisplayName("Реєстрація - порожній username")
        void register_BlankUsername() throws Exception {
            RegisterRequest request = new RegisterRequest();
            request.setUsername("");
            request.setPassword("password123");
            request.setEmail("test@example.com");

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Реєстрація - невалідний email")
        void register_InvalidEmail() throws Exception {
            RegisterRequest request = new RegisterRequest();
            request.setUsername("testuser");
            request.setPassword("password123");
            request.setEmail("not-an-email");

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Реєстрація - пароль менше 6 символів")
        void register_PasswordTooShort() throws Exception {
            RegisterRequest request = new RegisterRequest();
            request.setUsername("testuser");
            request.setPassword("12345");
            request.setEmail("test@example.com");

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.password").exists());
        }

        @Test
        @DisplayName("Реєстрація - без email (nullable)")
        void register_NoEmail() throws Exception {
            RegisterRequest request = new RegisterRequest();
            request.setUsername("testuser");
            request.setPassword("password123");
            request.setEmail(null);

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").doesNotExist());
        }
    }

    @Nested
    @DisplayName("POST /api/auth/login")
    class LoginTests {

        @BeforeEach
        void setUpUser() {
            userRepository.deleteAll();
            AppUser user = new AppUser();
            user.setUsername("logintest");
            user.setPassword(passwordEncoder.encode("password123"));
            user.setEmail("login@example.com");
            user.setRole(UserRole.ROLE_USER);
            userRepository.save(user);
        }

        @Test
        @DisplayName("Успішний логін")
        void login_Success() throws Exception {
            LoginRequest request = new LoginRequest();
            request.setUsername("logintest");
            request.setPassword("password123");

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isString())
                    .andExpect(jsonPath("$").isNotEmpty());
        }

        @Test
        @DisplayName("Логін - невірний пароль")
        void login_WrongPassword() throws Exception {
            LoginRequest request = new LoginRequest();
            request.setUsername("logintest");
            request.setPassword("wrongpassword");

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string("Invalid credentials"));
        }

        @Test
        @DisplayName("Логін - користувач не існує")
        void login_UserNotFound() throws Exception {
            LoginRequest request = new LoginRequest();
            request.setUsername("nonexistent");
            request.setPassword("password123");

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string("Invalid credentials"));
        }

        @Test
        @DisplayName("Логін - порожній username")
        void login_BlankUsername() throws Exception {
            LoginRequest request = new LoginRequest();
            request.setUsername("");
            request.setPassword("password123");

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Логін - порожній пароль")
        void login_BlankPassword() throws Exception {
            LoginRequest request = new LoginRequest();
            request.setUsername("logintest");
            request.setPassword("");

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Логін - тіло запиту пусте")
        void login_EmptyBody() throws Exception {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("JWT Token Tests")
    class JwtTokenTests {

        @Test
        @DisplayName("Валідний JWT токен містить username")
        void jwtToken_ContainsUsername() throws Exception {
            AppUser user = new AppUser();
            user.setUsername("jwttest");
            user.setPassword(passwordEncoder.encode("password123"));
            user.setRole(UserRole.ROLE_USER);
            userRepository.save(user);

            LoginRequest request = new LoginRequest();
            request.setUsername("jwttest");
            request.setPassword("password123");

            String token = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            String extractedUsername = jwtService.extractUsername(token);
            assertEquals("jwttest", extractedUsername);
        }

        @Test
        @DisplayName("Токен прострочений або невалідний")
        void jwtToken_Invalid() throws Exception {
            mockMvc.perform(post("/api/auth/login")
                            .header("Authorization", "Bearer invalidtoken")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());
        }
    }
}
