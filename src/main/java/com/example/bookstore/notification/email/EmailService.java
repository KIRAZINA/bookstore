package com.example.bookstore.notification.email;

import com.example.bookstore.metrics.BusinessMetrics;
import com.example.bookstore.notification.model.EmailLog;
import com.example.bookstore.notification.model.EmailStatus;
import com.example.bookstore.notification.repository.EmailLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private static final int MAX_RETRIES = 3;

    private final EmailSender emailSender;
    private final EmailLogRepository emailLogRepository;
    private final BusinessMetrics businessMetrics;

    public EmailService(EmailSender emailSender, EmailLogRepository emailLogRepository,
                        BusinessMetrics businessMetrics) {
        this.emailSender = emailSender;
        this.emailLogRepository = emailLogRepository;
        this.businessMetrics = businessMetrics;
    }

    public void sendEmail(String to, String subject, String body, String emailType) {
        EmailLog emailLog = new EmailLog(to, subject, body, emailType);
        emailLog = emailLogRepository.save(emailLog);

        Exception lastException = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                emailSender.send(to, subject, body);
                emailLog.setStatus(EmailStatus.SENT);
                emailLog.setSentAt(LocalDateTime.now());
                emailLog.setRetryCount(attempt + 1);
                emailLogRepository.save(emailLog);
                businessMetrics.recordEmailSent();
                return;
            } catch (Exception e) {
                lastException = e;
                log.warn("Email send attempt {} failed for {}: {}", attempt + 1, to, e.getMessage());
                emailLog.setRetryCount(attempt + 1);
                emailLog.setErrorMessage(e.getMessage());
                emailLogRepository.save(emailLog);

                if (attempt < MAX_RETRIES - 1) {
                    try {
                        Thread.sleep(1000L * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        emailLog.setStatus(EmailStatus.FAILED);
        emailLog.setErrorMessage(lastException != null ? lastException.getMessage() : "Unknown error");
        emailLogRepository.save(emailLog);
        businessMetrics.recordEmailFailed();
        log.error("Failed to send email to {} after {} attempts: {}", to, MAX_RETRIES,
                lastException != null ? lastException.getMessage() : "Unknown error");
    }
}
