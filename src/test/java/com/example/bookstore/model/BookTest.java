package com.example.bookstore.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BookTest {

    @Test
    void book_ShouldSetAndGetFields() {
        Book book = new Book();
        book.setId(1L);
        book.setTitle("Test Book");
        book.setAuthor("Test Author");
        book.setPrice(19.99);
        book.setCategory("Fiction");
        book.setStock(10);

        assertEquals(1L, book.getId());
        assertEquals("Test Book", book.getTitle());
        assertEquals("Test Author", book.getAuthor());
        assertEquals(19.99, book.getPrice());
        assertEquals("Fiction", book.getCategory());
        assertEquals(10, book.getStock());
    }

    @Test
    void booksWithSameId_ShouldBeEqual() {
        Book book1 = new Book();
        book1.setId(1L);
        book1.setTitle("Book 1");

        Book book2 = new Book();
        book2.setId(1L);
        book2.setTitle("Different Title");

        assertEquals(book1, book2);
        assertEquals(book1.hashCode(), book2.hashCode());
    }

    @Test
    void booksWithDifferentId_ShouldNotBeEqual() {
        Book book1 = new Book();
        book1.setId(1L);

        Book book2 = new Book();
        book2.setId(2L);

        assertNotEquals(book1, book2);
    }

    @Test
    void toString_ShouldContainTitle() {
        Book book = new Book();
        book.setTitle("Test Book");

        assertTrue(book.toString().contains("Test Book"));
    }
}
