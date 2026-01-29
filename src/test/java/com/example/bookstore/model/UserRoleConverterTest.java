package com.example.bookstore.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UserRoleConverterTest {

    private UserRoleConverter converter;

    @BeforeEach
    void setUp() {
        converter = new UserRoleConverter();
    }

    @Test
    void convertToDbColumn_ShouldReturnRoleName() {
        assertEquals("ROLE_USER", converter.convertToDatabaseColumn(UserRole.ROLE_USER));
        assertEquals("ROLE_ADMIN", converter.convertToDatabaseColumn(UserRole.ROLE_ADMIN));
    }

    @Test
    void convertToDbColumn_ShouldReturnNullForNull() {
        assertNull(converter.convertToDatabaseColumn(null));
    }

    @Test
    void convertToEntityAttribute_ShouldReturnRole() {
        assertEquals(UserRole.ROLE_USER, converter.convertToEntityAttribute("ROLE_USER"));
        assertEquals(UserRole.ROLE_ADMIN, converter.convertToEntityAttribute("ROLE_ADMIN"));
    }

    @Test
    void convertToEntityAttribute_ShouldBeCaseInsensitive() {
        assertEquals(UserRole.ROLE_USER, converter.convertToEntityAttribute("role_user"));
        assertEquals(UserRole.ROLE_ADMIN, converter.convertToEntityAttribute("ROLE_ADMIN"));
    }

    @Test
    void convertToEntityAttribute_ShouldReturnNullForNull() {
        assertNull(converter.convertToEntityAttribute(null));
    }

    @Test
    void convertToEntityAttribute_ShouldReturnDefaultForInvalidValue() {
        assertEquals(UserRole.ROLE_USER, converter.convertToEntityAttribute("INVALID_ROLE"));
    }

    @Test
    void convertToEntityAttribute_ShouldHandleTrimmedValue() {
        assertEquals(UserRole.ROLE_USER, converter.convertToEntityAttribute("  ROLE_USER  "));
    }
}
