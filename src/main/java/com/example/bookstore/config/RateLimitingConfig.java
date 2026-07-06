package com.example.bookstore.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.RedisTemplate;

import java.io.IOException;
import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = "app.rate-limiting.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(RedisTemplate.class)
public class RateLimitingConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimitingConfig.class);

    static final Duration WINDOW = Duration.ofMinutes(1);

    private final RedisRateLimiter redisRateLimiter;

    public RateLimitingConfig(RedisRateLimiter redisRateLimiter) {
        this.redisRateLimiter = redisRateLimiter;
    }

    @Bean
    public FilterRegistrationBean<RateLimitingFilter> rateLimitingFilter() {
        FilterRegistrationBean<RateLimitingFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RateLimitingFilter(redisRateLimiter));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    public static class RateLimitingFilter implements Filter {

        private final RedisRateLimiter redisRateLimiter;

        public RateLimitingFilter(RedisRateLimiter redisRateLimiter) {
            this.redisRateLimiter = redisRateLimiter;
        }

        @Override
        public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse,
                             FilterChain filterChain) throws IOException, ServletException {

            HttpServletRequest request = (HttpServletRequest) servletRequest;
            HttpServletResponse response = (HttpServletResponse) servletResponse;

            String clientIp = getClientIp(request);
            String path = request.getRequestURI();
            String method = request.getMethod();
            String key = clientIp + ":" + method + ":" + resolveLimitGroup(path);

            LimitConfig limit = getLimitForPath(path);

            if (redisRateLimiter.tryConsume(key, limit.limit(), WINDOW, 1)) {
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
            if (path.startsWith("/api/v1/auth/login") || path.startsWith("/api/v1/auth/register")) {
                return "auth";
            }
            if (path.startsWith("/api/v1/orders") || path.startsWith("/api/v1/payments")) {
                return "checkout";
            }
            if (path.startsWith("/api/v1/admin")) {
                return "admin";
            }
            if (path.startsWith("/api/v1/")) {
                return "api";
            }
            return "default";
        }

        private LimitConfig getLimitForPath(String path) {
            String group = resolveLimitGroup(path);
            return switch (group) {
                case "auth" -> new LimitConfig(5, 60);
                case "checkout" -> new LimitConfig(20, 60);
                case "admin" -> new LimitConfig(60, 60);
                case "api" -> new LimitConfig(100, 60);
                default -> new LimitConfig(200, 60);
            };
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

        private record LimitConfig(int limit, int windowSeconds) {}
    }
}
