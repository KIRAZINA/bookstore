# Bookstore API

REST API for an online bookstore built with Spring Boot 4, JWT authentication, PostgreSQL, Swagger/OpenAPI, and a standalone HTML API tester.

## Current Status
- Spring Boot 4 / Java 21 project
- PostgreSQL database (Docker Compose)
- JWT auth is working
- Swagger UI is integrated
- OpenAPI docs are exposed
- Main API flows were verified locally on `http://localhost:8080`
- Maven test suite is passing (116 tests)
- Database schema and seed data managed by Flyway versioned migrations
- Order lifecycle with finite state machine (PENDING → PAID → PROCESSING → SHIPPED → DELIVERED / CANCELLED)
- Persistent order status history with full traceability
- Admin order management endpoints
- Customer order tracking and cancellation

## Tech Stack
- Java 21
- Spring Boot 4.0.0
- Spring Security
- Spring Data JPA
- PostgreSQL 16 (Docker)
- Redis 7 (Docker) — distributed cache
- Spring Cache Abstraction
- springdoc-openapi / Swagger UI
- Docker Compose
- Flyway (database migrations)
- Maven Wrapper

## Features
- User registration and login with JWT
- Public book catalog endpoints (with Redis caching)
- Admin-only book management (with automatic cache invalidation)
- User cart management
- Order creation from the active cart
- Optimistic locking support for stock updates (PostgreSQL-compatible)
- Distributed Redis caching with JSON serialization
- Graceful cache degradation (app works without Redis)
- Interactive Swagger UI and OpenAPI docs
- Standalone `api-tester.html` for browser-based API testing

## Repository Layout
```text
src/main/java/com/example/bookstore/
|-- config/        Security, JWT, cache, exception handling, OpenAPI config
|-- controller/    REST controllers and DTOs
|-- model/         JPA entities and enums
|-- repository/    Spring Data repositories
`-- service/       Business logic (with caching annotations)
```

## Prerequisites

- Java 21+
- Docker & Docker Compose

## Quick Start

### 1. Start PostgreSQL and Redis

```bash
docker compose up -d
```

This starts:

PostgreSQL 16 on `localhost:5432`:
- Database: `bookstore`
- User: `bookstore`
- Password: `bookstore`
- Persistent data volume: `postgres_data`

Redis 7 on `localhost:6379`:
- Persistent data volume: `redis_data`
- Health check via `redis-cli ping`

### 2. Start the Application

Windows:
```bash
mvnw.cmd spring-boot:run
```

macOS/Linux:
```bash
./mvnw spring-boot:run
```

The application starts at `http://localhost:8080`.

### 3. Run Tests

```bash
mvnw.cmd test
```

Tests use an embedded H2 database (via the `test` Spring profile), so no PostgreSQL connection is required.

## Spring Profiles

| Profile | Database | DDL | Migrations | Purpose |
|---------|----------|-----|------------|---------|
| `default` (dev) | PostgreSQL (Docker) | `none` | Flyway | Local development |
| `prod` | PostgreSQL (env vars) | `none` | Flyway | Production |
| `test` | H2 in-memory | `none` | Flyway | Automated tests |

Override the active profile:
```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=prod
```

## Database Migrations (Flyway)

