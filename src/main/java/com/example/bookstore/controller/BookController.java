package com.example.bookstore.controller;

import com.example.bookstore.controller.dto.BookRequest;
import com.example.bookstore.controller.dto.BookResponse;
import com.example.bookstore.controller.dto.UpdateStockRequest;
import com.example.bookstore.model.Book;
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

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/books")
public class BookController {
    private final BookService bookService;
    private final InventoryService inventoryService;
    private final InventoryValidator inventoryValidator;

    public BookController(BookService bookService,
                          InventoryService inventoryService,
                          InventoryValidator inventoryValidator) {
        this.bookService = bookService;
        this.inventoryService = inventoryService;
        this.inventoryValidator = inventoryValidator;
    }

    @GetMapping
    public List<BookResponse> getAllBooks() {
        return bookService.getAllBooks().stream().map(this::toBookResponse).collect(Collectors.toList());
    }

    @GetMapping("/category")
    public List<BookResponse> getBooksByCategory(@RequestParam String category) {
        return bookService.getBooksByCategory(category).stream().map(this::toBookResponse).collect(Collectors.toList());
    }

    @GetMapping("/search")
    public List<BookResponse> searchBooks(@RequestParam String search) {
        return bookService.searchBooks(search).stream().map(this::toBookResponse).collect(Collectors.toList());
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
}
