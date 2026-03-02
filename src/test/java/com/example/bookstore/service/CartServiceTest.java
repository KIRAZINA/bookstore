package com.example.bookstore.service;

import com.example.bookstore.config.SecurityUtils;
import com.example.bookstore.model.AppUser;
import com.example.bookstore.model.Book;
import com.example.bookstore.model.Cart;
import com.example.bookstore.model.UserRole;
import com.example.bookstore.repository.BookRepository;
import com.example.bookstore.repository.CartRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock
    private CartRepository cartRepository;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private SecurityUtils securityUtils;

    @InjectMocks
    private CartService cartService;

    private AppUser testUser;
    private Book testBook;
    private Cart testCart;

    @BeforeEach
    void setUp() {
        testUser = new AppUser();
        testUser.setId(1L);
        testUser.setUsername("testuser");
        testUser.setEmail("test@example.com");
        testUser.setRole(UserRole.ROLE_USER);

        testBook = new Book();
        testBook.setId(1L);
        testBook.setTitle("Test Book");
        testBook.setPrice(29.99);
        testBook.setStock(10);

        testCart = new Cart();
        testCart.setId(1L);
        testCart.setUser(testUser);
    }

    @Nested
    @DisplayName("getCart")
    class GetCartTests {
        @Test
        @DisplayName("Отримання існуючого кошика")
        void getCart_ExistingCart() {
            when(securityUtils.getCurrentUser()).thenReturn(testUser);
            when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(testCart));

            Cart result = cartService.getCart();

            assertNotNull(result);
            assertEquals(1L, result.getId());
            verify(cartRepository).findByUserId(1L);
        }

        @Test
        @DisplayName("Створення нового кошика")
        void getCart_NewCart() {
            when(securityUtils.getCurrentUser()).thenReturn(testUser);
            when(cartRepository.findByUserId(1L)).thenReturn(Optional.empty());
            when(cartRepository.save(any(Cart.class))).thenAnswer(invocation -> {
                Cart cart = invocation.getArgument(0);
                cart.setId(1L);
                return cart;
            });

            Cart result = cartService.getCart();

            assertNotNull(result);
            assertEquals(1L, result.getId());
            verify(cartRepository).save(any(Cart.class));
        }
    }

    @Nested
    @DisplayName("addToCart")
    class AddToCartTests {
        @Test
        @DisplayName("Додавання книги до кошика")
        void addToCart_Success() {
            when(securityUtils.getCurrentUser()).thenReturn(testUser);
            when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(testCart));
            when(bookRepository.findById(1L)).thenReturn(Optional.of(testBook));
            when(cartRepository.save(any(Cart.class))).thenReturn(testCart);

            Cart result = cartService.addToCart(1L, 2);

            assertNotNull(result);
            verify(cartRepository).save(any(Cart.class));
        }
    }
}
