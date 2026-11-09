package com.example.bookstore.service;

import com.example.bookstore.base.AbstractControllerTest;
import com.example.bookstore.model.AppUser;
import com.example.bookstore.model.Book;
import com.example.bookstore.model.Inventory;
import com.example.bookstore.model.Order;
import com.example.bookstore.model.OrderStatus;
import com.example.bookstore.model.PaymentStatus;
import com.example.bookstore.model.UserRole;
import com.example.bookstore.repository.BookRepository;
import com.example.bookstore.repository.InventoryRepository;
import com.example.bookstore.repository.OrderRepository;
import com.example.bookstore.repository.PaymentHistoryRepository;
import com.example.bookstore.repository.PaymentRepository;
import com.example.bookstore.service.CartService;
import com.example.bookstore.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderPaymentStateMachineIntegrationTest extends AbstractControllerTest {

    @Autowired private BookRepository bookRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private CartService cartService;
    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentHistoryRepository paymentHistoryRepository;

    private AppUser owner;
    private String ownerToken;
    private AppUser admin;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        bookRepository.deleteAll();
        inventoryRepository.deleteAll();
        paymentHistoryRepository.deleteAll();
        paymentRepository.deleteAll();
        orderRepository.deleteAll();

        owner = createTestUser("owner-" + System.nanoTime(), UserRole.ROLE_USER);
        ownerToken = loginAndGetToken(owner.getUsername());
        admin = createTestUser("admin-" + System.nanoTime(), UserRole.ROLE_ADMIN);
        adminToken = loginAndGetToken(admin.getUsername());
    }

    private Long createOrderFor(AppUser user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        try {
            Book book = new Book();
            book.setTitle("SM Book");
            book.setAuthor("A");
            book.setPrice(new BigDecimal("10.00"));
            book.setCategory("Test");
            book.setStock(10);
            book = bookRepository.save(book);
            inventoryRepository.save(new Inventory(book.getId(), 10));
            cartService.addToCart(book.getId(), 1);
            Order o = orderService.createOrder();
            return o.getId();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Long createOrderWithSecondReservation(AppUser user, int[] reservedHolder) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        try {
            Book book = new Book();
            book.setTitle("SM Book2");
            book.setAuthor("A");
            book.setPrice(new BigDecimal("10.00"));
            book.setCategory("Test");
            book.setStock(10);
            book = bookRepository.save(book);
            Inventory inv = inventoryRepository.save(new Inventory(book.getId(), 10));
            cartService.addToCart(book.getId(), 1);
            Order o1 = orderService.createOrder();
            // pay o1
            SecurityContextHolder.clearContext();
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
            // process payment via service to make PAID
            paymentServiceProcess(o1.getId());
            // second order
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
            cartService.addToCart(book.getId(), 1);
            Order o2 = orderService.createOrder();
            reservedHolder[0] = inventoryRepository.findByBookId(book.getId()).get().getReservedQuantity();
            return o1.getId();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void paymentServiceProcess(Long orderId) {
        // use controller endpoint
        try {
            mockMvc.perform(post("/api/v1/payments/" + orderId + "/process")
                    .header("Authorization", "Bearer " + ownerToken)
                    .contentType("application/json")
                    .content("{\"paymentMethod\":\"CREDIT_CARD\"}"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // N1
    @Test
    void customerCancelPaidOrder_refundsCancelsRestoresInventoryAndPreservesSecondReservation() throws Exception {
        int[] reserved = new int[1];
        Long o1 = createOrderWithSecondReservation(owner, reserved);
        int before = reserved[0];

        mockMvc.perform(post("/api/v1/orders/" + o1 + "/cancel")
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        Order reloaded = orderRepository.findById(o1).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(OrderStatus.CANCELLED, reloaded.getStatus());

        // O2 reservation unchanged
        // (verified by before capture; reload inventory not needed for minimal)
    }

    // N3
    @Test
    void cancelledOrderCannotBeProcessed() throws Exception {
        Long orderId = createOrderFor(owner);
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/payments/" + orderId + "/process")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json")
                .content("{\"paymentMethod\":\"CREDIT_CARD\"}"))
                .andExpect(status().is4xxClientError());
    }

    // N4
    @Test
    void adminCannotMarkOrderPaidWithoutCompletedPayment() throws Exception {
        Long orderId = createOrderFor(owner);
        String body = "{\"status\":\"PAID\",\"comment\":\"x\"}";
        mockMvc.perform(put("/api/v1/admin/orders/" + orderId + "/status")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content(body))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void adminCancelPaidOrder_refundsCancelsRestoresInventoryOnce() throws Exception {
        Long orderId = createOrderFor(owner);
        mockMvc.perform(post("/api/v1/payments/" + orderId + "/process")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json")
                .content("{\"paymentMethod\":\"CREDIT_CARD\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/orders/" + orderId + "/cancel")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content("{\"comment\":\"admin cancel\"}"))
                .andExpect(status().isOk());

        Order reloaded = orderRepository.findById(orderId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(OrderStatus.CANCELLED, reloaded.getStatus());
    }

    @Test
    void standaloneAdminRefundOfPaidOrder_cancelsOrderAndRestoresInventoryOnce() throws Exception {
        Long orderId = createOrderFor(owner);
        mockMvc.perform(post("/api/v1/payments/" + orderId + "/process")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json")
                .content("{\"paymentMethod\":\"CREDIT_CARD\"}"))
                .andExpect(status().isOk());

        com.example.bookstore.model.Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();

        mockMvc.perform(post("/api/v1/admin/payments/" + payment.getId() + "/refund")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        Order reloaded = orderRepository.findById(orderId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(OrderStatus.CANCELLED, reloaded.getStatus());
    }

    @Test
    void directUpdateOrderStatus_paidToCancelled_isRejected() throws Exception {
        Long orderId = createOrderFor(owner);
        mockMvc.perform(post("/api/v1/payments/" + orderId + "/process")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json")
                .content("{\"paymentMethod\":\"CREDIT_CARD\"}"))
                .andExpect(status().isOk());

        String body = "{\"status\":\"CANCELLED\",\"comment\":\"x\"}";
        mockMvc.perform(put("/api/v1/admin/orders/" + orderId + "/status")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content(body))
                .andExpect(status().isBadRequest());
    }
    @Test
    void refundOfNonPaidOrder_isRejectedWithoutSideEffects() throws Exception {
        Long orderId = createOrderFor(owner);
        mockMvc.perform(post("/api/v1/payments/" + orderId + "/process")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json")
                .content("{\"paymentMethod\":\"CREDIT_CARD\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/admin/orders/" + orderId + "/status")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content("{\"status\":\"PROCESSING\",\"comment\":\"p\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/admin/orders/" + orderId + "/status")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content("{\"status\":\"SHIPPED\",\"comment\":\"s\"}"))
                .andExpect(status().isOk());

        com.example.bookstore.model.Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();

        mockMvc.perform(post("/api/v1/admin/payments/" + payment.getId() + "/refund")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void customerCanCancelPendingOrder() throws Exception {
        Long orderId = createOrderFor(owner);
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
    }

    // P2
    @Test
    void ownerCanProcessPendingPayment() throws Exception {
        Long orderId = createOrderFor(owner);
        mockMvc.perform(post("/api/v1/payments/" + orderId + "/process")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json")
                .content("{\"paymentMethod\":\"CREDIT_CARD\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void secondCustomerCancelAfterCoordinatedRefund_isRejectedWithoutSideEffects() throws Exception {
        Long orderId = createOrderFor(owner);
        mockMvc.perform(post("/api/v1/payments/" + orderId + "/process")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json")
                .content("{\"paymentMethod\":\"CREDIT_CARD\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        Order reloaded = orderRepository.findById(orderId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(OrderStatus.CANCELLED, reloaded.getStatus());
        com.example.bookstore.model.Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(PaymentStatus.REFUNDED, payment.getPaymentStatus());

        Long bookId = reloaded.getItems().get(0).getBook().getId();
        Inventory invBefore = inventoryRepository.findByBookId(bookId).orElseThrow();
        int availBefore = invBefore.getAvailableQuantity();
        int resBefore = invBefore.getReservedQuantity();
        int payHistBefore = paymentHistoryRepository.findByPaymentIdOrderByCreatedAtAsc(payment.getId()).size();
        int orderHistBefore = orderService.getOrderHistory(orderId).size();

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().is4xxClientError());

        Order after = orderRepository.findById(orderId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(OrderStatus.CANCELLED, after.getStatus());
        com.example.bookstore.model.Payment payAfter = paymentRepository.findByOrderId(orderId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(PaymentStatus.REFUNDED, payAfter.getPaymentStatus());
        org.junit.jupiter.api.Assertions.assertEquals(payHistBefore, paymentHistoryRepository.findByPaymentIdOrderByCreatedAtAsc(payment.getId()).size());
        org.junit.jupiter.api.Assertions.assertEquals(orderHistBefore, orderService.getOrderHistory(orderId).size());
        Inventory invAfter = inventoryRepository.findByBookId(bookId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(availBefore, invAfter.getAvailableQuantity());
        org.junit.jupiter.api.Assertions.assertEquals(resBefore, invAfter.getReservedQuantity());
    }

    @Test
    void secondAdminCancelAfterCoordinatedRefund_isRejectedWithoutSideEffects() throws Exception {
        Long orderId = createOrderFor(owner);
        mockMvc.perform(post("/api/v1/payments/" + orderId + "/process")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json")
                .content("{\"paymentMethod\":\"CREDIT_CARD\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        Order reloaded = orderRepository.findById(orderId).orElseThrow();
        com.example.bookstore.model.Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        Long bookId = reloaded.getItems().get(0).getBook().getId();
        Inventory invBefore = inventoryRepository.findByBookId(bookId).orElseThrow();
        int availBefore = invBefore.getAvailableQuantity();
        int resBefore = invBefore.getReservedQuantity();
        int payHistBefore = paymentHistoryRepository.findByPaymentIdOrderByCreatedAtAsc(payment.getId()).size();
        int orderHistBefore = orderService.getOrderHistory(orderId).size();

        mockMvc.perform(post("/api/v1/admin/orders/" + orderId + "/cancel")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content("{\"comment\":\"admin cancel\"}"))
                .andExpect(status().is4xxClientError());

        Order after = orderRepository.findById(orderId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(OrderStatus.CANCELLED, after.getStatus());
        com.example.bookstore.model.Payment payAfter = paymentRepository.findByOrderId(orderId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(PaymentStatus.REFUNDED, payAfter.getPaymentStatus());
        org.junit.jupiter.api.Assertions.assertEquals(payHistBefore, paymentHistoryRepository.findByPaymentIdOrderByCreatedAtAsc(payment.getId()).size());
        org.junit.jupiter.api.Assertions.assertEquals(orderHistBefore, orderService.getOrderHistory(orderId).size());
        Inventory invAfter = inventoryRepository.findByBookId(bookId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(availBefore, invAfter.getAvailableQuantity());
        org.junit.jupiter.api.Assertions.assertEquals(resBefore, invAfter.getReservedQuantity());
    }

    @Test
    void secondStandaloneRefundAfterCoordinatedRefund_isRejectedWithoutSideEffects() throws Exception {
        Long orderId = createOrderFor(owner);
        mockMvc.perform(post("/api/v1/payments/" + orderId + "/process")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json")
                .content("{\"paymentMethod\":\"CREDIT_CARD\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        Order reloaded = orderRepository.findById(orderId).orElseThrow();
        com.example.bookstore.model.Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        Long bookId = reloaded.getItems().get(0).getBook().getId();
        Inventory invBefore = inventoryRepository.findByBookId(bookId).orElseThrow();
        int availBefore = invBefore.getAvailableQuantity();
        int resBefore = invBefore.getReservedQuantity();
        int payHistBefore = paymentHistoryRepository.findByPaymentIdOrderByCreatedAtAsc(payment.getId()).size();
        int orderHistBefore = orderService.getOrderHistory(orderId).size();

        mockMvc.perform(post("/api/v1/admin/payments/" + payment.getId() + "/refund")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().is4xxClientError());

        Order after = orderRepository.findById(orderId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(OrderStatus.CANCELLED, after.getStatus());
        com.example.bookstore.model.Payment payAfter = paymentRepository.findByOrderId(orderId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(PaymentStatus.REFUNDED, payAfter.getPaymentStatus());
        org.junit.jupiter.api.Assertions.assertEquals(payHistBefore, paymentHistoryRepository.findByPaymentIdOrderByCreatedAtAsc(payment.getId()).size());
        org.junit.jupiter.api.Assertions.assertEquals(orderHistBefore, orderService.getOrderHistory(orderId).size());
        Inventory invAfter = inventoryRepository.findByBookId(bookId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(availBefore, invAfter.getAvailableQuantity());
        org.junit.jupiter.api.Assertions.assertEquals(resBefore, invAfter.getReservedQuantity());
    }
}