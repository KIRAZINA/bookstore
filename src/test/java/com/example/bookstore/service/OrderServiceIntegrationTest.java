package com.example.bookstore.service;

import com.example.bookstore.base.AbstractIntegrationTest;
import com.example.bookstore.config.SecurityUtils;
import com.example.bookstore.model.*;
import com.example.bookstore.repository.BookRepository;
import com.example.bookstore.repository.InventoryRepository;
import com.example.bookstore.repository.OrderRepository;
import com.example.bookstore.repository.OrderStatusHistoryRepository;
import com.example.bookstore.repository.PaymentRepository;
import com.example.bookstore.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

// No class-level transaction: REQUIRES_NEW boundaries require committed data visibility.
class OrderServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OrderService orderService;
    @Autowired
    private PaymentService paymentService;

    @Autowired
    private CartService cartService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @MockitoBean
    private SecurityUtils securityUtils;

    private AppUser createUser() {
        AppUser user = new AppUser();
        user.setUsername("order-it-" + UUID.randomUUID());
        user.setPassword("password");
        user.setEmail("order-it@example.com");
        user.setRole(UserRole.ROLE_USER);
        return userRepository.save(user);
    }

    private Book createBook(String title, BigDecimal price, int stock) {
        Book book = new Book();
        book.setTitle(title);
        book.setAuthor("Author");
        book.setPrice(price);
        book.setCategory("Testing");
        book.setStock(stock);
        book = bookRepository.save(book);
        inventoryRepository.save(new Inventory(book.getId(), stock));
        return book;
    }

    private Order createOrder(AppUser user, Book book, int quantity) {
        when(securityUtils.getCurrentUser()).thenReturn(user);
        cartService.addToCart(book.getId(), quantity);
        return orderService.createOrder();
    }

    @Test
    @DisplayName("Checkout uses frozen cart price after catalog price changes")
    void createOrder_UsesPriceAtAdd_NotCurrentBookPrice() {
        AppUser user = createUser();
        Book book = createBook("Price Freeze Book", new BigDecimal("10.00"), 10);

        when(securityUtils.getCurrentUser()).thenReturn(user);
        cartService.addToCart(book.getId(), 2);

        book.setPrice(new BigDecimal("20.00"));
        bookRepository.save(book);

        Order createdOrder = orderService.createOrder();
        assertNotNull(createdOrder.getId());
        assertEquals(1, createdOrder.getItems().size());

        OrderItem item = createdOrder.getItems().get(0);
        assertEquals(new BigDecimal("10.0000"), item.getUnitPrice());
        assertEquals(new BigDecimal("20.0000"), item.getSubtotal());
        assertEquals(new BigDecimal("20.0000"), createdOrder.getTotalPrice());

        Inventory inventory = inventoryRepository.findByBookId(book.getId()).orElseThrow();
        assertEquals(8, inventory.getAvailableQuantity());
    }

    @Test
    @DisplayName("Payment is created alongside order with PENDING status")
    void createOrder_CreatesPayment() {
        AppUser user = createUser();
        Book book = createBook("Payment Order Book", new BigDecimal("25.00"), 10);
        Order order = createOrder(user, book, 2);

        assertNotNull(order.getId());
        assertTrue(paymentRepository.findByOrderId(order.getId()).isPresent());
        com.example.bookstore.model.Payment payment = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        assertEquals(com.example.bookstore.model.PaymentStatus.PENDING, payment.getPaymentStatus());
        assertEquals(new BigDecimal("50.0000"), payment.getAmount());
        assertEquals("DUMMY", payment.getPaymentProvider());
    }

    @Test
    @DisplayName("Order status transitions and history are persisted")
    void orderStatusTransition_PersistsHistory() {
        AppUser user = createUser();
        Book book = createBook("Transition Test Book", new BigDecimal("15.00"), 10);
          Order order = createOrder(user, book, 1);

          when(securityUtils.getCurrentUser()).thenReturn(user);
          paymentService.processPayment(order.getId(), "CREDIT_CARD");

          Order paidOrder = orderRepository.findById(order.getId()).orElseThrow();
          assertEquals(OrderStatus.PAID, paidOrder.getStatus());

          orderService.updateOrderStatus(order.getId(), OrderStatus.PROCESSING, "Fulfillment started");
        Order processingOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertEquals(OrderStatus.PROCESSING, processingOrder.getStatus());

        List<OrderStatusHistory> history = orderService.getOrderHistory(order.getId());
        assertEquals(2, history.size());
        assertEquals(OrderStatus.PROCESSING, history.get(1).getNewStatus());
        assertEquals(OrderStatus.PAID, history.get(0).getNewStatus());
    }

    @Test
    @DisplayName("Invalid status transition throws exception")
    void invalidStatusTransition_ThrowsException() {
        AppUser user = createUser();
        Book book = createBook("Invalid Transition Book", new BigDecimal("15.00"), 10);
        Order order = createOrder(user, book, 1);

        assertThrows(IllegalStateException.class,
                () -> orderService.updateOrderStatus(order.getId(), OrderStatus.DELIVERED, ""));
    }

    @Test
    @DisplayName("Customer can cancel own PENDING order")
    void customerCancelsPendingOrder_Success() {
        AppUser user = createUser();
        Book book = createBook("Cancel Test Book", new BigDecimal("10.00"), 5);
        Order order = createOrder(user, book, 1);

        assertEquals(OrderStatus.PENDING, order.getStatus());

        orderService.cancelOwnOrder(order.getId());
        Order cancelledOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertEquals(OrderStatus.CANCELLED, cancelledOrder.getStatus());
    }

    @Test
    @DisplayName("Admin cancels PAID order with completed payment: refunds, cancels, restores inventory")
    void adminCancelPaidOrder_withCompletedPayment_refundsCancelsAndRestoresInventory() {
        AppUser user = createUser();
        Book book = createBook("Admin Cancel Book", new BigDecimal("10.00"), 5);
        Order order = createOrder(user, book, 1);

        when(securityUtils.getCurrentUser()).thenReturn(user);
        paymentService.processPayment(order.getId(), "CREDIT_CARD");

        AppUser admin = new AppUser();
        admin.setUsername("admin");
        when(securityUtils.getCurrentUser()).thenReturn(admin);

        Inventory invBefore = inventoryRepository.findByBookId(book.getId()).orElseThrow();
        int availableBefore = invBefore.getAvailableQuantity();
        int reservedBefore = invBefore.getReservedQuantity();

        orderService.adminCancelOrder(order.getId(), "Admin cancellation");

        Order cancelledOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertEquals(OrderStatus.CANCELLED, cancelledOrder.getStatus());

        Payment payment = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        assertEquals(PaymentStatus.REFUNDED, payment.getPaymentStatus());

        Inventory invAfter = inventoryRepository.findByBookId(book.getId()).orElseThrow();
        assertEquals(availableBefore + 1, invAfter.getAvailableQuantity());

        List<OrderStatusHistory> history = orderStatusHistoryRepository.findByOrderIdOrderByCreatedAtAsc(order.getId());
        assertTrue(history.stream().anyMatch(h -> h.getNewStatus() == OrderStatus.CANCELLED));

        List<PaymentHistory> payHistory = paymentService.getPaymentHistory(payment.getId());
        assertTrue(payHistory.stream().anyMatch(h -> h.getNewStatus() == PaymentStatus.REFUNDED));
    }

    @Test
    @DisplayName("History is recorded for each status change")
    void historyRecordedForEachTransition() {
        AppUser user = createUser();
        Book book = createBook("History Test Book", new BigDecimal("10.00"), 5);
          Order order = createOrder(user, book, 1);

          when(securityUtils.getCurrentUser()).thenReturn(user);
          paymentService.processPayment(order.getId(), "CREDIT_CARD");

          orderService.updateOrderStatus(order.getId(), OrderStatus.PROCESSING, "Processing");
          orderService.updateOrderStatus(order.getId(), OrderStatus.SHIPPED, "Shipped");
          orderService.updateOrderStatus(order.getId(), OrderStatus.DELIVERED, "Delivered");

        List<OrderStatusHistory> history = orderService.getOrderHistory(order.getId());
        assertEquals(4, history.size());
        assertEquals(OrderStatus.DELIVERED, history.get(3).getNewStatus());
    }
}
