package com.example.bookstore.notification.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(value = "spring.mail.host", matchIfMissing = true)
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(String to, String subject, String body) {
        log.info("===== EMAIL (logging mode) =====");
        log.info("To:      {}", to);
        log.info("Subject: {}", subject);
        log.info("Body:\n{}", body);
        log.info("=================================");
    }
}