Schema and seed data are managed by [Flyway](https://flywaydb.org/) versioned migrations.

Migration scripts are in `src/main/resources/db/migration/`:

- `V1__Initial_schema.sql` — all 6 tables with constraints, defaults, and foreign keys
- `V2__Seed_demo_data.sql` — idempotent seed data (admin user, test user, books)
- `V3__Order_status_history.sql` — order status history table
- `V4__Create_payment_tables.sql` — payment and payment history tables

On startup, Flyway runs any pending migrations automatically. Once applied, a migration never runs again (the `flyway_schema_history` table tracks applied versions).

Because Spring Boot 4.0.0 removed Flyway auto-configuration from its core module, this project uses a manual `FlywayConfig` class (`config/FlywayConfig.java`) that creates and runs a `Flyway` instance when `spring.flyway.enabled=true`. Hibernate DDL is set to `none` — Flyway is the sole source of truth for the database schema.

### Adding a new migration

1. Create a file named `V<next_version>__<description>.sql` in `src/main/resources/db/migration/`
2. Write migration SQL compatible with both PostgreSQL and H2
3. Restart the application — Flyway applies it automatically

### Resetting the database

```bash
# Remove Docker volume (PostgreSQL)
docker compose down -v

# Recreate volume and start
docker compose up -d

# Application will apply all migrations on next startup
```

## Environment Variables (Production)

| Variable | Default | Description |
|----------|---------|-------------|
| `DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/bookstore` | PostgreSQL JDBC URL |
| `DATASOURCE_USERNAME` | `bookstore` | Database user |
| `DATASOURCE_PASSWORD` | `bookstore` | Database password |
| `JWT_SECRET` | (required in prod) | HMAC-SHA256 key for JWT signing |
| `REDIS_HOST` | `localhost` | Redis server hostname |
| `REDIS_PORT` | `6379` | Redis server port |
| `REDIS_CONNECT_TIMEOUT` | `2000` | Redis connection timeout (ms) |
| `REDIS_TIMEOUT` | `2000` | Redis read/write timeout (ms) |

## API Documentation
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- OpenAPI YAML: `http://localhost:8080/v3/api-docs.yaml`
- Standalone local tester: [api-tester.html](api-tester.html)

## Order Lifecycle

Orders follow a finite state machine with validated transitions.

### State Machine

```
PENDING ──→ PAID ──→ PROCESSING ──→ SHIPPED ──→ DELIVERED
    │          │
    └──→ CANCELLED ←──┘
```

| From | To | Allowed By |
|------|----|------------|
| PENDING | PAID | Admin |
| PENDING | CANCELLED | Customer or Admin |
| PAID | PROCESSING | Admin |
| PAID | CANCELLED | Admin only |
| PROCESSING | SHIPPED | Admin |
| SHIPPED | DELIVERED | Admin |
| any other transition | — | ❌ Rejected |

Terminal states: `DELIVERED`, `CANCELLED` — no further transitions allowed.

### State History

Every status change is recorded in `order_status_history` with:
- Previous and new status
- Timestamp of the change
- Username of who made the change
- Optional comment/reason

The full lifecycle of any order is traceable via the history endpoint.

### Admin Order Endpoints

All under `/api/admin/orders/`, require `ROLE_ADMIN`:

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/admin/orders` | List all orders (optional `?status=` filter) |
| GET | `/api/admin/orders/{id}` | Get order details |
| PUT | `/api/admin/orders/{id}/status` | Update order status |
| POST | `/api/admin/orders/{id}/cancel` | Cancel an order (with optional comment) |
| GET | `/api/admin/orders/{id}/history` | View full status history |

### Customer Order Endpoints

All under `/api/orders/`, require `ROLE_USER`:

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/orders` | Create order from cart (checkout) |
| GET | `/api/orders` | List own orders (paginated) |
| GET | `/api/orders/{id}` | Get own order details |
| POST | `/api/orders/{id}/cancel` | Cancel own order (only if PENDING) |
| GET | `/api/orders/{id}/history` | View own order status history |

### Authorization

| Role | Can |
|------|-----|
| `ROLE_USER` | Create orders, view own orders, cancel own PENDING orders, view own order history |
| `ROLE_ADMIN` | View all orders, update any order status, cancel cancellable orders, view any order history |

### Future Extensibility

Adding a new status requires:
1. Add the value to `OrderStatus` enum
2. Update the `TRANSITIONS` map in `OrderStatus` with allowed transitions
3. Create a new Flyway migration (only if the new status changes the schema — unlikely)
4. No other code changes needed

This design supports future Payment, Shipment, Inventory, Notification, and Analytics modules without modifying the core state machine logic.

## Payment Module

A Payment is created automatically alongside every order at checkout. The payment lifecycle is managed independently of the order lifecycle but stays synchronised with it through business logic in `PaymentService`.

### Architecture

Payments are an independent domain with their own entity, state machine, service, and provider abstraction.

```
Payment (entity) ──@OneToOne──→ Order
    │
    ├── PaymentHistory (entity) — audit trail
    │
    └── PaymentProvider (interface) — Strategy Pattern
            └── DummyPaymentProvider — simulated implementation
```

### Design Decisions

1. **One payment per order** — `@OneToOne` relationship from Payment to Order. Every order has exactly one payment.
2. **Provider abstraction** — `PaymentProvider` interface allows swapping providers (Dummy, Stripe, PayPal, LiqPay) without changing core logic.
3. **Order-Payment coordination** — When payment completes, `PaymentService` transitions the associated Order from `PENDING` → `PAID` automatically.
4. **No circular dependency** — `OrderService` creates the Payment via `PaymentRepository` directly (data setup), while `PaymentService` updates the Order status via `OrderRepository` directly. No `@Lazy` required.
5. **DummyProvider simulates success** — No external API calls; generates deterministic transaction IDs for testing.

### Payment State Machine

```
PENDING ──→ PROCESSING ──→ COMPLETED ──→ REFUNDED
    │            │
    └──→ CANCELLED      └──→ FAILED ──→ PENDING (retry allowed)
```

| From | To | Description |
|------|----|-------------|
| PENDING | PROCESSING | Payment processing initiated |
| PENDING | CANCELLED | Customer cancelled payment |
| PROCESSING | COMPLETED | Provider confirmed success |
| PROCESSING | FAILED | Provider reported failure |
| COMPLETED | REFUNDED | Admin refunded the payment |
| FAILED | PENDING | Customer can retry payment |

Terminal states: `COMPLETED`, `CANCELLED`, `REFUNDED`.

### Payment History

Every payment status transition is recorded in `payment_history` with:
- Previous and new status
- Timestamp
- Actor (who triggered the change)
- Reason/comment

### Payment Flow

1. **Order Created** → Payment created in `PENDING` state (automatic, same transaction)
2. **Customer processes payment** → `POST /api/payments/{orderId}/process`
3. **Provider processes** → DummyProvider immediately completes
4. **Payment completes** → Payment transitions to `COMPLETED`, Order transitions to `PAID`
5. **Admin can refund** → `POST /api/admin/payments/{id}/refund`
6. **Customer can cancel** → `POST /api/payments/{orderId}/cancel` (only if PENDING)

### Adding a New Payment Provider

1. Implement `PaymentProvider` interface
2. Annotate with `@Component`
3. Update the provider injection in `PaymentService` (or use a registry/map pattern for multiple providers)

### Payment Endpoints (Customer)

All under `/api/payments/`, require `ROLE_USER`:

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/payments/{orderId}` | Get payment for an order |
| POST | `/api/payments/{orderId}/process` | Process payment (`{ "paymentMethod": "card" }`) |
| POST | `/api/payments/{orderId}/cancel` | Cancel a PENDING payment |
| GET | `/api/payments/{orderId}/history` | View payment status history |

### Payment Endpoints (Admin)

All under `/api/admin/payments/`, require `ROLE_ADMIN`:

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/admin/payments` | List all payments (optional `?status=` filter) |
| GET | `/api/admin/payments/{id}` | Get payment details |
| POST | `/api/admin/payments/{id}/refund` | Refund a COMPLETED payment |
| GET | `/api/admin/payments/{id}/history` | View any payment's status history |

### Database Migrations

`V4__Create_payment_tables.sql` creates:
- `payment` table — payment entity with order FK, provider, status, amount, transaction ID, timestamps, optimistic lock
- `payment_history` table — auditable status transitions
- Indices on `order_id`, `status`, `transaction_id`

## Distributed Caching with Redis

A production-grade distributed caching layer using Redis 7. The cache improves application performance while maintaining database consistency.

### Architecture

```
┌──────────────┐     @Cacheable      ┌───────────┐     Redis Protocol      ┌──────────┐
│  Controller   │ ──────────────────→ │  Service   │ ────────────────────→  │   Redis   │
│  (REST API)   │                    │  Layer     │                        │  (Cache)  │
└──────────────┘                    └───────┬───┘                        └──────────┘
                                            │
                                            │ Cache miss / fallback
                                            ▼
                                     ┌───────────┐
                                     │    JPA     │
                                     │ Repository │
                                     └─────┬─────┘
                                           │
                                           ▼
                                     ┌───────────┐
                                     │ PostgreSQL │
                                     │ (Source of │
                                     │  Truth)    │
                                     └───────────┘
```

- **PostgreSQL** remains the authoritative data source
- **Redis** is only an optimization layer
- **Spring Cache** abstraction prevents Redis-specific code from leaking into business logic
- If Redis becomes unavailable, the application continues functioning by querying the database directly

### Cache Regions

| Region | TTL | Contents | Caching Rationale |
|--------|-----|----------|-------------------|
| `books` | 10 min | `getAllBooks()`, `getBooksByCategory()`, `searchBooks()` | Read-mostly catalog data, low write frequency (admin only) |
| `bookById` | 30 min | Individual book lookups | Very stable data, longer TTL reduces DB load |

### TTL Strategy

- **books (10 min)**: Book catalog changes infrequently (admin creates/updates/deletes). A 10-minute TTL balances freshness against database load. Under millions of requests, this eliminates ~99.9% of catalog queries.
- **bookById (30 min)**: Individual book records are even more stable. Once created, a book's metadata rarely changes. Stock updates are the main mutation, and they trigger cache eviction anyway.

### Cache Invalidation

Invalidation is automatic — triggered by write operations:

| Operation | Eviction Target |
|-----------|----------------|
| `addBook()` | `books` (all entries) |
| `deleteBook()` | `books` + `bookById` (all entries) |
| `updateStock()` | `books` + `bookById` (all entries) |

When books are created, updated, or deleted, the `books` region is cleared entirely (since list queries could contain stale results). The `bookById` region is also cleared on mutations to avoid serving stale individual records.

### What NOT to Cache

| Data | Reason |
|------|--------|
| Authentication / JWT | Security-sensitive |
| Payments | Business-critical state machine |
| Orders | User-specific mutable business state |
| Cart | User-specific mutable state |
| User profiles | Authentication infrastructure |

### Failure Handling

The custom `CacheErrorHandler` in `CacheConfig` catches all Redis errors and logs them as warnings without propagating exceptions. This ensures:

1. The application never fails because Redis is unavailable
2. All operations fall through to the database transparently
3. Cache failures are invisible to clients (they see normal responses)

### Serialization

Values are serialized using `GenericJackson2JsonRedisSerializer`:
- JSON serialization (not Java native) — enables cross-language compatibility
- `StringRedisSerializer` for cache keys
- Java Time module registered for proper date/time handling
- Polymorphic type handling via `@class` property

### Configuration

Dev profile (`application.yml`):
```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      connect-timeout: 2000
      timeout: 2000
  cache:
    type: redis

app:
  cache:
    enabled: true
```

Production profile (`application-prod.yml`):
```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      connect-timeout: ${REDIS_CONNECT_TIMEOUT:2000}
      timeout: ${REDIS_TIMEOUT:2000}
  cache:
    type: redis

app:
  cache:
    enabled: true
```

Test profile (`application-test.yml`): Caching is disabled (`spring.cache.type=none`, `app.cache.enabled=false`) to avoid requiring Redis during unit tests.

### Cache Configuration Class

`CacheConfig` at `config/CacheConfig.java`:
- Annotated with `@EnableCaching`
- Implements `CachingConfigurer` to provide a custom `CacheErrorHandler`
- Configures `RedisCacheManager` with per-region TTL and JSON serialization
- The `RedisCacheManager` bean is conditional on `spring.cache.type=redis` (falls back to Spring Boot's auto-configured cache manager for other types)
- The entire config is conditional on `app.cache.enabled=true`

### Performance Benefits

- Eliminates redundant database queries for the most frequently accessed data
- Cache stampede is naturally mitigated by per-region TTL and key-based caching (different search terms don't compete for the same cache entry)
- Distributed cache supports horizontal scaling — all application instances share the same Redis cache
- No duplicated cache entries — each query has a unique key within its region

### Future Scalability

The current architecture supports:
- **Redis Cluster**: No code changes needed — just update Redis connection configuration
- **CDN Integration**: Pre-populate CDN from Redis cache
- **Read Replica Databases**: Cache can reduce load on both primary and replica databases
- **API Gateway Caching**: Shared Redis allows gateway-level caching
- **Prometheus Integration**: Spring Cache metrics (hit rate, miss rate, puts, evictions) are available via Micrometer

To add a new cache region:
1. Define the region name and TTL in `CacheConfig`
2. Add `@Cacheable` / `@CacheEvict` annotations to the relevant service methods
3. Add eviction logic for write operations

### Starting Redis

Redis starts automatically with Docker Compose:

```bash
docker compose up -d
```

This starts:
- PostgreSQL 16 on port 5432
- Redis 7 on port 6379 (with persistent volume `redis_data`)

### Environment Variables (Production)

| Variable | Default | Description |
|----------|---------|-------------|
| `REDIS_HOST` | `localhost` | Redis server hostname |
| `REDIS_PORT` | `6379` | Redis server port |
| `REDIS_CONNECT_TIMEOUT` | `2000` | Connection timeout (ms) |
| `REDIS_TIMEOUT` | `2000` | Read/write timeout (ms) |

### Testing

The test suite includes:
- **Cache hit/miss tests**: Verify that repeated calls use cached data
- **Cache eviction tests**: Verify that write operations clear the appropriate regions
- **Cache invalidation tests**: Verify cross-region eviction behavior
- **Redis failure tests**: Verify graceful degradation when Redis is unavailable (6 tests)
- **Cache region isolation tests**: Verify that different query parameters produce independent cache entries

All cache tests use `spring.cache.type=simple` (in-process cache) to avoid requiring a running Redis instance. The Redis failure tests connect to a non-routable address to simulate an unavailable cache.

## Default Seed Data

Users:
- Admin: `admin / admin123`
- User: `testuser / testuser`

Books:
- `Book1` by `Author1` (`Fiction`, stock `10`)
- `Book2` by `Author2` (`SciFi`, stock `5`)

Seed data is inserted idempotently by Flyway migration `V2__Seed_demo_data.sql` on first startup and persists across restarts.

## Authentication

Protected endpoints require:

```text
Authorization: Bearer <jwt-token>
```

Get the token from `POST /api/auth/login`.

## API Endpoints

### Auth
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| POST | `/api/auth/register` | Public | Register a new user |
| POST | `/api/auth/login` | Public | Login and receive JWT |
| GET | `/api/auth/me` | Authenticated | Get current user profile |

### Books
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| GET | `/api/books` | Public | List all books |
| GET | `/api/books/category?category=Fiction` | Public | Filter books by category |
| GET | `/api/books/search?search=Book` | Public | Search books by title or author |
| POST | `/api/books` | Admin | Create a new book |
| PUT | `/api/books/{id}/stock` | Admin | Update book stock |
| DELETE | `/api/books/{id}` | Admin | Delete a book |

### Cart
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| GET | `/api/cart` | User | Get current cart |
| POST | `/api/cart/items` | User | Add item to cart |
| PUT | `/api/cart/items/{bookId}` | User | Update cart item quantity |
| DELETE | `/api/cart/items/{bookId}` | User | Remove item from cart |
| DELETE | `/api/cart` | User | Clear cart |

### Orders (Customer)
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| POST | `/api/orders` | User | Create order from cart |
| GET | `/api/orders?page=0&size=10&sortBy=createdAt&direction=desc` | User | Get paged own orders |
| GET | `/api/orders/{id}` | User | Get own order details |
| POST | `/api/orders/{id}/cancel` | User | Cancel own PENDING order |
| GET | `/api/orders/{id}/history` | User | View own order status history |

### Orders (Admin)
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| GET | `/api/admin/orders?page=0&size=10&status=` | Admin | List all orders (optional status filter) |
| GET | `/api/admin/orders/{id}` | Admin | Get any order details |
| PUT | `/api/admin/orders/{id}/status` | Admin | Update order status |
| POST | `/api/admin/orders/{id}/cancel` | Admin | Cancel an order |
| GET | `/api/admin/orders/{id}/history` | Admin | View any order status history |

## Example Requests

Register:
```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"newuser\",\"password\":\"password123\",\"email\":\"newuser@example.com\"}"
```

Login:
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"testuser\",\"password\":\"testuser\"}"
```

Create a book as admin:
```bash
curl -X POST http://localhost:8080/api/books \
  -H "Authorization: Bearer <admin-token>" \
  -H "Content-Type: application/json" \
  -d "{\"title\":\"Clean Code\",\"author\":\"Robert C. Martin\",\"price\":39.99,\"category\":\"Programming\",\"stock\":8}"
```

Add to cart:
```bash
curl -X POST http://localhost:8080/api/cart/items \
  -H "Authorization: Bearer <user-token>" \
  -H "Content-Type: application/json" \
  -d "{\"bookId\":1,\"quantity\":2}"
```

Create order:
```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Authorization: Bearer <user-token>"
```

## Docker Compose

```bash
# Start PostgreSQL and Redis
docker compose up -d

# View logs
docker compose logs -f

# Stop all services
docker compose down

# Stop and delete volumes (removes all data)
docker compose down -v
```

## Stopping the Application

Press `Ctrl+C` in the terminal where the application is running.

To stop PostgreSQL:
```bash
docker compose down
```

## Notes
- Book write operations require `ROLE_ADMIN`.
- Cart and order endpoints require `ROLE_USER`.
- Validation errors return `400 Bad Request`.
- Swagger endpoints are intentionally open for local testing.
- Database schema and data persist across restarts via Docker volume `postgres_data`.
- Redis cache persists across restarts via Docker volume `redis_data`.
- Run `docker compose down -v` to reset both database and cache completely; Flyway reapplies all migrations on next startup.
- The application functions correctly without Redis — cache failures degrade gracefully to direct database queries.
