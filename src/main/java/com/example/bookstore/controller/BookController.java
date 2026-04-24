package com.example.bookstore.controller;

import com.example.bookstore.controller.dto.BookRequest;
import com.example.bookstore.controller.dto.BookResponse;
import com.example.bookstore.controller.dto.UpdateStockRequest;
import com.example.bookstore.model.Book;
import com.example.bookstore.service.BookService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/books")
public class BookController {
    private final BookService bookService;

    public BookController(BookService bookService) {
        this.bookService = bookService;
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
        book.setStock(request.getStock());
        return ResponseEntity.status(201).body(toBookResponse(bookService.addBook(book)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteBook(@PathVariable Long id) {
        bookService.deleteBook(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/stock")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BookResponse> updateStock(@PathVariable Long id, @Valid @RequestBody UpdateStockRequest request) {
        return ResponseEntity.ok(toBookResponse(bookService.updateStock(id, request.getStock())));
    }

    private BookResponse toBookResponse(Book book) {
        return new BookResponse(
                book.getId(),
                book.getTitle(),
                book.getAuthor(),
                book.getPrice(),
                book.getCategory(),
                book.getStock()
        );
    }
}
