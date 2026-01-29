package com.example.bookstore.service;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private final JwtService jwtService = new JwtService();

    @Test
    void generateToken_ShouldReturnValidToken() {
        String username = "testuser";
        String token = jwtService.generateToken(username);
        assertNotNull(token);
        assertFalse(token.isEmpty());
    }

    @Test
    void extractUsername_ShouldReturnCorrectUsername() {
        String username = "testuser";
        String token = jwtService.generateToken(username);
        String extractedUsername = jwtService.extractUsername(token);
        assertEquals(username, extractedUsername);
    }

    @Test
    void isTokenValid_ShouldReturnTrueForValidToken() {
        String username = "testuser";
        String token = jwtService.generateToken(username);
        UserDetails userDetails = User.withUsername(username)
                .password("password")
                .authorities(Collections.emptyList())
                .build();
        assertTrue(jwtService.isTokenValid(token, userDetails));
    }

    @Test
    void isTokenValid_ShouldReturnFalseForDifferentUsername() {
        String username = "testuser";
        String token = jwtService.generateToken(username);
        UserDetails userDetails = User.withUsername("differentuser")
                .password("password")
                .authorities(Collections.emptyList())
                .build();
        assertFalse(jwtService.isTokenValid(token, userDetails));
    }

    @Test
    void generateToken_WithUserDetails_ShouldReturnValidToken() {
        UserDetails userDetails = User.withUsername("admin")
                .password("password")
                .authorities(Collections.emptyList())
                .build();
        
        String token = jwtService.generateToken(userDetails);
        assertNotNull(token);
        assertEquals("admin", jwtService.extractUsername(token));
    }

    @Test
    void generateToken_WithExtraClaims_ShouldIncludeClaims() {
        String token = jwtService.generateToken("testuser");
        assertNotNull(token);
        assertEquals("testuser", jwtService.extractUsername(token));
    }
}
