package com.example.bookstore.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class UserRoleConverter implements AttributeConverter<UserRole, String> {

    @Override
    public String convertToDatabaseColumn(UserRole role) {
        return role == null ? null : role.name();
    }

    @Override
    public UserRole convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        String normalized = dbData.trim().toUpperCase();
        if (normalized.equals("USER")) return UserRole.ROLE_USER;
        if (normalized.equals("ADMIN")) return UserRole.ROLE_ADMIN;
        try {
            return UserRole.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return UserRole.ROLE_USER; // Fallback
        }
    }
}