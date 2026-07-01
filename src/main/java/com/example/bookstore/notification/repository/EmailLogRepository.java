package com.example.bookstore.notification.repository;

import com.example.bookstore.notification.model.EmailLog;
import com.example.bookstore.notification.model.EmailStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmailLogRepository extends JpaRepository<EmailLog, Long> {
    List<EmailLog> findByStatus(EmailStatus status);
}
