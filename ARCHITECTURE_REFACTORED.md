# Bookstore API - Refactored Enterprise Architecture

> **Version:** 2.0  
> **Status:** Architecture Review Complete  
> **Author:** Senior Software Architect

---

## Executive Summary

This document outlines a comprehensive refactoring of the Bookstore API to address critical architectural flaws, security vulnerabilities, and deviations from enterprise REST standards. The refactoring ensures financial data integrity, robust concurrency control, proper security posture, and clean API contracts.

---

## Critical Issues Addressed

| Issue | Severity | Solution |
|-------|----------|----------|
| Floating-point financial calculations | **CRITICAL** | `BigDecimal` with explicit scale/precision |
| Disabled CSRF protection | **HIGH** | JWT-based stateless authentication |
| Race conditions in stock management | **HIGH** | Optimistic Locking with `@Version` |
| Query parameters for state mutation | **MEDIUM** | Strict DTO-based request bodies |
| JPA Entity exposure in API | **MEDIUM** | Complete DTO layer with MapStruct |
| Price freezing ambiguity | **MEDIUM** | Explicit `priceAtAdd` in CartItem |

---

## 1. Refactored Domain Model (Entity Layer)

### 1.1 AppUser (Unchanged)
```java
@Entity
@Table(name = "app_user")
public class AppUser implements UserDetails, Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(unique = true, nullable = false)
    private String username;
    
    @Column(nullable = false)
    private String password;
    
    private String email;
    
    @Enumerated(EnumType.STRING)
    private UserRole role = UserRole.ROLE_USER;
}
```

---

### 1.2 Book (REFACTORED - Financial Precision)
```java
@Entity
@Getter
@Setter
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Book {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(nullable = false)
    @NotBlank(message = "Title is required")
    private String title;

    @Column(nullable = false)
    @NotBlank(message = "Author is required")
    private String author;

    // REFACTORED: BigDecimal instead of double
    @Column(nullable = false, precision = 19, scale = 4)
    @NotNull(message = "Price is required")
    @DecimalMin(value = "0.0001", message = "Price must be positive")
    private BigDecimal price;

    private String category;

    @Column(nullable = false)
    @PositiveOrZero(message = "Stock cannot be negative")
    private int stock;

    // REFACTORED: Optimistic Locking for concurrency
    @Version
    private Long version;
}
```

**Key Changes:**
- `price`: `double` → `BigDecimal(precision=19, scale=4)`
- Added `@Version` for optimistic locking
- Scale 4 supports currencies requiring sub-cent precision (e.g., gasoline, metals)

---

### 1.3 CartItem (REFACTORED - Price Freezing)
```java
@Entity
@Table(name = "cart_items")
@Getter
@Setter
@ToString
@EqualsAndHashCode(of = "id")
public class CartItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    @Column(nullable = false)
    @Positive(message = "Quantity must be positive")
    private int quantity;

    // REFACTORED: Price freezing at add time
    @Column(name = "price_at_add", nullable = false, precision = 19, scale = 4)
    private BigDecimal priceAtAdd;

    // REFACTORED: Timestamp for audit trail
    @Column(name = "added_at", nullable = false)
    private LocalDateTime addedAt;

    @PrePersist
    public void prePersist() {
        if (priceAtAdd == null && book != null) {
            priceAtAdd = book.getPrice();
        }
        if (addedAt == null) {
            addedAt = LocalDateTime.now();
        }
    }
}
```

**Key Changes:**
- Added `priceAtAdd`: Stores book price at moment of adding to cart
- Added `addedAt`: Audit timestamp
- `@PrePersist` ensures price is frozen from current book price

**Business Rule:** Cart displays `priceAtAdd` × quantity, not current book price. This prevents price shock at checkout.

---

