package com.example.bookstore.notification.repository;

import com.example.bookstore.notification.model.EmailLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailLogRepository extends JpaRepository<EmailLog, Long> {
}
