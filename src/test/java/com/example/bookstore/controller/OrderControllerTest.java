package com.example.bookstore.controller;

import com.example.bookstore.config.GlobalExceptionHandler;
import com.example.bookstore.config.jwt.JwtUtil;
import com.example.bookstore.service.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderService orderService;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private UserDetailsService userDetailsService;

    @Test
    @DisplayName("POST /api/orders returns 409 on optimistic lock conflict")
    void createOrder_OptimisticLockConflict_ReturnsConflict() throws Exception {
        when(orderService.createOrder())
                .thenThrow(new ObjectOptimisticLockingFailureException("Book", 1L));

        mockMvc.perform(post("/api/orders"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Concurrent modification detected. Please retry."))
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }
}
