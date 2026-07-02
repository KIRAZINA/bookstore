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
- Apache Kafka (event-driven architecture)
- Kubernetes (container orchestration)
- Prometheus + Grafana (metrics & dashboards)
- Tempo (distributed tracing)
- NGINX Ingress Controller
- Kustomize (K8s manifest management)
- Trivy + Cosign (container security & signing)
- Kubeconform + Checkov (manifest validation)

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

---

## CI/CD Pipeline

### Pipeline Architecture

The project uses GitHub Actions for continuous integration and continuous delivery. The pipeline is designed for an enterprise Spring Boot application with multiple developers, frequent pull requests, and production deployments.

```
┌─────────────────────────────────────────────────────────────┐
│                    CI/CD Pipeline                            │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌─────────────────┐   ┌─────────────────┐   ┌───────────┐  │
│  │ Static Analysis  │   │  Build & Test    │   │  Security  │  │
│  │ (Checkstyle,     │   │  (Compile,       │   │  (OWASP    │  │
│  │  PMD, SpotBugs)  │   │   Unit + IT,     │   │   Dep.)    │  │
│  │                  │   │   Coverage)      │   │           │  │
│  └────────┬────────┘   └────────┬─────────┘   └─────┬─────┘  │
│           │                     │                    │        │
│           └──────────┬──────────┘                    │        │
│                      │                               │        │
│              ┌───────┴───────┐                       │        │
│              │  Quality Gate │                       │        │
│              │  (All Pass?)  │                       │        │
│              └───────┬───────┘                       │        │
│                      │                               │        │
│              ┌───────┴───────┐                       │        │
│              │ Docker Image  │                       │        │
│              │  Validation   │                       │        │
│              └───────────────┘                       │        │
│                                                      │        │
│              ┌───────────────────────────────────────┘        │
│              │                                                │
│       ┌──────┴──────┐                                         │
│       │   Artifacts  │                                         │
│       │   Published  │                                         │
│       └─────────────┘                                         │
└─────────────────────────────────────────────────────────────┘
```

### Pipeline Philosophy

- **Fail Fast**: Static analysis and compilation run early to provide immediate feedback
- **Parallel Execution**: Independent jobs (static analysis, build, security) run concurrently
- **Progressive Validation**: Fast checks first, comprehensive validation follows
- **Artifact Preservation**: All reports (coverage, tests, static analysis) are saved as build artifacts
- **Quality Gates**: The pipeline enforces strict quality standards at every stage
- **Reproducibility**: Every build runs identically on a clean GitHub Actions runner and a developer machine

### Workflow Files

| File | Trigger | Purpose |
|------|---------|---------|
| `.github/workflows/ci.yml` | Push to `main`/`release/*`, PR to `main` | Main CI/CD orchestrator |
| `.github/workflows/codeql-analysis.yml` | Push/PR to `main`, weekly schedule | CodeQL security analysis |
| `.github/workflows/reusable-build.yml` | Called by `ci.yml` | Build, test, coverage |
| `.github/workflows/reusable-static-analysis.yml` | Called by `ci.yml` | Checkstyle, PMD, SpotBugs |
| `.github/workflows/reusable-security.yml` | Called by `ci.yml` | OWASP Dependency Check |

### Pipeline Stages

#### 1. Static Code Analysis
**Tools**: Checkstyle, PMD, SpotBugs
**What it validates**:
- Code formatting and style conventions (`checkstyle.xml`)
- Common programming flaws and anti-patterns (`pmd-ruleset.xml`)
- Bug patterns and correctness issues (SpotBugs with Medium+ threshold)
**Failure**: Pipeline fails on any error-level violation

#### 2. Build & Test
**Phases**:
- Compilation (`mvn clean compile`)
- Unit tests (Surefire)
- Integration tests (Testcontainers with PostgreSQL + Redis)
- JaCoCo coverage report and threshold check
**Coverage Thresholds**:
| Counter | Minimum Coverage |
|---------|-----------------|
| Line | 60% |
| Branch | 50% |
| Method | 60% |
| Class | 80% |

#### 3. Security Scanning
**Tools**:
- **OWASP Dependency Check**: Scans all Maven dependencies for known CVEs
  - Fails build on CVSS >= 7.0 (High/Critical)
  - Produces HTML and JSON reports
- **CodeQL** (separate workflow): Deep security analysis of Java source code
  - Runs on push/PR to `main` and weekly
  - Uses `security-and-quality` query suite

#### 4. Docker Image Validation
- Builds the application Docker image using the multi-stage `Dockerfile`
- Validates the image builds successfully and is structurally sound
- Runs only on `main` and `release/*` branches after successful tests

#### 5. Quality Gate
- Aggregates results from all parallel jobs
- Pipeline fails if any of: static analysis, tests, or security scan fail
- Ensures only high-quality code reaches the main branch

### Branch Strategy

| Branch | CI Trigger | Artifacts | Docker Build |
|--------|-----------|-----------|--------------|
| `feature/*` | PR to `main` | Reports | No |
| `main` | Push | JAR + Reports | Yes |
| `release/*` | Push | JAR + Reports | Yes |

### Required GitHub Secrets

The following secrets must be configured in the GitHub repository:

| Secret | Required | Description |
|--------|----------|-------------|
| `NVD_API_KEY` | Optional | NVD API key for OWASP Dependency Check (speeds up vulnerability data download) |

**Future secrets** (for upcoming deployment stages):
| Secret | Purpose |
|--------|---------|
| `DOCKER_REGISTRY` | Docker registry URL |
| `DOCKER_USERNAME` | Docker registry username |
| `DOCKER_PASSWORD` | Docker registry password |
| `DEPLOY_KEY` | SSH key or cloud provider token |

### Running CI Locally

To simulate the CI pipeline locally:

```bash
# Full CI build (all checks)
./mvnw clean verify -Pci -Dspring.profiles.active=test

# Static analysis only
./mvnw checkstyle:check pmd:check pmd:cpd-check spotbugs:check -Pci

# Coverage check
./mvnw test jacoco:report jacoco:check

# Security scan (requires network for NVD download)
./mvnw dependency-check:check -Pci
```

### Dependabot Configuration

Dependabot is configured (`.github/dependabot.yml`) to automatically create pull requests for:
- **Maven dependencies**: Weekly on Monday
- **GitHub Actions**: Weekly on Monday
- **Docker images**: Weekly on Monday

### Troubleshooting Failed Builds

