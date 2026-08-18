package com.example.bookstore.controller;

import com.example.bookstore.base.AbstractE2ETest;
import com.example.bookstore.config.KafkaTopicConfig;
import com.example.bookstore.event.repository.ProcessedEventRepository;
import com.example.bookstore.model.*;
import com.example.bookstore.notification.model.EmailLog;
import com.example.bookstore.notification.repository.EmailLogRepository;
import com.example.bookstore.repository.*;
import com.example.bookstore.service.payment.DummyPaymentProvider;
import com.example.bookstore.service.payment.PaymentProviderResponse;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EmbeddedKafka(
    topics = {
        KafkaTopicConfig.TOPIC_ORDERS,
        KafkaTopicConfig.TOPIC_PAYMENTS,
        KafkaTopicConfig.TOPIC_INVENTORY,
        KafkaTopicConfig.TOPIC_NOTIFICATIONS
    },
    partitions = 1,
    controlledShutdown = true,
    bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class PurchaseFlowE2ETest extends AbstractE2ETest {

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Autowired
    private EmailLogRepository emailLogRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private com.example.bookstore.repository.CartRepository cartRepository;

    @MockitoBean
    private DummyPaymentProvider paymentProvider;

    private String adminToken;
    private String userToken;
    private Long bookId;

    @BeforeEach
    void setUp() throws Exception {
        emailLogRepository.deleteAll();
        processedEventRepository.deleteAll();
        orderStatusHistoryRepository.deleteAll();
        orderRepository.deleteAll();
        inventoryRepository.deleteAll();
        cartRepository.deleteAll();
        bookRepository.deleteAll();

        when(paymentProvider.getName()).thenReturn("DUMMY");

        adminToken = registerAdminAndGetToken("e2e-admin-" + System.nanoTime());

        String username = "e2e-user-" + System.nanoTime();
        createTestUser(username, UserRole.ROLE_USER);
        userToken = loginAndGetToken(username);

        String bookJson = """
                {"title": "E2E Book", "author": "E2E Author",
                 "price": 29.99, "category": "E2E", "stock": 20}
                """;

        String response = mockMvc.perform(post("/api/v1/books")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        bookId = objectMapper.readTree(response).get("id").asLong();

        mockMvc.perform(put("/api/v1/books/" + bookId + "/stock")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stock\": 20}"))
                .andExpect(status().isOk());
    }

    private Long performPurchase(int quantity) throws Exception {
        mockMvc.perform(post("/api/v1/cart/items")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\": " + bookId + ", \"quantity\": " + quantity + "}"))
                .andExpect(status().isOk());

        String orderResponse = mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(orderResponse).get("id").asLong();
    }

    @Test
    @DisplayName("Section 1: Complete purchase flow via HTTP API")
    void completePurchase_Success() throws Exception {
        when(paymentProvider.processPayment(any()))
                .thenReturn(PaymentProviderResponse.success("TXN-E2E-001", PaymentStatus.COMPLETED));

        Long orderId = performPurchase(3);

        mockMvc.perform(post("/api/v1/payments/" + orderId + "/process")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethod\": \"CREDIT_CARD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.transactionId").isNotEmpty());

        Order order = orderRepository.findById(orderId).orElseThrow();
        assertEquals(OrderStatus.PAID, order.getStatus());

        Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        assertEquals(PaymentStatus.COMPLETED, payment.getPaymentStatus());
        assertNotNull(payment.getTransactionId());

        Inventory inventory = inventoryRepository.findByBookId(bookId).orElseThrow();
        assertEquals(17, inventory.getAvailableQuantity());
        assertEquals(0, inventory.getReservedQuantity());

        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> {
                    List<EmailLog> logs = emailLogRepository.findAll();
                    return logs.stream().anyMatch(l -> "ORDER_CONFIRMATION".equals(l.getEmailType()))
                        && logs.stream().anyMatch(l -> "PAYMENT_SUCCESS".equals(l.getEmailType()));
                });

        List<OrderStatusHistory> history = orderStatusHistoryRepository
                .findByOrderIdOrderByCreatedAtAsc(orderId);
        assertTrue(history.stream().anyMatch(h -> h.getNewStatus() == OrderStatus.PAID));
    }

    @Test
    @DisplayName("Section 2: Payment failure frees inventory and notifies")
    void paymentFailure_FreesInventory() throws Exception {
        when(paymentProvider.processPayment(any()))
                .thenReturn(PaymentProviderResponse.failed("INSUFFICIENT_FUNDS"));

        Long orderId = performPurchase(3);

        mockMvc.perform(post("/api/v1/payments/" + orderId + "/process")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethod\": \"CREDIT_CARD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("INSUFFICIENT_FUNDS"));

        Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        assertEquals(PaymentStatus.FAILED, payment.getPaymentStatus());

        Inventory inventory = inventoryRepository.findByBookId(bookId).orElseThrow();
        assertEquals(20, inventory.getAvailableQuantity(),
                "Inventory should be fully restored after failed payment");

        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> {
                    List<EmailLog> logs = emailLogRepository.findAll();
                    return logs.stream().anyMatch(l -> "PAYMENT_FAILED".equals(l.getEmailType()));
                });
    }

    @Test
    @DisplayName("Section 3: Refund via admin API restores stock and notifies")
    void refundPayment_RestoresStock() throws Exception {
        when(paymentProvider.processPayment(any()))
                .thenReturn(PaymentProviderResponse.success("TXN-REFUND-001", PaymentStatus.COMPLETED));
        when(paymentProvider.refundPayment(any()))
                .thenReturn(PaymentProviderResponse.success("TXN-REFUND-REF-001", PaymentStatus.REFUNDED));

        Long orderId = performPurchase(2);

        mockMvc.perform(post("/api/v1/payments/" + orderId + "/process")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethod\": \"CREDIT_CARD\"}"))
                .andExpect(status().isOk());

        Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        Long paymentId = payment.getId();

        mockMvc.perform(post("/api/v1/admin/payments/" + paymentId + "/refund")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("REFUNDED"));

        Payment refunded = paymentRepository.findById(paymentId).orElseThrow();
        assertEquals(PaymentStatus.REFUNDED, refunded.getPaymentStatus());

        Inventory inventory = inventoryRepository.findByBookId(bookId).orElseThrow();
        assertEquals(20, inventory.getAvailableQuantity(),
                "Stock should be restored to original after refund");

        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> {
                    List<EmailLog> logs = emailLogRepository.findAll();
                    return logs.stream().anyMatch(l -> "REFUND_NOTIFICATION".equals(l.getEmailType()));
                });
    }

    @Test
    @DisplayName("Section 4: Customer cancels pending order via API")
    void cancelOrder_ReleasesInventory() throws Exception {
        Long orderId = performPurchase(4);

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());

        Order order = orderRepository.findById(orderId).orElseThrow();
        assertEquals(OrderStatus.CANCELLED, order.getStatus());

        Inventory inventory = inventoryRepository.findByBookId(bookId).orElseThrow();
        assertEquals(20, inventory.getAvailableQuantity(),
                "Inventory should be fully restored after cancellation");
        assertEquals(0, inventory.getReservedQuantity());

        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> {
                    List<EmailLog> logs = emailLogRepository.findAll();
                    return logs.stream().anyMatch(l -> "ORDER_STATUS_CHANGED".equals(l.getEmailType()));
                });
    }

    @Test
    @DisplayName("Section 4: Admin cancels paid order releases inventory")
    void adminCancelsPaidOrder_ReleasesInventory() throws Exception {
        when(paymentProvider.processPayment(any()))
                .thenReturn(PaymentProviderResponse.success("TXN-CANCEL-001", PaymentStatus.COMPLETED));

        Long orderId = performPurchase(1);

        mockMvc.perform(post("/api/v1/payments/" + orderId + "/process")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethod\": \"CREDIT_CARD\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/orders/" + orderId + "/cancel")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        Order order = orderRepository.findById(orderId).orElseThrow();
        assertEquals(OrderStatus.CANCELLED, order.getStatus());

        Inventory inventory = inventoryRepository.findByBookId(bookId).orElseThrow();
        assertEquals(20, inventory.getAvailableQuantity(),
                "Inventory should be restored after admin cancellation");
    }

    @Test
    @DisplayName("Section 4: Repeated cancellation is rejected")
    void repeatedCancellation_Rejected() throws Exception {
        Long orderId = performPurchase(2);

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Section 10: Data consistency after complete purchase")
    void purchase_DataConsistency() throws Exception {
        when(paymentProvider.processPayment(any()))
                .thenReturn(PaymentProviderResponse.success("TXN-CONSIST-001", PaymentStatus.COMPLETED));

        Long orderId = performPurchase(5);

        mockMvc.perform(post("/api/v1/payments/" + orderId + "/process")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethod\": \"CREDIT_CARD\"}"))
                .andExpect(status().isOk());

        Order order = orderRepository.findById(orderId).orElseThrow();
        assertEquals(OrderStatus.PAID, order.getStatus());
        assertEquals(0, new BigDecimal("149.95").compareTo(order.getTotalPrice()));

        Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        assertEquals(PaymentStatus.COMPLETED, payment.getPaymentStatus());
        assertNotNull(payment.getCompletedAt());
        assertNotNull(payment.getTransactionId());

        Inventory inventory = inventoryRepository.findByBookId(bookId).orElseThrow();
        assertEquals(15, inventory.getAvailableQuantity());
        assertEquals(0, inventory.getReservedQuantity());

        List<OrderStatusHistory> history = orderStatusHistoryRepository
                .findByOrderIdOrderByCreatedAtAsc(orderId);
        assertTrue(history.stream().anyMatch(h -> h.getNewStatus() == OrderStatus.PAID));

        List<EmailLog> emailLogs = emailLogRepository.findAll();
        assertFalse(emailLogs.isEmpty());

        assertEquals(1, orderRepository.count());
        assertEquals(1, paymentRepository.count());
        assertEquals(1, inventoryRepository.count());
    }
}
