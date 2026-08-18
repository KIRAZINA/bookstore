package com.example.bookstore.controller;

import com.example.bookstore.controller.dto.PaymentHistoryResponse;
import com.example.bookstore.controller.dto.PaymentResponse;
import com.example.bookstore.model.PaymentHistory;
import com.example.bookstore.model.PaymentStatus;
import com.example.bookstore.service.AuditService;
import com.example.bookstore.service.PaymentService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin/payments")
@PreAuthorize("hasRole('ADMIN')")
public class AdminPaymentController {

    private final PaymentService paymentService;
    private final AuditService auditService;
    private final PaymentResponseMapper paymentResponseMapper;

    public AdminPaymentController(PaymentService paymentService, AuditService auditService,
                                  PaymentResponseMapper paymentResponseMapper) {
        this.paymentService = paymentService;
        this.auditService = auditService;
        this.paymentResponseMapper = paymentResponseMapper;
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
                        .map(paymentResponseMapper::toResponse));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable Long id) {
        return ResponseEntity.ok(paymentResponseMapper.toResponse(paymentService.getPaymentById(id)));
    }

    @PostMapping("/{id}/refund")
    public ResponseEntity<PaymentResponse> refundPayment(@PathVariable Long id) {
        PaymentResponse response = paymentResponseMapper.toResponse(paymentService.refundPayment(id));
        auditService.logAdminAction("REFUND_PAYMENT", "paymentId=" + id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<List<PaymentHistoryResponse>> getPaymentHistory(@PathVariable Long id) {
        List<PaymentHistory> history = paymentService.getPaymentHistory(id);
        return ResponseEntity.ok(history.stream().map(paymentResponseMapper::toHistoryResponse).collect(Collectors.toList()));
    }
}
