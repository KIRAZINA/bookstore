package com.example.bookstore.service;

import com.example.bookstore.config.SecurityUtils;
import com.example.bookstore.event.publisher.DomainEventPublisher;
import com.example.bookstore.metrics.BusinessMetrics;
import com.example.bookstore.model.AppUser;
import com.example.bookstore.model.Order;
import com.example.bookstore.model.OrderStatus;
import com.example.bookstore.model.Payment;
import com.example.bookstore.model.PaymentStatus;
import com.example.bookstore.repository.OrderRepository;
import com.example.bookstore.repository.PaymentHistoryRepository;
import com.example.bookstore.repository.PaymentRepository;
import com.example.bookstore.service.payment.DummyPaymentProvider;
import com.example.bookstore.service.payment.PaymentProviderResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Refund record integrity.
 *
 * <p>The refund path used to overwrite {@code payment.transaction_id} with the refund's
 * provider transaction id, destroying the original payment identifier. It also never
 * recorded the refund amount, timestamp, or refund provider transaction id.
 *
 * <p>The 4D fields are read reflectively so this test class compiles against the
 * pre-4D entity. A field that does not exist yet is treated as {@code null}, which is
 * observationally identical to "not populated" and gives genuine assertion-level red
 * evidence rather than a compile error.
 */
@ExtendWith(MockitoExtension.class)
class PaymentRefundIntegrityTest {

    private static final String ORIGINAL_TXN = "TXN-123";
    private static final String REFUND_TXN = "TXN-123-REF";

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentHistoryRepository paymentHistoryRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private DummyPaymentProvider paymentProvider;

    @Mock
    private DomainEventPublisher domainEventPublisher;

    @Mock
    private BusinessMetrics businessMetrics;

    @Mock
    private InventoryService inventoryService;

    @Mock
    private SecurityUtils securityUtils;

    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private PaymentService paymentService;

    private Order order;
    private Payment payment;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());

        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("testuser");

        order = new Order();
        order.setId(1L);
        order.setUser(user);
        order.setStatus(OrderStatus.PAID);
        order.setTotalPrice(new BigDecimal("100.00"));

        payment = new Payment(order, "DUMMY", PaymentStatus.COMPLETED, new BigDecimal("100.00"));
        payment.setId(1L);
        payment.onCreate();
        payment.setTransactionId(ORIGINAL_TXN);

        when(paymentRepository.findOrderIdByPaymentId(1L)).thenReturn(Optional.of(1L));
        when(paymentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(payment));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
    }

    private Payment refundWith(PaymentProviderResponse providerResponse) {
        when(paymentProvider.refundPayment(any(Payment.class))).thenReturn(providerResponse);
        return paymentService.refundPayment(1L);
    }

    private static Object refundField(Payment payment, String name) {
        try {
            Field field = Payment.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(payment);
        } catch (NoSuchFieldException e) {
            // Pre-4D entity: the field is necessarily unpopulated.
            return null;
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("Successful refund preserves the ORIGINAL payment transaction id")
    void refundPath_preservesOriginalTransactionId() {
        Payment result = refundWith(PaymentProviderResponse.success(REFUND_TXN, PaymentStatus.REFUNDED));

        assertEquals(PaymentStatus.REFUNDED, result.getPaymentStatus());
        assertEquals(ORIGINAL_TXN, result.getTransactionId(),
                "transaction_id must remain the original payment id after a refund");
    }

    @Test
    @DisplayName("Successful refund records amount, timestamp and refund transaction id")
    void refundPath_populatesRefundFields() {
        Payment result = refundWith(PaymentProviderResponse.success(REFUND_TXN, PaymentStatus.REFUNDED));

        assertEquals(new BigDecimal("100.00"), refundField(result, "refundedAmount"));
        assertNotNull(refundField(result, "refundedAt"));
        assertEquals(REFUND_TXN, refundField(result, "refundTransactionId"));
    }

    @Test
    @DisplayName("Failed refund leaves all refund fields NULL and keeps the original transaction id")
    void failedRefund_doesNotPopulateRefundFields() {
        Payment result = refundWith(PaymentProviderResponse.failed("Provider unavailable"));

        assertEquals(PaymentStatus.COMPLETED, result.getPaymentStatus());
        assertEquals(ORIGINAL_TXN, result.getTransactionId());
        assertNull(refundField(result, "refundedAmount"));
        assertNull(refundField(result, "refundedAt"));
        assertNull(refundField(result, "refundTransactionId"));
    }
}
