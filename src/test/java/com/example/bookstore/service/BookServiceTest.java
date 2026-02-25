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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        @DisplayName("Отримання всіх книг з пагінацією")
        void getAllBooks_Success() {
            List<Book> books = Arrays.asList(testBook);
            Page<Book> page = new PageImpl<>(books);
            when(bookRepository.findAll(any(PageRequest.class))).thenReturn(page);

            Page<Book> result = bookService.getAllBooks(0, 10, "title", "asc");

            assertNotNull(result);
            assertEquals(1, result.getTotalElements());
            assertEquals("Test Book", result.getContent().get(0).getTitle());
        }

        @Test
        @DisplayName("Отримання книг з сортуванням по спаданню")
        void getAllBooks_DescendingSort() {
            List<Book> books = Arrays.asList(testBook);
            Page<Book> page = new PageImpl<>(books);
            when(bookRepository.findAll(any(PageRequest.class))).thenReturn(page);

            Page<Book> result = bookService.getAllBooks(0, 10, "price", "desc");

            assertNotNull(result);
            verify(bookRepository).findAll(any(PageRequest.class));
        }
    }

    @Nested
    @DisplayName("getBooksByCategory")
    class GetBooksByCategoryTests {

        @Test
        @DisplayName("Отримання книг за категорією")
        void getBooksByCategory_Success() {
            List<Book> books = Arrays.asList(testBook);
            Page<Book> page = new PageImpl<>(books);
            when(bookRepository.findByCategory(eq("Programming"), any(PageRequest.class))).thenReturn(page);

            Page<Book> result = bookService.getBooksByCategory("Programming", 0, 10, "title", "asc");

            assertNotNull(result);
            assertEquals(1, result.getTotalElements());
            verify(bookRepository).findByCategory(eq("Programming"), any(PageRequest.class));
        }

        @Test
        @DisplayName("Отримання книг за неіснуючою категорією")
        void getBooksByCategory_EmptyCategory() {
            Page<Book> emptyPage = new PageImpl<>(List.of());
            when(bookRepository.findByCategory(eq("NonExistent"), any(PageRequest.class))).thenReturn(emptyPage);

            Page<Book> result = bookService.getBooksByCategory("NonExistent", 0, 10, "title", "asc");

            assertNotNull(result);
            assertEquals(0, result.getTotalElements());
        }
    }

    @Nested
    @DisplayName("searchBooks")
    class SearchBooksTests {

        @Test
        @DisplayName("Пошук книг за назвою або автором")
        void searchBooks_Success() {
            List<Book> books = Arrays.asList(testBook);
            Page<Book> page = new PageImpl<>(books);
            when(bookRepository.findByTitleOrAuthor(eq("Test"), any(PageRequest.class))).thenReturn(page);

            Page<Book> result = bookService.searchBooks("Test", 0, 10, "title", "asc");

            assertNotNull(result);
            assertEquals(1, result.getTotalElements());
            verify(bookRepository).findByTitleOrAuthor(eq("Test"), any(PageRequest.class));
        }

        @Test
        @DisplayName("Пошук без результатів")
        void searchBooks_NoResults() {
            Page<Book> emptyPage = new PageImpl<>(List.of());
            when(bookRepository.findByTitleOrAuthor(eq("XYZ123"), any(PageRequest.class))).thenReturn(emptyPage);

            Page<Book> result = bookService.searchBooks("XYZ123", 0, 10, "title", "asc");

            assertNotNull(result);
            assertEquals(0, result.getTotalElements());
        }
    }

    @Nested
    @DisplayName("deleteBook")
    class DeleteBookTests {

        @Test
        @DisplayName("Видалення книги")
        void deleteBook_Success() {
            doNothing().when(bookRepository).deleteById(1L);

            bookService.deleteBook(1L);

            verify(bookRepository).deleteById(1L);
        }
    }

    @Nested
    @DisplayName("updateStock")
    class UpdateStockTests {

        @Test
        @DisplayName("Успішне оновлення库存")
        void updateStock_Success() {
            when(bookRepository.findById(1L)).thenReturn(Optional.of(testBook));
            when(bookRepository.save(any(Book.class))).thenReturn(testBook);

            Book result = bookService.updateStock(1L, 20);

            assertNotNull(result);
            assertEquals(20, result.getStock());
            verify(bookRepository).findById(1L);
            verify(bookRepository).save(testBook);
        }

        @Test
        @DisplayName("Оновлення库存 для неіснуючої книги")
        void updateStock_BookNotFound() {
            when(bookRepository.findById(999L)).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class, () -> bookService.updateStock(999L, 20));
        }

        @Test
        @DisplayName("Оновлення库存 з від'ємним значенням")
        void updateStock_NegativeStock() {
            when(bookRepository.findById(1L)).thenReturn(Optional.of(testBook));

            assertThrows(IllegalArgumentException.class, () -> bookService.updateStock(1L, -5));
        }

        @Test
        @DisplayName("Оновлення库存 до нуля")
        void updateStock_ZeroStock() {
            when(bookRepository.findById(1L)).thenReturn(Optional.of(testBook));
            testBook.setStock(0);
            when(bookRepository.save(any(Book.class))).thenReturn(testBook);

            Book result = bookService.updateStock(1L, 0);

            assertNotNull(result);
            assertEquals(0, result.getStock());
        }
    }
}
