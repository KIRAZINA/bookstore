package com.example.bookstore.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UserRoleTest {

    @Test
    void userRole_ShouldHaveTwoValues() {
        UserRole[] roles = UserRole.values();
        assertEquals(2, roles.length);
    }

    @Test
    void userRole_ShouldHaveUserAndAdmin() {
        assertNotNull(UserRole.ROLE_USER);
        assertNotNull(UserRole.ROLE_ADMIN);
    }

    @Test
    void userRole_ValueOf_ShouldReturnCorrectRole() {
        assertEquals(UserRole.ROLE_USER, UserRole.valueOf("ROLE_USER"));
        assertEquals(UserRole.ROLE_ADMIN, UserRole.valueOf("ROLE_ADMIN"));
    }

    @Test
    void userRole_Name_ShouldReturnStringValue() {
        assertEquals("ROLE_USER", UserRole.ROLE_USER.name());
        assertEquals("ROLE_ADMIN", UserRole.ROLE_ADMIN.name());
    }
}
