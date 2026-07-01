package com.example.bookstore.controller;

import com.example.bookstore.controller.dto.OrderResponse;
import com.example.bookstore.controller.dto.OrderStatusHistoryResponse;
import com.example.bookstore.controller.dto.UpdateOrderStatusRequest;
import com.example.bookstore.model.Order;
import com.example.bookstore.model.OrderStatus;
import com.example.bookstore.model.OrderStatusHistory;
import com.example.bookstore.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/orders")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final OrderService orderService;

    public AdminController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public ResponseEntity<Page<OrderResponse>> getAllOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) OrderStatus status) {
        return ResponseEntity.ok(
                orderService.getAllOrders(page, size, sortBy, direction, status)
                        .map(this::toOrderResponse));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable Long id) {
        return ResponseEntity.ok(toOrderResponse(orderService.getOrderById(id)));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<OrderResponse> updateOrderStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateOrderStatusRequest request) {
        orderService.updateOrderStatus(id, request.getStatus(), request.getComment());
        return ResponseEntity.ok(toOrderResponse(orderService.getOrderById(id)));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<OrderResponse> cancelOrder(
            @PathVariable Long id,
            @RequestBody(required = false) UpdateOrderStatusRequest request) {
        String comment = request != null ? request.getComment() : null;
        orderService.adminCancelOrder(id, comment);
        return ResponseEntity.ok(toOrderResponse(orderService.getOrderById(id)));
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<List<OrderStatusHistoryResponse>> getOrderHistory(@PathVariable Long id) {
        List<OrderStatusHistory> history = orderService.getOrderHistory(id);
        return ResponseEntity.ok(history.stream().map(this::toHistoryResponse).collect(Collectors.toList()));
    }

    private OrderResponse toOrderResponse(Order order) {
        List<com.example.bookstore.controller.dto.OrderItemResponse> items = order.getItems().stream()
                .map(item -> new com.example.bookstore.controller.dto.OrderItemResponse(
                        item.getId(),
                        item.getBook().getId(),
                        item.getBook().getTitle(),
                        item.getBook().getAuthor(),
                        item.getQuantity(),
                        item.getUnitPrice(),
                        item.getSubtotal()))
                .collect(Collectors.toList());
        return new OrderResponse(
                order.getId(),
                order.getUser().getId(),
                order.getUser().getUsername(),
                order.getStatus(),
                order.getCreatedAt(),
                items,
                order.getTotalPrice()
        );
    }

    private OrderStatusHistoryResponse toHistoryResponse(OrderStatusHistory h) {
        return new OrderStatusHistoryResponse(
                h.getId(),
                h.getPreviousStatus(),
                h.getNewStatus(),
                h.getChangedBy(),
                h.getComment(),
                h.getCreatedAt()
        );
    }
}
