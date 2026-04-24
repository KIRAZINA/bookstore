package com.example.bookstore.service;

import com.example.bookstore.config.SecurityUtils;
import com.example.bookstore.model.AppUser;
import com.example.bookstore.model.Book;
import com.example.bookstore.model.Cart;
import com.example.bookstore.model.UserRole;
import com.example.bookstore.repository.BookRepository;
import com.example.bookstore.repository.CartItemRepository;
import com.example.bookstore.repository.CartRepository;
import com.example.bookstore.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:cartserviceitdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class CartServiceIntegrationTest {

    @Autowired
    private CartService cartService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private CartItemRepository cartItemRepository;

    @MockBean
    private SecurityUtils securityUtils;

    @Test
    @DisplayName("clearCart physically deletes CartItem rows from database")
    void clearCart_RemovesCartItemsFromDatabase() {
        AppUser user = new AppUser();
        user.setUsername("cart-it-" + UUID.randomUUID());
        user.setPassword("password");
        user.setEmail("cart-it@example.com");
        user.setRole(UserRole.ROLE_USER);
        user = userRepository.save(user);

        Book book = new Book();
        book.setTitle("Integration Test Book");
        book.setAuthor("IT Author");
        book.setPrice(new BigDecimal("12.50"));
        book.setCategory("IT");
        book.setStock(10);
        book = bookRepository.save(book);

        when(securityUtils.getCurrentUser()).thenReturn(user);

        cartService.addToCart(book.getId(), 2);
        Cart cart = cartRepository.findByUserId(user.getId()).orElseThrow();
        assertNotNull(cart.getId());
        assertEquals(1, cartItemRepository.countByCartId(cart.getId()));

        cartService.clearCart();
        cartRepository.flush();
        cartItemRepository.flush();

        assertEquals(0, cartItemRepository.countByCartId(cart.getId()));
    }
}
