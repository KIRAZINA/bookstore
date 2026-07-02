package com.example.bookstore.builder;

import com.example.bookstore.model.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public final class TestDataBuilder {

    private TestDataBuilder() {}

    public static AppUser createUser(Long id, String username, String roleName) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setUsername(username);
        user.setPassword("password");
        user.setEmail(username + "@test.com");
        user.setRole(UserRole.valueOf(roleName));
        return user;
    }

    public static AppUser createUser() {
        return createUser(null, "user-" + UUID.randomUUID().toString().substring(0, 8), "ROLE_USER");
    }

    public static AppUser createAdmin() {
        return createUser(null, "admin-" + UUID.randomUUID().toString().substring(0, 8), "ROLE_ADMIN");
    }

    public static Book createBook(Long id, String title, String author, BigDecimal price, String category, int stock) {
        Book book = new Book();
        book.setId(id);
        book.setTitle(title);
        book.setAuthor(author);
        book.setPrice(price);
        book.setCategory(category);
        book.setStock(stock);
        return book;
    }

    public static Book createBook(String title, BigDecimal price) {
        return createBook(null, title, "Test Author", price, "General", 10);
    }

    public static Book createBook(String title, BigDecimal price, int stock) {
        return createBook(null, title, "Test Author", price, "General", stock);
    }

    public static Book createBook() {
        return createBook(null, "Test Book " + UUID.randomUUID().toString().substring(0, 8),
                "Test Author", new BigDecimal("19.99"), "General", 10);
    }

    public static Order createOrder(Long id, AppUser user, OrderStatus status, BigDecimal total) {
        Order order = new Order();
        order.setId(id);
        order.setUser(user);
        order.setStatus(status);
        order.setTotalPrice(total);
        return order;
    }

    public static Order createOrder(AppUser user, BigDecimal total) {
        Order order = new Order();
        order.setUser(user);
        order.setStatus(OrderStatus.PENDING);
        order.setTotalPrice(total);
        return order;
    }

    public static OrderItem createOrderItem(Book book, int quantity) {
        OrderItem item = new OrderItem();
        item.setBook(book);
        item.setQuantity(quantity);
        item.setUnitPrice(book.getPrice());
        item.setSubtotal(book.getPrice().multiply(BigDecimal.valueOf(quantity)));
        return item;
    }

    public static Payment createPayment(Order order, PaymentStatus status, BigDecimal amount) {
        Payment payment = new Payment(order, "DUMMY", status, amount);
        payment.onCreate();
        return payment;
    }

    public static String uniqueUsername() {
        return "user-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String uniqueEmail() {
        return "test-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com";
    }
}
