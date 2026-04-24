package com.example.bookstore.service;

import com.example.bookstore.config.SecurityUtils;
import com.example.bookstore.model.*;
import com.example.bookstore.repository.BookRepository;
import com.example.bookstore.repository.OrderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {
    private final OrderRepository orderRepository;
    private final CartService cartService;
    private final BookRepository bookRepository;
    private final SecurityUtils securityUtils;

    public OrderService(OrderRepository orderRepository, CartService cartService,
                       BookRepository bookRepository, SecurityUtils securityUtils) {
        this.orderRepository = orderRepository;
        this.cartService = cartService;
        this.bookRepository = bookRepository;
        this.securityUtils = securityUtils;
    }

    @Transactional
    public Order createOrder() {
        AppUser user = securityUtils.getCurrentUser();
        Cart cart = cartService.getCart();

        if (cart.getItems().isEmpty()) {
            throw new IllegalStateException("Cart is empty");
        }

        // Validate stock availability before attempting modifications
        for (CartItem item : cart.getItems()) {
            Book book = item.getBook();
            if (book.getStock() < item.getQuantity()) {
                throw new IllegalStateException("Not enough stock for book: " + book.getTitle());
            }
        }

        Order order = new Order();
        order.setUser(user);
        order.setStatus(OrderStatus.PENDING);

        // Create order items with frozen prices from cart (priceAtAdd)
        for (CartItem cartItem : cart.getItems()) {
            Book book = cartItem.getBook();

            // Deduct stock (optimistic locking enforced by @Version)
            book.setStock(book.getStock() - cartItem.getQuantity());
            bookRepository.save(book);

            OrderItem orderItem = new OrderItem();
            orderItem.setBook(book);
            orderItem.setQuantity(cartItem.getQuantity());
            orderItem.setUnitPrice(cartItem.getPriceAtAdd());  // Frozen price at add time
            orderItem.recalculateSubtotal();
            order.addItem(orderItem);
        }

        order.calculateTotal();
        orderRepository.save(order);
        cartService.clearCart();

        return order;
    }

    public Page<Order> getUserOrders(int page, int size, String sortBy, String direction) {
        AppUser user = securityUtils.getCurrentUser();
        Sort sort = Sort.by(direction.equalsIgnoreCase("asc") ? Sort.Direction.ASC : Sort.Direction.DESC, sortBy);
        Pageable pageable = PageRequest.of(page, size, sort);
        return orderRepository.findByUserId(user.getId(), pageable);
    }
}
