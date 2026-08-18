package com.example.bookstore.service;

import com.example.bookstore.config.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

    private static final Logger auditLog = LoggerFactory.getLogger("AUDIT");

    private final SecurityUtils securityUtils;

    public AuditService(SecurityUtils securityUtils) {
        this.securityUtils = securityUtils;
    }

    public void logAuthenticationSuccess(String username) {
        auditLog.info("AUTH_SUCCESS username={}", sanitize(username));
    }

    public void logAuthenticationFailure(String username) {
        auditLog.warn("AUTH_FAILURE username={}", sanitize(username));
    }

    public void logRegistration(String username) {
        auditLog.info("REGISTRATION username={}", sanitize(username));
    }

    public void logAdminAction(String action, String detail) {
        String actor = getCurrentUsername();
        auditLog.info("ADMIN_ACTION actor={} action={} detail={}", sanitize(actor), sanitize(action), sanitize(detail));
    }

    public void logOrderAction(String action, Long orderId, String detail) {
        String actor = getCurrentUsername();
        auditLog.info("ORDER_ACTION actor={} orderId={} action={} detail={}",
                sanitize(actor), orderId, sanitize(action), sanitize(detail));
    }

    public void logPaymentAction(String action, Long orderId, String detail) {
        String actor = getCurrentUsername();
        auditLog.info("PAYMENT_ACTION actor={} orderId={} action={} detail={}",
                sanitize(actor), orderId, sanitize(action), sanitize(detail));
    }

    public void logSecurityEvent(String event, String detail) {
        auditLog.warn("SECURITY_EVENT event={} detail={}", sanitize(event), sanitize(detail));
    }

    private String getCurrentUsername() {
        try {
            return securityUtils.getCurrentUser().getUsername();
        } catch (Exception e) {
            return "anonymous";
        }
    }

    private String sanitize(String input) {
        if (input == null) {
            return "null";
        }
        return input.replaceAll("[\\n\\r\\t]", "_");
    }
}
