package com.example.bookstore.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;

import io.github.bucket4j.Refill;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class RateLimitingConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimitingConfig.class);

    @Bean
    @ConditionalOnProperty(name = "app.rate-limiting.enabled", havingValue = "true", matchIfMissing = true)
    public FilterRegistrationBean<RateLimitingFilter> rateLimitingFilter() {
        FilterRegistrationBean<RateLimitingFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RateLimitingFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    public static class RateLimitingFilter implements Filter {

        private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

        @Override
        public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse,
                             FilterChain filterChain) throws IOException, ServletException {

            HttpServletRequest request = (HttpServletRequest) servletRequest;
            HttpServletResponse response = (HttpServletResponse) servletResponse;

            String clientIp = getClientIp(request);
            String path = request.getRequestURI();
            String method = request.getMethod();
            String key = clientIp + ":" + method + ":" + resolveLimitGroup(path);

            Bucket bucket = buckets.computeIfAbsent(key, k -> createBucketForPath(path));

            if (bucket.tryConsume(1)) {
                filterChain.doFilter(request, response);
            } else {
                response.setStatus(429);
                response.setContentType("application/json");
                response.setHeader("Retry-After", "60");
                response.getWriter().write(
                        "{\"status\":429,\"error\":\"Too Many Requests\"," +
                        "\"message\":\"Rate limit exceeded. Please wait before retrying.\"," +
                        "\"code\":\"RATE_LIMIT_EXCEEDED\"}");
            }
        }

        private String resolveLimitGroup(String path) {
            if (path.startsWith("/api/auth/login") || path.startsWith("/api/auth/register")) {
                return "auth";
            }
            if (path.startsWith("/api/orders") || path.startsWith("/api/payments")) {
                return "checkout";
            }
            if (path.startsWith("/api/admin")) {
                return "admin";
            }
            if (path.startsWith("/api")) {
                return "api";
            }
            return "default";
        }

        private Bucket createBucketForPath(String path) {
            String group = resolveLimitGroup(path);
            return switch (group) {
                case "auth" -> newBucket(5, 1);
                case "checkout" -> newBucket(20, 10);
                case "admin" -> newBucket(60, 30);
                case "api" -> newBucket(100, 50);
                default -> newBucket(200, 100);
            };
        }

        private Bucket newBucket(int capacity, int refillPerMinute) {
            Bandwidth limit = Bandwidth.classic(capacity, Refill.greedy(refillPerMinute, Duration.ofMinutes(1)));
            return Bucket.builder().addLimit(limit).build();
        }

        private String getClientIp(HttpServletRequest request) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
            String xri = request.getHeader("X-Real-IP");
            if (xri != null && !xri.isBlank()) {
                return xri;
            }
            return request.getRemoteAddr();
        }
    }
}
