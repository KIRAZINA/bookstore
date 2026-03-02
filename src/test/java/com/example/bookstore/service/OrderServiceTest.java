package com.example.bookstore.service;

import com.example.bookstore.config.SecurityUtils;
import com.example.bookstore.model.*;
import com.example.bookstore.repository.BookRepository;
import com.example.bookstore.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private CartService cartService;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private SecurityUtils securityUtils;

    @InjectMocks
    private OrderService orderService;

    private AppUser testUser;

    @BeforeEach
    void setUp() {
        testUser = new AppUser();
        testUser.setId(1L);
        testUser.setUsername("testuser");
        testUser.setEmail("test@example.com");
        testUser.setRole(UserRole.ROLE_USER);
    }

    @Nested
    @DisplayName("createOrder")
    class CreateOrderTests {
        @Test
        @DisplayName("Створення замовлення з порожнім кошиком")
        void createOrder_EmptyCart() {
            Cart emptyCart = new Cart();
            emptyCart.setUser(testUser);
            when(securityUtils.getCurrentUser()).thenReturn(testUser);
            when(cartService.getCart()).thenReturn(emptyCart);

            assertThrows(IllegalStateException.class, () -> orderService.createOrder());
        }

        @Test
        @DisplayName("Створення замовлення без автентифікації")
        void createOrder_Unauthenticated() {
            when(securityUtils.getCurrentUser()).thenThrow(new IllegalStateException("User not authenticated"));

            assertThrows(IllegalStateException.class, () -> orderService.createOrder());
        }
    }

    @Nested
    @DisplayName("getUserOrders")
    class GetUserOrdersTests {
        @Test
        @DisplayName("Отримання замовлень користувача")
        void getUserOrders_Success() {
            Order order = new Order();
            order.setId(1L);
            order.setUser(testUser);
            order.setTotalPrice(100.0);
            Page<Order> page = new PageImpl<>(List.of(order));

            when(securityUtils.getCurrentUser()).thenReturn(testUser);
            when(orderRepository.findByUserId(anyLong(), any())).thenReturn(page);

            Page<Order> result = orderService.getUserOrders(0, 10, "createdAt", "desc");

            assertNotNull(result);
            assertEquals(1, result.getContent().size());
        }
    }
}
