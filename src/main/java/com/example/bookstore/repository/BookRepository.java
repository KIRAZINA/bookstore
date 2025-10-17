package com.example.bookstore.repository;

import com.example.bookstore.model.Book;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BookRepository extends JpaRepository<Book, Long> {
    Page<Book> findByCategory(String category, Pageable pageable);

    @Query("SELECT b FROM Book b WHERE b.title LIKE %:search% OR b.author LIKE %:search%")
    Page<Book> findByTitleOrAuthor(String search, Pageable pageable);
}