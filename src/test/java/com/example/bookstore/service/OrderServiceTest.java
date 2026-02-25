package com.example.bookstore.service;

import com.example.bookstore.model.*;
import com.example.bookstore.repository.BookRepository;
import com.example.bookstore.repository.OrderRepository;
import com.example.bookstore.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
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
    private EmailService emailService;

    @Mock
    private UserRepository userRepository;

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

        UserDetails userDetails = org.springframework.security.core.userdetails.User
                .withUsername("testuser")
                .password("password")
                .roles("USER")
                .build();
        Authentication auth = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Nested
    @DisplayName("createOrder")
    class CreateOrderTests {
        @Test
        @DisplayName("Створення замовлення з порожнім кошиком")
        void createOrder_EmptyCart() {
            Cart emptyCart = new Cart();
            emptyCart.setUser(testUser);
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(cartService.getCart()).thenReturn(emptyCart);

            assertThrows(IllegalStateException.class, () -> orderService.createOrder());
        }

        @Test
        @DisplayName("Створення замовлення без автентифікації")
        void createOrder_Unauthenticated() {
            SecurityContextHolder.getContext().setAuthentication(null);

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
            order.setCreatedAt(LocalDateTime.now());
            
            List<Order> orders = List.of(order);
            Page<Order> page = new PageImpl<>(orders);
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(orderRepository.findByUserId(anyLong(), any(PageRequest.class))).thenReturn(page);

            Page<Order> result = orderService.getUserOrders(0, 10, "createdAt", "desc");

            assertNotNull(result);
            assertEquals(1, result.getTotalElements());
            verify(orderRepository).findByUserId(eq(1L), any(PageRequest.class));
        }

        @Test
        @DisplayName("Отримання замовлень з пагінацією")
        void getUserOrders_WithPagination() {
            Page<Order> emptyPage = new PageImpl<>(List.of());
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(orderRepository.findByUserId(anyLong(), any(PageRequest.class))).thenReturn(emptyPage);

            Page<Order> result = orderService.getUserOrders(0, 5, "id", "asc");

            assertNotNull(result);
            assertEquals(0, result.getTotalElements());
        }

        @Test
        @DisplayName("Отримання замовлень за зростанням")
        void getUserOrders_AscendingSort() {
            Page<Order> emptyPage = new PageImpl<>(List.of());
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(orderRepository.findByUserId(anyLong(), any(PageRequest.class))).thenReturn(emptyPage);

            Page<Order> result = orderService.getUserOrders(0, 10, "totalPrice", "asc");

            assertNotNull(result);
            verify(orderRepository).findByUserId(eq(1L), any(PageRequest.class));
        }
    }
}
