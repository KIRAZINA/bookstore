package com.example.bookstore.service;

import com.example.bookstore.model.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Email service stub - currently just logs order confirmations.
 * Replace with real email implementation when needed (e.g., Spring Mail, SendGrid, etc.)
 */
@Service
public class EmailService {
    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    public void sendOrderConfirmation(String to, Order order) {
        // Simple logging instead of actual email sending
        // To implement real email: add spring-boot-starter-mail dependency and configure
        log.info("ORDER CONFIRMATION [simulated] - To: {}, Order ID: {}, Total: ${}",
                to, order.getId(), order.getTotalPrice());
    }
}
