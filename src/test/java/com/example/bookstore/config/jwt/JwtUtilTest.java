package com.example.bookstore.config.jwt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", "this-is-a-test-secret-that-is-at-least-32-bytes-long!!");
        ReflectionTestUtils.setField(jwtUtil, "expiration", 3600000L);
        ReflectionTestUtils.setField(jwtUtil, "clockSkewSeconds", 30L);
    }

    @Nested
    @DisplayName("generateToken")
    class GenerateToken {
        @Test
        @DisplayName("Generates token from authentication")
        void fromAuthentication() {
            Authentication auth = new UsernamePasswordAuthenticationToken(
                    new User("testuser", "pass", List.of(new SimpleGrantedAuthority("ROLE_USER"))),
                    "pass", List.of(new SimpleGrantedAuthority("ROLE_USER")));
            String token = jwtUtil.generateToken(auth);
            assertNotNull(token);
            assertTrue(token.split("\\.").length == 3);
        }

        @Test
        @DisplayName("Generates token from username")
        void fromUsername() {
            String token = jwtUtil.generateToken("testuser");
            assertNotNull(token);
            assertTrue(token.split("\\.").length == 3);
        }
    }

    @Nested
    @DisplayName("validateToken")
    class ValidateToken {
        @Test
        @DisplayName("Validates own token")
        void validToken() {
            String token = jwtUtil.generateToken("testuser");
            assertTrue(jwtUtil.validateToken(token));
        }

        @Test
        @DisplayName("Rejects malformed token")
        void malformedToken() {
            assertFalse(jwtUtil.validateToken("not-a-jwt"));
        }

        @Test
        @DisplayName("Rejects empty token")
        void emptyToken() {
            assertFalse(jwtUtil.validateToken(""));
        }

        @Test
        @DisplayName("Rejects token with bad signature")
        void badSignature() {
            String token = jwtUtil.generateToken("testuser");
            String tampered = token.substring(0, token.lastIndexOf('.') + 1) + "badsignature";
            assertFalse(jwtUtil.validateToken(tampered));
        }
    }

    @Nested
    @DisplayName("getUsernameFromToken")
    class GetUsername {
        @Test
        @DisplayName("Extracts username")
        void extractsUsername() {
            String token = jwtUtil.generateToken("testuser");
            assertEquals("testuser", jwtUtil.getUsernameFromToken(token));
        }

        @Test
        @DisplayName("Extracts username from auth-generated token")
        void extractsUsernameFromAuth() {
            Authentication auth = new UsernamePasswordAuthenticationToken(
                    new User("admin", "pass", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))),
                    "pass", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
            String token = jwtUtil.generateToken(auth);
            assertEquals("admin", jwtUtil.getUsernameFromToken(token));
        }
    }

    @Nested
    @DisplayName("getRolesFromToken")
    class GetRoles {
        @Test
        @DisplayName("Extracts roles from auth-generated token")
        void extractsRoles() {
            Authentication auth = new UsernamePasswordAuthenticationToken(
                    new User("admin", "pass", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))),
                    "pass", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
            String token = jwtUtil.generateToken(auth);
            String roles = jwtUtil.getRolesFromToken(token);
            assertTrue(roles.contains("ROLE_ADMIN"));
        }

        @Test
        @DisplayName("Returns empty for username-generated token")
        void noRolesForUsernameToken() {
            String token = jwtUtil.generateToken("testuser");
            assertEquals("", jwtUtil.getRolesFromToken(token));
        }
    }

    @Nested
    @DisplayName("getJtiFromToken")
    class GetJti {
        @Test
        @DisplayName("Extracts JTI from token")
        void extractsJti() {
            String token = jwtUtil.generateToken("testuser");
            String jti = jwtUtil.getJtiFromToken(token);
            assertNotNull(jti);
            assertFalse(jti.isBlank());
        }
    }

    @Nested
    @DisplayName("getExpirationTime")
    class GetExpiration {
        @Test
        @DisplayName("Returns configured expiration")
        void returnsExpiration() {
            assertEquals(3600000L, jwtUtil.getExpirationTime());
        }
    }
}
