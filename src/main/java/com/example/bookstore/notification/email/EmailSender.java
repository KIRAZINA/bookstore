package com.example.bookstore.notification.email;

public interface EmailSender {
    void send(String to, String subject, String body);
}
