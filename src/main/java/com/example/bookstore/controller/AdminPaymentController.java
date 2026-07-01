package com.example.bookstore.controller;

import com.example.bookstore.controller.dto.PaymentHistoryResponse;
import com.example.bookstore.controller.dto.PaymentResponse;
import com.example.bookstore.model.Payment;
import com.example.bookstore.model.PaymentHistory;
import com.example.bookstore.model.PaymentStatus;
import com.example.bookstore.service.PaymentService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/payments")
@PreAuthorize("hasRole('ADMIN')")
public class AdminPaymentController {

    private final PaymentService paymentService;

    public AdminPaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping
    public ResponseEntity<Page<PaymentResponse>> getAllPayments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) PaymentStatus status) {
        return ResponseEntity.ok(
                paymentService.getAllPayments(page, size, sortBy, direction, status)
                        .map(this::toResponse));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable Long id) {
        return ResponseEntity.ok(toResponse(paymentService.getPaymentById(id)));
    }

    @PostMapping("/{id}/refund")
    public ResponseEntity<PaymentResponse> refundPayment(@PathVariable Long id) {
        return ResponseEntity.ok(toResponse(paymentService.refundPayment(id)));
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<List<PaymentHistoryResponse>> getPaymentHistory(@PathVariable Long id) {
        List<PaymentHistory> history = paymentService.getPaymentHistory(id);
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
