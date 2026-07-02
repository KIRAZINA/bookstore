package com.example.bookstore.service;

import com.example.bookstore.event.EventTypes;
import com.example.bookstore.event.PaymentCompletedEvent;
import com.example.bookstore.event.PaymentFailedEvent;
import com.example.bookstore.event.PaymentRefundedEvent;
import com.example.bookstore.event.publisher.DomainEventPublisher;
import com.example.bookstore.exception.InvalidPaymentStateException;
import com.example.bookstore.exception.PaymentException;
import com.example.bookstore.metrics.BusinessMetrics;
import com.example.bookstore.model.*;
import com.example.bookstore.repository.*;
import com.example.bookstore.model.OrderItem;
import com.example.bookstore.service.payment.DummyPaymentProvider;
import com.example.bookstore.service.payment.PaymentProvider;
import com.example.bookstore.service.payment.PaymentProviderResponse;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentHistoryRepository paymentHistoryRepository;
    private final OrderRepository orderRepository;
    private final PaymentProvider paymentProvider;
    private final DomainEventPublisher domainEventPublisher;
    private final BusinessMetrics businessMetrics;
    private final InventoryService inventoryService;

    public PaymentService(PaymentRepository paymentRepository,
                          PaymentHistoryRepository paymentHistoryRepository,
                          OrderRepository orderRepository,
                          DummyPaymentProvider paymentProvider,
                          DomainEventPublisher domainEventPublisher,
                          BusinessMetrics businessMetrics,
                          InventoryService inventoryService) {
        this.paymentRepository = paymentRepository;
        this.paymentHistoryRepository = paymentHistoryRepository;
        this.orderRepository = orderRepository;
        this.paymentProvider = paymentProvider;
        this.domainEventPublisher = domainEventPublisher;
        this.businessMetrics = businessMetrics;
        this.inventoryService = inventoryService;
    }

    private Map<Long, Integer> getOrderItemQuantities(Order order) {
        Map<Long, Integer> items = new LinkedHashMap<>();
        for (OrderItem item : order.getItems()) {
            items.put(item.getBook().getId(), item.getQuantity());
        }
        return items;
    }

    @Transactional
    public Payment createPayment(Order order) {
        Payment payment = new Payment(order, paymentProvider.getName(), PaymentStatus.PENDING, order.getTotalPrice());
        payment.onCreate();
        return paymentRepository.save(payment);
    }

    public Payment getPaymentByOrderId(Long orderId) {
        return paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new PaymentException("No payment found for order: " + orderId));
    }

    public Payment getPaymentById(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentException("Payment not found: " + paymentId));
    }

    @WithSpan("PaymentService.processPayment")
    @Transactional
    public Payment processPayment(Long orderId, String paymentMethod) {
        Payment payment = getPaymentByOrderId(orderId);
        Order order = payment.getOrder();

        if (!payment.getPaymentStatus().canTransitionTo(PaymentStatus.PROCESSING)) {
            throw new InvalidPaymentStateException(
                    "Cannot process payment in status: " + payment.getPaymentStatus());
        }

        payment.setPaymentMethod(paymentMethod);
        payment.recordTransition(PaymentStatus.PROCESSING, "system", "Processing payment");
        payment.setUpdatedAt(LocalDateTime.now());

        businessMetrics.recordPaymentProcessed();
        PaymentProviderResponse providerResponse = paymentProvider.processPayment(payment);
        AppUser user = order.getUser();

        if (providerResponse.isSuccess()) {
            payment.setTransactionId(providerResponse.getTransactionId());
            payment.setCompletedAt(LocalDateTime.now());
            payment.recordTransition(PaymentStatus.COMPLETED, "system", "Payment completed");

            order.recordTransition(OrderStatus.PAID, "system", "Payment completed via " + paymentProvider.getName());
            orderRepository.save(order);

            inventoryService.finalizeAllReservationsForOrder(
                    getOrderItemQuantities(order), order.getId());

            businessMetrics.recordPaymentCompleted();

            domainEventPublisher.publish(
                    new PaymentCompletedEvent(payment.getId(), order.getId(), user.getId(), user.getUsername(),
                            user.getEmail(), payment.getAmount(), payment.getTransactionId()),
                    EventTypes.AGGREGATE_PAYMENT, String.valueOf(payment.getId()), EventTypes.PAYMENT_COMPLETED);
        } else {
            payment.setFailureReason(providerResponse.getFailureReason());
            payment.recordTransition(PaymentStatus.FAILED, "system", providerResponse.getFailureReason());

            inventoryService.releaseAllReservationsForOrder(
                    getOrderItemQuantities(order), order.getId(),
                    "Payment failed: " + providerResponse.getFailureReason(), "system");

            businessMetrics.recordPaymentFailed();

            domainEventPublisher.publish(
                    new PaymentFailedEvent(payment.getId(), order.getId(), user.getId(), user.getUsername(),
                            user.getEmail(), payment.getAmount(), providerResponse.getFailureReason()),
                    EventTypes.AGGREGATE_PAYMENT, String.valueOf(payment.getId()), EventTypes.PAYMENT_FAILED);
        }

        payment.setUpdatedAt(LocalDateTime.now());
        return paymentRepository.save(payment);
    }

    @Transactional
    public Payment cancelPayment(Long orderId) {
        Payment payment = getPaymentByOrderId(orderId);

        if (!payment.getPaymentStatus().canTransitionTo(PaymentStatus.CANCELLED)) {
            throw new InvalidPaymentStateException(
                    "Cannot cancel payment in status: " + payment.getPaymentStatus());
        }

        payment.recordTransition(PaymentStatus.CANCELLED, "system", "Payment cancelled");
        payment.setUpdatedAt(LocalDateTime.now());
        return paymentRepository.save(payment);
    }

    @WithSpan("PaymentService.refundPayment")
    @Transactional
    public Payment refundPayment(Long paymentId) {
        Payment payment = getPaymentById(paymentId);

        if (!payment.getPaymentStatus().canTransitionTo(PaymentStatus.REFUNDED)) {
            throw new InvalidPaymentStateException(
                    "Cannot refund payment in status: " + payment.getPaymentStatus());
        }

        PaymentProviderResponse providerResponse = paymentProvider.refundPayment(payment);
        AppUser user = payment.getOrder().getUser();

        if (providerResponse.isSuccess()) {
            payment.setTransactionId(providerResponse.getTransactionId());
            payment.recordTransition(PaymentStatus.REFUNDED, "system", "Payment refunded");

            inventoryService.restoreAllStockForOrder(
                    getOrderItemQuantities(payment.getOrder()), payment.getOrder().getId(),
                    "Stock restored after refund", "system");

            businessMetrics.recordPaymentRefunded();

            domainEventPublisher.publish(
                    new PaymentRefundedEvent(payment.getId(), payment.getOrder().getId(), user.getId(), user.getUsername(),
                            user.getEmail(), payment.getAmount(), payment.getTransactionId()),
                    EventTypes.AGGREGATE_PAYMENT, String.valueOf(payment.getId()), EventTypes.PAYMENT_REFUNDED);
        } else {
            payment.setFailureReason(providerResponse.getFailureReason());
            payment.recordTransition(PaymentStatus.FAILED, "system", "Refund failed: " + providerResponse.getFailureReason());
        }

        payment.setUpdatedAt(LocalDateTime.now());
        return paymentRepository.save(payment);
    }

    public List<PaymentHistory> getPaymentHistory(Long paymentId) {
        return paymentHistoryRepository.findByPaymentIdOrderByCreatedAtAsc(paymentId);
    }

    public List<PaymentHistory> getPaymentHistoryByOrderId(Long orderId) {
        Payment payment = getPaymentByOrderId(orderId);
        return paymentHistoryRepository.findByPaymentIdOrderByCreatedAtAsc(payment.getId());
    }

    public org.springframework.data.domain.Page<Payment> getAllPayments(
            int page, int size, String sortBy, String direction, PaymentStatus status) {
        org.springframework.data.domain.Sort sort = org.springframework.data.domain.Sort.by(
                direction.equalsIgnoreCase("asc") ? org.springframework.data.domain.Sort.Direction.ASC
                        : org.springframework.data.domain.Sort.Direction.DESC, sortBy);
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size, sort);
        if (status != null) {
            return paymentRepository.findByPaymentStatus(status, pageable);
        }
        return paymentRepository.findAll(pageable);
    }
}
