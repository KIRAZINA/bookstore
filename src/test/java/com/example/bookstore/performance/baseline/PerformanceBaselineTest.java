package com.example.bookstore.performance.baseline;

import com.example.bookstore.base.AbstractPerformanceTest;
import com.example.bookstore.model.PaymentStatus;
import com.example.bookstore.model.UserRole;
import com.example.bookstore.performance.LatencyStatistics;
import com.example.bookstore.repository.BookRepository;
import com.example.bookstore.repository.CartRepository;
import com.example.bookstore.repository.InventoryRepository;
import com.example.bookstore.repository.OrderRepository;
import com.example.bookstore.repository.PaymentRepository;
import com.example.bookstore.service.payment.PaymentProviderResponse;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Section 1: Performance baseline for critical business operations.
 * Measures end-to-end HTTP latency against a production-like stack (Postgres, Redis, Kafka).
 */
@Tag("performance")
class PerformanceBaselineTest extends AbstractPerformanceTest {

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private DataSource dataSource;

    private String userToken;
    private String adminToken;
    private Long bookId;

    @BeforeEach
    void setUp() throws Exception {
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
        cartRepository.deleteAll();
        inventoryRepository.deleteAll();
        bookRepository.deleteAll();

        adminToken = loginAndGetToken(createTestUser("perf-admin-" + System.nanoTime(), UserRole.ROLE_ADMIN).getUsername());
        String username = "perf-user-" + System.nanoTime();
        createTestUser(username, UserRole.ROLE_USER);
        userToken = loginAndGetToken(username);

        ResponseEntity<String> bookResponse = restTemplate.exchange(
                baseUrl() + "/api/v1/books",
                HttpMethod.POST,
                jsonEntity(adminToken, """
                        {"title":"Perf Book","author":"Perf Author","price":19.99,"category":"Performance","initialStock":1000}
                        """),
                String.class
        );
        assertEquals(HttpStatus.CREATED, bookResponse.getStatusCode());
        bookId = objectMapper.readTree(bookResponse.getBody()).get("id").asLong();

        restTemplate.exchange(
                baseUrl() + "/api/v1/books/" + bookId + "/stock",
                HttpMethod.PUT,
                jsonEntity(adminToken, "{\"stock\": 1000}"),
                String.class
        );

        when(paymentProvider.processPayment(any()))
                .thenReturn(PaymentProviderResponse.success("TXN-PERF", PaymentStatus.COMPLETED));
        when(paymentProvider.refundPayment(any()))
                .thenReturn(PaymentProviderResponse.success("TXN-REFUND", PaymentStatus.REFUNDED));
    }

    @Test
    @DisplayName("Section 1: Baseline latency for critical operations")
    void baselineCriticalOperations() throws Exception {
        LatencyStatistics authStats = measure("Authentication (login)", this::measureLogin);
        LatencyStatistics searchStats = measure("Book search", this::measureBookSearch);
        LatencyStatistics listStats = measure("Book retrieval (list)", this::measureBookList);
        LatencyStatistics orderStats = measure("Order creation + inventory reservation", this::measureOrderCreation);
        LatencyStatistics paymentStats = measure("Payment processing", this::measurePaymentProcessing);
        LatencyStatistics refundStats = measure("Refund (admin)", this::measureRefund);

        System.out.println("\n========== PERFORMANCE BASELINE REPORT ==========");
        authStats.snapshot("Authentication").log();
        searchStats.snapshot("Book search").log();
        listStats.snapshot("Book retrieval").log();
        orderStats.snapshot("Order creation").log();
        paymentStats.snapshot("Payment processing").log();
        refundStats.snapshot("Refund").log();
        logJvmAndPoolMetrics();

        assertTrue(authStats.size() > 0);
        assertTrue(orderStats.size() > 0);
    }

    private LatencyStatistics measure(String name, MeasuredOperation operation) throws Exception {
        for (int i = 0; i < DEFAULT_WARMUP; i++) {
            operation.run();
        }
        LatencyStatistics stats = new LatencyStatistics(DEFAULT_ITERATIONS);
        for (int i = 0; i < DEFAULT_ITERATIONS; i++) {
            long start = System.nanoTime();
            operation.run();
            stats.recordNanos(System.nanoTime() - start);
        }
        return stats;
    }

