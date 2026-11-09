package com.example.bookstore.controller;

import com.example.bookstore.controller.dto.BookRequest;
import com.example.bookstore.controller.dto.BookResponse;
import com.example.bookstore.controller.dto.UpdateStockRequest;
import com.example.bookstore.model.Book;
import com.example.bookstore.model.Inventory;
import com.example.bookstore.repository.InventoryRepository;
import com.example.bookstore.service.BookService;
import com.example.bookstore.service.InventoryService;
import com.example.bookstore.service.InventoryValidator;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/books")
public class BookController {
    private final BookService bookService;
    private final InventoryService inventoryService;
    private final InventoryValidator inventoryValidator;
    private final InventoryRepository inventoryRepository;

    public BookController(BookService bookService,
                          InventoryService inventoryService,
                          InventoryValidator inventoryValidator,
                          InventoryRepository inventoryRepository) {
        this.bookService = bookService;
        this.inventoryService = inventoryService;
        this.inventoryValidator = inventoryValidator;
        this.inventoryRepository = inventoryRepository;
    }

    @GetMapping
    public org.springframework.http.ResponseEntity<List<BookResponse>> getAllBooks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        int pageSize = Math.min(Math.max(size, 1), 100);
        List<Book> fetched = bookService.getAllBooks(page, pageSize + 1);
        boolean truncated = fetched.size() > pageSize;
        List<Book> pageBooks = fetched.size() > pageSize ? fetched.subList(0, pageSize) : fetched;
        List<BookResponse> body = toBookResponses(pageBooks);
        org.springframework.http.ResponseEntity<List<BookResponse>> response =
                org.springframework.http.ResponseEntity.ok()
                        .header("X-Page", String.valueOf(page))
                        .header("X-Page-Size", String.valueOf(pageSize))
                        .header("X-Truncated", String.valueOf(truncated))
                        .body(body);
        return response;
    }

    @GetMapping("/category")
    public org.springframework.http.ResponseEntity<List<BookResponse>> getBooksByCategory(
            @RequestParam String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        int pageSize = Math.min(Math.max(size, 1), 100);
        List<Book> fetched = bookService.getBooksByCategory(category, page, pageSize + 1);
        boolean truncated = fetched.size() > pageSize;
        List<Book> pageBooks = fetched.size() > pageSize ? fetched.subList(0, pageSize) : fetched;
        return org.springframework.http.ResponseEntity.ok()
                .header("X-Page", String.valueOf(page))
                .header("X-Page-Size", String.valueOf(pageSize))
                .header("X-Truncated", String.valueOf(truncated))
                .body(toBookResponses(pageBooks));
    }

    @GetMapping("/search")
    public org.springframework.http.ResponseEntity<List<BookResponse>> searchBooks(
            @RequestParam String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (search != null && search.trim().length() > 128) {
            throw new IllegalArgumentException("search term too long");
        }
        int pageSize = Math.min(Math.max(size, 1), 100);
        List<Book> fetched = bookService.searchBooks(search, page, pageSize + 1);
        boolean truncated = fetched.size() > pageSize;
        List<Book> pageBooks = fetched.size() > pageSize ? fetched.subList(0, pageSize) : fetched;
        return org.springframework.http.ResponseEntity.ok()
                .header("X-Page", String.valueOf(page))
                .header("X-Page-Size", String.valueOf(pageSize))
                .header("X-Truncated", String.valueOf(truncated))
                .body(toBookResponses(pageBooks));
    }

    @GetMapping("/{id}")
    public ResponseEntity<BookResponse> getBookById(@PathVariable Long id) {
        Book book = bookService.getBookById(id);
        return ResponseEntity.ok(toBookResponse(book));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BookResponse> addBook(@Valid @RequestBody BookRequest request) {
        Book book = new Book();
        book.setTitle(request.getTitle());
        book.setAuthor(request.getAuthor());
        book.setPrice(request.getPrice());
        book.setCategory(request.getCategory());
        Book saved = bookService.addBook(book);
        return ResponseEntity.status(201).body(toBookResponse(saved));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteBook(@PathVariable Long id) {
        bookService.deleteBook(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/stock")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BookResponse> updateStock(@PathVariable Long id,
                                                     @Valid @RequestBody UpdateStockRequest request) {
        return ResponseEntity.ok(toBookResponse(bookService.updateStock(id, request.getStock())));
    }

    private BookResponse toBookResponse(Book book) {
        int avail = inventoryService.getAvailableQuantity(book.getId());
        String status = inventoryValidator.determineStatus(avail).name();
        return new BookResponse(
                book.getId(),
                book.getTitle(),
                book.getAuthor(),
                book.getPrice(),
                book.getCategory(),
                avail,
                avail,
                status
        );
    }

    private List<BookResponse> toBookResponses(List<Book> books) {
        if (books == null || books.isEmpty()) {
            return List.of();
        }
        List<Long> ids = books.stream().map(Book::getId).toList();
        Map<Long, Integer> availMap = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Inventory inv : inventoryRepository.findByBookIdIn(ids)) {
                availMap.put(inv.getBookId(), inv.getAvailableQuantity());
            }
        }
        return books.stream().map(b -> {
            int avail = availMap.getOrDefault(b.getId(), 0);
            String status = inventoryValidator.determineStatus(avail).name();
            return new BookResponse(
                    b.getId(),
                    b.getTitle(),
                    b.getAuthor(),
                    b.getPrice(),
                    b.getCategory(),
                    avail,
                    avail,
                    status
            );
        }).collect(Collectors.toList());
    }
}
