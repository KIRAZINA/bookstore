package com.example.bookstore.controller;

import com.example.bookstore.controller.dto.OrderItemResponse;
import com.example.bookstore.controller.dto.OrderResponse;
import com.example.bookstore.controller.dto.OrderStatusHistoryResponse;
import com.example.bookstore.model.Order;
import com.example.bookstore.model.OrderItem;
import com.example.bookstore.model.OrderStatusHistory;
import com.example.bookstore.service.OrderService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<OrderResponse> createOrder() {
        return ResponseEntity.ok(toOrderResponse(orderService.createOrder()));
    }

    @GetMapping
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Page<OrderResponse>> getUserOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String direction) {
        return ResponseEntity.ok(orderService.getUserOrders(page, size, sortBy, direction).map(this::toOrderResponse));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable Long id) {
        return ResponseEntity.ok(toOrderResponse(orderService.getUserOrderById(id)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Void> cancelOrder(@PathVariable Long id) {
        orderService.cancelOwnOrder(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<List<OrderStatusHistoryResponse>> getOrderHistory(@PathVariable Long id) {
        List<OrderStatusHistory> history = orderService.getOrderHistoryForUser(id);
        return ResponseEntity.ok(history.stream().map(this::toHistoryResponse).collect(Collectors.toList()));
    }

    private OrderResponse toOrderResponse(Order order) {
        List<OrderItemResponse> items = order.getItems().stream()
                .map(this::toOrderItemResponse)
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

    private OrderItemResponse toOrderItemResponse(OrderItem item) {
        return new OrderItemResponse(
                item.getId(),
                item.getBook().getId(),
                item.getBook().getTitle(),
                item.getBook().getAuthor(),
                item.getQuantity(),
                item.getUnitPrice(),
                item.getSubtotal()
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