package com.example.bookstore.service;

import com.example.bookstore.exception.InvalidPaymentStateException;
import com.example.bookstore.exception.PaymentException;
import com.example.bookstore.metrics.BusinessMetrics;
import com.example.bookstore.model.*;
import com.example.bookstore.repository.OrderRepository;
import com.example.bookstore.repository.PaymentHistoryRepository;
import com.example.bookstore.repository.PaymentRepository;
import com.example.bookstore.service.payment.DummyPaymentProvider;
import com.example.bookstore.service.payment.PaymentProviderResponse;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentHistoryRepository paymentHistoryRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private DummyPaymentProvider paymentProvider;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private BusinessMetrics businessMetrics;

    @InjectMocks
    private PaymentService paymentService;

    private Order order;
    private Payment payment;

    @BeforeEach
    void setUp() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("testuser");

        order = new Order();
        order.setId(1L);
        order.setUser(user);
        order.setStatus(OrderStatus.PENDING);
        order.setTotalPrice(new BigDecimal("100.00"));

        payment = new Payment(order, "DUMMY", PaymentStatus.PENDING, new BigDecimal("100.00"));
        payment.setId(1L);
        payment.onCreate();
    }

    @Nested
    @DisplayName("createPayment")
    class CreatePaymentTests {
    @Test
    @DisplayName("Creates PENDING payment for order")
    void createPayment_Success() {
        when(paymentProvider.getName()).thenReturn("DUMMY");
        when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));

        Payment result = paymentService.createPayment(order);

            assertNotNull(result);
            assertEquals(PaymentStatus.PENDING, result.getPaymentStatus());
            assertEquals(order, result.getOrder());
            assertEquals(new BigDecimal("100.00"), result.getAmount());
            assertEquals("DUMMY", result.getPaymentProvider());
            verify(paymentRepository).save(any(Payment.class));
        }
    }

    @Nested
    @DisplayName("getPaymentByOrderId")
    class GetPaymentByOrderIdTests {
        @Test
        @DisplayName("Returns payment when found")
        void getPaymentByOrderId_Success() {
            when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.of(payment));

            Payment result = paymentService.getPaymentByOrderId(1L);

            assertNotNull(result);
            assertEquals(payment.getId(), result.getId());
        }

        @Test
        @DisplayName("Throws when not found")
        void getPaymentByOrderId_NotFound() {
            when(paymentRepository.findByOrderId(99L)).thenReturn(Optional.empty());

            assertThrows(PaymentException.class, () -> paymentService.getPaymentByOrderId(99L));
        }
    }

    @Nested
    @DisplayName("processPayment")
    class ProcessPaymentTests {
        @Test
        @DisplayName("Completes payment and transitions order to PAID")
        void processPayment_Success() {
            when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.of(payment));
            when(paymentProvider.getName()).thenReturn("DUMMY");
            when(paymentProvider.processPayment(any(Payment.class)))
                    .thenReturn(PaymentProviderResponse.success("TXN-123", PaymentStatus.COMPLETED));
            when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));
            when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));

            Payment result = paymentService.processPayment(1L, "card");

            assertNotNull(result);
            assertEquals(PaymentStatus.COMPLETED, result.getPaymentStatus());
            assertEquals("TXN-123", result.getTransactionId());
            assertEquals("card", result.getPaymentMethod());
            assertNotNull(result.getCompletedAt());

            assertEquals(OrderStatus.PAID, order.getStatus());
            verify(orderRepository).save(order);
        }

    @Test
    @DisplayName("Throws when payment already completed")
    void processPayment_AlreadyCompleted() {
        payment.setPaymentStatus(PaymentStatus.COMPLETED);
        when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.of(payment));

        assertThrows(InvalidPaymentStateException.class, () -> paymentService.processPayment(1L, "card"));
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("Throws when payment is cancelled")
    void processPayment_Cancelled() {
        payment.setPaymentStatus(PaymentStatus.CANCELLED);
        when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.of(payment));

        assertThrows(InvalidPaymentStateException.class, () -> paymentService.processPayment(1L, "card"));
    }

        @Test
        @DisplayName("Payment not found throws PaymentException")
        void processPayment_OrderNotFound() {
            when(paymentRepository.findByOrderId(99L)).thenReturn(Optional.empty());

            assertThrows(PaymentException.class, () -> paymentService.processPayment(99L, "card"));
        }
    }

    @Nested
    @DisplayName("cancelPayment")
    class CancelPaymentTests {
        @Test
        @DisplayName("Cancels PENDING payment")
        void cancelPayment_Success() {
            when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));

            Payment result = paymentService.cancelPayment(1L);

            assertNotNull(result);
            assertEquals(PaymentStatus.CANCELLED, result.getPaymentStatus());
        }

    @Test
    @DisplayName("Throws when payment already completed")
    void cancelPayment_AlreadyCompleted() {
        payment.setPaymentStatus(PaymentStatus.COMPLETED);
        when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.of(payment));

        assertThrows(InvalidPaymentStateException.class, () -> paymentService.cancelPayment(1L));
    }
    }

    @Nested
    @DisplayName("refundPayment")
    class RefundPaymentTests {
        @Test
        @DisplayName("Refunds COMPLETED payment")
        void refundPayment_Success() {
            payment.setPaymentStatus(PaymentStatus.COMPLETED);
            payment.setTransactionId("TXN-123");

            when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
            when(paymentProvider.refundPayment(any(Payment.class)))
                    .thenReturn(PaymentProviderResponse.success("TXN-123-REF", PaymentStatus.REFUNDED));
            when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));

            Payment result = paymentService.refundPayment(1L);

            assertNotNull(result);
            assertEquals(PaymentStatus.REFUNDED, result.getPaymentStatus());
        }

        @Test
        @DisplayName("Throws when refunding PENDING payment")
        void refundPayment_Pending_Throws() {
            when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

            assertThrows(InvalidPaymentStateException.class, () -> paymentService.refundPayment(1L));
        }
    }

    @Nested
    @DisplayName("getPaymentHistory")
    class GetPaymentHistoryTests {
        @Test
        @DisplayName("Returns payment history")
        void getPaymentHistory_Success() {
            when(paymentHistoryRepository.findByPaymentIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());

            List<PaymentHistory> result = paymentService.getPaymentHistory(1L);

            assertNotNull(result);
        }

        @Test
        @DisplayName("Returns payment history by order id")
        void getPaymentHistoryByOrderId_Success() {
            when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.of(payment));
            when(paymentHistoryRepository.findByPaymentIdOrderByCreatedAtAsc(payment.getId())).thenReturn(List.of());

            List<PaymentHistory> result = paymentService.getPaymentHistoryByOrderId(1L);

            assertNotNull(result);
        }
    }
}