### 1.4 Cart (REFACTORED - Cascade Behavior)
```java
@Entity
@Getter
@Setter
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Cart {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @OneToOne
    @JoinColumn(name = "user_id")
    @ToString.Exclude
    private AppUser user;

    // REFACTORED: Explicit cascade with orphan removal
    @OneToMany(mappedBy = "cart", 
               cascade = CascadeType.ALL, 
               orphanRemoval = true,
               fetch = FetchType.EAGER)
    private List<CartItem> items = new ArrayList<>();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PreUpdate
    @PrePersist
    public void updateTimestamp() {
        this.updatedAt = LocalDateTime.now();
    }

    // REFACTORED: Bidirectional relationship helper
    public void addItem(CartItem item) {
        items.add(item);
        item.setCart(this);
    }

    public void removeItem(CartItem item) {
        items.remove(item);
        item.setCart(null);
    }
}
```

**Key Changes:**
- `mappedBy = "cart"`: Proper bidirectional relationship
- `orphanRemoval = true`: Deleting CartItem from list removes DB record
- `CascadeType.ALL`: Operations propagate to items
- Added `updatedAt` for cache invalidation

---

### 1.5 OrderItem (REFACTORED - Financial Precision)
```java
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

    @ManyToOne
    @JoinColumn(name = "order_id", nullable = false)
    @ToString.Exclude
    private Order order;

    @Column(nullable = false)
    @Positive(message = "Quantity must be positive")
    private int quantity;

    // REFACTORED: BigDecimal with historical price
    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    // REFACTORED: Denormalized subtotal for query performance
    @Column(name = "subtotal", nullable = false, precision = 19, scale = 4)
    private BigDecimal subtotal;

    @PrePersist
    public void prePersist() {
        if (unitPrice != null && quantity > 0) {
            subtotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
        }
    }
}
```

**Key Changes:**
- `unitPrice`: Price at order time (from `CartItem.priceAtAdd`)
- `subtotal`: Denormalized calculation (`unitPrice × quantity`)
- Proper `@ManyToOne` to Order (bidirectional)

---

### 1.6 Order (REFACTORED - Financial Precision)
```java
@Entity
@Table(name = "orders")
@Getter
@Setter
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private AppUser user;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // REFACTORED: BigDecimal for total
    @Column(name = "total_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalPrice;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "USD";

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private OrderStatus status = OrderStatus.PENDING;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    @Version
    private Long version;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);
    }

    // REFACTORED: Explicit total calculation
    public void calculateTotal() {
        this.totalPrice = items.stream()
            .map(OrderItem::getSubtotal)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
```

**Key Changes:**
- `totalPrice`: `double` → `BigDecimal(precision=19, scale=4)`
- Added `currency`: ISO 4217 currency code
- Added `status`: `OrderStatus` enum (PENDING, CONFIRMED, SHIPPED, CANCELLED)
- `calculateTotal()`: Explicit method for total calculation
- `@Version`: Optimistic locking

---

### 1.7 OrderStatus (NEW)
```java
public enum OrderStatus {
    PENDING,      // Order created, payment pending
    CONFIRMED,    // Payment confirmed, processing
    SHIPPED,      // Items shipped
    DELIVERED,    // Items delivered
    CANCELLED     // Order cancelled
}
```

---

## 2. DTO Layer (NEW - API Contract Isolation)

### 2.1 Package Structure
```
controller/dto/
├── request/
│   ├── AddToCartRequest.java
│   ├── UpdateCartRequest.java
│   ├── CreateOrderRequest.java
│   ├── AddBookRequest.java
│   ├── UpdateStockRequest.java
│   └── RegisterRequest.java
└── response/
    ├── BookResponse.java
    ├── CartResponse.java
    ├── CartItemResponse.java
    ├── OrderResponse.java
    ├── OrderItemResponse.java
    └── UserResponse.java
```

---

### 2.2 Request DTOs

#### AddToCartRequest
```java
@Data
public class AddToCartRequest {
    @NotNull(message = "bookId is required")
    private Long bookId;
    
    @NotNull(message = "quantity is required")
    @Min(value = 1, message = "Quantity must be at least 1")
    @Max(value = 100, message = "Maximum quantity is 100")
    private Integer quantity;
}
```

