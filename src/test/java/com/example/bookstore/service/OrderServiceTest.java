package com.example.bookstore.service;

import com.example.bookstore.config.SecurityUtils;
import com.example.bookstore.metrics.BusinessMetrics;
import com.example.bookstore.model.*;
import com.example.bookstore.repository.BookRepository;
import com.example.bookstore.repository.OrderRepository;
import com.example.bookstore.repository.OrderStatusHistoryRepository;
import com.example.bookstore.repository.PaymentRepository;
import com.example.bookstore.service.payment.DummyPaymentProvider;
import org.junit.jupiter.api.BeforeEach;
import com.example.bookstore.event.publisher.DomainEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
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
    private OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Mock
    private CartService cartService;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private DummyPaymentProvider paymentProvider;

    @Mock
    private DomainEventPublisher domainEventPublisher;

    @Mock
    private SecurityUtils securityUtils;

    @Mock
    private BusinessMetrics businessMetrics;

    @Mock
    private InventoryService inventoryService;

    @InjectMocks
    private OrderService orderService;

    private AppUser testUser;
    private AppUser adminUser;

    @BeforeEach
    void setUp() {
        testUser = new AppUser();
        testUser.setId(1L);
        testUser.setUsername("testuser");
        testUser.setEmail("test@example.com");
        testUser.setRole(UserRole.ROLE_USER);

        adminUser = new AppUser();
        adminUser.setId(2L);
        adminUser.setUsername("admin");
        adminUser.setRole(UserRole.ROLE_ADMIN);
    }

    @Nested
    @DisplayName("createOrder")
    class CreateOrderTests {
        @Test
        @DisplayName("Створення замовлення з порожнім кошиком")
        void createOrder_EmptyCart() {
            Cart emptyCart = new Cart();
            emptyCart.setUser(testUser);
            when(cartService.getCart()).thenReturn(emptyCart);

            assertThrows(IllegalStateException.class, () -> orderService.createOrder());
        }

        @Test
        @DisplayName("Створення замовлення без автентифікації")
        void createOrder_Unauthenticated() {
            Book book = new Book();
            book.setId(1L);
            book.setTitle("Book");
            book.setPrice(new BigDecimal("9.99"));

            CartItem cartItem = new CartItem();
            cartItem.setBook(book);
            cartItem.setQuantity(1);
            cartItem.setPriceAtAdd(new BigDecimal("9.99"));

            Cart cart = new Cart();
            cart.setUser(testUser);
            cart.addItem(cartItem);

            when(cartService.getCart()).thenReturn(cart);
            when(inventoryService.getAvailableQuantity(1L)).thenReturn(5);
            when(securityUtils.getCurrentUser()).thenThrow(new IllegalStateException("User not authenticated"));

            assertThrows(IllegalStateException.class, () -> orderService.createOrder());
        }

        @Test
        @DisplayName("Створення замовлення при конкурентному оновленні stock")
        void createOrder_OptimisticLockConflict() {
            Book book = new Book();
            book.setId(10L);
            book.setTitle("Concurrent Book");
            book.setPrice(new BigDecimal("19.99"));

            CartItem cartItem = new CartItem();
            cartItem.setBook(book);
            cartItem.setQuantity(2);
            cartItem.setPriceAtAdd(new BigDecimal("19.99"));

            Cart cart = new Cart();
            cart.setUser(testUser);
            cart.addItem(cartItem);

            when(securityUtils.getCurrentUser()).thenReturn(testUser);
            when(cartService.getCart()).thenReturn(cart);
            when(inventoryService.getAvailableQuantity(10L)).thenReturn(5);
            when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
                Order saved = invocation.getArgument(0);
                if (saved.getId() == null) {
                    saved.setId(100L);
                }
                return saved;
            });
            doThrow(new ObjectOptimisticLockingFailureException(Book.class, 10L))
                    .when(inventoryService).reserveAll(anyLong(), any(), anyString());

            assertThrows(ObjectOptimisticLockingFailureException.class, () -> orderService.createOrder());
            verify(orderRepository, times(1)).save(any(Order.class));
            verify(cartService, never()).clearCart();
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
            order.setTotalPrice(new BigDecimal("100.0"));
            Page<Order> page = new PageImpl<>(List.of(order));

            when(securityUtils.getCurrentUser()).thenReturn(testUser);
            when(orderRepository.findByUserId(anyLong(), any())).thenReturn(page);

            Page<Order> result = orderService.getUserOrders(0, 10, "createdAt", "desc");

            assertNotNull(result);
            assertEquals(1, result.getContent().size());
        }
    }

    @Nested
    @DisplayName("cancelOwnOrder")
    class CancelOwnOrderTests {
        @Test
        @DisplayName("Скасування власного замовлення в статусі PENDING")
        void cancelOwnOrder_Success() {
            Order order = new Order();
            order.setId(1L);
            order.setUser(testUser);
            order.setStatus(OrderStatus.PENDING);

            when(securityUtils.getCurrentUser()).thenReturn(testUser);
            when(orderRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(order));

            orderService.cancelOwnOrder(1L);

            assertEquals(OrderStatus.CANCELLED, order.getStatus());
            verify(orderRepository).save(order);
        }

        @Test
        @DisplayName("Скасування замовлення в термінальному статусі - помилка")
        void cancelOwnOrder_TerminalStatus_ThrowsException() {
            Order order = new Order();
            order.setId(1L);
            order.setUser(testUser);
            order.setStatus(OrderStatus.DELIVERED);

            when(securityUtils.getCurrentUser()).thenReturn(testUser);
            when(orderRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(order));

            assertThrows(IllegalStateException.class, () -> orderService.cancelOwnOrder(1L));
        }

        @Test
        @DisplayName("Скасування чужого замовлення - помилка")
        void cancelOwnOrder_OtherUsersOrder_ThrowsException() {
            when(securityUtils.getCurrentUser()).thenReturn(testUser);
            when(orderRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class, () -> orderService.cancelOwnOrder(1L));
        }
    }

    @Nested
    @DisplayName("updateOrderStatus")
    class UpdateOrderStatusTests {
        @Test
        @DisplayName("Валідний перехід статусу адміном")
        void updateOrderStatus_ValidTransition() {
            Order order = new Order();
            order.setId(1L);
            order.setUser(testUser);
            order.setStatus(OrderStatus.PENDING);

            when(securityUtils.getCurrentUser()).thenReturn(adminUser);
            when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

            orderService.updateOrderStatus(1L, OrderStatus.PAID, "Payment received");

            assertEquals(OrderStatus.PAID, order.getStatus());
            verify(orderRepository).save(order);
        }

        @Test
        @DisplayName("Невалідний перехід статусу - помилка")
        void updateOrderStatus_InvalidTransition_ThrowsException() {
            Order order = new Order();
            order.setId(1L);
            order.setUser(testUser);
            order.setStatus(OrderStatus.PENDING);

            when(securityUtils.getCurrentUser()).thenReturn(adminUser);
            when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

            assertThrows(IllegalStateException.class,
                    () -> orderService.updateOrderStatus(1L, OrderStatus.DELIVERED, ""));
        }
    }

    @Nested
    @DisplayName("adminCancelOrder")
    class AdminCancelOrderTests {
        @Test
        @DisplayName("Адмін скасовує замовлення в PAID")
        void adminCancelOrder_Success() {
            Order order = new Order();
            order.setId(1L);
            order.setUser(testUser);
            order.setStatus(OrderStatus.PAID);

            when(securityUtils.getCurrentUser()).thenReturn(adminUser);
            when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

            orderService.adminCancelOrder(1L, "Admin override");

            assertEquals(OrderStatus.CANCELLED, order.getStatus());
            verify(orderRepository).save(order);
        }

        @Test
        @DisplayName("Адмін не може скасувати DELIVERED")
        void adminCancelOrder_Delivered_ThrowsException() {
            Order order = new Order();
            order.setId(1L);
            order.setUser(testUser);
            order.setStatus(OrderStatus.DELIVERED);

            when(securityUtils.getCurrentUser()).thenReturn(adminUser);
            when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

            assertThrows(IllegalStateException.class, () -> orderService.adminCancelOrder(1L, ""));
        }
    }

    @Nested
    @DisplayName("getOrderHistory")
    class GetOrderHistoryTests {
        @Test
        @DisplayName("Отримання історії замовлення")
        void getOrderHistory_Success() {
            when(orderStatusHistoryRepository.findByOrderIdOrderByCreatedAtAsc(1L))
                    .thenReturn(List.of());

            List<OrderStatusHistory> result = orderService.getOrderHistory(1L);

            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("stateMachine")
    class StateMachineTests {
        @Test
        @DisplayName("PENDING може перейти в PAID")
        void pending_CanTransitionTo_Paid() {
            assertTrue(OrderStatus.PENDING.canTransitionTo(OrderStatus.PAID));
        }

        @Test
        @DisplayName("PENDING може перейти в CANCELLED")
        void pending_CanTransitionTo_Cancelled() {
            assertTrue(OrderStatus.PENDING.canTransitionTo(OrderStatus.CANCELLED));
        }

        @Test
        @DisplayName("PENDING не може перейти в DELIVERED")
        void pending_CannotTransitionTo_Delivered() {
            assertFalse(OrderStatus.PENDING.canTransitionTo(OrderStatus.DELIVERED));
        }

        @Test
        @DisplayName("DELIVERED - термінальний статус")
        void delivered_IsTerminal() {
            assertTrue(OrderStatus.DELIVERED.isTerminal());
        }

        @Test
        @DisplayName("CANCELLED - термінальний статус")
        void cancelled_IsTerminal() {
            assertTrue(OrderStatus.CANCELLED.isTerminal());
        }

        @Test
        @DisplayName("DELIVERED не може перейти нікуди")
        void delivered_HasNoTransitions() {
            assertFalse(OrderStatus.DELIVERED.canTransitionTo(OrderStatus.PENDING));
            assertFalse(OrderStatus.DELIVERED.canTransitionTo(OrderStatus.CANCELLED));
            assertFalse(OrderStatus.DELIVERED.canTransitionTo(OrderStatus.PAID));
        }

        @Test
        @DisplayName("PENDING є cancelable")
        void pending_IsCancelable() {
            assertTrue(OrderStatus.PENDING.isCancelable());
        }

        @Test
        @DisplayName("DELIVERED не є cancelable")
        void delivered_IsNotCancelable() {
            assertFalse(OrderStatus.DELIVERED.isCancelable());
        }
    }
}
