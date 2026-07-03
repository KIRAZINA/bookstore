package com.example.bookstore.performance.ratelimit;

import com.example.bookstore.base.AbstractIntegrationTest;
import com.example.bookstore.performance.LatencyStatistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Section 10: Rate limiter HTTP filter performance under attack-like traffic.
 */
@Tag("performance")
@AutoConfigureMockMvc
@ActiveProfiles({"test", "perf-test"})
@TestPropertySource(properties = "app.rate-limiting.enabled=true")
class RateLimiterHttpPerformanceTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Section 10: Rate limiter rejects excess traffic with stable latency")
    void rateLimiterUnderAttackTraffic() throws Exception {
        int totalRequests = 150;
        int allowed = 0;
        int rejected = 0;
        LatencyStatistics latencies = new LatencyStatistics(totalRequests);

        for (int i = 0; i < totalRequests; i++) {
            long start = System.nanoTime();
            int status = mockMvc.perform(get("/api/v1/books"))
                    .andReturn()
                    .getResponse()
                    .getStatus();
            latencies.recordNanos(System.nanoTime() - start);

            if (status == 200) {
                allowed++;
            } else if (status == 429) {
                rejected++;
            }
        }

        System.out.printf("%n=== Rate Limiter HTTP (%d requests) ===%n  allowed=%d  rejected=%d%n",
                totalRequests, allowed, rejected);
        latencies.snapshot("GET /api/v1/books under rate limit").log();

        assertTrue(allowed <= 100, "API group limit is 100/min");
        assertTrue(rejected > 0, "Excess traffic should be rejected with 429");
    }
}