#### UpdateCartRequest
```java
@Data
public class UpdateCartRequest {
    @NotNull(message = "bookId is required")
    private Long bookId;
    
    @NotNull(message = "quantity is required")
    @Min(value = 1, message = "Quantity must be at least 1")
    @Max(value = 100, message = "Maximum quantity is 100")
    private Integer quantity;
}
```

#### AddBookRequest (Admin)
```java
@Data
public class AddBookRequest {
    @NotBlank(message = "Title is required")
    @Size(max = 255, message = "Title too long")
    private String title;
    
    @NotBlank(message = "Author is required")
    @Size(max = 255, message = "Author name too long")
    private String author;
    
    @NotNull(message = "Price is required")
    @DecimalMin(value = "0.01", message = "Price must be positive")
    @Digits(integer = 15, fraction = 4, message = "Price format invalid")
    private BigDecimal price;
    
    @Size(max = 100, message = "Category too long")
    private String category;
    
    @NotNull(message = "Stock is required")
    @Min(value = 0, message = "Stock cannot be negative")
    private Integer stock;
}
```

#### UpdateStockRequest (Admin)
```java
@Data
public class UpdateStockRequest {
    @NotNull(message = "Stock is required")
    @Min(value = 0, message = "Stock cannot be negative")
    private Integer stock;
}
```

---

### 2.3 Response DTOs

#### BookResponse
```java
@Data
@Builder
public class BookResponse {
    private Long id;
    private String title;
    private String author;
    private String price;           // Formatted as string: "10.99"
    private String currency;
    private String category;
    private Integer stock;
    private boolean available;      // Derived: stock > 0
}
```

#### CartItemResponse
```java
@Data
@Builder
public class CartItemResponse {
    private Long id;
    private BookSummaryResponse book;
    private Integer quantity;
    private String unitPrice;       // Price when added to cart
    private String lineTotal;       // quantity × unitPrice
    private String addedAt;         // ISO-8601 timestamp
}
```

#### CartResponse
```java
@Data
@Builder
public class CartResponse {
    private Long id;
    private List<CartItemResponse> items;
    private String total;           // Sum of all line totals
    private Integer itemCount;      // Total items (not unique books)
    private String updatedAt;
}
```

#### OrderItemResponse
```java
@Data
@Builder
public class OrderItemResponse {
    private Long id;
    private BookSummaryResponse book;
    private Integer quantity;
    private String unitPrice;       // Historical price
    private String subtotal;
}
```

#### OrderResponse
```java
@Data
@Builder
public class OrderResponse {
    private Long id;
    private String orderNumber;     // Formatted: ORD-2024-0001
    private String status;
    private List<OrderItemResponse> items;
    private String totalPrice;
    private String currency;
    private String createdAt;
}
```

---

## 3. Refactored Security Architecture

### 3.1 JWT-Based Stateless Authentication (NEW)

**Rationale:** Session-based auth with disabled CSRF is a vulnerability. JWT provides:
- Stateless scalability (no server-side session storage)
- Natural CSRF protection (custom headers required)
- Mobile/API-friendly

#### JwtConfig
```java
@Configuration
@ConfigurationProperties(prefix = "jwt")
@Data
public class JwtConfig {
    private String secret;
    private long accessTokenExpiration = 86400000;      // 24 hours
    private long refreshTokenExpiration = 604800000;    // 7 days
}
```

#### JwtTokenProvider
```java
@Component
public class JwtTokenProvider {
    
    private final JwtConfig jwtConfig;
    private final UserDetailsService userDetailsService;
    
    public String generateToken(String username, UserRole role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + jwtConfig.getAccessTokenExpiration());
        
        return Jwts.builder()
            .setSubject(username)
            .claim("role", role.name())
            .setIssuedAt(now)
            .setExpiration(expiry)
            .signWith(getSigningKey(), SignatureAlgorithm.HS512)
            .compact();
    }
    
    public boolean validateToken(String token) {
        try {
            Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
    
    public Authentication getAuthentication(String token) {
        String username = extractUsername(token);
        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
        return new UsernamePasswordAuthenticationToken(
            userDetails, null, userDetails.getAuthorities());
    }
}
```

