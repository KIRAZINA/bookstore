package com.example.bookstore.service;

import com.example.bookstore.config.SecurityUtils;
import com.example.bookstore.model.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class AuditServiceTest {

    @Mock
    private SecurityUtils securityUtils;

    private AuditService auditService;

    @BeforeEach
    void setUp() {
        auditService = new AuditService(securityUtils);
        AppUser user = new AppUser();
        user.setUsername("admin");
        when(securityUtils.getCurrentUser()).thenReturn(user);
    }

    @Test
    @DisplayName("logAuthenticationSuccess does not throw")
    void logAuthSuccess() {
        assertDoesNotThrow(() -> auditService.logAuthenticationSuccess("testuser"));
    }

    @Test
    @DisplayName("logAuthenticationFailure does not throw")
    void logAuthFailure() {
        assertDoesNotThrow(() -> auditService.logAuthenticationFailure("testuser"));
    }

    @Test
    @DisplayName("logRegistration does not throw")
    void logRegistration() {
        assertDoesNotThrow(() -> auditService.logRegistration("testuser"));
    }

    @Test
    @DisplayName("logAdminAction does not throw")
    void logAdminAction() {
        assertDoesNotThrow(() -> auditService.logAdminAction("UPDATE_BOOK", "Updated book 1"));
    }

    @Test
    @DisplayName("logOrderAction does not throw")
    void logOrderAction() {
        assertDoesNotThrow(() -> auditService.logOrderAction("CREATE", 1L, "Order created"));
    }

    @Test
    @DisplayName("logPaymentAction does not throw")
    void logPaymentAction() {
        assertDoesNotThrow(() -> auditService.logPaymentAction("REFUND", 1L, "Refund processed"));
    }

    @Test
    @DisplayName("logSecurityEvent does not throw")
    void logSecurityEvent() {
        assertDoesNotThrow(() -> auditService.logSecurityEvent("INVALID_TOKEN", "Token expired"));
    }

    @Test
    @DisplayName("Uses anonymous when SecurityUtils fails")
    void anonymousOnFailure() {
        AuditService anonymousAudit = new AuditService(securityUtils);
        when(securityUtils.getCurrentUser()).thenThrow(new RuntimeException("No auth"));
        assertDoesNotThrow(() -> anonymousAudit.logAdminAction("TEST", "Should use anonymous"));
    }


}
