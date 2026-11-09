package com.example.bookstore.service;

import com.example.bookstore.config.SecurityUtils;
import org.springframework.security.access.AccessDeniedException;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PaymentService {

    private final SecurityUtils securityUtils;
    private final PaymentRepository paymentRepository;
    private final PaymentHistoryRepository paymentHistoryRepository;
    private final OrderRepository orderRepository;
    private final PaymentProvider paymentProvider;
    private final DomainEventPublisher domainEventPublisher;
    private final BusinessMetrics businessMetrics;
    private final InventoryService inventoryService;
    private final TransactionTemplate requiresNewTx;
    private final TransactionTemplate noTransactionTx;

    public PaymentService(PaymentRepository paymentRepository,
                          PaymentHistoryRepository paymentHistoryRepository,
                          OrderRepository orderRepository,
                          DummyPaymentProvider paymentProvider,
                          DomainEventPublisher domainEventPublisher,
                          BusinessMetrics businessMetrics,
                          InventoryService inventoryService,
                          SecurityUtils securityUtils,
                          PlatformTransactionManager transactionManager) {
        this.paymentRepository = paymentRepository;
        this.paymentHistoryRepository = paymentHistoryRepository;
        this.orderRepository = orderRepository;
        this.paymentProvider = paymentProvider;
        this.domainEventPublisher = domainEventPublisher;
        this.businessMetrics = businessMetrics;
        this.inventoryService = inventoryService;
        this.securityUtils = securityUtils;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.noTransactionTx = new TransactionTemplate(transactionManager);
        this.noTransactionTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
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
        Long currentUserId = securityUtils.getCurrentUser().getId();
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new AccessDeniedException("Access denied"));
        if (orderRepository.findByIdAndUserId(orderId, currentUserId).isEmpty()) {
            throw new AccessDeniedException("Access denied");
        }
        return payment;
    }

    public Payment getPaymentById(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentException("Payment not found: " + paymentId));
    }

    /**
     * Resolves the payment id for an order while preserving the original ownership
     * and authorization semantics of {@link #getPaymentByOrderId(Long)}: a missing
     * payment and an order that does not belong to the current user both surface as
     * {@link AccessDeniedException}.
     *
     * <p>This uses a projection rather than loading the Payment entity. Loading the
     * entity here could cache a stale copy that shadows the state committed by the
     * {@code REQUIRES_NEW} phase fences.
     */
    private Long resolveOwnedPaymentId(Long orderId) {
        Long currentUserId = securityUtils.getCurrentUser().getId();
        Long paymentId = paymentRepository.findPaymentIdByOrderId(orderId)
                .orElseThrow(() -> new AccessDeniedException("Access denied"));
        if (orderRepository.findByIdAndUserId(orderId, currentUserId).isEmpty()) {
            throw new AccessDeniedException("Access denied");
        }
        return paymentId;
    }

    /**
     * Reads the payment inside its own {@code REQUIRES_NEW} transaction so the read
     * uses a fresh EntityManager and therefore observes the state committed by the
     * preceding phase fence. The returned entity is detached; no lazy relationship is
     * navigated by callers of the provider-using methods.
     */
    private Payment readCommittedPayment(Long paymentId) {
        return requiresNewTx.execute(status -> paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentException("Payment not found: " + paymentId)));
    }

    /**
     * Processes a payment for an order.
     *
     * <p>Transaction boundary: phase 1 and phase 3 each commit in their own
     * {@code REQUIRES_NEW} transaction. The provider call is made inside a narrow
     * {@code NOT_SUPPORTED} fence, which suspends the ambient caller database
     * transaction only for the provider invocation.
     */
    @WithSpan("PaymentService.processPayment")
    public Payment processPayment(Long orderId, String paymentMethod) {
        Long paymentId = resolveOwnedPaymentId(orderId);

        // Phase 1: commit the in-flight state before the provider is called.
        requiresNewTx.executeWithoutResult(status -> processPaymentPhase1(paymentId, orderId, paymentMethod));

        // Read the committed in-flight state in a fresh transaction.
        Payment initialized = readCommittedPayment(paymentId);

        // Phase 2: provider call inside a narrow NOT_SUPPORTED fence. Phase 1 has
        // already committed, so no transaction is active during the provider invocation.
        PaymentProviderResponse providerResponse = noTransactionTx.execute(status ->
                paymentProvider.processPayment(initialized));

        // Phase 3: commit the final state after the provider responded.
        requiresNewTx.executeWithoutResult(status -> processPaymentPhase3(paymentId, orderId, providerResponse));

        return readCommittedPayment(paymentId);
    }

    private void processPaymentPhase1(Long paymentId, Long orderId, String paymentMethod) {
        Payment locked = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new PaymentException("Payment not found: " + paymentId));
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new PaymentException("Order not found: " + orderId));
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new IllegalStateException("Cannot process payment for a cancelled order");
        }
        if (!locked.getPaymentStatus().canTransitionTo(PaymentStatus.PROCESSING)) {
            throw new InvalidPaymentStateException(
                    "Cannot process payment in status: " + locked.getPaymentStatus());
        }
        locked.setPaymentMethod(paymentMethod);
        locked.recordTransition(PaymentStatus.PROCESSING, "system", "Processing payment");
        locked.setUpdatedAt(LocalDateTime.now());
        businessMetrics.recordPaymentProcessed();
        paymentRepository.save(locked);
    }

    private void processPaymentPhase3(Long paymentId, Long orderId, PaymentProviderResponse providerResponse) {
        Payment reloaded = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new PaymentException("Payment not found: " + paymentId));
        if (reloaded.getPaymentStatus() != PaymentStatus.PROCESSING) {
            throw new InvalidPaymentStateException(
                    "Payment status changed during processing: " + reloaded.getPaymentStatus());
        }
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new PaymentException("Order not found: " + orderId));
        AppUser user = order.getUser();

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
        paymentRepository.save(reloaded);
    }

    /**
     * Cancels a pending payment.
     *
     * <p>This method keeps its original {@code @Transactional} boundary unchanged. It
     * performs no provider call, so the ambient provider transaction risk does not
     * apply here.
     */
    @Transactional
    public Payment cancelPayment(Long orderId) {
        Payment ownershipPayment = getPaymentByOrderId(orderId);
        Long paymentId = ownershipPayment.getId();

        String actor = "system";
        try {
            AppUser current = securityUtils.getCurrentUser();
            if (current != null && current.getUsername() != null) {
                actor = current.getUsername();
            }
        } catch (RuntimeException e) {
            actor = "system";
        }

        Payment locked = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new PaymentException("Payment not found: " + paymentId));

        if (!locked.getPaymentStatus().canTransitionTo(PaymentStatus.CANCELLED)) {
            throw new InvalidPaymentStateException(
                    "Cannot cancel payment in status: " + locked.getPaymentStatus());
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new PaymentException("Order not found: " + orderId));

        if (order.getStatus() == OrderStatus.PENDING) {
            locked.recordTransition(PaymentStatus.CANCELLED, "system", "Payment cancelled");
            locked.setUpdatedAt(LocalDateTime.now());
            paymentRepository.save(locked);

            order.recordTransition(OrderStatus.CANCELLED, actor, "Payment cancelled; order cancelled");
            orderRepository.save(order);

            inventoryService.releaseAllReservationsForOrder(
                    getOrderItemQuantities(order), order.getId(), "Payment cancelled", actor);
        } else if (order.getStatus() == OrderStatus.CANCELLED) {
            locked.recordTransition(PaymentStatus.CANCELLED, "system", "Payment cancelled");
            locked.setUpdatedAt(LocalDateTime.now());
            paymentRepository.save(locked);
        } else {
            throw new InvalidPaymentStateException(
                    "Cannot cancel payment for order in status: " + order.getStatus());
        }

        return locked;
    }

    @WithSpan("PaymentService.refundPayment")
    public Payment refundPayment(Long paymentId) {
        String actor = "system";
        try {
            AppUser current = securityUtils.getCurrentUser();
            if (current != null && current.getUsername() != null) {
                actor = current.getUsername();
            }
        } catch (RuntimeException e) {
            actor = "system";
        }
        return refundPayment(paymentId, actor, "Payment refunded; order cancelled");
    }

    /**
     * Refunds a completed payment.
     *
     * <p>Transaction boundary: phase 1 and phase 3 each commit in their own
     * {@code REQUIRES_NEW} transaction. The provider call is made inside a narrow
     * {@code NOT_SUPPORTED} fence, which suspends any ambient caller database
     * transaction (for example the {@code @Transactional} OrderService cancellation
     * methods) only for the provider invocation.
     */
    @WithSpan("PaymentService.refundPayment")
    public Payment refundPayment(Long paymentId, String actor, String reason) {
        Long orderId = paymentRepository.findOrderIdByPaymentId(paymentId)
                .orElseThrow(() -> new PaymentException("Payment not found: " + paymentId));

        // Phase 1: commit the in-flight state before the provider is called.
        requiresNewTx.executeWithoutResult(status -> refundPaymentPhase1(paymentId, orderId));

        // Read the committed in-flight state in a fresh transaction.
        Payment initialized = readCommittedPayment(paymentId);

        // Phase 2: provider call inside a narrow NOT_SUPPORTED fence. Phase 1 has
        // already committed, so no transaction is active during the provider invocation.
        PaymentProviderResponse providerResponse = noTransactionTx.execute(status ->
                paymentProvider.refundPayment(initialized));

        // Phase 3: commit the final state after the provider responded.
        requiresNewTx.executeWithoutResult(status ->
                refundPaymentPhase3(paymentId, orderId, actor, reason, providerResponse));

        return readCommittedPayment(paymentId);
    }

    private void refundPaymentPhase1(Long paymentId, Long orderId) {
        Payment locked = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new PaymentException("Payment not found: " + paymentId));
        if (locked.getPaymentStatus() != PaymentStatus.COMPLETED) {
            throw new InvalidPaymentStateException(
                    "Payment cannot be refunded in the current order state");
        }
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new PaymentException("Order not found: " + orderId));
        if (order == null || order.getStatus() != OrderStatus.PAID) {
            throw new InvalidPaymentStateException(
                    "Payment cannot be refunded in the current order state");
        }
        if (!locked.getPaymentStatus().canTransitionTo(PaymentStatus.REFUND_PROCESSING)) {
            throw new InvalidPaymentStateException(
                    "Cannot refund payment in status: " + locked.getPaymentStatus());
        }
        locked.recordTransition(PaymentStatus.REFUND_PROCESSING, "system", "Initializing refund");
        locked.setUpdatedAt(LocalDateTime.now());
        paymentRepository.save(locked);
    }

    private void refundPaymentPhase3(Long paymentId, Long orderId, String actor, String reason, PaymentProviderResponse providerResponse) {
        Payment reloaded = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new PaymentException("Payment not found: " + paymentId));
        if (reloaded.getPaymentStatus() != PaymentStatus.REFUND_PROCESSING) {
            throw new InvalidPaymentStateException(
                    "Refund status changed during processing: " + reloaded.getPaymentStatus());
        }
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new PaymentException("Order not found: " + orderId));
        AppUser user = order.getUser();

        if (providerResponse.isSuccess()) {
            reloaded.setTransactionId(providerResponse.getTransactionId());
            reloaded.recordTransition(PaymentStatus.REFUNDED, "system", "Payment refunded");
            inventoryService.restoreAllStockForOrder(
                    getOrderItemQuantities(order), order.getId(),
                    "Stock restored after refund", "system");
            businessMetrics.recordPaymentRefunded();
            domainEventPublisher.publish(
                    new PaymentRefundedEvent(reloaded.getId(), order.getId(), user.getId(), user.getUsername(),
                            user.getEmail(), reloaded.getAmount(), reloaded.getTransactionId()),
                    EventTypes.AGGREGATE_PAYMENT, String.valueOf(reloaded.getId()), EventTypes.PAYMENT_REFUNDED);
        } else {
            reloaded.setFailureReason(providerResponse.getFailureReason());
            reloaded.recordTransition(PaymentStatus.COMPLETED, "system", "Refund failed, payment restored");
        }
        reloaded.setUpdatedAt(LocalDateTime.now());
        paymentRepository.save(reloaded);

        if (providerResponse.isSuccess()) {
            Order reloadedOrder = orderRepository.findById(order.getId())
                    .orElseThrow(() -> new PaymentException("Order not found: " + order.getId()));
            if (reloadedOrder.getStatus() == OrderStatus.PAID) {
                reloadedOrder.recordTransition(OrderStatus.CANCELLED, actor, reason);
                orderRepository.save(reloadedOrder);
            }
        }
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
