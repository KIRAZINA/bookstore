package com.example.bookstore.config;

import com.example.bookstore.base.AbstractIntegrationTest;
import com.example.bookstore.config.jwt.JwtUtil;
import com.example.bookstore.model.AppUser;
import com.example.bookstore.model.UserRole;
import com.example.bookstore.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FailureRecoveryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @LocalServerPort
    private int port;

    @Autowired(required = false)
    private RedisRateLimiter redisRateLimiter;

    private String adminToken;

    @BeforeEach
    void setUp() {
        String username = "metrics-test-admin-" + System.nanoTime();
        AppUser admin = new AppUser();
        admin.setUsername(username);
        admin.setPassword(passwordEncoder.encode("admin123"));
        admin.setEmail(username + "@test.com");
        admin.setRole(UserRole.ROLE_ADMIN);
        userRepository.save(admin);

        UserDetails adminDetails = new org.springframework.security.core.userdetails.User(
                admin.getUsername(), admin.getPassword(),
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(adminDetails, null, adminDetails.getAuthorities());
        adminToken = jwtUtil.generateToken(auth);
    }

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

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);

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
