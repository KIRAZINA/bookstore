package com.example.bookstore.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AppUserTest {

    @Test
    void appUser_ShouldSetAndGetFields() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("testuser");
        user.setPassword("password");
        user.setEmail("test@example.com");
        user.setRole(UserRole.ROLE_USER);

        assertEquals(1L, user.getId());
        assertEquals("testuser", user.getUsername());
        assertEquals("password", user.getPassword());
        assertEquals("test@example.com", user.getEmail());
        assertEquals(UserRole.ROLE_USER, user.getRole());
    }

    @Test
    void appUser_ShouldReturnCorrectAuthorities() {
        AppUser user = new AppUser();
        user.setUsername("admin");
        user.setRole(UserRole.ROLE_ADMIN);

        var authorities = user.getAuthorities();
        assertEquals(1, authorities.size());
        assertTrue(authorities.stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
    }

    @Test
    void appUser_ShouldReturnUserAuthorities() {
        AppUser user = new AppUser();
        user.setUsername("user");
        user.setRole(UserRole.ROLE_USER);

        var authorities = user.getAuthorities();
        assertEquals(1, authorities.size());
        assertTrue(authorities.stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_USER")));
    }

    @Test
    void appUser_AccountStatusMethods_ShouldReturnTrue() {
        AppUser user = new AppUser();

        assertTrue(user.isAccountNonExpired());
        assertTrue(user.isAccountNonLocked());
        assertTrue(user.isCredentialsNonExpired());
        assertTrue(user.isEnabled());
    }

    @Test
    void appUser_DefaultRole_ShouldBeUser() {
        AppUser user = new AppUser();
        assertEquals(UserRole.ROLE_USER, user.getRole());
    }
}