    private void measureLogin() throws Exception {
        loginAndGetToken(createTestUser("login-" + System.nanoTime(), UserRole.ROLE_USER).getUsername());
    }

    private void measureBookSearch() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                baseUrl() + "/api/v1/books/search?search=Perf",
                String.class
        );
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    private void measureBookList() {
        ResponseEntity<String> response = restTemplate.getForEntity(baseUrl() + "/api/v1/books", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    private void measureOrderCreation() {
        String uniqueUser = "order-" + System.nanoTime();
        createTestUser(uniqueUser, UserRole.ROLE_USER);
        String token;
        try {
            token = loginAndGetToken(uniqueUser);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        restTemplate.exchange(
                baseUrl() + "/api/v1/cart/items",
                HttpMethod.POST,
                jsonEntity(token, "{\"bookId\": " + bookId + ", \"quantity\": 1}"),
                String.class
        );

        ResponseEntity<String> orderResponse = restTemplate.exchange(
                baseUrl() + "/api/v1/orders",
                HttpMethod.POST,
                authEntity(token),
                String.class
        );
        assertEquals(HttpStatus.OK, orderResponse.getStatusCode());
    }

    private void measurePaymentProcessing() throws Exception {
        Long orderId = createOrderForUser(userToken);
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/api/v1/payments/" + orderId + "/process",
                HttpMethod.POST,
                jsonEntity(userToken, "{\"paymentMethod\": \"CREDIT_CARD\"}"),
                String.class
        );
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    private void measureRefund() throws Exception {
        Long orderId = createOrderForUser(userToken);
        restTemplate.exchange(
                baseUrl() + "/api/v1/payments/" + orderId + "/process",
                HttpMethod.POST,
                jsonEntity(userToken, "{\"paymentMethod\": \"CREDIT_CARD\"}"),
                String.class
        );
        Long paymentId = paymentRepository.findByOrderId(orderId).orElseThrow().getId();
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/api/v1/admin/payments/" + paymentId + "/refund",
                HttpMethod.POST,
                authEntity(adminToken),
                String.class
        );
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    private Long createOrderForUser(String token) throws Exception {
        restTemplate.exchange(
                baseUrl() + "/api/v1/cart/items",
                HttpMethod.POST,
                jsonEntity(token, "{\"bookId\": " + bookId + ", \"quantity\": 1}"),
                String.class
        );
        ResponseEntity<String> orderResponse = restTemplate.exchange(
                baseUrl() + "/api/v1/orders",
                HttpMethod.POST,
                authEntity(token),
                String.class
        );
        return objectMapper.readTree(orderResponse.getBody()).get("id").asLong();
    }

    private void logJvmAndPoolMetrics() {
        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long maxMb = runtime.maxMemory() / (1024 * 1024);
        System.out.printf("%n--- JVM ---%n  heap used=%dMB  heap max=%dMB%n", usedMb, maxMb);

        if (dataSource instanceof HikariDataSource hikari) {
            System.out.printf("--- HikariCP ---%n  active=%d  idle=%d  total=%d  awaiting=%d%n",
                    hikari.getHikariPoolMXBean().getActiveConnections(),
                    hikari.getHikariPoolMXBean().getIdleConnections(),
                    hikari.getHikariPoolMXBean().getTotalConnections(),
                    hikari.getHikariPoolMXBean().getThreadsAwaitingConnection());
        }

        Double checkoutMean = meterRegistry.find("bookstore.checkout.duration").timer() != null
                ? meterRegistry.find("bookstore.checkout.duration").timer().mean(java.util.concurrent.TimeUnit.MILLISECONDS)
                : null;
        if (checkoutMean != null) {
            System.out.printf("--- Micrometer ---%n  bookstore.checkout.duration mean=%.2fms%n", checkoutMean);
        }
    }

    @FunctionalInterface
    private interface MeasuredOperation {
        void run() throws Exception;
    }
}
