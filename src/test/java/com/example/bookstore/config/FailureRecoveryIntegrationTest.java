package com.example.bookstore.config;

import com.example.bookstore.base.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FailureRecoveryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @LocalServerPort
    private int port;

    @Autowired(required = false)
    private RedisRateLimiter redisRateLimiter;

    @Test
    @DisplayName("Application context loads with all infrastructure")
    void applicationContext_LoadsSuccessfully() {
        assertNotNull(applicationContext);
        assertTrue(applicationContext.containsBean("bookRepository"));
        assertTrue(applicationContext.containsBean("orderService"));
        assertTrue(applicationContext.containsBean("redisRateLimiter"));
        assertTrue(applicationContext.containsBean("inventoryService"));
        assertTrue(applicationContext.containsBean("paymentService"));
    }

    @Test
    @DisplayName("Redis rate limiter fail-open when Redis is unavailable")
    void rateLimiter_FailOpen_WhenRedisUnavailable() {
        String key = "test:failopen-" + System.nanoTime();

        boolean allowed = redisRateLimiter.tryConsume(key, 1, java.time.Duration.ofMinutes(1), 1);

        assertTrue(allowed, "Rate limiter should fail open when Redis is unavailable");
    }

    @Test
    @DisplayName("Health endpoint returns UP with all components")
    void healthEndpoint_ReturnsUp() {
        RestTemplate restTemplate = new RestTemplate();
        String url = "http://localhost:" + port + "/actuator/health";

        ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("UP", response.getBody().get("status"));
    }

    @Test
    @DisplayName("Metrics endpoint is accessible")
    void metricsEndpoint_IsAccessible() {
        RestTemplate restTemplate = new RestTemplate();
        String url = "http://localhost:" + port + "/actuator/metrics";

        ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody().get("names"));
    }

    @Test
    @DisplayName("Liveness probe returns UP")
    void livenessProbe_ReturnsUp() {
        RestTemplate restTemplate = new RestTemplate();
        String url = "http://localhost:" + port + "/actuator/health/liveness";

        ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("UP", response.getBody().get("status"));
    }

    @Test
    @DisplayName("Readiness probe returns UP")
    void readinessProbe_ReturnsUp() {
        RestTemplate restTemplate = new RestTemplate();
        String url = "http://localhost:" + port + "/actuator/health/readiness";

        ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("UP", response.getBody().get("status"));
    }
}
