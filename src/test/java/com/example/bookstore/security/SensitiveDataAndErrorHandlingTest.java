package com.example.bookstore.security;

import com.example.bookstore.base.AbstractControllerTest;
import com.example.bookstore.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("Sensitive Data & Error Handling Security Tests")
class SensitiveDataAndErrorHandlingTest extends AbstractControllerTest {

    @Autowired
    private UserRepository userRepository;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        userRepository.deleteAll();
        adminToken = registerAdminAndGetToken("sec-data-admin-" + System.nanoTime());
    }

    @Test
    @DisplayName("Error responses don't expose stack traces")
    void errorResponses_DoNotExposeStackTraces() throws Exception {
        mockMvc.perform(get("/api/v1/books/9999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    @Test
    @DisplayName("Error responses use proper HTTP status codes")
    void errorResponses_UseProperStatusCodes() throws Exception {
        mockMvc.perform(post("/api/v1/books")
                        .contentType("application/json")
                        .content("{invalid}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/admin/inventory"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Actuator sensitive endpoints are protected")
    void actuatorSensitiveEndpoints_Protected() throws Exception {
        mockMvc.perform(get("/actuator"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Actuator health endpoint is public")
    void actuatorHealthEndpoint_IsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
