package com.example.bookstore.service;

import com.example.bookstore.model.Order;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Service
public class EmailService {
    private final JavaMailSender mailSender;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Async
    public void sendOrderConfirmation(String to, Order order) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject("Order Confirmation #" + order.getId());
        message.setText("Thank you for your order!\n" +
                "Order ID: " + order.getId() + "\n" +
                "Total Price: $" + order.getTotalPrice() + "\n" +
                "Items:\n" + order.getItems().stream()
                .map(item -> item.getBook().getTitle() + " x" + item.getQuantity())
                .collect(Collectors.joining("\n")));
        mailSender.send(message);
    }
}