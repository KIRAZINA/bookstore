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
        when(emailLogRepository.save(any(EmailLog.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Nested
    @DisplayName("sendEmail")
    class SendEmail {
        @Test
        @DisplayName("Sends email successfully on first attempt")
        void success() {
            emailService.sendEmail("user@example.com", "Subject", "Body", "ORDER_CONFIRMATION");

            verify(emailSender).send("user@example.com", "Subject", "Body");
            verify(emailLogRepository, times(2)).save(any(EmailLog.class));
            verify(businessMetrics).recordEmailSent();
        }

        @Test
        @DisplayName("Retries on failure and eventually succeeds")
        void retryThenSuccess() {
            doThrow(new RuntimeException("SMTP timeout"))
                    .doThrow(new RuntimeException("SMTP timeout"))
                    .doNothing()
                    .when(emailSender).send(anyString(), anyString(), anyString());

            emailService.sendEmail("user@example.com", "Subject", "Body", "ORDER_CONFIRMATION");

            verify(emailSender, times(3)).send(anyString(), anyString(), anyString());
            verify(businessMetrics).recordEmailSent();
            verify(businessMetrics, never()).recordEmailFailed();
        }

        @Test
        @DisplayName("Marks as FAILED after exhausting retries")
        void allRetriesFailed() {
            doThrow(new RuntimeException("SMTP down"))
                    .when(emailSender).send(anyString(), anyString(), anyString());

            emailService.sendEmail("user@example.com", "Subject", "Body", "ORDER_CONFIRMATION");

            verify(emailSender, times(3)).send(anyString(), anyString(), anyString());
            ArgumentCaptor<EmailLog> captor = ArgumentCaptor.forClass(EmailLog.class);
            verify(emailLogRepository, times(5)).save(captor.capture());
            EmailLog saved = captor.getAllValues().get(4);
            assertEquals(EmailStatus.FAILED, saved.getStatus());
            verify(businessMetrics).recordEmailFailed();
        }

        @Test
        @DisplayName("Logs initial creation")
        void createsLogEntry() {
            emailService.sendEmail("user@example.com", "Sub", "Body", "WELCOME");

            ArgumentCaptor<EmailLog> captor = ArgumentCaptor.forClass(EmailLog.class);
            verify(emailLogRepository, atLeast(1)).save(captor.capture());
            EmailLog initial = captor.getAllValues().get(0);
            assertEquals("user@example.com", initial.getRecipient());
            assertEquals("Sub", initial.getSubject());
            assertEquals("WELCOME", initial.getEmailType());
        }
    }
}