#### JwtAuthenticationFilter
```java
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    
    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";
    
    private final JwtTokenProvider tokenProvider;
    
    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                    HttpServletResponse response, 
                                    FilterChain chain) throws ServletException, IOException {
        String token = resolveToken(request);
        
        if (token != null && tokenProvider.validateToken(token)) {
            Authentication auth = tokenProvider.getAuthentication(token);
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        
        chain.doFilter(request, response);
    }
    
    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader(HEADER);
        if (bearer != null && bearer.startsWith(PREFIX)) {
            return bearer.substring(PREFIX.length());
        }
        return null;
    }
}
```

---

### 3.2 SecurityConfig (REFACTORED)
```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
    
    private final JwtAuthenticationFilter jwtFilter;
    
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())  // Disabled for JWT (stateless)
            .sessionManagement(session -> 
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/h2-console/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/books/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/books/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/books/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/books/**").hasRole("ADMIN")
                .requestMatchers("/api/cart/**", "/api/orders/**").authenticated()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .headers(headers -> headers.frameOptions(frame -> frame.disable()));
            
        return http.build();
    }
    
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);  // Strength 12 (default is 10)
    }
    
    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
```

**Key Changes:**
- `SessionCreationPolicy.STATELESS`: No server-side sessions
- JWT filter before standard auth filter
- BCrypt strength increased to 12

---

## 4. Concurrency Control & Stock Management

### 4.1 Optimistic Locking Strategy

**Choice:** Optimistic Locking via `@Version`  
**Rationale:** 
- Read-heavy workload (browsing books)
- Low conflict probability (different users buy different books)
- No database locks held during transactions
- Automatic retry on conflict

#### Book Entity with Version
```java
@Entity
public class Book {
    // ... fields ...
    
    @Version
    private Long version;
}
```

### 4.2 OrderService with Concurrency Control (REFACTORED)
```java
@Service
@Slf4j
public class OrderService {
    
    private final OrderRepository orderRepository;
    private final CartService cartService;
    private final BookRepository bookRepository;
    private final SecurityUtils securityUtils;
    
    /**
     * Creates an order from the current user's cart.
     * Uses optimistic locking for stock management.
     * On concurrent modification, throws OptimisticLockException
     * which triggers a retry at controller level.
     */
    @Transactional
    public Order createOrder() throws InsufficientStockException {
        AppUser user = securityUtils.getCurrentUser();
        Cart cart = cartService.getCart();
        
        if (cart.getItems().isEmpty()) {
            throw new IllegalStateException("Cart is empty");
        }
        
        // Validate stock availability before attempting modifications
        for (CartItem item : cart.getItems()) {
            Book book = item.getBook();
            if (book.getStock() < item.getQuantity()) {
                throw new InsufficientStockException(
                    book.getTitle(), book.getStock(), item.getQuantity());
            }
        }
        
        Order order = new Order();
        order.setUser(user);
        order.setStatus(OrderStatus.PENDING);
        
        // Create order items with frozen prices from cart
        for (CartItem cartItem : cart.getItems()) {
            Book book = cartItem.getBook();
            
            // Deduct stock (optimistic locking enforced by @Version)
            book.setStock(book.getStock() - cartItem.getQuantity());
            bookRepository.save(book);  // Will throw OptimisticLockException if stale
            
            OrderItem orderItem = new OrderItem();
            orderItem.setBook(book);
            orderItem.setQuantity(cartItem.getQuantity());
            orderItem.setUnitPrice(cartItem.getPriceAtAdd());  // Frozen price
            order.addItem(orderItem);
        }
        
        order.calculateTotal();
        orderRepository.save(order);
        
        cartService.clearCart();
        
        log.info("Order created: {} for user: {}", order.getId(), user.getUsername());
        return order;
    }
}
```