| Failure | Likely Cause | Resolution |
|---------|-------------|------------|
| Compilation error | Syntax error or incompatible dependency | Check changed Java files |
| Test failure | Business logic regression or flaky test | Examine test report artifact |
| Coverage below threshold | New code without tests | Add unit/integration tests |
| Checkstyle violation | Formatting inconsistency | Run `mvn checkstyle:check` locally to identify issues |
| PMD violation | Code quality issue | Review PMD report artifact for details |
| SpotBugs error | Potential bug pattern | Review SpotBugs report for findings |
| OWASP finding | Vulnerable dependency version | Update dependency or add suppression |
| Docker build failure | Incorrect Dockerfile or JAR not found | Verify Dockerfile syntax and JAR existence |

### Artifacts Available Per Build

After every successful CI run, the following artifacts are available for download:
- **Application JAR**: Deployable Spring Boot artifact
- **JaCoCo Coverage Report**: HTML coverage report with line/branch/method/class metrics
- **Test Reports**: Surefire and Failsafe XML reports
- **Static Analysis Reports**: Checkstyle, PMD, SpotBugs HTML reports
- **OWASP Dependency Report**: Vulnerability scan results

### Future Roadmap (CI/CD)

The pipeline is designed for easy extension to support:

1. **Docker Registry Publishing**: Push validated images to Docker Hub / ECR / ACR
2. **GitHub Releases**: Automated semantic versioning and release notes
3. **Cloud Deployment**: AWS/GCP/Azure deployment automation
4. **Kubernetes**: Helm chart validation and cluster deployment
5. **Blue-Green Deployment**: Zero-downtime deployment strategies
6. **Performance Testing**: Automated load and stress tests
7. **API Contract Testing**: OpenAPI/Swagger contract validation
8. **End-to-End Tests**: Browser-based or API E2E test suites

Adding these capabilities requires minimal workflow modifications — the existing modular architecture supports incremental extension.

---

## Security Architecture

### Security Overview

The application implements defense-in-depth security following OWASP Top 10 recommendations and Spring Security best practices. The security architecture covers authentication, authorization, transport security, input validation, rate limiting, audit logging, and operational security.

### Security Layers

```
┌──────────────────────────────────────────────────┐
│                  Rate Limiting                    │
├──────────────────────────────────────────────────┤
│           HTTP Security Headers + CORS            │
├──────────────────────────────────────────────────┤
│            JWT Authentication Filter              │
├──────────────────────────────────────────────────┤
│         SecurityConfig (URL-based access)          │
├──────────────────────────────────────────────────┤
│        @PreAuthorize (Method-level access)         │
├──────────────────────────────────────────────────┤
│         Input Validation (Bean Validation)         │
├──────────────────────────────────────────────────┤
│       GlobalExceptionHandler (Safe errors)         │
├──────────────────────────────────────────────────┤
│          Audit Logging (Security events)           │
├──────────────────────────────────────────────────┤
│              Secrets Externalization               │
└──────────────────────────────────────────────────┘
```

### Authentication

- **Stateless JWT-based authentication** — no session state on the server
- **JWT token format**: HMAC-SHA256 (`HS256`) signed token containing:
  - `sub` — username
  - `roles` — comma-separated granted authorities
  - `jti` — unique token ID (for future revocation)
  - `iat` / `exp` — issued at / expiry timestamps
- **Token expiry**: 30 minutes (configurable via `JWT_EXPIRATION`)
- **Clock skew**: 30 seconds allowed (configurable via `jwt.clock-skew-seconds`)
- **Secret**: Must be at least 32 bytes; provided via `JWT_SECRET` environment variable
- **No hardcoded secrets** in source code — all secrets are externalized

### JWT Token Validation Flow

```
Request → Authorization: Bearer <token>
              │
              ▼
    JwtAuthenticationFilter
              │
    ┌─────────┴─────────┐
    │  Parse token       │
    │  Verify signature   │
    │  Check expiry       │
    │  Extract roles      │
    └─────────┬─────────┘
              │
              ▼
    SecurityContextHolder.setAuthentication()
              │
              ▼
    Access decision (URL pattern → @PreAuthorize)
```

### Authorization Matrix

| Endpoint | Method | Role Required |
|----------|--------|---------------|
| `/api/auth/**` | ALL | Public |
| `/swagger-ui/**`, `/v3/api-docs/**` | ALL | Public |
| `/api/books`, `/api/books/search`, `/api/books/category` | GET | Public |
| `/api/books/**` | POST/PUT/DELETE | ADMIN |
| `/api/admin/**` | ALL | ADMIN |
| `/api/cart/**` | ALL | USER |
| `/api/orders/**` | ALL | USER |
| `/api/payments/**` | ALL | USER |
| `/actuator/health/**`, `/actuator/info` | GET | Public |
| `/actuator/prometheus` | GET | ADMIN (production) |
| `/actuator/**` | GET | ADMIN |

### HTTP Security Headers

Configured via `SecurityConfig`:

