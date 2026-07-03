package com.example.bookstore.performance.observability;

import com.example.bookstore.base.AbstractPerformanceTest;
import com.example.bookstore.model.UserRole;
import com.example.bookstore.performance.LatencyStatistics;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Section 11: Observability remains available under concurrent load.
 */
@Tag("performance")
class ObservabilityUnderLoadTest extends AbstractPerformanceTest {

    private static final int CONCURRENT_REQUESTS = 50;

    @Autowired
    private MeterRegistry meterRegistry;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = loginAndGetToken(
                createTestUser("obs-admin-" + System.nanoTime(), UserRole.ROLE_ADMIN).getUsername());
    }

    @Test
    @DisplayName("Section 11: Prometheus metrics available under concurrent API load")
    void prometheusAvailableUnderLoad() throws InterruptedException {
        AtomicInteger apiSuccess = new AtomicInteger();
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENT_REQUESTS);

        try (ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS)) {
            for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
                executor.submit(() -> {
                    try {
                        startGate.await();
                        ResponseEntity<String> response = restTemplate.getForEntity(
                                baseUrl() + "/api/v1/books",
                                String.class
                        );
                        if (response.getStatusCode() == HttpStatus.OK) {
                            apiSuccess.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            startGate.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
        }

        LatencyStatistics metricsLatencies = new LatencyStatistics(20);
        for (int i = 0; i < 20; i++) {
            long start = System.nanoTime();
            ResponseEntity<String> metrics = restTemplate.exchange(
                    baseUrl() + "/actuator/prometheus",
                    HttpMethod.GET,
                    authEntity(adminToken),
                    String.class
            );
            metricsLatencies.recordNanos(System.nanoTime() - start);
            assertEquals(HttpStatus.OK, metrics.getStatusCode());
            assertNotNull(metrics.getBody());
            assertTrue(metrics.getBody().contains("jvm_memory_used_bytes"));
            assertTrue(metrics.getBody().contains("http_server_requests_seconds"));
        }

        System.out.printf("%n=== Observability Under Load ===%n  concurrent API successes=%d%n",
                apiSuccess.get());
        metricsLatencies.snapshot("Prometheus scrape under load").log();

        assertNotNull(meterRegistry.find("http.server.requests").timer());
    }
}
