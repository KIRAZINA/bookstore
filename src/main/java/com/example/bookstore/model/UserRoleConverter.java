// В UserRoleConverter.java
package com.example.bookstore.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Locale;

@Converter(autoApply = true)
public class UserRoleConverter implements AttributeConverter<UserRole, String> {

    @Override
    public String convertToDatabaseColumn(UserRole role) {
        return role == null ? null : role.name();
    }

    @Override
    public UserRole convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        try {
            return UserRole.valueOf(dbData.trim().toUpperCase(Locale.ENGLISH));
        } catch (IllegalArgumentException e) {
            return UserRole.ROLE_USER; // Fallback
        }
    }
}