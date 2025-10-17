package com.example.bookstore.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Positive;
import lombok.*;

@Entity
@Table(name = "order_items")
@Getter
@Setter
@ToString
@EqualsAndHashCode(of = "id")
public class OrderItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    @Column(nullable = false)
    @Positive(message = "Quantity must be positive")
    private int quantity;

    @Column(nullable = false)
    @Positive(message = "Price must be positive")
    private double price;

    public Book getBook() {
        if (book == null) return null;
        Book copy = new Book();
        copy.setId(book.getId());
        copy.setTitle(book.getTitle());
        copy.setAuthor(book.getAuthor());
        copy.setPrice(book.getPrice());
        return copy;
    }

    public void setBook(Book book) {
        if (book == null) {
            this.book = null;
            return;
        }
        this.book = new Book();
        this.book.setId(book.getId());
        this.book.setTitle(book.getTitle());
        this.book.setAuthor(book.getAuthor());
        this.book.setPrice(book.getPrice());
    }
}