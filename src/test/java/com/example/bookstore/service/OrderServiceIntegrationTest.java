package com.example.bookstore.service;

import com.example.bookstore.config.SecurityUtils;
import com.example.bookstore.model.AppUser;
import com.example.bookstore.model.Book;
import com.example.bookstore.model.Order;
import com.example.bookstore.model.OrderItem;
import com.example.bookstore.model.UserRole;
import com.example.bookstore.repository.BookRepository;
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
        "spring.datasource.url=jdbc:h2:mem:orderserviceitdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class OrderServiceIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private CartService cartService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BookRepository bookRepository;

    @MockBean
    private SecurityUtils securityUtils;

    @Test
    @DisplayName("Checkout uses frozen cart price after catalog price changes")
    void createOrder_UsesPriceAtAdd_NotCurrentBookPrice() {
        AppUser user = new AppUser();
        user.setUsername("order-it-" + UUID.randomUUID());
        user.setPassword("password");
        user.setEmail("order-it@example.com");
        user.setRole(UserRole.ROLE_USER);
        user = userRepository.save(user);

        Book book = new Book();
        book.setTitle("Price Freeze Book");
        book.setAuthor("Architect");
        book.setPrice(new BigDecimal("10.00"));
        book.setCategory("Testing");
        book.setStock(10);
        book = bookRepository.save(book);

        when(securityUtils.getCurrentUser()).thenReturn(user);

        cartService.addToCart(book.getId(), 2);

        book.setPrice(new BigDecimal("20.00"));
        bookRepository.save(book);

        Order createdOrder = orderService.createOrder();
        assertNotNull(createdOrder.getId());
        assertEquals(1, createdOrder.getItems().size());

        OrderItem item = createdOrder.getItems().get(0);
        assertEquals(new BigDecimal("10.00"), item.getUnitPrice());
        assertEquals(new BigDecimal("20.00"), item.getSubtotal());
        assertEquals(new BigDecimal("20.00"), createdOrder.getTotalPrice());

        Book updatedBook = bookRepository.findById(book.getId()).orElseThrow();
        assertEquals(8, updatedBook.getStock());
    }
}
