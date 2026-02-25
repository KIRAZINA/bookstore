package com.example.bookstore.service;

import com.example.bookstore.model.AppUser;
import com.example.bookstore.model.Book;
import com.example.bookstore.model.Cart;
import com.example.bookstore.model.UserRole;
import com.example.bookstore.repository.BookRepository;
import com.example.bookstore.repository.CartRepository;
import com.example.bookstore.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

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
    private UserRepository userRepository;

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

        UserDetails userDetails = org.springframework.security.core.userdetails.User
                .withUsername("testuser")
                .password("password")
                .roles("USER")
                .build();
        Authentication auth = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Nested
    @DisplayName("getCart")
    class GetCartTests {
        @Test
        @DisplayName("Отримання існуючого кошика")
        void getCart_ExistingCart() {
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(testCart));

            Cart result = cartService.getCart();

            assertNotNull(result);
            assertEquals(1L, result.getId());
            verify(cartRepository).findByUserId(1L);
        }

        @Test
        @DisplayName("Створення нового кошика")
        void getCart_NewCart() {
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(cartRepository.findByUserId(1L)).thenReturn(Optional.empty());
            when(cartRepository.save(any(Cart.class))).thenAnswer(invocation -> {
                Cart cart = invocation.getArgument(0);
                cart.setId(1L);
                return cart;
            });

            Cart result = cartService.getCart();

            assertNotNull(result);
            verify(cartRepository).save(any(Cart.class));
        }
    }

    @Nested
    @DisplayName("addToCart")
    class AddToCartTests {
        @Test
        @DisplayName("Успішне додавання книги до кошика")
        void addToCart_Success() {
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(testCart));
            when(bookRepository.findById(1L)).thenReturn(Optional.of(testBook));
            when(cartRepository.save(any(Cart.class))).thenReturn(testCart);

            Cart result = cartService.addToCart(1L, 2);

            assertNotNull(result);
            verify(bookRepository).findById(1L);
            verify(cartRepository).save(testCart);
        }

        @Test
        @DisplayName("Додавання книги з негативною кількістю")
        void addToCart_NegativeQuantity() {
            assertThrows(IllegalArgumentException.class, () -> cartService.addToCart(1L, -1));
        }

        @Test
        @DisplayName("Додавання книги з нульовою кількістю")
        void addToCart_ZeroQuantity() {
            assertThrows(IllegalArgumentException.class, () -> cartService.addToCart(1L, 0));
        }

        @Test
        @DisplayName("Додавання неіснуючої книги")
        void addToCart_BookNotFound() {
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(testCart));
            when(bookRepository.findById(999L)).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class, () -> cartService.addToCart(999L, 1));
        }

        @Test
        @DisplayName("Додавання книги з недостатнім запасом")
        void addToCart_InsufficientStock() {
            testBook.setStock(1);
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(testCart));
            when(bookRepository.findById(1L)).thenReturn(Optional.of(testBook));

            assertThrows(IllegalStateException.class, () -> cartService.addToCart(1L, 5));
        }
    }

    @Nested
    @DisplayName("removeFromCart")
    class RemoveFromCartTests {
        @Test
        @DisplayName("Успішне видалення книги з кошика")
        void removeFromCart_Success() {
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(testCart));
            when(cartRepository.save(any(Cart.class))).thenReturn(testCart);

            cartService.removeFromCart(1L);

            verify(cartRepository).save(testCart);
        }
    }

    @Nested
    @DisplayName("clearCart")
    class ClearCartTests {
        @Test
        @DisplayName("Очищення кошика")
        void clearCart_Success() {
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(testCart));
            when(cartRepository.save(any(Cart.class))).thenReturn(testCart);

            cartService.clearCart();

            verify(cartRepository).save(testCart);
        }
    }
}
