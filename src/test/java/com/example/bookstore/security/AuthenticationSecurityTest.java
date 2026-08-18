package com.example.bookstore.security;

import com.example.bookstore.base.AbstractControllerTest;
import com.example.bookstore.config.jwt.JwtUtil;
import com.example.bookstore.model.UserRole;
import com.example.bookstore.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("Authentication Security Tests")
class AuthenticationSecurityTest extends AbstractControllerTest {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserRepository userRepository;

    private String validUserToken;

    @BeforeEach
    void setUp() throws Exception {
        userRepository.deleteAll();
        validUserToken = registerUserAndGetToken("auth-test-user-" + System.nanoTime());
        registerAdminAndGetToken("auth-test-admin-" + System.nanoTime());
    }

    @Nested
    @DisplayName("Valid JWT")
    class ValidJwtTests {
        @Test
        @DisplayName("Authenticates successfully with valid JWT")
        void validToken_AuthenticatesSuccessfully() throws Exception {
            mockMvc.perform(get("/api/v1/cart")
                            .header("Authorization", "Bearer " + validUserToken))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("Invalid JWT Variations")
    class InvalidJwtTests {
        @Test
        @DisplayName("Malformed JWT returns unauthorized")
        void malformedToken_ReturnsUnauthorized() throws Exception {
            mockMvc.perform(get("/api/v1/cart")
                            .header("Authorization", "Bearer this.is.not.a.jwt"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Missing JWT returns unauthorized on protected endpoints")
        void missingToken_ReturnsUnauthorized() throws Exception {
            mockMvc.perform(get("/api/v1/cart"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Invalid signature JWT returns unauthorized")
        void invalidSignatureToken_ReturnsUnauthorized() throws Exception {
            String user = "invalid-sig-user-" + System.nanoTime();
            createTestUser(user, UserRole.ROLE_USER);
            String validToken = loginAndGetToken(user);

            String[] parts = validToken.split("\\.");
            String invalidSigToken = parts[0] + "." + parts[1] + ".invalid-signature-here";

            mockMvc.perform(get("/api/v1/cart")
                            .header("Authorization", "Bearer " + invalidSigToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Tampered JWT payload returns unauthorized")
        void tamperedPayloadToken_ReturnsUnauthorized() throws Exception {
            String user = "tampered-user-" + System.nanoTime();
            createTestUser(user, UserRole.ROLE_USER);
            String validToken = loginAndGetToken(user);

            String[] parts = validToken.split("\\.");
            String tamperedPayload = parts[0] + ".eyJ1c2VybmFtZSI6ImFkbWluIiwicm9sZXMiOlsiUk9MRV9BRE1JTiJdfQ." + parts[2];

            mockMvc.perform(get("/api/v1/cart")
                            .header("Authorization", "Bearer " + tamperedPayload))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Non-Bearer token is ignored")
        void nonBearerToken_ReturnsUnauthorized() throws Exception {
            mockMvc.perform(get("/api/v1/cart")
                            .header("Authorization", "Basic dXNlcjpwYXNzd29yZA=="))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("Anonymous Requests")
    class AnonymousTests {
        @Test
        @DisplayName("Public endpoints are accessible anonymously")
        void publicEndpoint_AnonymousAccess_Succeeds() throws Exception {
            mockMvc.perform(get("/api/v1/books"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Protected endpoints are blocked for anonymous")
        void protectedEndpoint_AnonymousAccess_Fails() throws Exception {
            mockMvc.perform(get("/api/v1/orders"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/v1/admin/inventory/1"))
                    .andExpect(status().isForbidden());
        }
    }
}
