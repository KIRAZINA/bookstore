package com.example.bookstore.operational;

import com.example.bookstore.base.AbstractControllerTest;
import com.example.bookstore.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("Operational Readiness Tests")
class OperationalReadinessTest extends AbstractControllerTest {

    @Autowired
    private ApplicationContext applicationContext;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        userRepository.findByUsername("op-readiness-admin")
                .orElseGet(() -> createTestUser("op-readiness-admin", UserRole.ROLE_ADMIN));
        adminToken = loginAndGetToken("op-readiness-admin");
    }

    @Nested
    @DisplayName("Application Startup Validation")
    class ApplicationStartupValidation {
        @Test
        @DisplayName("ApplicationContext loads successfully")
        void applicationContextLoads() {
            assertNotNull(applicationContext);
        }

        @Test
        @DisplayName("All beans are initialized")
        void allBeansInitialized() {
            String[] beanNames = applicationContext.getBeanDefinitionNames();
            assertThat(beanNames.length).isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("Health Endpoints Validation")
    class HealthEndpointsValidation {
        @Test
        @DisplayName("Liveness endpoint returns UP")
        void livenessEndpointReturnsUp() throws Exception {
            mockMvc.perform(get("/actuator/health/liveness"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Readiness endpoint returns UP")
        void readinessEndpointReturnsUp() throws Exception {
            mockMvc.perform(get("/actuator/health/readiness"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Startup endpoint returns UP")
        void startupEndpointReturnsUp() throws Exception {
            mockMvc.perform(get("/actuator/health/startup"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Health endpoint returns UP")
        void healthEndpointReturnsUp() throws Exception {
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Info endpoint returns application info")
        void infoEndpointReturnsAppInfo() throws Exception {
            mockMvc.perform(get("/actuator/info"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("Metrics Validation")
    class MetricsValidation {
        @Test
        @DisplayName("Prometheus endpoint is available")
        void prometheusEndpointAvailable() throws Exception {
            mockMvc.perform(get("/actuator/prometheus")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Metrics endpoint is available")
        void metricsEndpointAvailable() throws Exception {
            mockMvc.perform(get("/actuator/metrics")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("Application Availability")
    class ApplicationAvailability {
        @Test
        @DisplayName("Public API endpoint responds")
        void publicApiEndpointResponds() throws Exception {
            mockMvc.perform(get("/api/v1/books"))
                    .andExpect(status().isOk());
        }
    }
}