### 4.3 Retry Mechanism (NEW)
```java
@RestControllerAdvice
public class ConcurrencyExceptionHandler {
    
    private static final int MAX_RETRIES = 3;
    
    @ExceptionHandler(OptimisticLockException.class)
    public ResponseEntity<Map<String, String>> handleOptimisticLock(
            OptimisticLockException ex, WebRequest request) {
        
        Map<String, String> error = new HashMap<>();
        error.put("error", "Concurrent modification detected. Please retry.");
        error.put("code", "CONCURRENT_MODIFICATION");
        error.put("retryable", "true");
        
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }
}
```

---

## 5. Refactored REST API

### 5.1 API Endpoint Mapping (REFACTORED)

| Resource | Method | Old Endpoint | **New Endpoint** | Request Body |
|----------|--------|--------------|------------------|--------------|
| **Auth** | POST | `/api/auth/register` | `/api/auth/register` | `RegisterRequest` |
| | POST | `/api/auth/login` | `/api/auth/login` | `LoginRequest` |
| | POST | - | `/api/auth/refresh` | `RefreshTokenRequest` |
| **Books** | GET | `/api/books` | `/api/books` | - |
| | GET | `/api/books/search?search=...` | `/api/books/search?query=...` | - |
| | GET | `/api/books/category?category=...` | `/api/books?category=...` | - |
| | POST | `/api/books` | `/api/books` | `AddBookRequest` |
| | DELETE | `/api/books/{id}` | `/api/books/{id}` | - |
| | PUT | `/api/books/{id}/stock?stock=...` | `/api/books/{id}/stock` | `UpdateStockRequest` |
| **Cart** | GET | `/api/cart` | `/api/cart` | - |
| | POST | `/api/cart/add?bookId=...&quantity=...` | `/api/cart/items` | `AddToCartRequest` |
| | PUT | `/api/cart/update?bookId=...&quantity=...` | `/api/cart/items/{bookId}` | `UpdateCartRequest` |
| | DELETE | `/api/cart/remove?bookId=...` | `/api/cart/items/{bookId}` | - |
| | DELETE | `/api/cart/clear` | `/api/cart` | - |
| **Orders** | POST | `/api/orders` | `/api/orders` | - |
| | GET | `/api/orders` | `/api/orders` | - |
| | GET | - | `/api/orders/{id}` | - |

### 5.2 CartController (REFACTORED)
```java
@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {
    
    private final CartService cartService;
    private final CartMapper cartMapper;
    
    @GetMapping
    public ResponseEntity<CartResponse> getCart() {
        Cart cart = cartService.getCart();
        return ResponseEntity.ok(cartMapper.toResponse(cart));
    }
    
    // REFACTORED: POST with request body instead of query params
    @PostMapping("/items")
    public ResponseEntity<CartResponse> addItem(
            @Valid @RequestBody AddToCartRequest request) {
        Cart cart = cartService.addToCart(request.getBookId(), request.getQuantity());
        return ResponseEntity.status(HttpStatus.CREATED).body(cartMapper.toResponse(cart));
    }
    
    // REFACTORED: PUT with path variable and request body
    @PutMapping("/items/{bookId}")
    public ResponseEntity<CartResponse> updateItem(
            @PathVariable Long bookId,
            @Valid @RequestBody UpdateCartRequest request) {
        if (!bookId.equals(request.getBookId())) {
            throw new IllegalArgumentException("Path bookId must match body bookId");
        }
        Cart cart = cartService.updateCartItem(bookId, request.getQuantity());
        return ResponseEntity.ok(cartMapper.toResponse(cart));
    }
    
    // REFACTORED: DELETE with path variable
    @DeleteMapping("/items/{bookId}")
    public ResponseEntity<CartResponse> removeItem(@PathVariable Long bookId) {
        Cart cart = cartService.removeFromCart(bookId);
        return ResponseEntity.ok(cartMapper.toResponse(cart));
    }
    
    // REFACTORED: DELETE on collection to clear
    @DeleteMapping
    public ResponseEntity<Void> clearCart() {
        cartService.clearCart();
        return ResponseEntity.noContent().build();
    }
}
```

