package com.example.bookstore.notification.email;

import com.example.bookstore.metrics.BusinessMetrics;
import com.example.bookstore.notification.model.EmailLog;
import com.example.bookstore.notification.model.EmailStatus;
import com.example.bookstore.notification.repository.EmailLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock
    private EmailSender emailSender;
    @Mock
    private EmailLogRepository emailLogRepository;
    @Mock
    private BusinessMetrics businessMetrics;

    private EmailService emailService;

    @BeforeEach
    void setUp() {
        emailService = new EmailService(emailSender, emailLogRepository, businessMetrics);
    }

    @Nested
    @DisplayName("sendEmail")
    class SendEmail {
        @Test
        @DisplayName("Sends email once and persists one SENT log entry")
        void success() {
            emailService.sendEmail("user@example.com", "Subject", "Body", "ORDER_CONFIRMATION");

            verify(emailSender).send("user@example.com", "Subject", "Body");
            ArgumentCaptor<EmailLog> captor = ArgumentCaptor.forClass(EmailLog.class);
            verify(emailLogRepository).save(captor.capture());
            assertEquals(EmailStatus.SENT, captor.getValue().getStatus());
            verify(businessMetrics).recordEmailSent();
        }

        @Test
        @DisplayName("Propagates SMTP failure instead of retrying inside EmailService")
        void smtpFailurePropagates() {
            doThrow(new RuntimeException("SMTP timeout"))
                    .when(emailSender).send(anyString(), anyString(), anyString());

            assertThrows(RuntimeException.class, () ->
                    emailService.sendEmail("user@example.com", "Subject", "Body", "ORDER_CONFIRMATION"));

            verify(emailSender, times(1)).send(anyString(), anyString(), anyString());
            verify(emailLogRepository, never()).save(any(EmailLog.class));
            verify(businessMetrics, never()).recordEmailSent();
        }

        @Test
        @DisplayName("Does not resend or throw when the log save fails after delivery")
        void logSaveFailureDoesNotResend() {
            when(emailLogRepository.save(any(EmailLog.class)))
                    .thenThrow(new IllegalStateException("database unavailable"));

            assertDoesNotThrow(() ->
                    emailService.sendEmail("user@example.com", "Subject", "Body", "ORDER_CONFIRMATION"));

            verify(emailSender, times(1)).send(anyString(), anyString(), anyString());
            verify(businessMetrics).recordEmailSent();
        }

        @Test
        @DisplayName("Persists the recipient, subject, and email type")
        void createsLogEntry() {
            emailService.sendEmail("user@example.com", "Sub", "Body", "WELCOME");

            ArgumentCaptor<EmailLog> captor = ArgumentCaptor.forClass(EmailLog.class);
            verify(emailLogRepository).save(captor.capture());
            EmailLog saved = captor.getValue();
            assertEquals("user@example.com", saved.getRecipient());
            assertEquals("Sub", saved.getSubject());
            assertEquals("WELCOME", saved.getEmailType());
            assertNotNull(saved.getSentAt());
        }
    }
}