| Header | Value | Rationale |
|--------|-------|-----------|
| `X-Frame-Options` | `DENY` | Prevents clickjacking attacks |
| `X-Content-Type-Options` | `nosniff` | Prevents MIME type sniffing |
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` | Enforces HTTPS when deployed behind TLS |
| `Referrer-Policy` | `strict-origin-when-cross-origin` | Controls referrer header leakage |
| `Permissions-Policy` | `geolocation=(), microphone=(), camera=()` | Disables unused browser features |
| `Cache-Control` | `no-cache, no-store, max-age=0, must-revalidate` | Prevents caching of API responses |

### CORS Configuration

- **Allowed origins**: Configurable via `CORS_ALLOWED_ORIGINS` environment variable
- **Methods**: GET, POST, PUT, DELETE, PATCH, OPTIONS
- **Headers**: Authorization, Content-Type, X-Requested-With, Accept, Origin
- **Exposed headers**: X-Correlation-Id
- **Credentials**: Enabled
- **Preflight cache**: 3600 seconds
- **No wildcard origins** in production

### Rate Limiting

Rate limiting uses the token-bucket algorithm (Bucket4j) with configurable limits per endpoint group:

| Group | Capacity | Refill Rate | Affected Endpoints |
|-------|----------|-------------|-------------------|
| `auth` | 5 tokens | 1 per minute | `/api/auth/login`, `/api/auth/register` |
| `checkout` | 20 tokens | 10 per minute | `/api/orders`, `/api/payments` |
| `admin` | 60 tokens | 30 per minute | `/api/admin/**` |
| `api` | 100 tokens | 50 per minute | All other `/api/**` |
| `default` | 200 tokens | 100 per minute | Non-API endpoints |

- Limits are per **client IP** (respects `X-Forwarded-For` behind proxies)
- Response status: `429 Too Many Requests` with `Retry-After` header
- **Future**: Redis-backed distributed rate limiting across multiple instances
- Rate limiting can be disabled via `app.rate-limiting.enabled=false`

### Input Validation

All request DTOs enforce strict validation:

| Field | Constraint |
|-------|-----------|
| Username | 3-50 chars, alphanumeric + underscore only |
| Password | 6-128 chars |
| Email | Valid email format, max 255 chars |
| Title / Author | Max 255 chars |
| Price | 0.0001 to 999999.99 |
| Stock | 0 to 999999 |
| Quantity | 1 to 999 |
| Payment method | Max 50 chars |
| Comment | Max 500 chars |

### Error Handling

All API errors follow a consistent JSON structure:

```json
{
    "status": 409,
    "error": "Conflict",
    "message": "Concurrent modification detected. Please retry.",
    "code": "CONCURRENT_MODIFICATION",
    "path": "/api/orders",
    "timestamp": "2026-07-02T12:00:00Z",
    "fieldErrors": null
}
```

Fields:
- **status**: HTTP status code
- **error**: HTTP status reason phrase
- **message**: User-facing error description (no stack traces or internal details)
- **code**: Application-specific error code for programmatic handling
- **path**: Request URI that caused the error
- **timestamp**: When the error occurred
- **fieldErrors**: Validation field errors (only for 400 validation responses)

Key protections:
- No stack traces exposed to clients (`server.error.include-stacktrace=never`)
- No database exception details leaked
- Generic message for data integrity violations
- Catch-all handler for unhandled exceptions returns 500 with safe message
- Access denied and authentication failures return appropriate codes

### Audit Logging

Security-relevant events are logged to the `AUDIT` logger (separate from application logs):

| Event | Description |
|-------|-------------|
| `AUTH_SUCCESS` | Successful login |
| `AUTH_FAILURE` | Failed login attempt |
| `REGISTRATION` | New user registration |
| `ADMIN_ACTION` | Admin operations (status updates, cancellations, refunds) |
| `ORDER_ACTION` | Order state changes |
| `PAYMENT_ACTION` | Payment state changes |
| `SECURITY_EVENT` | Security-related events |

Log format:
```
AUDIT - AUTH_SUCCESS username=johndoe
AUDIT - ADMIN_ACTION actor=admin action=UPDATE_ORDER_STATUS detail=orderId=42 newStatus=SHIPPED
```

### Secrets Management

All secrets are externalized from source code:

| Configuration | Environment Variable | Required |
|---------------|---------------------|----------|
| JWT signing key | `JWT_SECRET` | Yes (no default) |
| Database URL | `DATASOURCE_URL` | Yes |
| Database username | `DATASOURCE_USERNAME` | Yes |
| Database password | `DATASOURCE_PASSWORD` | Yes |
| Redis host | `REDIS_HOST` | Yes (prod) |
| Redis password | `REDIS_PASSWORD` | Optional |
| Redis TLS | `REDIS_SSL_ENABLED` | Optional |
| CORS origins | `CORS_ALLOWED_ORIGINS` | Yes (prod) |
| JWT expiry | `JWT_EXPIRATION` | Optional (default: 30min) |
| DB pool size | `DB_POOL_MAX_SIZE` | Optional (default: 10) |

**Hardcoded defaults in `application.yml`** (dev profile):
- Database credentials default to local Docker Compose values
- These are safe defaults for local development only
- Production profile requires all secrets via environment variables

### Actuator Security

| Environment | Exposed Endpoints | Security |
|-------------|------------------|----------|
| Development | health, info, metrics, prometheus, beans, env, configprops, mappings | `env`/`configprops`: read-only; prometheus/health/info: public; others: ADMIN |
| Production | health, info, prometheus, metrics | All require ADMIN role; separate management port (8081) |
| Test | health, info, metrics, prometheus | Show details always (test only) |

### Redis Cache Security

- **Polymorphic deserialization**: Restricted to `com.example.bookstore.model.*`, `java.util.*`, `java.time.*` packages only (prevents arbitrary class deserialization)
- **Graceful degradation**: Cache failures fall back to database queries
- **Future**: Redis authentication (`REDIS_PASSWORD`), TLS (`REDIS_SSL_ENABLED`)

### Docker Security

| Measure | Implementation |
|---------|---------------|
| Non-root user | `bookstore` user in runtime container |
| Minimal base image | `eclipse-temurin:21-jre-alpine` |
| Read-only root | Container runs without unnecessary write permissions |
| HEALTHCHECK | `curl` liveness probe at `/actuator/health/liveness` |
| No source code | Only JAR copied to runtime image |
| Multi-stage build | Builder stage discarded after JAR creation |

### API Versioning Strategy

The project is currently at API version **v1** (declared in OpenAPI info). The following versioning strategy is prepared:

1. **URL-based versioning** (`/api/v1/books`, `/api/v2/books`):
   - Chosen for simplicity, explicit client declaration, and easy routing
   - Future controllers can be placed under versioned packages
2. **Backward compatibility**: Breaking changes require a new version
3. **Deprecation**: Deprecated endpoints should include `Deprecated` annotation and `X-API-Deprecated` response header
4. **Sunset period**: Minimum 6 months between deprecation and removal

### Security-Focused Tests

The test suite includes security-focused tests covering:
- **Authentication**: Login success, invalid credentials, missing token
- **Authorization**: USER vs ADMIN endpoint access, unauthorized access returns 403
- **Input validation**: Boundary values (min/max lengths, negative numbers, null fields)
- **Optimistic locking**: Concurrent modification returns 409 CONFLICT
- **Error handling**: Proper status codes and safe error messages
- **Audit logging**: (Future) Verify audit events for auth success/failure

### Required Environment Variables (Production)

```bash
# Mandatory
export JWT_SECRET=<32+ character random secret>
export DATASOURCE_URL=jdbc:postgresql://<host>:5432/bookstore
export DATASOURCE_USERNAME=bookstore
export DATASOURCE_PASSWORD=<secure-password>
export REDIS_HOST=<redis-host>
export CORS_ALLOWED_ORIGINS=https://your-frontend.com

# Optional (with defaults)
export JWT_EXPIRATION=1800000
export REDIS_PORT=6379
export REDIS_PASSWORD=
export REDIS_SSL_ENABLED=false
export DB_POOL_MAX_SIZE=10
export MANAGEMENT_PORT=8081
export NVD_API_KEY=<nvd-api-key>
```

### Security Best Practices (Production Deployment)

1. **Never use default credentials** — change all passwords before production deployment
2. **Use a strong JWT secret** — generate with `openssl rand -base64 48` 
3. **Enable HTTPS** — configure TLS at the load balancer / API Gateway level
4. **Set `CORS_ALLOWED_ORIGINS`** to your specific frontend domain(s)
5. **Use Redis password and TLS** in production (`REDIS_PASSWORD`, `REDIS_SSL_ENABLED`)
6. **Restrict network access** — database and Redis should not be publicly accessible
7. **Run containers as non-root** — already configured in Dockerfile
8. **Rotate secrets regularly** — use Vault or cloud secret manager
9. **Monitor audit logs** — watch for repeated `AUTH_FAILURE` events
10. **Use API Gateway** — add WAF, DDoS protection, and request validation at the edge

---

## Event-Driven Architecture with Apache Kafka

### Architecture Overview

The application uses an event-driven architecture with Kafka for reliable, asynchronous communication between bounded contexts. Events are persisted to a database-backed outbox before being published to Kafka, guaranteeing atomicity between business transactions and event publication.

```
┌──────────────────────────────┐     ┌─────────────────┐     ┌───────────────────┐
│  OrderService                │     │  Outbox          │     │  Kafka Topics      │
│  PaymentService              │────>│  EventOutbox     │────>│  orders.events     │
│  InventoryService            │     │  (DB table)      │     │  payments.events   │
│  InventoryAdminService       │     │                  │     │  inventory.events  │
│                              │     │  ┌──────────────┐│     │  notifications.evt │
│  DomainEventPublisher        │     │  │OutboxSched   ││     └───────────────────┘
│  (writes to outbox +         │     │  │(polls +      ││              │
│   publishes Spring events)   │     │  │ sends to     ││              ▼
└──────────────────────────────┘     │  │ Kafka)       ││     ┌───────────────────┐
                                      │  └──────────────┘│     │  Kafka Consumers   │
                                      └─────────────────┘     │                    │
                                                                │  OrderConsumer     │
                                                                │  PaymentConsumer   │
                                                                │  InventoryConsumer │
                                                                │  NotificationCons. │
                                                                └───────────────────┘
```

### Flow: Business Transaction → Outbox → Kafka → Consumer

```
1. Business service calls domainEventPublisher.publish(event)
2. OutboxEventPublisher:
   a. Serializes event to JSON
   b. Writes to event_outbox table (same DB transaction as business operation)
   c. Also calls ApplicationEventPublisher.publishEvent() for backward compatibility
3. Transaction commits → outbox record is persisted
4. OutboxPublisherScheduler (@Scheduled, every 1s):
   a. Polls event_outbox for PENDING records
   b. Constructs DomainEventEnvelope (with correlationId, traceId, metadata)
   c. Sends to appropriate Kafka topic (partitioned by aggregateId)
   d. Marks outbox record as PUBLISHED on success
   e. Increments retry_count on failure; moves to FAILED after max retries
5. Kafka consumer receives event:
   a. Checks processed_events table for idempotency
   b. If already processed → skips (at-least-once guarantee)
   c. If new → executes business logic
   d. Records eventId + consumerGroup in processed_events
6. On consumer failure → DefaultErrorHandler retries with exponential backoff
7. After retries exhausted → event sent to dead letter topic (dlt.events)
```

### Topics

| Topic | Partitions | Replication | Retention | Key | Purpose |
|-------|-----------|-------------|-----------|-----|---------|
| `orders.events` | 3 | 1 | 72h | `aggregateId` (order ID) | Order lifecycle events |
| `payments.events` | 3 | 1 | 72h | `aggregateId` (payment ID) | Payment lifecycle events |
| `inventory.events` | 3 | 1 | 168h | `aggregateId` (order ID) | Inventory reservation events |
| `notifications.events` | 1 | 1 | 24h | `aggregateId` (correlation ID) | Email notification events |
| `dlt.events` | 1 | 1 | 168h | original `aggregateId` | Dead letter topic |

**Partitioning strategy**: 3 partitions for high-throughput topics (orders, payments, inventory) provide parallelism while preserving per-aggregate ordering. The `aggregateId` key ensures all events for the same entity go to the same partition.

**Partition key selection**: `aggregateId` guarantees that events for a specific order/payment/inventory item are consumed in order. This avoids the need for global ordering while maintaining per-aggregate sequence guarantees.

### Domain Events

Every event is immutable and versioned with these fields:

| Field | Type | Description |
|-------|------|-------------|
| `eventId` | UUID | Unique event identifier |
| `aggregateType` | String | Bounded context: ORDER, PAYMENT, INVENTORY, NOTIFICATION |
| `aggregateId` | String | The business entity ID (order ID, payment ID, book ID) |
| `eventType` | String | Event name: ORDER_CREATED, PAYMENT_COMPLETED, etc. |
| `eventVersion` | int | Schema version (currently 1) |
| `timestamp` | Instant | When the event was created |
| `correlationId` | String | Traces a request across all services |
| `traceId` | String | OpenTelemetry trace ID for distributed tracing |
| `producer` | String | Service name: "bookstore" |
| `payload` | Map<String, Object> | Business data specific to the event |
| `metadata` | Map<String, Object> | Infrastructure metadata (retry info, DLQ info) |

**Versioning strategy**: Events use integer version numbers. New fields are always optional (nullable). Consumers check `eventVersion` and apply backward-compatible deserialization. When breaking changes are needed, the event type receives a new name (e.g., `ORDER_CREATED_V2`).

### Event Catalog

| Event | Producer | Consumers | Trigger |
|-------|----------|-----------|---------|
| `ORDER_CREATED` | OrderService | NotificationConsumer, OrderConsumer | Successful order creation |
| `ORDER_STATUS_CHANGED` | OrderService | NotificationConsumer, OrderConsumer | Order cancel/status update |
| `ORDER_CANCELLED` | OrderService | NotificationConsumer, OrderConsumer | Admin cancels order |
| `PAYMENT_COMPLETED` | PaymentService | NotificationConsumer, PaymentConsumer | Payment processing succeeds |
| `PAYMENT_FAILED` | PaymentService | NotificationConsumer, PaymentConsumer | Payment processing fails |
| `PAYMENT_REFUNDED` | PaymentService | NotificationConsumer, PaymentConsumer | Refund succeeds |
| `INVENTORY_RESERVED` | InventoryService | InventoryConsumer | Items reserved for an order |
| `INVENTORY_RELEASED` | InventoryService | InventoryConsumer | Reservation released |
| `INVENTORY_FINALIZED` | InventoryService | InventoryConsumer | Reservation finalized (sold) |
| `INVENTORY_ADJUSTED` | InventoryAdminService | InventoryConsumer | Admin adjusts inventory |

### Transactional Outbox Pattern

The outbox pattern guarantees atomicity between database operations and event publication:

1. **Write to outbox within the business transaction** — the `event_outbox` table shares the same datasource as the business tables. The `OutboxEventPublisher` uses `@Transactional(propagation = Propagation.MANDATORY)` to ensure it's always called within an existing transaction.

2. **Poll and publish** — the `OutboxPublisherScheduler` polls the `event_outbox` table every second for records with `status = 'PENDING'`. It sends each to Kafka and marks as `PUBLISHED`. 

3. **Retry and failure handling** — if publishing fails, `retry_count` is incremented. After 5 failures, the record is marked `FAILED` but remains in the database for operator inspection and replay.

4. **Recovery** — an admin can reset FAILED records back to PENDING via SQL for replay.

### Consumer Idempotency

Every consumer implements idempotent processing using the `processed_events` table:

```sql
PRIMARY KEY (event_id, consumer_group)
```

Before processing, the consumer checks if a record exists for this `(event_id, consumer_group)` pair. If found, the event is skipped (duplicate detection). This handles:
- At-least-once delivery (Kafka may redeliver)
- Producer retries (same event published multiple times)
- Consumer crashes after processing but before committing offset

### Retry Strategy

| Layer | Mechanism | Backoff | Max Attempts |
|-------|-----------|---------|-------------|
| Kafka Producer | `retries=3`, `enable.idempotence=true` | 100ms | 3 |
| Outbox Publisher | `retry_count` in DB, scheduled repoll | 1s (next poll cycle) | 5 |
| Kafka Consumer | `DefaultErrorHandler` with exponential backoff | 1s initial, 2x multiplier, 30s max | ~10 (300s total) |

**Non-retryable exceptions**: `MessageConversionException`, `SerializationException`, `IllegalArgumentException`, `IllegalStateException` — these skip retry and go directly to DLQ.

### Dead Letter Topic (DLT)

After all retries are exhausted, the consumer error handler sends the original event (enriched with error metadata in `metadata` field) to the `dlt.events` topic:

```json
{
  "metadata": {
    "dlt_original_topic": "orders.events",
    "dlt_error_message": "Order not found: 99999",
    "dlt_error_type": "jakarta.persistence.EntityNotFoundException",
    "dlt_timestamp": 1719876543210
  }
}
```

DLT events are retained for 7 days. Operators can:
1. **Inspect** failed events via Kafka UI (port 8082)
2. **Fix** the underlying issue (e.g., restore missing data)
3. **Replay** by reading from DLT and publishing corrected events to the original topic

### Observability

**Micrometer metrics** (exposed via Prometheus at `/actuator/prometheus`):

| Metric | Type | Description |
|--------|------|-------------|
| `bookstore.messaging.events.published` | Counter | Events written to outbox |
| `bookstore.messaging.events.consumed` | Counter | Events successfully consumed |
| `bookstore.messaging.events.failed` | Counter | Consumer processing failures |
| `bookstore.messaging.events.dlt` | Counter | Events sent to dead letter topic |
| `bookstore.messaging.outbox.published` | Counter | Outbox events published to Kafka |
| `bookstore.messaging.outbox.failed` | Counter | Outbox events permanently failed |
| `bookstore.messaging.event.processing.duration` | Timer | Consumer processing latency |

**OpenTelemetry integration**: Trace IDs are extracted from the current span and propagated through the `DomainEventEnvelope`. Correlation IDs tie events together across topic boundaries (future: Kafka interceptor for automatic trace propagation).

**Kafka UI** is available at `http://localhost:8082` for manual inspection of topics, messages, consumer groups, and offsets.

### Configuration

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      acks: all
      retries: 3
      compression-type: snappy
      enable-idempotence: true
    consumer:
      auto-offset-reset: earliest
      enable-auto-commit: false
      max-poll-records: 100
    listener:
      concurrency: 3
      missing-topics-fatal: false

app:
  outbox:
    poll-interval: 1000      # ms between polls
    max-retries: 5            # max publish attempts before FAILED
    batch-size: 100           # max records per poll
```

### Docker Compose

Kafka runs in KRaft mode (no ZooKeeper dependency):

```yaml
kafka:
  image: apache/kafka:4.0.0
  environment:
    KAFKA_NODE_ID: 1
    KAFKA_PROCESS_ROLES: broker,controller
    KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
    KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
    KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
    CLUSTER_ID: bookstore-kafka-cluster
```

### Message Serialization

Events are serialized as JSON using Jackson. The `DomainEventEnvelope` is a Java record with `Map<String, Object>` payload for schema flexibility. This allows adding new fields without breaking consumers.

**Schema evolution strategy**:
- **Backward-compatible changes** (adding optional fields): increment `eventVersion` but keep the same `eventType`
- **Breaking changes**: create a new event type (e.g., `ORDER_CREATED_V2`)
- Consumers read `eventVersion` and use appropriate deserialization

### Database Tables

**`event_outbox`** — transactional outbox:
| Column | Type | Description |
|--------|------|-------------|
| `id` | UUID PK | Unique event ID |
| `aggregate_type` | VARCHAR(100) | Bounded context |
| `aggregate_id` | VARCHAR(100) | Entity ID |
| `event_type` | VARCHAR(100) | Event name |
| `event_version` | INTEGER | Schema version |
| `correlation_id` | VARCHAR(100) | Request correlation |
| `trace_id` | VARCHAR(100) | OpenTelemetry trace |
| `producer` | VARCHAR(100) | Service name |
| `payload` | JSONB | Event data |
| `metadata` | JSONB | Infrastructure data |
| `status` | VARCHAR(20) | PENDING / PUBLISHED / FAILED |
| `retry_count` | INTEGER | Publish attempt count |
| `created_at` | TIMESTAMP | Creation time |
| `published_at` | TIMESTAMP | Publish time |

**`processed_events`** — consumer idempotency:
| Column | Type | Description |
|--------|------|-------------|
| `event_id` | VARCHAR(100) PK | Event UUID |
| `consumer_group` | VARCHAR(100) PK | Consumer group name |
| `processed_at` | TIMESTAMP | Processing time |

### Consumer Groups

| Consumer Group | Topics Consumed | Purpose |
|----------------|----------------|---------|
| `orders-consumer` | `orders.events` | Order analytics, saga coordination |
| `payments-consumer` | `payments.events` | Payment reconciliation, fraud detection |
| `inventory-consumer` | `inventory.events` | Low stock alerts, warehouse integration |
| `notifications-consumer` | `notifications.events` | Email notifications |

### Backward Compatibility (Migration Phase)

During the transition from Spring events to Kafka:
1. `OutboxEventPublisher` writes events to the outbox AND publishes via `ApplicationEventPublisher`
2. `NotificationEventHandler` continues to consume Spring events for email notifications
3. Kafka consumers run in parallel, eventually replacing the Spring event handlers
4. Once all consumers are verified stable, the `ApplicationEventPublisher` call can be removed

### Testing

Kafka is disabled by default in tests (no `spring.kafka.bootstrap-servers` property). The `@ConditionalOnProperty` annotation on all Kafka components ensures they are not loaded during unit tests.

For integration tests, use `@EmbeddedKafka` from `spring-kafka-test`:

```java
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"orders.events", "notifications.events"})
class KafkaIntegrationTest {
    @Test
    void testEventFlow() { ... }
}
```

### Ordering Guarantees

- **Per-aggregate ordering**: Events for the same aggregate (e.g., Order #123) are guaranteed to arrive in order because they share the same partition key (`aggregateId = "123"`)
- **Cross-aggregate ordering**: No guarantees (not required by business rules)
- **Partition assignment**: 3 partitions per topic provide sufficient parallelism while maintaining per-key ordering
- **Trade-off**: More partitions increase throughput but reduce the effectiveness of ordering guarantees across different keys — acceptable since cross-aggregate ordering is not a requirement

### Future Microservice Migration

The architecture is designed for minimal changes when extracting bounded contexts into independent services:

1. **Current**: Monolith — all services share one `DomainEventPublisher` and one database
2. **Intermediate**: Deploy multiple instances of the monolith — Kafka ensures at-least-once delivery
3. **Microservice**: Extract Order, Payment, Inventory, and Notification into separate services — each service has its own `event_outbox` table, its own `OutboxPublisherScheduler`, and publishes events to the same Kafka topics

No domain logic changes are needed for the extraction — the event contracts, topic structure, consumer groups, and serialization format remain identical.

---

## Stage 14 — Production Deployment, Kubernetes & Cloud-Native Infrastructure

### Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Cloud Load Balancer                          │
└──────────────────────────┬──────────────────────────────────────────┘
                           │
                    ┌──────▼──────┐
                    │   Ingress    │  NGINX Ingress Controller
                    │  (TLS 1.3)   │  Security Headers, Rate Limiting
                    └──────┬──────┘
                           │
                    ┌──────▼──────┐
                    │   Service   │  ClusterIP (internal)
                    │  :80→:8080  │  Management: 8081
                    └──────┬──────┘
                           │
              ┌────────────┼────────────┐
              │            │            │
        ┌─────▼─────┐ ┌───▼───┐ ┌───▼───┐
        │  Pod (AZ1) │ │Pod(AZ2)│ │Pod(AZ3)│  3+ replicas across AZs
        │  Bookstore │ │       │ │       │
        │  API       │ │       │ │       │
        └─────┬─────┘ └───┬───┘ └───┬───┘
              │            │         │
              └────────────┼─────────┘
                           │
              ┌────────────┼────────────┐
              │            │            │
        ┌─────▼─────┐ ┌───▼────┐ ┌────▼─────┐
        │ PostgreSQL │ │ Redis  │ │  Kafka   │
        │ (Managed)  │ │(Managed)│ │(Managed) │
        │ RDS/Aurora │ │ElastiCache│  MSK/CC  │
        └────────────┘ └────────┘ └──────────┘
                           │
              ┌────────────┼────────────┐
              │            │            │
        ┌─────▼─────┐ ┌───▼────┐ ┌────▼─────┐
        │ Prometheus│ │ Grafana│ │  Tempo   │
        │(in-cluster)│ │(in-cluster) │(in-cluster) │
        └───────────┘ └────────┘ └──────────┘
```

**Key design principle**: Stateful services (PostgreSQL, Redis, Kafka) are managed externally in production. Only stateless application components and observability tools run inside Kubernetes. This follows the cloud-native best practice of separating compute from state.

### Docker Optimization

The Dockerfile has been optimized for production with the following strategies:

#### Multi-Stage Build

| Stage | Base Image | Purpose |
|-------|-----------|---------|
| `dependencies` | `eclipse-temurin:21-jdk-alpine` | Pre-resolves Maven dependencies (cached layer) |
| `builder` | Inherits from `dependencies` | Compiles source, builds JAR, copies OTEL agent |
| `runtime` | `eclipse-temurin:21-jre-alpine` | Minimal runtime with only the JRE |

#### Optimizations Applied

1. **Dependency caching**: `pom.xml` + Maven wrapper are copied first and dependencies resolved in a separate stage. This layer is cached and only invalidated when `pom.xml` changes.

2. **Minimal runtime image**: Only `eclipse-temurin:21-jre-alpine` with `tzdata` and `ca-certificates`. No build tools, no curl. Image size is reduced by ~60% compared to a JDK-based runtime.

3. **Non-root user**: Application runs as `bookstore` user (UID 1001) with no shell access.

4. **Read-only root filesystem**: Container root filesystem is read-only. Temporary files use `/tmp` (emptyDir volume in Kubernetes).

5. **Proper signal handling**: `exec java ...` in ENTRYPOINT ensures Java process gets PID 1 and receives SIGTERM directly (not via shell). This enables Spring Boot graceful shutdown.

6. **JVM container awareness**: `-XX:+UseContainerSupport`, `-XX:+HeapDumpOnOutOfMemoryError`, `-XX:+ExitOnOutOfMemoryError` configured via `JAVA_TOOL_OPTIONS`.

7. **`.dockerignore`**: Excludes everything except source code and build files, reducing build context from ~100MB to <1MB.

### Deployment Strategy

#### Rolling Update Configuration

```yaml
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxSurge: 1       # One extra pod during update
    maxUnavailable: 0  # Zero downtime guarantee
```

**Trade-offs**:
- **Rolling update** (chosen): Gradual replacement, no downtime, no extra infrastructure. Suitable for stateless API workloads.
- **Blue-green** (future): Complete environment swap, instant rollback, requires double infrastructure cost. Useful for major version migrations.
- **Canary** (future): Percentage-based traffic shifting, risk mitigation, requires service mesh (Istio/Linkerd). Useful for high-risk changes.

#### Graceful Shutdown Sequence

1. **PreStop hook** (15s delay): Pod is removed from Service endpoints; load balancer drains in-flight requests.
2. **SIGTERM** sent to Java PID 1: Spring Boot initiates graceful shutdown.
3. **Spring lifecycle** (30s timeout): Embedded web server stops accepting new requests; in-flight requests complete; beans are destroyed.
4. **SIGKILL** (after 60s `terminationGracePeriodSeconds`): Force kill if not shut down gracefully.

#### Rollback Strategy

- **Automatic rollback** on deployment failure (via CI/CD pipeline).
- **Manual rollback**: `kubectl rollout undo deployment/bookstore`
- **Versioned rollback**: Previous ReplicaSet is retained for 3 revisions by default.

### Scaling Strategy

#### Horizontal Pod Autoscaler

| Metric | Target | Rationale |
|--------|--------|-----------|
| CPU utilization | 70% | Primary scaling signal for compute-bound JVM workloads |
| Memory utilization | 80% | Prevents OOM kills under memory pressure |
| HTTP requests/s | 1000/pod (future) | Business-relevant metric via Prometheus adapter |

#### Scaling Behavior

- **Scale-up**: Aggressive (100% increase per minute, 4 pods max per 60s). Stabilization window: 60s.
- **Scale-down**: Conservative (25% decrease per minute, 1 pod max per 60s). Stabilization window: 300s.

#### Minimum Replicas

**3 replicas minimum** across 3 availability zones ensures:
- Tolerance to single node failure
- Zero-downtime rolling updates
- Load balancing during traffic spikes

### Stateful Services Decision Matrix

| Service | In-Cluster | Managed External | Rationale |
|---------|-----------|-----------------|-----------|
| PostgreSQL | No | **Yes** (RDS/Aurora/Cloud SQL) | Data loss risk, backup complexity, performance tuning |
| Redis | No | **Yes** (ElastiCache/Memorystore) | Data persistence, failover, auto-scaling |
| Kafka | No | **Yes** (MSK/Confluent Cloud) | Leader election, partition rebalancing, data retention |
| Prometheus | **Yes** | No (optional Grafana Cloud) | Operator-based, PVC-backed, alerting integration |
| Grafana | **Yes** | No (optional Grafana Cloud) | Dashboard provisioning, datasource config via ConfigMap |
| Tempo | **Yes** | No (optional Grafana Cloud) | S3-backed storage, configurable retention |

### Kubernetes Architecture

#### Directory Structure

```
k8s/
├── kustomization.yaml           # Production base Kustomization
├── namespace.yaml               # bookstore namespace
├── serviceaccount.yaml          # ServiceAccount + RBAC Role
├── configmap.yaml               # Non-sensitive configuration
├── secret.yaml                  # Secrets template + ExternalSecret
├── priorityclass.yaml           # Scheduling priority
├── pdb.yaml                     # PodDisruptionBudget (min 2)
├── deployment.yaml              # Main Deployment
├── service.yaml                 # ClusterIP Service
├── hpa.yaml                     # HorizontalPodAutoscaler
├── networkpolicy.yaml           # Network policies
└── ingress.yaml                 # TLS 1.3 Ingress
└── monitoring/
    ├── kustomization.yaml
    ├── namespace.yaml
    ├── prometheus-*.yaml         # Prometheus + PVC + Service
    ├── grafana-*.yaml            # Grafana + PVC + Service + ConfigMaps
    └── tempo-*.yaml              # Tempo + PVC + Service + Config
```

#### Resource Requests & Limits

| Container | CPU Request | CPU Limit | Memory Request | Memory Limit |
|-----------|------------|-----------|---------------|-------------|
| Bookstore API | 1 core | 2 cores | 1 GiB | 2 GiB |
| Prometheus | 0.5 core | 2 cores | 2 GiB | 4 GiB |
| Grafana | 0.2 core | 1 core | 256 MiB | 512 MiB |
| Tempo | 0.5 core | 2 cores | 512 MiB | 1 GiB |

**JVM tuning**: `-XX:+UseContainerSupport` (default in JDK 21) detects container memory limits and adjusts heap sizes automatically. Heap dumps on OOM are written to `/tmp` for collection.

#### Health Probes

| Probe | Endpoint | Port | Period | Failure Threshold | Purpose |
|-------|----------|------|--------|-------------------|---------|
| Startup | `/actuator/health/startup` | 8081 | 5s | 18 (90s) | Blocks readiness until app is fully initialized |
| Readiness | `/actuator/health/readiness` | 8081 | 10s | 3 (30s) | Traffic routing: pod removed from Service on failure |
| Liveness | `/actuator/health/liveness` | 8081 | 15s | 3 (45s) | Pod restart: only checks JVM liveness, not dependencies |

The management port (8081) is separated from the application port (8080) to ensure probes and metrics don't interfere with application traffic.

### Security Architecture

#### Container Security

| Control | Implementation |
|---------|---------------|
| Non-root user | `runAsUser: 1001`, `runAsNonRoot: true` |
| Read-only root filesystem | `readOnlyRootFilesystem: true` |
| Capability drop | `capabilities.drop: ALL` |
| Seccomp profile | `RuntimeDefault` |
| Privilege escalation | `allowPrivilegeEscalation: false` |
| Image signing | Cosign (sigstore) in CI/CD |

#### Network Security

- **Zero-trust networking**: Default deny-all ingress and egress policies.
- **Least privilege**: Only required ports and protocols are allowed.
- **Internal/external separation**: Application pods can reach managed services (PostgreSQL, Redis, Kafka) but cannot access other namespaces without explicit policies.
- **Monitoring access**: Only Prometheus can scrape metrics from the management port.

#### Secrets Management

**Development only**: Placeholder Secrets in `secret.yaml` with `stringData`.

**Production**: The `secret.yaml` includes a commented `ExternalSecret` resource for [External Secrets Operator](https://external-secrets.io/). Cloud provider integration:

```yaml
# AWS Secrets Manager
secretStoreRef:
  kind: ClusterSecretStore
  name: aws-secrets-manager
data:
  - secretKey: DATASOURCE_URL
    remoteRef:
      key: bookstore/production/database
      property: DATASOURCE_URL
```

Supported backends:
- AWS Secrets Manager / Parameter Store
- Azure Key Vault
- GCP Secret Manager
- HashiCorp Vault

### Observability

#### Metrics Pipeline

```
Application (Micrometer) ──> /actuator/prometheus ──> Prometheus (scrape) ──> Grafana (dashboards)
                                                          │
                                                    Alertmanager ──> PagerDuty / Slack
```

**Prometheus scrape configuration** uses Kubernetes service discovery to find all bookstore API pods, scrape metrics from port 8081, and filter for relevant metric namespaces (JVM, Tomcat, HikariCP, HTTP, Logback, Messaging).

#### Tracing Pipeline

```
Application (OpenTelemetry) ──> OTLP gRPC ──> Tempo (distributed tracing backend) ──> Grafana (trace explorer)
```

Tempo is configured with:
- S3 backend for long-term trace storage
- 48h block retention
- Rate limiting: 15 MB/s ingestion
- OTLP receiver on port 4317

#### Grafana Dashboards

Dashboards are provisioned automatically via ConfigMaps:
- JVM (Micrometer) — heap, GC, threads, classes
- Tomcat — request throughput, error rate, latency
- HikariCP — connection pool utilization
- HTTP — request rate, p50/p95/p99 latency
- Messaging — Kafka producer/consumer metrics
- Kubernetes — pod status, resource usage, cluster health

### Disaster Recovery

#### Recovery Priority

1. **PostgreSQL** — most critical (order data, user data, inventory)
2. **Redis** — cache can be rebuilt (data loss = performance impact, not data loss)
3. **Kafka** — durable topics provide replay capability
4. **Prometheus** — metric data (valuable but not business-critical)
5. **Grafana** — dashboards can be reprovisioned from ConfigMaps
6. **Tempo** — trace data (lowest priority)

#### Backup Strategy

| Component | Backup Method | Frequency | Retention |
|-----------|--------------|-----------|-----------|
| PostgreSQL | Automated snapshots (RDS/Aurora) + pg_dump | Daily snapshots + continuous WAL archiving | 30 days |
| Redis | RDB snapshots (ElastiCache automated) | Hourly | 7 days |
| Kafka | Topic replication + S3 sink connector | Continuous | 72h retention + archival |
| Prometheus | PVC snapshot or Thanos | Real-time (Thanos) | 30 days |
| Grafana | ConfigMap-based provisioning (stateless) | On deploy | N/A |
| Tempo | S3 backend with lifecycle policies | Continuous | 48h retention |

#### Recovery Procedures

**Database corruption**:
1. Restore latest RDS snapshot to new instance
2. Apply WAL logs up to point of failure
3. Update DATASOURCE_URL in ExternalSecret
4. Rolling restart of bookstore pods

**Full cluster failure**:
1. Restore Kubernetes cluster from backup (Velero)
2. Apply Kustomize manifests (`kubectl apply -k k8s/`)
3. Update ExternalSecret with managed service endpoints
4. Verify rollout: `kubectl rollout status deployment/bookstore`

**Region failure** (future):
1. Route traffic to secondary region via DNS (Route53 / Azure DNS / Cloud DNS)
2. Promote read-replica to primary in secondary region
3. Deploy manifests to secondary cluster
4. Update connection strings to point to secondary region

### CI/CD Pipeline

```
                    ┌──────────────────┐
                    │  Code Push / PR  │
                    └────────┬─────────┘
                             │
                    ┌────────▼─────────┐
                    │  Static Analysis  │  Checkstyle, PMD, SpotBugs
                    └────────┬─────────┘
                             │
                    ┌────────▼─────────┐
                    │  Build & Test    │  Maven verify, JaCoCo coverage
                    └────────┬─────────┘
                             │
                    ┌────────▼─────────┐
                    │  Security Scan   │  OWASP Dependency Check, CodeQL
                    └────────┬─────────┘
                             │
                    ┌────────▼─────────┐
                    │  Quality Gate    │  All checks must pass
                    └────────┬─────────┘
                             │
                    ┌────────▼─────────┐
                    │  Docker Build    │  Multi-stage, Trivy scan, Cosign sign
                    └────────┬─────────┘
                             │
                    ┌────────▼─────────┐
                    │  Manifest Prep   │  Kustomize, Kubeconform, Checkov
                    └────────┬─────────┘
                             │
                    ┌────────▼─────────┐
                    │  Deploy          │  kubectl apply, rollout verify, smoke test
                    └──────────────────┘
```

#### Deployment Triggers

| Branch | Environment | Automatic | Rollback |
|--------|------------|-----------|----------|
| `main` | dev | Yes | Manual |
| `release/*` | staging | Yes | Manual |
| `stable/*` | prod | Yes (with approval) | Automatic on failure |
| Manual dispatch | Any | Via workflow_dispatch | N/A |

Deployment to production requires the GitHub Actions environment protection rule (approval gate).

### Testing & Validation

#### Validation Stages

| Stage | Tool | What it validates |
|-------|------|-------------------|
| Docker build | Docker CLI | Image builds successfully |
| Vulnerability scan | Trivy | No CRITICAL/HIGH CVEs in image |
| Image signing | Cosign | Image is signed with Sigstore |
| Manifest generation | Kustomize | Manifests build without errors |
| Schema validation | Kubeconform | Manifests conform to K8s API schema |
| Security scanning | Checkov | K8s security best practices (non-root, seccomp, etc.) |
| Deployment rollout | kubectl | Pods start and pass readiness probes |
| Smoke test | cURL | Health endpoint returns 200 |

#### Testing Commands

```bash
# Build and validate Docker image
docker build -t bookstore:test .
docker run --rm bookstore:test java -version

# Validate Kubernetes manifests
kustomize build k8s/ > /dev/null
kubeconform -strict -summary k8s/

# Checkov security scan
checkov -d k8s/ --framework kubernetes

# Local deployment test
kind create cluster
kubectl apply -k k8s/
kubectl -n bookstore rollout status deployment/bookstore

# Graceful shutdown test
kubectl -n bookstore delete pod -l app.kubernetes.io/name=bookstore
# Verify zero dropped requests during pod termination
```

### Future Capabilities

The architecture is designed for progressive upgrades without redesign:

| Capability | Prerequisites | Migration Path |
|-----------|--------------|----------------|
| GitOps (ArgoCD/Flux) | Kustomize structure | Point ArgoCD to `k8s/overlays/prod` |
| Service Mesh (Istio/Linkerd) | NetworkPolicies → mTLS | Add Istio sidecar injection annotation |
| Multi-cluster | Kustomize overlays | Create cluster-specific overlays |
| Multi-region | External DNS + Global Load Balancer | Add region-specific ConfigMaps |
| Blue-Green deployments | Service mesh or duplicate Deployments | Use Istio VirtualService for traffic switching |
| Canary deployments | Service mesh | Use Flagger with Prometheus metrics |
| Custom metrics scaling | Prometheus Adapter | Add Pods metric to HPA |
| Managed cloud services | Cloud provider | Update secret store references |
| API Gateway (Kong/AWS API GW) | Ingress changes | Add API Gateway before Ingress |
