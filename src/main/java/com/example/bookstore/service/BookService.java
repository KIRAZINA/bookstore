package com.example.bookstore.service;

import com.example.bookstore.model.Book;
import com.example.bookstore.repository.BookRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class BookService {
    private final BookRepository bookRepository;

    public BookService(BookRepository bookRepository) {
        this.bookRepository = bookRepository;
    }

    public Book addBook(Book book) {
        return bookRepository.save(book);
    }

    public List<Book> getAllBooks() {
        return bookRepository.findAll();
    }

    public List<Book> getBooksByCategory(String category) {
        return bookRepository.findByCategory(category);
    }

    public List<Book> searchBooks(String search) {
        return bookRepository.findByTitleOrAuthor(search);
    }

    public void deleteBook(Long id) {
        bookRepository.deleteById(id);
    }

    public Book updateStock(Long bookId, int stock) {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("Book not found"));
        if (stock < 0) {
            throw new IllegalArgumentException("Stock cannot be negative");
        }
        book.setStock(stock);
        return bookRepository.save(book);
    }
}
