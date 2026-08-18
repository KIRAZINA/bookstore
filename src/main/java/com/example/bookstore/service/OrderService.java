package com.example.bookstore.service;

import com.example.bookstore.config.SecurityUtils;
import com.example.bookstore.event.EventTypes;
import com.example.bookstore.event.OrderCreatedEvent;
import com.example.bookstore.event.OrderStatusChangedEvent;
import com.example.bookstore.event.publisher.DomainEventPublisher;
import com.example.bookstore.metrics.BusinessMetrics;
import com.example.bookstore.model.AppUser;
import com.example.bookstore.model.Book;
import com.example.bookstore.model.Cart;
import com.example.bookstore.model.CartItem;
import com.example.bookstore.model.Order;
import com.example.bookstore.model.OrderItem;
import com.example.bookstore.model.OrderStatus;
import com.example.bookstore.model.OrderStatusHistory;
import com.example.bookstore.model.Payment;
import com.example.bookstore.model.PaymentStatus;
import com.example.bookstore.repository.BookRepository;
import com.example.bookstore.repository.OrderRepository;
import com.example.bookstore.repository.OrderStatusHistoryRepository;
import com.example.bookstore.repository.PaymentRepository;
import com.example.bookstore.service.payment.DummyPaymentProvider;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class OrderService {
    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final CartService cartService;
    private final BookRepository bookRepository;
    private final PaymentRepository paymentRepository;
    private final DummyPaymentProvider paymentProvider;
    private final DomainEventPublisher domainEventPublisher;
    private final SecurityUtils securityUtils;
    private final BusinessMetrics businessMetrics;
    private final InventoryService inventoryService;

    public OrderService(OrderRepository orderRepository,
                        OrderStatusHistoryRepository orderStatusHistoryRepository,
                        CartService cartService,
                        BookRepository bookRepository,
                        PaymentRepository paymentRepository,
                        DummyPaymentProvider paymentProvider,
                        DomainEventPublisher domainEventPublisher,
                        SecurityUtils securityUtils,
                        BusinessMetrics businessMetrics,
                        InventoryService inventoryService) {
        this.orderRepository = orderRepository;
        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
        this.cartService = cartService;
        this.bookRepository = bookRepository;
        this.paymentRepository = paymentRepository;
        this.paymentProvider = paymentProvider;
        this.domainEventPublisher = domainEventPublisher;
        this.securityUtils = securityUtils;
        this.businessMetrics = businessMetrics;
        this.inventoryService = inventoryService;
    }

    @WithSpan("OrderService.createOrder")
    @Transactional
    public Order createOrder() {
        long start = System.currentTimeMillis();
        Cart cart = cartService.getCart();

        if (cart.getItems().isEmpty()) {
            throw new IllegalStateException("Cart is empty");
        }

        for (CartItem item : cart.getItems()) {
            Book book = item.getBook();
            int available = inventoryService.getAvailableQuantity(book.getId());
            if (available < item.getQuantity()) {
                throw new IllegalStateException("Not enough stock for book: " + book.getTitle());
            }
        }

        AppUser user = securityUtils.getCurrentUser();
        Order order = new Order();
        order.setUser(user);
        order.setStatus(OrderStatus.PENDING);

        Map<Long, Integer> reservations = new LinkedHashMap<>();
        int totalItems = 0;
        for (CartItem cartItem : cart.getItems()) {
            Long bookId = cartItem.getBook().getId();
            int qty = cartItem.getQuantity();
            reservations.put(bookId, qty);
            totalItems += qty;
        }

        for (CartItem cartItem : cart.getItems()) {
            Book book = cartItem.getBook();
            OrderItem orderItem = new OrderItem();
            orderItem.setBook(book);
            orderItem.setQuantity(cartItem.getQuantity());
            orderItem.setUnitPrice(cartItem.getPriceAtAdd());
            orderItem.recalculateSubtotal();
            order.addItem(orderItem);
        }

        order.calculateTotal();
        orderRepository.save(order);

        inventoryService.reserveAll(order.getId(), reservations, user.getUsername());

        Payment payment = new Payment(order, paymentProvider.getName(), PaymentStatus.PENDING, order.getTotalPrice());
        payment.onCreate();
        paymentRepository.save(payment);

        cartService.clearCart();

        businessMetrics.recordOrderCreated();
        businessMetrics.recordBooksSold(totalItems);
        businessMetrics.recordCheckoutDuration(System.currentTimeMillis() - start);

        domainEventPublisher.publish(
                new OrderCreatedEvent(order.getId(), user.getId(), user.getUsername(), user.getEmail(), order.getTotalPrice()),
                EventTypes.AGGREGATE_ORDER, String.valueOf(order.getId()), EventTypes.ORDER_CREATED);

        return order;
    }

    public Page<Order> getUserOrders(int page, int size, String sortBy, String direction) {
        AppUser user = securityUtils.getCurrentUser();
        Sort sort = Sort.by("asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC, sortBy);
        Pageable pageable = PageRequest.of(page, size, sort);
        return orderRepository.findByUserId(user.getId(), pageable);
    }

    public Order getUserOrderById(Long orderId) {
        AppUser user = securityUtils.getCurrentUser();
        return orderRepository.findByIdAndUserId(orderId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Order not found or access denied"));
    }

    @WithSpan("OrderService.cancelOwnOrder")
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

        releaseInventoryForOrder(order, "Order cancelled by customer", user.getUsername());

        businessMetrics.recordOrderCancelled();

        domainEventPublisher.publish(
                new OrderStatusChangedEvent(order.getId(), user.getId(), user.getUsername(), user.getEmail(),
                        previousStatus, OrderStatus.CANCELLED),
                EventTypes.AGGREGATE_ORDER, String.valueOf(order.getId()), EventTypes.ORDER_STATUS_CHANGED);
    }

    public Page<Order> getAllOrders(int page, int size, String sortBy, String direction, OrderStatus status) {
        Sort sort = Sort.by("asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC, sortBy);
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

    @WithSpan("OrderService.updateOrderStatus")
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

        if (targetStatus == OrderStatus.CANCELLED) {
            releaseInventoryForOrder(order, "Admin cancelled order: " + comment, admin.getUsername());
            businessMetrics.recordOrderCancelled();
        } else if (targetStatus == OrderStatus.DELIVERED) {
            businessMetrics.recordOrderCompleted();
        }

        domainEventPublisher.publish(
                new OrderStatusChangedEvent(order.getId(), order.getUser().getId(), order.getUser().getUsername(),
                        order.getUser().getEmail(), previousStatus, targetStatus),
                EventTypes.AGGREGATE_ORDER, String.valueOf(order.getId()), EventTypes.ORDER_STATUS_CHANGED);
    }

    @WithSpan("OrderService.adminCancelOrder")
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

        if (previousStatus == OrderStatus.PAID) {
            restoreInventoryForOrder(order, "Admin cancelled: " + comment, admin.getUsername());
        } else {
            releaseInventoryForOrder(order, "Admin cancelled: " + comment, admin.getUsername());
        }

        businessMetrics.recordOrderCancelled();

        domainEventPublisher.publish(
                new OrderStatusChangedEvent(order.getId(), order.getUser().getId(), order.getUser().getUsername(),
                        order.getUser().getEmail(), previousStatus, OrderStatus.CANCELLED),
                EventTypes.AGGREGATE_ORDER, String.valueOf(order.getId()), EventTypes.ORDER_CANCELLED);
    }

    private void releaseInventoryForOrder(Order order, String reason, String changedBy) {
        Map<Long, Integer> items = new LinkedHashMap<>();
        for (OrderItem item : order.getItems()) {
            items.put(item.getBook().getId(), item.getQuantity());
        }
        inventoryService.releaseAllReservationsForOrder(items, order.getId(), reason, changedBy);
    }

    private void restoreInventoryForOrder(Order order, String reason, String changedBy) {
        Map<Long, Integer> items = new LinkedHashMap<>();
        for (OrderItem item : order.getItems()) {
            items.put(item.getBook().getId(), item.getQuantity());
        }
        inventoryService.restoreAllStockForOrder(items, order.getId(), reason, changedBy);
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