### 5.3 BookController (REFACTORED)
```java
@RestController
@RequestMapping("/api/books")
@RequiredArgsConstructor
public class BookController {
    
    private final BookService bookService;
    private final BookMapper bookMapper;
    
    @GetMapping
    public ResponseEntity<List<BookResponse>> getAllBooks(
            @RequestParam(required = false) String category) {
        
        List<Book> books = category != null 
            ? bookService.getBooksByCategory(category)
            : bookService.getAllBooks();
            
        return ResponseEntity.ok(books.stream()
            .map(bookMapper::toResponse)
            .collect(Collectors.toList()));
    }
    
    @GetMapping("/search")
    public ResponseEntity<List<BookResponse>> searchBooks(
            @RequestParam String query) {  // Renamed from 'search' to 'query'
        return ResponseEntity.ok(
            bookService.searchBooks(query).stream()
                .map(bookMapper::toResponse)
                .collect(Collectors.toList()));
    }
    
    // REFACTORED: Request body instead of query param for state mutation
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BookResponse> addBook(
            @Valid @RequestBody AddBookRequest request) {
        Book book = bookMapper.toEntity(request);
        Book saved = bookService.addBook(book);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(bookMapper.toResponse(saved));
    }
    
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteBook(@PathVariable Long id) {
        bookService.deleteBook(id);
        return ResponseEntity.noContent().build();
    }
    
    // REFACTORED: Request body for stock update
    @PutMapping("/{id}/stock")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BookResponse> updateStock(
            @PathVariable Long id,
            @Valid @RequestBody UpdateStockRequest request) {
        Book updated = bookService.updateStock(id, request.getStock());
        return ResponseEntity.ok(bookMapper.toResponse(updated));
    }
}
```

---

## 6. Mapping Layer (MapStruct)

### 6.1 BookMapper
```java
@Mapper(componentModel = "spring")
public interface BookMapper {
    
    @Mapping(target = "price", expression = "java(formatCurrency(book.getPrice()))")
    @Mapping(target = "currency", constant = "USD")
    @Mapping(target = "available", expression = "java(book.getStock() > 0)")
    BookResponse toResponse(Book book);
    
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "version", ignore = true)
    Book toEntity(AddBookRequest request);
    
    default String formatCurrency(BigDecimal price) {
        return price != null ? price.setScale(2, RoundingMode.HALF_UP).toString() : null;
    }
}
```

### 6.2 CartMapper
```java
@Mapper(componentModel = "spring", uses = {BookMapper.class})
public interface CartMapper {
    
    @Mapping(target = "total", expression = "java(calculateTotal(cart))")
    @Mapping(target = "itemCount", expression = "java(calculateItemCount(cart))")
    @Mapping(target = "updatedAt", expression = "java(cart.getUpdatedAt().toString())")
    CartResponse toResponse(Cart cart);
    
    @Mapping(target = "lineTotal", expression = "java(calculateLineTotal(item))")
    @Mapping(target = "addedAt", expression = "java(item.getAddedAt().toString())")
    CartItemResponse toItemResponse(CartItem item);
    
    default String calculateLineTotal(CartItem item) {
        if (item.getPriceAtAdd() == null) return "0.00";
        return item.getPriceAtAdd()
            .multiply(BigDecimal.valueOf(item.getQuantity()))
            .setScale(2, RoundingMode.HALF_UP)
            .toString();
    }
    
    default String calculateTotal(Cart cart) {
        return cart.getItems().stream()
            .map(item -> {
                if (item.getPriceAtAdd() == null) return BigDecimal.ZERO;
                return item.getPriceAtAdd().multiply(BigDecimal.valueOf(item.getQuantity()));
            })
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(2, RoundingMode.HALF_UP)
            .toString();
    }
    
    default Integer calculateItemCount(Cart cart) {
        return cart.getItems().stream()
            .mapToInt(CartItem::getQuantity)
            .sum();
    }
}
```

