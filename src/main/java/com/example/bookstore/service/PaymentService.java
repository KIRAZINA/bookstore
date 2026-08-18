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
        Payment payment = initializeProcessing(orderId, paymentMethod);
        PaymentProviderResponse providerResponse = paymentProvider.processPayment(payment);
        return completeProcessing(payment, providerResponse);
    }

    @Transactional
    protected Payment initializeProcessing(Long orderId, String paymentMethod) {
        Payment payment = getPaymentByOrderId(orderId);

        if (!payment.getPaymentStatus().canTransitionTo(PaymentStatus.PROCESSING)) {
            throw new InvalidPaymentStateException(
                    "Cannot process payment in status: " + payment.getPaymentStatus());
        }

        payment.setPaymentMethod(paymentMethod);
        payment.recordTransition(PaymentStatus.PROCESSING, "system", "Processing payment");
        payment.setUpdatedAt(LocalDateTime.now());

        businessMetrics.recordPaymentProcessed();
        return paymentRepository.save(payment);
    }

    @Transactional
    protected Payment completeProcessing(Payment payment, PaymentProviderResponse providerResponse) {
        Order order = payment.getOrder();
        AppUser user = order.getUser();

        Payment reloaded = paymentRepository.findById(payment.getId())
                .orElseThrow(() -> new PaymentException("Payment not found: " + payment.getId()));

        if (reloaded.getPaymentStatus() != PaymentStatus.PROCESSING) {
            throw new InvalidPaymentStateException(
                    "Payment status changed during processing: " + reloaded.getPaymentStatus());
        }

        if (providerResponse.isSuccess()) {
            reloaded.setTransactionId(providerResponse.getTransactionId());
            reloaded.setCompletedAt(LocalDateTime.now());
            reloaded.recordTransition(PaymentStatus.COMPLETED, "system", "Payment completed");

            order.recordTransition(OrderStatus.PAID, "system", "Payment completed via " + paymentProvider.getName());
            orderRepository.save(order);

            inventoryService.finalizeAllReservationsForOrder(
                    getOrderItemQuantities(order), order.getId());

            businessMetrics.recordPaymentCompleted();

            domainEventPublisher.publish(
                    new PaymentCompletedEvent(reloaded.getId(), order.getId(), user.getId(), user.getUsername(),
                            user.getEmail(), reloaded.getAmount(), reloaded.getTransactionId()),
                    EventTypes.AGGREGATE_PAYMENT, String.valueOf(reloaded.getId()), EventTypes.PAYMENT_COMPLETED);
        } else {
            reloaded.setFailureReason(providerResponse.getFailureReason());
            reloaded.recordTransition(PaymentStatus.FAILED, "system", providerResponse.getFailureReason());

            inventoryService.releaseAllReservationsForOrder(
                    getOrderItemQuantities(order), order.getId(),
                    "Payment failed: " + providerResponse.getFailureReason(), "system");

            businessMetrics.recordPaymentFailed();

            domainEventPublisher.publish(
                    new PaymentFailedEvent(reloaded.getId(), order.getId(), user.getId(), user.getUsername(),
                            user.getEmail(), reloaded.getAmount(), providerResponse.getFailureReason()),
                    EventTypes.AGGREGATE_PAYMENT, String.valueOf(reloaded.getId()), EventTypes.PAYMENT_FAILED);
        }

        reloaded.setUpdatedAt(LocalDateTime.now());
        return paymentRepository.save(reloaded);
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
        Payment payment = initializeRefund(paymentId);
        PaymentProviderResponse providerResponse = paymentProvider.refundPayment(payment);
        return completeRefund(payment, providerResponse);
    }

    @Transactional
    protected Payment initializeRefund(Long paymentId) {
        Payment payment = getPaymentById(paymentId);

        if (!payment.getPaymentStatus().canTransitionTo(PaymentStatus.REFUND_PROCESSING)) {
            throw new InvalidPaymentStateException(
                    "Cannot refund payment in status: " + payment.getPaymentStatus());
        }

        payment.recordTransition(PaymentStatus.REFUND_PROCESSING, "system", "Initializing refund");
        payment.setUpdatedAt(LocalDateTime.now());
        return paymentRepository.save(payment);
    }

    @Transactional
    protected Payment completeRefund(Payment payment, PaymentProviderResponse providerResponse) {
        Payment reloaded = paymentRepository.findById(payment.getId())
                .orElseThrow(() -> new PaymentException("Payment not found: " + payment.getId()));

        if (reloaded.getPaymentStatus() != PaymentStatus.REFUND_PROCESSING) {
            throw new InvalidPaymentStateException(
                    "Refund status changed during processing: " + reloaded.getPaymentStatus());
        }

        AppUser user = reloaded.getOrder().getUser();

        if (providerResponse.isSuccess()) {
            reloaded.setTransactionId(providerResponse.getTransactionId());
            reloaded.recordTransition(PaymentStatus.REFUNDED, "system", "Payment refunded");

            inventoryService.restoreAllStockForOrder(
                    getOrderItemQuantities(reloaded.getOrder()), reloaded.getOrder().getId(),
                    "Stock restored after refund", "system");

            businessMetrics.recordPaymentRefunded();

            domainEventPublisher.publish(
                    new PaymentRefundedEvent(reloaded.getId(), reloaded.getOrder().getId(), user.getId(), user.getUsername(),
                            user.getEmail(), reloaded.getAmount(), reloaded.getTransactionId()),
                    EventTypes.AGGREGATE_PAYMENT, String.valueOf(reloaded.getId()), EventTypes.PAYMENT_REFUNDED);
        } else {
            reloaded.setFailureReason(providerResponse.getFailureReason());
            reloaded.recordTransition(PaymentStatus.COMPLETED, "system", "Refund failed, payment restored");
        }

        reloaded.setUpdatedAt(LocalDateTime.now());
        return paymentRepository.save(reloaded);
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
                "asc".equalsIgnoreCase(direction) ? org.springframework.data.domain.Sort.Direction.ASC
                        : org.springframework.data.domain.Sort.Direction.DESC, sortBy);
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size, sort);
        if (status != null) {
            return paymentRepository.findByPaymentStatus(status, pageable);
        }
        return paymentRepository.findAll(pageable);
    }
}
