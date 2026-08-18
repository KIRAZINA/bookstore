package com.example.bookstore.controller;

import com.example.bookstore.controller.dto.InventoryAdjustmentRequest;
import com.example.bookstore.controller.dto.InventoryHistoryResponse;
import com.example.bookstore.controller.dto.InventoryResponse;
import com.example.bookstore.model.Inventory;
import com.example.bookstore.model.InventoryHistory;
import com.example.bookstore.model.InventoryStatus;
import com.example.bookstore.model.Book;
import com.example.bookstore.repository.BookRepository;
import com.example.bookstore.service.InventoryAdminService;
import com.example.bookstore.service.InventoryValidator;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin/inventory")
@PreAuthorize("hasRole('ADMIN')")
public class InventoryAdminController {

    private final InventoryAdminService adminService;
    private final InventoryValidator validator;
    private final BookRepository bookRepository;

    public InventoryAdminController(InventoryAdminService adminService,
                                    InventoryValidator validator,
                                    BookRepository bookRepository) {
        this.adminService = adminService;
        this.validator = validator;
        this.bookRepository = bookRepository;
    }

    @GetMapping
    public ResponseEntity<List<InventoryResponse>> getAllInventory() {
        List<InventoryResponse> response = adminService.getAllInventory().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{bookId}")
    public ResponseEntity<InventoryResponse> getInventory(@PathVariable Long bookId) {
        return ResponseEntity.ok(toResponse(adminService.getInventoryByBookId(bookId)));
    }

    @PutMapping("/{bookId}")
    public ResponseEntity<InventoryResponse> adjustInventory(
            @PathVariable Long bookId,
            @Valid @RequestBody InventoryAdjustmentRequest request) {
        return ResponseEntity.ok(
                toResponse(adminService.adjustInventory(bookId, request.getAvailableQuantity(), request.getReason())));
    }

    @GetMapping("/{bookId}/history")
    public ResponseEntity<Page<InventoryHistoryResponse>> getHistory(
            @PathVariable Long bookId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<InventoryHistory> historyPage = adminService.getHistoryForBook(bookId, page, size);
        return ResponseEntity.ok(historyPage.map(this::toHistoryResponse));
    }

    @GetMapping("/low-stock")
    public ResponseEntity<List<InventoryResponse>> getLowStock() {
        List<InventoryResponse> response = adminService.getLowStock(Integer.MAX_VALUE).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/out-of-stock")
    public ResponseEntity<List<InventoryResponse>> getOutOfStock() {
        List<InventoryResponse> response = adminService.getOutOfStock().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<List<InventoryResponse>> getByStatus(@PathVariable InventoryStatus status) {
        List<InventoryResponse> response = adminService.getInventoryByStatus(status).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    private InventoryResponse toResponse(Inventory inv) {
        String bookTitle = bookRepository.findById(inv.getBookId())
                .map(Book::getTitle)
                .orElse("Unknown");
        return new InventoryResponse(
                inv.getBookId(),
                bookTitle,
                inv.getAvailableQuantity(),
                inv.getReservedQuantity(),
                inv.getTotalQuantity(),
                validator.determineStatus(inv.getAvailableQuantity())
        );
    }

    private InventoryHistoryResponse toHistoryResponse(InventoryHistory h) {
        return new InventoryHistoryResponse(
                h.getId(),
                h.getBookId(),
                h.getEventType(),
                h.getPreviousAvailable(),
                h.getNewAvailable(),
                h.getPreviousReserved(),
                h.getNewReserved(),
                h.getQuantityChange(),
                h.getOrderId(),
                h.getPaymentId(),
                h.getChangedBy(),
                h.getReason(),
                h.getCreatedAt()
        );
    }
}