---

## 7. Business Logic Clarifications

### 7.1 Cart Lifecycle & orphanRemoval

**Implementation Details:**

```java
@Entity
public class Cart {
    @OneToMany(mappedBy = "cart", 
               cascade = CascadeType.ALL, 
               orphanRemoval = true)
    private List<CartItem> items = new ArrayList<>();
}

@Entity
public class CartItem {
    @ManyToOne
    @JoinColumn(name = "cart_id")
    private Cart cart;
}
```

**Behavior:**
1. **Adding item:** `cart.addItem(item)` → `item.setCart(this)` → Cascade.PERSIST saves item
2. **Removing item:** `cart.removeItem(item)` → `item.setCart(null)` → orphanRemoval deletes from DB
3. **Clear cart:** `cart.getItems().clear()` → All items detached → orphanRemoval deletes all
4. **Deleting cart:** Cascade.REMOVE deletes all items

**CartService.clearCart() Implementation:**
```java
@Transactional
public void clearCart() {
    Cart cart = getCart();
    // This triggers orphanRemoval on all items
    cart.getItems().clear();
    cartRepository.save(cart);
}
```

### 7.2 Price Freezing Strategy

**Business Rule:** Prices are frozen at the moment of adding to cart.

**Flow:**
1. Book price: $10.00
2. User adds to cart → `CartItem.priceAtAdd = $10.00`
3. Admin changes book price to $12.00
4. Cart still shows `$10.00 × quantity`
5. At checkout → `OrderItem.unitPrice = CartItem.priceAtAdd = $10.00`

**Rationale:**
- Prevents price shock at checkout
- Legal requirement in some jurisdictions
- User expectation: price at add time is the "agreed" price

---

## 8. Implementation Roadmap

### Phase 1: Foundation (Week 1)

**Goals:** Database schema and entity refactoring

| Task | Effort | Dependencies |
|------|--------|--------------|
| Add `BigDecimal` fields to Book, OrderItem, Order | 2h | - |
| Add `@Version` to Book and Order | 1h | - |
| Add `priceAtAdd` to CartItem | 2h | - |
| Create OrderStatus enum | 1h | - |
| Create Flyway migration scripts | 4h | Entity changes |
| Update tests for BigDecimal | 4h | Entity changes |

**Deliverable:** Refactored entities with passing unit tests

---

### Phase 2: DTO Layer (Week 1-2)

**Goals:** Complete API contract isolation

| Task | Effort | Dependencies |
|------|--------|--------------|
| Create request/response DTOs | 6h | - |
| Add MapStruct dependency and mappers | 4h | DTOs |
| Refactor Controllers to use DTOs | 8h | Mappers |
| Add bean validation to DTOs | 2h | DTOs |
| Update integration tests | 8h | Controllers |

**Deliverable:** Controllers return DTOs only, no entity exposure

---

### Phase 3: Security Overhaul (Week 2)

**Goals:** JWT-based stateless authentication

| Task | Effort | Dependencies |
|------|--------|--------------|
| Add jjwt dependency | 1h | - |
| Create JwtConfig | 2h | - |
| Implement JwtTokenProvider | 4h | Config |
| Implement JwtAuthenticationFilter | 4h | Provider |
| Refactor SecurityConfig for JWT | 4h | Filter |
| Update AuthController for JWT | 4h | TokenProvider |
| Implement refresh token endpoint | 4h | AuthController |
| Update all tests for JWT | 8h | Security changes |

**Deliverable:** Stateless JWT authentication, no server-side sessions

