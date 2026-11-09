package com.example.bookstore.repository;

import com.example.bookstore.model.Book;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface BookRepository extends JpaRepository<Book, Long> {
    List<Book> findByCategory(String category);

    List<Book> findByCategory(String category, Pageable pageable);

    @Query("SELECT b FROM Book b WHERE b.title LIKE %:search% OR b.author LIKE %:search%")
    List<Book> findByTitleOrAuthor(String search);

    @Query("SELECT b FROM Book b WHERE b.title LIKE %:search% OR b.author LIKE %:search%")
    List<Book> findByTitleOrAuthor(String search, Pageable pageable);
}