package com.example.bookstore.service;

import com.example.bookstore.config.SecurityUtils;
import com.example.bookstore.event.OrderCreatedEvent;
import com.example.bookstore.event.OrderStatusChangedEvent;
import com.example.bookstore.model.*;
import com.example.bookstore.repository.BookRepository;
import com.example.bookstore.repository.OrderRepository;
import com.example.bookstore.repository.OrderStatusHistoryRepository;
import com.example.bookstore.repository.PaymentRepository;
import com.example.bookstore.service.payment.DummyPaymentProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {
    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final CartService cartService;
    private final BookRepository bookRepository;
    private final PaymentRepository paymentRepository;
    private final DummyPaymentProvider paymentProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final SecurityUtils securityUtils;

    public OrderService(OrderRepository orderRepository,
                        OrderStatusHistoryRepository orderStatusHistoryRepository,
                        CartService cartService,
                        BookRepository bookRepository,
                        PaymentRepository paymentRepository,
                        DummyPaymentProvider paymentProvider,
                        ApplicationEventPublisher eventPublisher,
                        SecurityUtils securityUtils) {
        this.orderRepository = orderRepository;
        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
        this.cartService = cartService;
        this.bookRepository = bookRepository;
        this.paymentRepository = paymentRepository;
        this.paymentProvider = paymentProvider;
        this.eventPublisher = eventPublisher;
        this.securityUtils = securityUtils;
    }

    @Transactional
    public Order createOrder() {
        AppUser user = securityUtils.getCurrentUser();
        Cart cart = cartService.getCart();

        if (cart.getItems().isEmpty()) {
            throw new IllegalStateException("Cart is empty");
        }

        for (CartItem item : cart.getItems()) {
            Book book = item.getBook();
            if (book.getStock() < item.getQuantity()) {
                throw new IllegalStateException("Not enough stock for book: " + book.getTitle());
            }
        }

        Order order = new Order();
        order.setUser(user);
        order.setStatus(OrderStatus.PENDING);

        for (CartItem cartItem : cart.getItems()) {
            Book book = cartItem.getBook();

            book.setStock(book.getStock() - cartItem.getQuantity());
            bookRepository.save(book);

            OrderItem orderItem = new OrderItem();
            orderItem.setBook(book);
            orderItem.setQuantity(cartItem.getQuantity());
            orderItem.setUnitPrice(cartItem.getPriceAtAdd());
            orderItem.recalculateSubtotal();
            order.addItem(orderItem);
        }

        order.calculateTotal();
        orderRepository.save(order);

        Payment payment = new Payment(order, paymentProvider.getName(), PaymentStatus.PENDING, order.getTotalPrice());
        payment.onCreate();
        paymentRepository.save(payment);

        cartService.clearCart();

        eventPublisher.publishEvent(new OrderCreatedEvent(
                order.getId(), user.getId(), user.getUsername(), user.getEmail(), order.getTotalPrice()));

        return order;
    }

    public Page<Order> getUserOrders(int page, int size, String sortBy, String direction) {
        AppUser user = securityUtils.getCurrentUser();
        Sort sort = Sort.by(direction.equalsIgnoreCase("asc") ? Sort.Direction.ASC : Sort.Direction.DESC, sortBy);
        Pageable pageable = PageRequest.of(page, size, sort);
        return orderRepository.findByUserId(user.getId(), pageable);
    }

    public Order getUserOrderById(Long orderId) {
        AppUser user = securityUtils.getCurrentUser();
        return orderRepository.findByIdAndUserId(orderId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Order not found or access denied"));
    }

    @Transactional
    public void cancelOwnOrder(Long orderId) {
        AppUser user = securityUtils.getCurrentUser();
        Order order = orderRepository.findByIdAndUserId(orderId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Order not found or access denied"));

        if (!order.getStatus().isCancelable()) {
            throw new IllegalStateException(
                    "Order in status " + order.getStatus() + " cannot be cancelled");
        }

        OrderStatus previousStatus = order.getStatus();
        order.recordTransition(OrderStatus.CANCELLED, user.getUsername(), "Cancelled by customer");
        orderRepository.save(order);

        eventPublisher.publishEvent(new OrderStatusChangedEvent(
                order.getId(), user.getId(), user.getUsername(), user.getEmail(),
                previousStatus, OrderStatus.CANCELLED));
    }

    public Page<Order> getAllOrders(int page, int size, String sortBy, String direction, OrderStatus status) {
        Sort sort = Sort.by(direction.equalsIgnoreCase("asc") ? Sort.Direction.ASC : Sort.Direction.DESC, sortBy);
        Pageable pageable = PageRequest.of(page, size, sort);
        if (status != null) {
            return orderRepository.findByStatus(status, pageable);
        }
        return orderRepository.findAll(pageable);
    }

    public Order getOrderById(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
    }

    @Transactional
    public void updateOrderStatus(Long orderId, OrderStatus targetStatus, String comment) {
        AppUser admin = securityUtils.getCurrentUser();
        Order order = getOrderById(orderId);

        if (!order.getStatus().canTransitionTo(targetStatus)) {
            throw new IllegalStateException(
                    "Cannot transition from " + order.getStatus() + " to " + targetStatus);
        }

        OrderStatus previousStatus = order.getStatus();
        order.recordTransition(targetStatus, admin.getUsername(), comment);
        orderRepository.save(order);

        eventPublisher.publishEvent(new OrderStatusChangedEvent(
                order.getId(), order.getUser().getId(), order.getUser().getUsername(), order.getUser().getEmail(),
                previousStatus, targetStatus));
    }

    @Transactional
    public void adminCancelOrder(Long orderId, String comment) {
        AppUser admin = securityUtils.getCurrentUser();
        Order order = getOrderById(orderId);

        if (!order.getStatus().isCancelable()) {
            throw new IllegalStateException(
                    "Order in status " + order.getStatus() + " cannot be cancelled");
        }

        OrderStatus previousStatus = order.getStatus();
        order.recordTransition(OrderStatus.CANCELLED, admin.getUsername(), comment);
        orderRepository.save(order);

        eventPublisher.publishEvent(new OrderStatusChangedEvent(
                order.getId(), order.getUser().getId(), order.getUser().getUsername(), order.getUser().getEmail(),
                previousStatus, OrderStatus.CANCELLED));
    }

    public java.util.List<OrderStatusHistory> getOrderHistory(Long orderId) {
        return orderStatusHistoryRepository.findByOrderIdOrderByCreatedAtAsc(orderId);
    }

    public java.util.List<OrderStatusHistory> getOrderHistoryForUser(Long orderId) {
        AppUser user = securityUtils.getCurrentUser();
        Order order = orderRepository.findByIdAndUserId(orderId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Order not found or access denied"));
        return orderStatusHistoryRepository.findByOrderIdOrderByCreatedAtAsc(order.getId());
    }
}
