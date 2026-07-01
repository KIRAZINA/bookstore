package com.example.bookstore.controller;

import com.example.bookstore.controller.dto.PaymentHistoryResponse;
import com.example.bookstore.controller.dto.PaymentResponse;
import com.example.bookstore.controller.dto.ProcessPaymentRequest;
import com.example.bookstore.model.Payment;
import com.example.bookstore.model.PaymentHistory;
import com.example.bookstore.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping("/{orderId}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable Long orderId) {
        return ResponseEntity.ok(toResponse(paymentService.getPaymentByOrderId(orderId)));
    }

    @PostMapping("/{orderId}/process")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<PaymentResponse> processPayment(
            @PathVariable Long orderId,
            @Valid @RequestBody ProcessPaymentRequest request) {
        return ResponseEntity.ok(toResponse(paymentService.processPayment(orderId, request.getPaymentMethod())));
    }

    @PostMapping("/{orderId}/cancel")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<PaymentResponse> cancelPayment(@PathVariable Long orderId) {
        return ResponseEntity.ok(toResponse(paymentService.cancelPayment(orderId)));
    }

    @GetMapping("/{orderId}/history")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<List<PaymentHistoryResponse>> getPaymentHistory(@PathVariable Long orderId) {
        List<PaymentHistory> history = paymentService.getPaymentHistoryByOrderId(orderId);
        return ResponseEntity.ok(history.stream().map(this::toHistoryResponse).collect(Collectors.toList()));
    }

    private PaymentResponse toResponse(Payment p) {
        return new PaymentResponse(
                p.getId(),
                p.getOrder().getId(),
                p.getPaymentProvider(),
                p.getPaymentMethod(),
                p.getPaymentStatus(),
                p.getAmount(),
                p.getCurrency(),
                p.getTransactionId(),
                p.getFailureReason(),
                p.getCreatedAt(),
                p.getUpdatedAt(),
                p.getCompletedAt()
        );
    }

    private PaymentHistoryResponse toHistoryResponse(PaymentHistory h) {
        return new PaymentHistoryResponse(
                h.getId(),
                h.getPreviousStatus(),
                h.getNewStatus(),
                h.getChangedBy(),
                h.getReason(),
                h.getCreatedAt()
        );
    }
}