---

### Phase 4: REST API Refactoring (Week 3)

**Goals:** Proper REST conventions

| Task | Effort | Dependencies |
|------|--------|--------------|
| Refactor CartController to use request body | 4h | DTOs |
| Refactor BookController stock update | 2h | DTOs |
| Standardize URL patterns (RESTful nesting) | 4h | - |
| Update API documentation (OpenAPI/Swagger) | 4h | All endpoints |
| Postman collection update | 2h | Documentation |

**Deliverable:** RESTful API with consistent conventions

---

### Phase 5: Concurrency & Stock (Week 3)

**Goals:** Race condition protection

| Task | Effort | Dependencies |
|------|--------|--------------|
| Verify @Version in Book entity | 1h | Phase 1 |
| Add OptimisticLockException handler | 2h | - |
| Implement retry logic in OrderService | 4h | Exception handler |
| Add pessimistic locking option for high-contention scenarios | 4h | - |
| Stress tests for concurrent checkout | 8h | Retry logic |

**Deliverable:** Concurrent-safe stock management

---

### Phase 6: Testing & Hardening (Week 4)

**Goals:** Production readiness

| Task | Effort | Dependencies |
|------|--------|--------------|
| Integration tests for all new endpoints | 12h | All phases |
| Security penetration testing | 8h | JWT implementation |
| Performance benchmarks | 8h | All changes |
| Documentation update | 4h | - |
| Code review and cleanup | 8h | All changes |

**Deliverable:** Production-ready codebase

---

## 9. Dependencies (Maven)

```xml
<dependencies>
    <!-- Existing -->
    
    <!-- MapStruct for DTO mapping -->
    <dependency>
        <groupId>org.mapstruct</groupId>
        <artifactId>mapstruct</artifactId>
        <version>1.5.5.Final</version>
    </dependency>
    
    <!-- JWT -->
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-api</artifactId>
        <version>0.12.3</version>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-impl</artifactId>
        <version>0.12.3</version>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-jackson</artifactId>
        <version>0.12.3</version>
        <scope>runtime</scope>
    </dependency>
    
    <!-- Database migration -->
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-core</artifactId>
    </dependency>
</dependencies>

<build>
    <plugins>
        <!-- MapStruct annotation processor -->
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <version>3.11.0</version>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>org.mapstruct</groupId>
                        <artifactId>mapstruct-processor</artifactId>
                        <version>1.5.5.Final</version>
                    </path>
                    <path>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok</artifactId>
                        <version>${lombok.version}</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

---

## 10. Configuration (application.yml)

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:testdb
    username: sa
    password:
  jpa:
    hibernate:
      ddl-auto: validate  # Use Flyway for schema management
    properties:
      hibernate:
        jdbc:
          batch_size: 20
        order_inserts: true
        order_updates: true
  flyway:
    enabled: true
    locations: classpath:db/migration

jwt:
  secret: ${JWT_SECRET:my-256-bit-secret-key-for-jwt-signing-at-least-32-characters-long}
  access-token-expiration: 86400000  # 24 hours
  refresh-token-expiration: 604800000  # 7 days

logging:
  level:
    com.example.bookstore: INFO
    org.hibernate.SQL: DEBUG
```

---

## Summary

This refactored architecture addresses all critical concerns:

1. **Financial Integrity:** All monetary values use `BigDecimal` with explicit scale/precision
2. **Security:** Stateless JWT authentication replaces vulnerable session-based auth
3. **Concurrency:** Optimistic locking prevents race conditions in stock management
4. **REST Standards:** Request bodies replace query parameters for state mutations
5. **API Contracts:** Complete DTO layer isolates domain model from external API
6. **Business Logic:** Explicit price freezing and cart lifecycle management

**Estimated Total Effort:** 4 weeks (1 developer)  
**Risk Level:** Medium (requires thorough regression testing)  
**Rollback Strategy:** Feature flags for gradual rollout
