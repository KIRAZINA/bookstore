package com.example.bookstore.notification.email;

import com.example.bookstore.metrics.BusinessMetrics;
import com.example.bookstore.notification.model.EmailLog;
import com.example.bookstore.notification.model.EmailStatus;
import com.example.bookstore.notification.repository.EmailLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final EmailSender emailSender;
    private final EmailLogRepository emailLogRepository;
    private final BusinessMetrics businessMetrics;

    /**
     * Suspends any caller transaction for the SMTP call. A method-level
     * {@code @Transactional} annotation is deliberately avoided for the same reason the
     * consumer dedup no longer uses one: method annotations are proxy-fragile.
     */
    private final TransactionTemplate smtpTemplate;

    /**
     * Persists the email log in an independent transaction so a log failure or a later
     * consumer rollback cannot undo (or trigger a resend of) the already delivered email.
     */
    private final TransactionTemplate emailLogTemplate;

    @Autowired
    public EmailService(EmailSender emailSender, EmailLogRepository emailLogRepository,
                        BusinessMetrics businessMetrics, PlatformTransactionManager transactionManager) {
        this.emailSender = emailSender;
        this.emailLogRepository = emailLogRepository;
        this.businessMetrics = businessMetrics;

        this.smtpTemplate = new TransactionTemplate(transactionManager);
        this.smtpTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);

        this.emailLogTemplate = new TransactionTemplate(transactionManager);
        this.emailLogTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Unit-test constructor. Transaction templates are not available, so calls execute
     * without a managed transaction. Production wiring always uses the four-argument
     * constructor above.
     */
    EmailService(EmailSender emailSender, EmailLogRepository emailLogRepository,
                 BusinessMetrics businessMetrics) {
        this.emailSender = emailSender;
        this.emailLogRepository = emailLogRepository;
        this.businessMetrics = businessMetrics;
        this.smtpTemplate = null;
        this.emailLogTemplate = null;
    }

    /**
     * Sends an email outside any caller database transaction. SMTP failures propagate so the
     * consumer transaction rolls back and Kafka can retry. Persisting the email log is a
     * separate transaction and never causes a resend.
     */
    public void sendEmail(String to, String subject, String body, String emailType) {
        if (smtpTemplate == null) {
            emailSender.send(to, subject, body);
        } else {
            smtpTemplate.executeWithoutResult(status -> emailSender.send(to, subject, body));
        }

        EmailLog emailLog = new EmailLog(to, subject, body, emailType);
        emailLog.setStatus(EmailStatus.SENT);
        emailLog.setSentAt(LocalDateTime.now());

        try {
            if (emailLogTemplate == null) {
                emailLogRepository.save(emailLog);
            } else {
                emailLogTemplate.executeWithoutResult(status -> emailLogRepository.save(emailLog));
            }
        } catch (Exception logSaveFailure) {
            // The email has already been delivered. Rethrowing here would roll back the
            // consumer transaction, leave no dedup row, and make Kafka resend the email.
            log.warn("Email delivered to {} but log save failed (no retry, no resend): {}",
                    to, logSaveFailure.getMessage());
        }

        businessMetrics.recordEmailSent();
    }
}
