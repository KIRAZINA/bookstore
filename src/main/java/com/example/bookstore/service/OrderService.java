package com.example.bookstore.service;

import com.example.bookstore.model.AppUser;
import com.example.bookstore.model.Book;
import com.example.bookstore.model.Cart;
import com.example.bookstore.model.CartItem;
import com.example.bookstore.model.Order;
import com.example.bookstore.model.OrderItem;
import com.example.bookstore.repository.BookRepository;
import com.example.bookstore.repository.OrderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

@Service
public class OrderService {
    private final OrderRepository orderRepository;
    private final CartService cartService;
    private final BookRepository bookRepository;
    private final EmailService emailService;

    public OrderService(OrderRepository orderRepository, CartService cartService, BookRepository bookRepository, EmailService emailService) {
        this.orderRepository = orderRepository;
        this.cartService = cartService;
        this.bookRepository = bookRepository;
        this.emailService = emailService;
    }

    @Transactional
    public Order createOrder() {
        AppUser user = (AppUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Cart cart = cartService.getCart();

        for (CartItem item : cart.getItems()) {
            Book book = item.getBook();
            if (book.getStock() < item.getQuantity()) {
                throw new IllegalStateException("Not enough stock for book: " + book.getTitle());
            }
        }

        Order order = new Order();
        order.setUser(user);
        order.setCreatedAt(LocalDateTime.now());

        order.setItems(cart.getItems().stream()
                .map(item -> {
                    OrderItem orderItem = new OrderItem();
                    orderItem.setBook(item.getBook());
                    orderItem.setQuantity(item.getQuantity());
                    orderItem.setPrice(item.getBook().getPrice());
                    return orderItem;
                })
                .collect(Collectors.toList()));

        order.getItems().forEach(order::addItem);

        order.setTotalPrice(order.getItems().stream()
                .mapToDouble(item -> item.getQuantity() * item.getPrice())
                .sum());

        for (CartItem item : cart.getItems()) {
            Book book = item.getBook();
            book.setStock(book.getStock() - item.getQuantity());
            bookRepository.save(book);
        }

        orderRepository.save(order);
        cartService.clearCart();
        emailService.sendOrderConfirmation(user.getEmail(), order);

        return order;
    }

    public Page<Order> getUserOrders(int page, int size, String sortBy, String direction) {
        AppUser user = (AppUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Sort sort = Sort.by(direction.equalsIgnoreCase("asc") ? Sort.Direction.ASC : Sort.Direction.DESC, sortBy);
        Pageable pageable = PageRequest.of(page, size, sort);
        return orderRepository.findByUserId(user.getId(), pageable);
    }
}