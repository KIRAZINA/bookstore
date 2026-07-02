package com.example.bookstore.controller;

import com.example.bookstore.config.GlobalExceptionHandler;
import com.example.bookstore.config.jwt.JwtUtil;
import com.example.bookstore.metrics.BusinessMetrics;
import com.example.bookstore.service.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrderController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private BusinessMetrics businessMetrics;

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

    @Test
    @DisplayName("POST /api/orders/{id}/cancel cancels own order")
    void cancelOwnOrder_Success() throws Exception {
        doNothing().when(orderService).cancelOwnOrder(1L);

        mockMvc.perform(post("/api/orders/1/cancel"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/orders/{id} returns order details")
    void getOrder_Success() throws Exception {
        com.example.bookstore.model.Order order = new com.example.bookstore.model.Order();
        order.setId(1L);
        com.example.bookstore.model.AppUser user = new com.example.bookstore.model.AppUser();
        user.setId(1L);
        user.setUsername("testuser");
        order.setUser(user);
        order.setStatus(com.example.bookstore.model.OrderStatus.PENDING);
        order.setTotalPrice(new java.math.BigDecimal("50.00"));

        when(orderService.getUserOrderById(1L)).thenReturn(order);

        mockMvc.perform(get("/api/orders/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.username").value("testuser"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }
}
