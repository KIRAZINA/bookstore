package com.example.bookstore.service;

import com.example.bookstore.model.Book;
import com.example.bookstore.repository.BookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookServiceTest {

    @Mock
    private BookRepository bookRepository;

    @InjectMocks
    private BookService bookService;

    private Book testBook;

    @BeforeEach
    void setUp() {
        testBook = new Book();
        testBook.setId(1L);
        testBook.setTitle("Test Book");
        testBook.setAuthor("Test Author");
        testBook.setPrice(29.99);
        testBook.setStock(10);
        testBook.setCategory("Programming");
    }

    @Nested
    @DisplayName("addBook")
    class AddBookTests {

        @Test
        @DisplayName("Успішне додавання книги")
        void addBook_Success() {
            when(bookRepository.save(any(Book.class))).thenReturn(testBook);

            Book result = bookService.addBook(testBook);

            assertNotNull(result);
            assertEquals("Test Book", result.getTitle());
            verify(bookRepository).save(testBook);
        }
    }

    @Nested
    @DisplayName("getAllBooks")
    class GetAllBooksTests {

        @Test
        @DisplayName("Отримання всіх книг")
        void getAllBooks_Success() {
            List<Book> books = Arrays.asList(testBook);
            when(bookRepository.findAll()).thenReturn(books);

            List<Book> result = bookService.getAllBooks();

            assertNotNull(result);
            assertEquals(1, result.size());
            assertEquals("Test Book", result.get(0).getTitle());
        }
    }

    @Nested
    @DisplayName("getBooksByCategory")
    class GetBooksByCategoryTests {

        @Test
        @DisplayName("Отримання книг за категорією")
        void getBooksByCategory_Success() {
            List<Book> books = Arrays.asList(testBook);
            when(bookRepository.findByCategory("Programming")).thenReturn(books);

            List<Book> result = bookService.getBooksByCategory("Programming");

            assertNotNull(result);
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("Категорія не знайдена - повертає порожній список")
        void getBooksByCategory_NotFound() {
            when(bookRepository.findByCategory("NonExistent")).thenReturn(Arrays.asList());

            List<Book> result = bookService.getBooksByCategory("NonExistent");

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("searchBooks")
    class SearchBooksTests {

        @Test
        @DisplayName("Пошук книг за назвою або автором")
        void searchBooks_Success() {
            List<Book> books = Arrays.asList(testBook);
            when(bookRepository.findByTitleOrAuthor("Test")).thenReturn(books);

            List<Book> result = bookService.searchBooks("Test");

            assertNotNull(result);
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("Пошук без результатів")
        void searchBooks_NoResults() {
            when(bookRepository.findByTitleOrAuthor("XYZ123")).thenReturn(Arrays.asList());

            List<Book> result = bookService.searchBooks("XYZ123");

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("deleteBook")
    class DeleteBookTests {

        @Test
        @DisplayName("Успішне видалення книги")
        void deleteBook_Success() {
            doNothing().when(bookRepository).deleteById(1L);

            assertDoesNotThrow(() -> bookService.deleteBook(1L));
            verify(bookRepository).deleteById(1L);
        }
    }

    @Nested
    @DisplayName("updateStock")
    class UpdateStockTests {

        @Test
        @DisplayName("Успішне оновлення stock")
        void updateStock_Success() {
            when(bookRepository.findById(1L)).thenReturn(Optional.of(testBook));
            when(bookRepository.save(any(Book.class))).thenReturn(testBook);

            Book result = bookService.updateStock(1L, 50);

            assertNotNull(result);
            assertEquals(50, result.getStock());
        }

        @Test
        @DisplayName("Книга не знайдена")
        void updateStock_BookNotFound() {
            when(bookRepository.findById(999L)).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class, () -> bookService.updateStock(999L, 50));
        }

        @Test
        @DisplayName("Від'ємний stock - помилка")
        void updateStock_NegativeStock() {
            when(bookRepository.findById(1L)).thenReturn(Optional.of(testBook));

            assertThrows(IllegalArgumentException.class, () -> bookService.updateStock(1L, -1));
        }
    }
}
