# Project Architecture — Bookstore Platform

> Comprehensive architectural reference for the Bookstore API — a Spring Boot 4 / Java 21
> monolith with event-driven internal communication, Kafka integration, Redis caching and
> rate-limiting, PostgreSQL persistence, and full Kubernetes deployment support.

---

## 1. Executive Summary

The Bookstore Platform is a single Spring Boot application (`bookstore`, 0.0.1-SNAPSHOT)
that exposes a RESTful e-commerce API for managing books, shopping carts, orders, payments,
and inventory. It combines a traditional request/response web tier with an asynchronous,
event-driven backbone: domain events are persisted to an **Outbox** table inside the same
database transaction, then polled and published to **Apache Kafka** by a scheduled publisher.
A second set of **Kafka consumers** reacts to those events (order lifecycle, payments,
inventory) and an optional **notification pipeline** renders and sends emails — either from
a local event handler (test/CI) or from the `notifications.events` topic consumer (prod).

**Key characteristics:**

| Dimension | Decision |
|---|---|
| Architecture | Monolithic Spring Boot app, layered (controllers → services → repositories) |
| Internal communication | Synchronous calls within a transaction; asynchronous via Kafka + Outbox |
| Deployment | Single Docker image, K8s base + 3 overlays (dev/staging/prod), Kustomize |
| Concurrency control | Pessimistic write locks on inventory; `@Version` optimistic locks on Order/Payment |
| Reliability | Idempotency keys in every outbox event; dead-letter queue; retry with backoff |
| Security | Stateless JWT auth + RBAC; bcrypt; fail-open rate limiter |
| Observability | OpenTelemetry traces → Tempo; Micrometer → Prometheus; structured logging |

The repository layout and CI/CD pipeline are documented in §3, §10, and §12.

---

## 2. Technology Stack

### 2.1 Runtime & Framework

| Layer | Technology | Version |
|---|---|---|
| Language | Java (LTS) | 21 |
| Framework | Spring Boot | 4.0.7 (Spring Framework 7.0.8, Spring Security 7.0.6) |
| Build tool | Apache Maven (via wrapper) | 3.9+ enforced |
| Servlet container | Embedded Tomcat | 11.0.25 (patched) |
| JSON | Jackson | 2.21.4 |
| Validation | Jakarta Bean Validation | via `spring-boot-starter-validation` |
| Code generation | Lombok | 1.18.34 (provided) |
| Mapping | — (manual mapping in services; DTOs mapped by hand) | |
| Retry | Spring Retry | 2.0.11 |
| Reactive netty (transitive) | Netty | 4.2.17.Final (patched CVE) |

### 2.2 Infrastructure

| Component | Technology | Notes |
|---|---|---|
| Database | PostgreSQL | 16 (docker-compose), managed (prod) |
| Migrations | Flyway | 11.x (manual config — see §9.3) |
| Cache / session store | Redis | 7 (docker-compose), managed (prod) |
| Message broker | Apache Kafka | 4.1.2 (client), `apache/kafka:4.0.0` image (compose) |
| Container runtime | Docker | Multi-stage, Alpine-based |
| Orchestration | Kubernetes | Kustomize base + overlays |
| Tracing | OpenTelemetry | Java agent injected in Dockerfile |
| Metrics | Micrometer → Prometheus | `/actuator/prometheus` |
| Structured logging | Logback + OTel appender | OTel log-to-trace correlation |

### 2.3 Dependencies (key)

From `pom.xml`:

**Application:** `spring-boot-starter-web`, `spring-boot-starter-data-jpa`,
`spring-boot-starter-security`, `spring-boot-starter-validation`,
`spring-boot-starter-actuator`, `spring-boot-starter-cache`,
`spring-boot-starter-data-redis`, `spring-boot-starter-kafka`,
`spring-boot-starter-mail`, `flyway-core` + `flyway-database-postgresql`,
`jjwt` (0.12.3), `bucket4j-core` (8.10.1), `micrometer-registry-prometheus` +
`micrometer-core`, `opentelemetry-api` + `opentelemetry-instrumentation-annotations`,
`spring-retry` (2.0.11), `springdoc-openapi-starter-webmvc-ui` (3.0.3), `lombok` (1.18.34).

**Test:** `spring-boot-starter-test`, `spring-boot-starter-webmvc-test`,
`spring-security-test`, `testcontainers` + `testcontainers-postgresql` +
`testcontainers-junit-jupiter` (2.0.5), `spring-boot-starter-kafka-test`,
`h2` (4.x, test scope).

### 2.4 Maven Profiles (build-time)

| Profile | Purpose | Effect |
|---|---|---|
| **default** | Normal build | Excludes `@Tag("performance")` tests via Surefire `excludedGroups` |
| `performance` | Run perf suite only | `groups=performance`, `forkCount=1`, `reuseForks=false` |
| `chaos` | Run chaos suite only | `groups=chaos`, single fork |
| `ci` | CI gate | Adds Checkstyle, PMD, SpotBugs, OWASP Dependency-Check |

Quality-gate thresholds (JaCoCo, enforced in `verify` phase):
**60 % line coverage, 50 % branch, 60 % method, 80 % class.**

---

## 3. Project Structure & Module Layout

This is a single-module Maven project. The complete source tree is organized by feature
layer, not by Spring's traditional `controller/service/repository` verticals only — there
are also dedicated packages for cross-cutting concerns (`config`, `event`, `messaging`,
`notification`, `metrics`, `exception`).

```
src/
├── main/
│   ├── java/com/example/bookstore/
│   │   ├── BookstoreApplication.java          # @SpringBootApplication, @EnableCaching, @EnableRetry
│   │   ├── controller/                        # 7 REST controllers (§5.3)
│   │   │   ├── OrderController.java
│   │   │   ├── PaymentController.java
│   │   │   ├── BookController.java
│   │   │   ├── CartController.java
│   │   │   ├── AuthController.java
│   │   │   ├── AdminController.java
│   │   │   ├── AdminPaymentController.java
│   │   │   ├── AdminInventoryController.java
│   │   │   └── dto/                           # Request/response DTOs (20+ classes)
│   │   ├── service/                           # Business logic (§5.2)
│   │   │   ├── OrderService.java
│   │   │   ├── PaymentService.java
│   │   │   ├── InventoryService.java
│   │   │   ├── InventoryValidator.java
│   │   │   ├── InventoryAdminService.java
│   │   │   ├── BookService.java
│   │   │   ├── CartService.java
│   │   │   ├── UserService.java
│   │   │   └── AuditService.java
│   │   ├── repository/                        # Spring Data JPA interfaces (§5.4)
│   │   ├── model/                             # JPA entities & enums (§4)
│   │   ├── config/                            # Spring & infra configuration (§5.1)
│   │   │   ├── jwt/                           # JWT utilities
│   │   │   └── OpenApiConfig.java
│   │   ├── event/                             # Domain events & outbox (§7)
│   │   │   ├── publisher/                     # Outbox polling publisher
│   │   │   └── repository/                    # EventOutboxRepository, ProcessedEventRepository
│   │   ├── messaging/                         # Kafka consumers, envelope, idempotency (§7)
│   │   ├── notification/                      # Email, templates, async handlers (§7)
│   │   ├── metrics/                           # BusinessMetrics, MessagingMetrics
│   │   ├── exception/                         # GlobalExceptionHandler, ErrorResponse
│   │   ├── service/payment/                   # PaymentProvider SPI + DummyPaymentProvider
│   │   └── filter/                            # CorrelationIdFilter
│   └── resources/
│       ├── application.yml                    # Base config
│       ├── application-{dev,staging,prod,perf,test,test-e2e,perf-test}.yml
│       ├── db/migration/                      # Flyway V1–V8
│       └── templates/email/                   # HTML email templates
└── test/
    ├── base/                                  # Abstract test base classes
    ├── builder/TestDataBuilder.java
    ├── controller/                            # Controller unit tests
    ├── service/                               # Service unit & integration tests
    ├── repository/                            # Repository & Flyway tests
    ├── config/                                # Security, rate-limiter, exception tests
    ├── event/                                 # Outbox & Kafka integration tests
    ├── messaging/                             # Consumer idempotency & integration tests
    ├── notification/                          # Email & notification tests
    ├── model/                                 # Model unit tests
    ├── security/                              # Security & abuse-protection tests
    ├── operational/OperationalReadinessTest.java
    ├── performance/                           # Performance baseline tests
    └── chaos/                                 # Chaos engineering tests
```

**Root-level infrastructure files:**

| File | Role |
|---|---|
| `pom.xml` | Maven declaration + build plugins + 3 profiles (`ci`, `performance`, `chaos`) |
| `Dockerfile` | 3-stage build: dependencies → builder → runtime (JRE, non-root, ZGC, OTel) |
| `docker-compose.yml` | PostgreSQL, Redis, Kafka (KRaft), Kafka-UI, Prometheus, Tempo, Grafana |
| `k8s/` | 13 base manifests + `overlays/{dev,staging,prod}` + `monitoring/` |
| `k8s/tests/deployment-validation.sh` | 40-assertion manifest/infra validation script |
| `.github/workflows/` | 6 reusable CI/CD workflows (see §10.3) |
| `prometheus.yml`, `tempo.yml` | Local compose observability config |
| `grafana/` | Datasource provisioning + dashboards (app + cluster) |
| `perf/` | k6 test scenarios + PowerShell runner scripts |
| `checkstyle.xml`, `pmd-ruleset.xml`, `spotbugs-exclude.xml`, `owasp-suppressions.xml` | Static-analysis rules |

---

## 4. Domain Model

### 4.1 Bounded Contexts

Although deployed as a monolith, the model cleanly separates four business domains plus
supporting cross-cutting concerns:

| Domain | Aggregate Root | Key Entities |
|---|---|---|
| **Catalog** | `Book` | `Book` |
| **Order** | `Order` | `Order`, `OrderItem`, `OrderStatusHistory`, `OrderStatus` |
| **Payment** | `Payment` | `Payment`, `PaymentHistory`, `PaymentStatus` |
| **Inventory** | `Inventory` | `Inventory`, `InventoryHistory`, `InventoryStatus`, `InventoryEventType` |
| **Identity** | `AppUser` | `AppUser`, `UserRole` |
| **Cart** | `Cart` | `Cart`, `CartItem` |
| **Notification** | `EmailLog` | `EmailLog`, `EmailStatus` |
| **Messaging** | `EventOutbox` | `EventOutbox`, `OutboxStatus`, `ProcessedEvent` |

### 4.2 Order Aggregate

Located in `model/Order.java`, `model/OrderItem.java`, `model/OrderStatus.java`,
`model/OrderStatusHistory.java`.

**`Order`** fields: `id` (`@Id @GeneratedValue`), `user` (`@ManyToOne AppUser`),
`status` (`@Enumerated STRING`), `totalPrice` (`BigDecimal`), `createdAt`, `updatedAt`,
`items` (`@OneToMany(cascade = ALL)` ordered items), `@Version version` (optimistic lock).

**`OrderItem`** fields: `book` (`@ManyToOne`), `quantity`, `unitPrice`, `subtotal`;
`recalculateSubtotal()` computes `unitPrice × quantity`.

**OrderStatus state machine** (`model/OrderStatus.java:14`):

```
PENDING ──→ PAID ──→ PROCESSING ──→ SHIPPED ──→ DELIVERED   (forward, terminal=DELIVERED)
  │          │
  │          └── CANCELLED  (terminal)
  └── CANCELLED          (cancelable from PENDING | PAID)
```

- `PENDING.canTransitionTo` → `{PAID, CANCELLED}`
- `PAID.canTransitionTo` → `{PROCESSING, CANCELLED}`
- `PROCESSING.canTransitionTo` → `{SHIPPED}`
- `SHIPPED.canTransitionTo` → `{DELIVERED}`
- `DELIVERED`, `CANCELLED` → terminal
- `isCancelable()` → `PENDING` or `PAID`

Each `Order.recordTransition(target, changedBy, reason)` creates an `OrderStatusHistory` row.

### 4.3 Payment Aggregate

`model/Payment.java`, `model/PaymentStatus.java`, `model/PaymentHistory.java`.

**PaymentStatus state machine** (`model/PaymentStatus.java:15`):

```
PENDING ──→ PROCESSING ──→ COMPLETED ──→ REFUND_PROCESSING ──→ REFUNDED (terminal)
   │                    └── FAILED ──→ PENDING  (retryable)
   └── CANCELLED         (terminal)
```

- `PENDING` → `{PROCESSING, CANCELLED}`
- `PROCESSING` → `{COMPLETED, FAILED}`
- `COMPLETED` → `{REFUND_PROCESSING}`
- `REFUND_PROCESSING` → `{REFUNDED, FAILED, COMPLETED}` (FAILED/COMPLETED = refund failed, payment restored)
- `FAILED` → `{PENDING}` (retry)
- `CANCELLED`, `REFUNDED` → terminal

### 4.4 Inventory Aggregate

`model/Inventory.java`, `model/InventoryStatus.java`, `model/InventoryHistory.java`,
`model/InventoryEventType.java`.

**Inventory** fields: `bookId` (unique), `availableQuantity`, `reservedQuantity`,
`lastUpdated`, `@Version version`. `getTotalQuantity()` = `availableQuantity + reservedQuantity`.

State mutation methods on `Inventory`:
- `reserve(qty)` — `available -= qty; reserved += qty` (throws if `available < qty`)
- `release(qty)` — `reserved -= qty; available += qty` (throws if `reserved < qty`)
- `finalizeReservation(qty)` — `reserved -= qty` (sold; total shrinks, items consumed)
- `restore(qty)` — `available += qty` (replenishment, e.g. refund)
- `adjustAvailable(newQty)` — set to absolute value (manual admin adjustment)

**InventoryEventType**: `CREATED`, `RESERVED`, `RELEASED`, `FINALIZED`, `RESTORED`, `ADJUSTED`.

**InventoryStatus** (`model/InventoryStatus.java`): `AVAILABLE`, `LOW_STOCK`, `OUT_OF_STOCK`,
determined by `InventoryValidator.determineStatus(available)` using threshold config
(`app.inventory.thresholds.low` = 10, `app.inventory.thresholds.critical` = 3).

### 4.5 Cross-Cutting Models

`AppUser` (`model/AppUser.java`): `id`, `username` (unique, not null), `password`
(bcrypt, not null), `email` (`@Email`; not unique), `role` (`UserRole.ROLE_USER` /
`UserRole.ROLE_ADMIN`, default `UserRole.ROLE_USER`). Implements `UserDetails`;
`equals/hashCode` only on `id`. No `address` field or `createdAt`/`updatedAt` columns exist
(the `app_user` table in V1 has only `id`, `username`, `password`, `email`, `role`).

`Book` (`model/Book.java`): `id`, `title`, `author`, `price`, `category`, `stock`
(`@Deprecated` — the `inventory` table is the source of truth; see §9.4 V6), `@Version version`.
No `isbn`, `stockQuantity`, `coverImageUrl`, `createdAt`/`updatedAt` fields or columns exist.
The response DTO `BookResponse` still exposes `stock` for backward compatibility, alongside
`availableQuantity` and `inventoryStatus` derived from `Inventory` (§6.5).

`Cart` (`model/Cart.java`): `id`, `user` (`@OneToOne`), `items` (`@OneToMany`).
`CartItem` (`model/CartItem.java`): `book`, `quantity`, `priceAtAdd` (captured at add-time
so price drift doesn't affect pending carts).

`EventOutbox` (`model/EventOutbox.java`): `id`, `aggregateType`, `aggregateId`, `eventType`,
`payload` (JSON), `status` (`PENDING`/`PUBLISHED`/`FAILED`), `createdAt`, `publishedAt`,
`retryCount`, `maxRetries`, `traceId`, `spanId`. `@Version` for optimistic concurrency on retry
increments.

`ProcessedEvent` (`model/ProcessedEvent.java`): `id`, `aggregateId`, `eventType`,
`processedAt`, `consumerName` — primary key is `(aggregateId, eventType, consumerName)` to
guarantee idempotent consumption.

`EmailLog` (`model/EmailLog.java` / `email/EmailStatus`): `id`, `recipient`, `template`,
`eventType`, `status` (`QUEUED`/`SENT`/`FAILED`/`SKIPPED`), `sentAt`, `failureReason`.

---

## 5. Architecture & Layering

### 5.1 Configuration Layer

**Entry point:** `BookstoreApplication.java:28`
```java
@SpringBootApplication
@EnableCaching
@EnableRetry
public class BookstoreApplication { ... }
```

Key configuration classes in `config/`:

| Class | Responsibility |
|---|---|
| `SecurityConfig.java` | Security filter chain, RBAC, CORS, BCrypt, headers |
| `config/jwt/JwtUtil.java` | JWT creation, validation, parsing (jjwt 0.12.3) |
| `config/jwt/JwtAuthenticationFilter.java` | `OncePerRequestFilter` that extracts/validates Bearer tokens |
| `config/SecurityUtils.java` | Resolves the authenticated `AppUser` from `SecurityContext` |
| `CacheConfig.java` | Redis-backed `RedisCacheManager` with per-cache TTLs; fail-open `CacheErrorHandler` |
| `RateLimitingConfig.java` | `FilterRegistrationBean` (order `HIGHEST_PRECEDENCE+1`) wrapping a sliding-window Redis limiter |
| `config/RedisRateLimiter.java` | Custom Redis sorted-set sliding-window limiter (atomic Lua script) |
| `config/RedisRateLimiterConfig.java` | Wires the `RedisRateLimiter` bean |
| `KafkaConfig.java` | Producer/Consumer factory beans, `DefaultErrorHandler` with DLQ, 3 listeners |
| `KafkaTopicConfig.java` | Topic creation (5 topics, 3 partitions each except notifications/DLT) |
| `FlywayConfig.java` | **Manual** Flyway bootstrap (see §9.3) |
| `CorrelationIdFilter.java` | Generates/propagates `X-Correlation-Id` header |
| `GlobalExceptionHandler.java` | `@RestControllerAdvice`; maps exceptions → `ErrorResponse` |
| `error/ErrorResponse.java` | Structured error body (status, error, message, code, timestamp, path) |
| `ActuatorConfig.java` | Exposes health, info, metrics, prometheus, env, mappings, threaddump |
| `OpenApiConfig.java` | OpenAPI 3 + Swagger UI, JWT bearer-auth scheme |
| `InventoryProperties.java` | `@ConfigurationProperties(prefix="app.inventory")` thresholds |
| `AsyncConfig.java` | Task executor for async email/notification |

### 5.2 Service Layer (Business Logic)

All services are `@Transactional` on mutation methods and use `@WithSpan` (OpenTelemetry)
for tracing. See §6 for flow details.

| Service | Responsibilities |
|---|---|
| `OrderService` | Create order (cart→order→reserve inventory→create payment→clear cart→publish event), status updates, cancellation (customer & admin) |
| `PaymentService` | Create payment record, process via `PaymentProvider`, refund; state-driven transitions + event publishing |
| `InventoryService` | Reserve/release/finalize/restore stock; pessimistic locking + retry; cache eviction on mutation |
| `InventoryValidator` | Validate reservation/release/finalization preconditions; determine stock status |
| `InventoryAdminService` | Bulk adjustments, low-stock queries |
| `BookService` | CRUD on books; `@Cacheable` on `books` / `bookById` caches |
| `CartService` | Add/update/remove items, get/clear cart; captures price-at-add |
| `UserService` | Register user, look up by username |
| `AuditService` | Persist audit trail of consumed domain events |

### 5.3 Controller Layer (API)

All controllers are under `/api/v1/…`. All mutation endpoints require `ROLE_USER` or
`ROLE_ADMIN`; public read endpoints (book listing) are permitted without auth.

| Controller | Base path | Methods |
|---|---|---|
| `OrderController` | `/api/v1/orders` | POST (create), GET (list user orders), GET /{id}, PUT /{id}/status (admin), POST /{id}/cancel |
| `PaymentController` | `/api/v1/payments` | POST (process payment), POST /{id}/refund (admin), GET (list), GET /{id}, GET /order/{orderId} |
| `BookController` | `/api/v1/books` | GET (list/search/category — public), POST/PUT/DELETE (admin), GET /{id} (public), PATCH /{id}/stock (admin) |
| `CartController` | `/api/v1/cart` | GET, POST /items, PUT /items/{id}, DELETE /items/{id}, DELETE (clear) |
| `AuthController` | `/api/v1/auth` | POST /register, POST /login |
| `AdminController` | `/api/v1/admin` | User/role management endpoints |
| `AdminPaymentController` | `/api/v1/admin/payments` | Admin payment operations |
| `AdminInventoryController` | `/api/v1/admin/inventory` | Admin inventory operations |

All controllers use the DTO layer (`controller/dto/`) for request/response contracts;
no JPA entities are serialized directly over the API.

### 5.4 Repository Layer

Spring Data JPA interfaces in `repository/` — 11 repositories:

`BookRepository`, `CartRepository`, `CartItemRepository`, `OrderRepository`,
`OrderStatusHistoryRepository`, `PaymentRepository`, `PaymentHistoryRepository`,
`InventoryRepository`, `InventoryHistoryRepository`, `UserRepository` (+ its read interface),
and `EventOutboxRepository` / `ProcessedEventRepository` (under `event/repository/`).

Key custom methods:
- `OrderRepository.findByIdAndUserId` — prevents IDOR in user-facing endpoints
- `OrderRepository.findByUserId` (paginated)
- `OrderRepository.findByStatus` (paginated)
- `InventoryRepository.findByBookIdWithLock` — `PESSIMISTIC_WRITE` (`LockLevel`) for safe
  reservation
- `EventOutboxRepository.findPendingEvents(maxRetries)` — queries the outbox for
  `PENDING` events with `retryCount < maxRetries`, ordered by creation time
- `EventOutboxRepository.markPublished(id, instant)` / `markFailed(id, instant)` /
  `incrementRetry(id)`
- `ProcessedEventRepository` — deduplication checks in idempotent consumers

**Note:** There is no `OrderItemRepository` — `OrderItem` is an `@Embeddable`-style child
entity managed exclusively through its parent `Order` (cascade `ALL`), accessed via
`Order.getItems()`.

---

## 6. Key Business Flows

### 6.1 Checkout (Place Order)

```
Customer → POST /api/v1/orders
```

1. `OrderController` calls `OrderService.createOrder()`.
2. `OrderService` (`OrderService.java:72`):
   a. Fetches the cart via `CartService.getCart()`.
   b. **Stock check** (read-only, cached): `inventoryService.getAvailableQuantity(bookId)`
      for each line item — throws `IllegalStateException` if any item is short.
   c. Creates the `Order` entity (status `PENDING`), maps cart items → `OrderItem`s,
      computes `totalPrice` via `order.calculateTotal()`.
   d. Saves the order (flush so the PK is assigned).
   e. **Reserve inventory** via `inventoryService.reserveAll(orderId, reservations, user)`
      — uses `PESSIMISTIC_WRITE` row lock + `@Retryable` (§4.3).
   f. Creates a `Payment` record (status `PENDING`).
   g. Clears the cart.
   h. Records metrics (`BusinessMetrics.recordOrderCreated`, `recordBooksSold`,
      `recordCheckoutDuration`).
   i. **Publishes `OrderCreatedEvent`** through `DomainEventPublisher` → Outbox table
      (same transaction).
3. The `OutboxPublisherScheduler` (§7.1) later publishes the event to Kafka.

### 6.2 Payment Processing

```
Customer → POST /api/v1/payments
```

1. `PaymentController` → `PaymentService.processPayment(orderId, method)`.
2. `PaymentService.initializeProcessing()` (`PaymentService.java:87`):
   - Loads `Payment` by order ID → `getPaymentByOrderId`.
   - Validates `payment.getPaymentStatus().canTransitionTo(PROCESSING)`.
   - Records transition → `PROCESSING`, saves.
3. Adapter calls `DummyPaymentProvider.processPayment(payment)` → returns
   `PaymentProviderResponse` (success/failure + transaction ID).
4. `completeProcessing()` (`PaymentService.java:103`):
   - **Reloads** payment to detect concurrent status change (optimistic re-check).
   - **On success:** transition → `COMPLETED`, set `order.status → PAID`,
     `inventoryService.finalizeAllReservationsForOrder` (deduct from reserved),
     publish `PaymentCompletedEvent`.
   - **On failure:** transition → `FAILED`,
     `inventoryService.releaseAllReservationsForOrder` (return to available),
     publish `PaymentFailedEvent`.
   - Metrics: `recordPaymentCompleted` / `recordPaymentFailed`.

> `DummyPaymentProvider` (`service/payment/DummyPaymentProvider.java`) is a synchronous
> stub (`getName()` returns `"DUMMY"`); in production it is swapped behind the
> `PaymentProvider` SPI.

### 6.3 Order Cancellation — Customer

```
Customer → POST /api/v1/orders/{id}/cancel
```

1. `OrderService.cancelOwnOrder()` (`OrderService.java:147`):
   - Fetches order scoped to current user (IDOR-safe via `findByIdAndUserId`).
   - Checks `order.getStatus().isCancelable()` (PENDING or PAID).
   - Records transition → `CANCELLED`, persists.
   - Calls `releaseInventoryForOrder()`:
     - If the payment was **PAID** → `restoreAllStockForOrder` (increases `available`,
       item was finalized — stock physically returns).
     - If still **PENDING** (payment never completed) → `releaseAllReservationsForOrder`
       (returns reserved → available).
   - Metrics: `recordOrderCancelled`.
   - Publishes `OrderStatusChangedEvent` (eventType = `ORDER_STATUS_CHANGED`).

### 6.4 Order Cancellation — Admin

`OrderService.adminCancelOrder()` (`OrderService.java:215`) is similar but:
- Uses `getOrderById(orderId)` (no user scoping) since it's an admin operation.
- Publishes `OrderStatusChangedEvent` with eventType `ORDER_CANCELLED` (distinct from
  the customer path's `ORDER_STATUS_CHANGED`), so the notification handler routes the
  admin-cancellation email template correctly.

### 6.5 Inventory State Lifecycle

```
             ┌─────────────┐
   created → │ AVAILABLE   │
             └─────┬───────┘
        reserve    │  (available ↓, reserved ↑)
                   ▼
            ┌──────────────┐
            │ RESERVED     │
            └──┬─────┬─────┘
   finalize /  │     │ release
   payment OK   │     │ (payment fail / order cancel)
        │       │     ▼
        │       │  ┌─────────────┐
        │       │  │ AVAILABLE   │ (back to pool)
        │       │  └─────────────┘
        ▼       │
   ┌──────────┐ │          restore (post-refund)
   │ FINALIZED│ │          ┌─────────┐
   │ (sold)   │ │          ▼         │
   └──────────┘          ┌────────────┐
                         │ AVAILABLE  │ (stock added)
                         └────────────┘
```

- `reserveAll` sorts entries by `bookId` before locking — a **deadlock-prevention** measure
  so concurrent orders lock rows in a deterministic global order.
- Each mutation records an `InventoryHistory` entry with before/after available & reserved,
  the `InventoryEventType`, order/payment ID, actor, and reason.
- `InventoryStatus` (AVAILABLE / LOW_STOCK / OUT_OF_STOCK) is derived from
  `getAvailableQuantity()` against `InventoryProperties` thresholds (§2.1, `InventoryProperties.java`).

### 6.6 Notification Delivery

Two mutually exclusive pathways, controlled by `app.notifications.delivery-mode`
(env-overridable via `NOTIFICATIONS_DELIVERY_MODE`):

| Mode | Handler | Trigger | Used by |
|---|---|---|---|
| `in-process` (default for tests) | `NotificationEventHandler` (`@EventListener`) | Local Spring events after DB commit | `test`, `test-e2e`, `perf-test` |
| `kafka` (default for prod/dev) | `NotificationEventConsumer` (`@KafkaListener`) | Consumes `orders.events`, `payments.events`, `notifications.events` topics | `dev`, `staging`, `prod`, `perf` |

The `@ConditionalOnProperty` annotations on each handler ensure only one is active per
profile, preventing duplicate emails.

---

## 7. Event-Driven Architecture

### 7.1 Outbox Pattern

To solve the dual-write problem (DB write + Kafka publish must be atomic), the app uses a
**transactional outbox**:

1. **Write phase:** `DomainEventPublisher.publish(event, aggregateType, aggregateId, eventType)`
   (`event/publisher/DomainEventPublisher.java`) — serializes the event to JSON, creates an
   `EventOutbox` row with status `PENDING`, and persists it inside the same `@Transactional`
   method that committed the business state. The outbox row carries a `traceId`/`spanId`
   captured from the active OpenTelemetry span.

2. **Poll & Publish phase:** `OutboxPublisherScheduler.publishPendingEvents()`
   (`event/publisher/OutboxPublisherScheduler.java:44`) — `@Scheduled(fixedDelayString =
   "${app.outbox.poll-interval:1000}")`, runs every 1 s by default. Within a `@Transactional`
   method it:
   - Queries `findPendingEvents(maxRetries)` (default 5).
   - Converts each to a `DomainEventEnvelope` via `EnvelopeConverter`.
   - Resolves the target topic via `resolveTopic(eventType)` (prefix match on
     `AGGREGATE_*`).
   - Builds a `ProducerRecord` and injects a W3C `traceparent` header
     (`00-{traceId}-{spanId}-01`) when valid.
   - Calls `kafkaTemplate.send(record).get()` (blocking, so the `@Transactional`
     context commits only on success).
   - On success → `markPublished(id, now)`. On failure → `incrementRetry(id)` / `markFailed`
     after exhausting retries.

3. **Cleanup:** `OutboxCleanupScheduler` purges successfully-published outbox events older
   than a retention window.

The scheduler is `@ConditionalOnProperty("spring.kafka.bootstrap-servers")` — when Kafka is
not configured (e.g. `test`, `test-e2e` profiles), the scheduler is absent and events are
delivered in-process only. This is documented in the CHANGELOG (§"Notification delivery mode").

### 7.2 Domain Events

All event type strings are centralized in `event/EventTypes.java`. The event classes live in
`event/` and extend `BaseEvent` (timestamp + `eventId` UUID + `correlationId`):

| Event class | Aggregate | EventType |
|---|---|---|
| `OrderCreatedEvent` | Order | `ORDER_CREATED` |
| `OrderStatusChangedEvent` | Order | `ORDER_STATUS_CHANGED` |
| | | `ORDER_CANCELLED` (used by admin cancel path) |
| `PaymentCompletedEvent` | Payment | `PAYMENT_COMPLETED` |
| `PaymentFailedEvent` | Payment | `PAYMENT_FAILED` |
| `PaymentRefundedEvent` | Payment | `PAYMENT_REFUNDED` |
| `InventoryReservedEvent` | Inventory | `INVENTORY_RESERVED` |
| `InventoryReleasedEvent` | Inventory | `INVENTORY_RELEASED` |
| `InventoryFinalizedEvent` | Inventory | `INVENTORY_FINALIZED` |
| `InventoryAdjustedEvent` | Inventory | `INVENTORY_ADJUSTED` |

### 7.3 Kafka Topology

| Topic | Partitions | Retention | Producer |
|---|---|---|---|
| `orders.events` | 3 | 72 h | `OrderService` |
| `payments.events` | 3 | 72 h | PaymentService |
| `inventory.events` | 3 | 168 h (7 days) | `InventoryService`, `InventoryAdminService` |
| `notifications.events` | 1 | 24 h | External/system producers (`NOTIFICATION_*` aggregate); consumed by `NotificationEventConsumer` |
| `dlt.events` | 1 | 7 days | `DefaultErrorHandler` recoverer → all consumers |

Producer config (`KafkaConfig.java:38`): `acks=all`, `retries=3`,
`max.in.flight.requests.per.connection=1` (preserves order), `enable.idempotence=true`,
`compression=snappy`, `linger.ms=5`, `batch.size=16 KB`, `request.timeout=30 s`,
`delivery.timeout=120 s`.

Consumer config (`KafkaConfig.java:63`): `auto.offset.reset=earliest`,
`enable.auto.commit=false` (manual ack), `max.poll.records=100`,
`max.poll.interval.ms=300000` (5 min), `listenConcurrency=3`.

Default error handler (`KafkaConfig.java:91`): `ExponentialBackOff(1000 ms, multiplier 2,
max 30 s, maxElapsed 300 s)`. Not-retryable:
`MessageConversionException`, `SerializationException`, `IllegalArgumentException`,
`IllegalStateException`. After retries exhausted → `DeadLetterService.sendToDlt` writes
to `dlt.events` topic.

### 7.4 Consumers & Idempotency

All consumers extend `AbstractIdempotentConsumer` (`messaging/consumer/AbstractIdempotentConsumer.java`),
which guarantees at-least-once delivery is made effectively-once via a `ProcessedEvent`
deduplication table. The pattern:

```java
@Override
protected void handleEvent(ConsumerRecord<String, String> record) {
    String dedupeKey = record.key() + ":" + record.headers()...;  // (aggregateId, eventType)
    if (processedEventRepository.existsById(...)) return;  // skip duplicate
    // ... process ...
    processedEventRepository.save(new ProcessedEvent(...));
    auditService.logEventConsumed(...);  // audit trail
}
```

| Consumer | `@KafkaListener` topics | Group | Action on event |
|---|---|---|---|
| `OrderEventConsumer` | `orders.events` | `orders-consumer` | No-op: records an audit trail via `AuditService.logEventConsumed`; idempotency de-dup + audit only, no domain side effects |
| `PaymentEventConsumer` | `payments.events` | `payments-consumer` | No-op: records an audit trail via `AuditService.logEventConsumed`; idempotency de-dup + audit only, no domain side effects |
| `InventoryEventConsumer` | `inventory.events` | `inventory-consumer` | No-op: records an audit trail via `AuditService.logEventConsumed`; idempotency de-dup + audit only, no domain side effects |
| `NotificationEventConsumer` | `orders.events`, `payments.events`, `notifications.events` | `notifications-consumer` | Renders & sends email per event type |

Each consumer runs at `listenConcurrency=3` (parallel across partitions).

### 7.5 Envelope & Idempotency

- `DomainEventEnvelope` (`messaging/DomainEventEnvelope.java`) — wraps `aggregateType`,
  `aggregateId`, `eventType`, `payload`, `eventId`, `correlationId`, `traceId`, `spanId`,
  `publishedAt`.
- `EnvelopeConverter` (`messaging/EnvelopeConverter.java`) — serializes/deserializes
  envelopes to/from JSON, converts `EventOutbox` ↔ `DomainEventEnvelope`.
- `MessagingMetrics` (`metrics/MessagingMetrics.java`) — instruments publish/publish-time,
  consume/latency, DLQ count.

---

## 8. Security Model

### 8.1 Authentication

- **JWT** (jjwt 0.12.3), HMAC-SHA256. `JwtUtil.generateToken(Authentication)` embeds
  `sub` (username), `roles` claim (comma-separated), `jti` (UUID), `iat`/`exp`.
  Default expiry 30 min (`jwt.expiration: 1800000` ms); 30 s clock skew tolerance.
  Secret must be ≥ 32 bytes (256 bits) — `JwtUtil.getSigningKey()` throws
  `IllegalStateException` otherwise.
- **Login flow:** `AuthController.login()` → authenticates via
  `AuthenticationManager` (bcrypt-verified) → returns `LoginResponse(token, expiresAt)`.
  Register flow: `UserService.register()` → persists bcrypt-hashed password → returns
  `AppUser` with `UserRole.ROLE_USER`.
- `JwtAuthenticationFilter` (OncePerRequest) extracts `Authorization: Bearer …`, validates,
  parses roles, and populates `SecurityContext`. Auth failures are **log-warn-only**
  (fail-open to let the request reach the secured endpoint where it gets a 401).

### 8.2 Authorization (RBAC)

Configured in `SecurityConfig.authorizeHttpRequests(...)` (`SecurityConfig.java:47`):

| Path pattern | Method | Access |
|---|---|---|
| `/api/v1/auth/**` | any | permitAll |
| `/swagger-ui/**`, `/v3/api-docs/**` | GET | permitAll |
| `/api/v1/books`, `/books/search`, `/books/category` | GET | permitAll |
| `/api/v1/books/**` | POST/PUT/DELETE | `ROLE_ADMIN` |
| `/api/v1/admin/**` | any | `ROLE_ADMIN` |
| `/api/v1/cart/**`, `/orders/**`, `/payments/**` | any | `ROLE_USER` |
| `/actuator/health/**`, `/actuator/info` | any | permitAll |
| `/actuator/prometheus` | GET | `ROLE_ADMIN` |
| `/actuator/**` | any | `ROLE_ADMIN` |
| *any other* | any | authenticated |

Roles are stored as `UserRole` enum (`ROLE_USER`, `ROLE_ADMIN`) on `AppUser`; the JWT `roles` claim is
parsed into `SimpleGrantedAuthority` objects in `JwtAuthenticationFilter.parseRoles()`.

### 8.3 CSRF & CORS

- **CSRF:** disabled (`csrf.disable()`) — this is a stateless JWT API; CSRF protection is
  not applicable.
- **CORS:** `CorsConfigurationSource` allows configurable origins
  (`app.cors.allowed-origins`), methods GET/POST/PUT/DELETE/PATCH/OPTIONS,
  headers `Authorization`, `Content-Type`, `X-Requested-With`, `Accept`, `Origin`,
  exposes `X-Correlation-Id`, `allowCredentials=true`, max-age 3600 s.
- **HSTS:** enforced (1 year, includeSubDomains), via `HttpStrictTransportSecurity`.

### 8.4 Rate Limiting

`RateLimitingConfig` (`config/RateLimitingConfig.java:18`) — a Servlet `Filter` registered
at `Ordered.HIGHEST_PRECEDENCE + 1` (after `CorrelationIdFilter`, before Spring Security).
Gates API traffic per client-IP + method + path-group using a Redis-backed **sliding-window**
counter implemented as an atomic Lua script in `RedisRateLimiter`:

- Key: `ratelimit:{ip}:{method}:{group}:{window-millis}`
- Script: `ZREMRANGEBYSCORE` (evict expired), `ZCARD` (count), if under limit `ZADD PEXPIRE`.
- **Fail-open:** if Redis is unavailable, the request is allowed (log-warn).
- Configurable via `app.rate-limiting.enabled` (default `true`, via ConfigMap `APP_RATE_LIMITING_ENABLED`).

| Path group | Limit / 60 s |
|---|---|
| auth (`/api/v1/auth/**`) | 5 |
| checkout (`/api/v1/orders`, `/payments`) | 20 |
| admin (`/api/v1/admin/**`) | 60 |
| api (`/api/v1/**`) | 100 |
| default (everything else) | 200 |

### 8.5 Security Headers

Via `SecurityConfig.headers()` (`SecurityConfig.java:38`): `X-Frame-Options: DENY`,
`X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`,
`Permissions-Policy: camera=(), microphone=(), geolocation=()`, HSTS, cache-control disabled.
Additionally enforced at the **Ingress level** (NGINX `nginx.ingress.kubernetes.io` annotations
in `k8s/ingress.yaml`): TLS 1.3 only, strong ciphers, HSTS, max body 10 MB.

### 8.6 Secret Management

- **Dev/test:** placeholders in `k8s/secret.yaml` and `application-dev.yml`.
- **Prod:** `ExternalSecret` resource (commented template in `k8s/secret.yaml`) backed by
  AWS Secrets Manager / Parameter Store, Azure Key Vault, or GCP Secret Manager. The in-cluster
  `Secret` is annotation-gated
  (`argocd.argoproj.io/compare-options: IgnoreExtraneous`).

---

## 9. Cross-Cutting Concerns

### 9.1 Caching

`CacheConfig` (`config/CacheConfig.java:30`) — `@EnableCaching` + `@ConditionalOnProperty`
gate (`app.cache.enabled`). Uses Redis `RedisCacheManager` with custom configs per named cache:

| Cache name | TTL | Nullable? |
|---|---|---|
| `books` | 10 min | no |
| `bookById` | 30 min | no |
| `inventory` | 1 min | no |
| `inventoryAvailable` | 30 s | no |
| *(default)* | 10 min | no |

Serialization uses a custom `ObjectMapper` with JavaTime + polymorphic type info (restricted
to `com.example.bookstore.model.*`, `java.util.*`, `java.time.*`, `java.math.*`).
A fail-open `CacheErrorHandler` logs and suppresses cache errors so a Redis outage doesn't
break business logic. `CacheEvict` on `inventory`/`inventoryAvailable` keys is fired on every
`reserve`/`release`/`finalize`/`restore` mutation (`InventoryService.java:92` etc.).

### 9.2 Concurrency & Locking

| Entity | Mechanism | Location |
|---|---|---|
| `Inventory` | `@Version` optimistic + `PESSIMISTIC_WRITE` (`LockLevel.PESSIMISTIC_WRITE`) in `findByBookIdWithLock` | `InventoryService.reserveAll`, `InventoryService.reserve` |
| `Order` | `@Version` optimistic lock | `OrderService` (relies on DB row lock for state consistency) |
| `Payment` | `@Version` optimistic lock | `PaymentService.completeProcessing` (reloads + re-checks status) |
| `EventOutbox` | `@Version` + retry counter | `OutboxPublisherScheduler` (concurrent poll safety) |

`@Retryable` (Spring Retry 2.0.11) is on every inventory mutation:
`@Retryable(retryFor={OptimisticLockingFailureException.class}, maxAttempts=3,
@Backoff(delay=100, multiplier=2))`. Lock exception types (`CannotAcquireLockException`,
`PessimisticLockingFailureException`, `LockTimeoutException`) are also covered in
`reserveAll`.

### 9.3 Flyway Migration (Manual Bootstrap)

`FlywayConfig.java:9` contains the sole migration trigger. **Spring Boot's Flyway
auto-configuration is inactive** — Flyway 11.x no longer ships the `DatabaseFactory` class
that `FlywayAutoConfiguration`'s condition requires (documented in the class javadoc and
verified empirically per the CHANGELOG: clean PostgreSQL container applies exactly 8
migrations in one pass with `success=true` in `flyway_schema_history`).

The config: `Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
.baselineOnMigrate(true).load().migrate()`. Enabled via
`@ConditionalOnProperty(prefix="spring.flyway", name="enabled", havingValue="true")`.
The Docker/K8s init container sets `-Dspring.flyway.enabled=true` to run migrations before
the app pod starts; the app container defaults to the profile's setting.

### 9.4 Flyway Migrations (V1–V8)

| Version | Description |
|---|---|
| V1 | `Initial schema` — `app_user`, `book`, `cart`, `cart_items`, `orders`, `order_items` |
| V2 | Seed demo data — admin/test users, `Book1`/`Book2` |
| V3 | Order status history — `order_status_history` |
| V4 | Payment tables — `payment`, `payment_history` |
| V5 | Email log — `email_log` |
| V6 | Inventory tables — `inventory`, `inventory_history` (`book.stock` deprecated; inventory is source of truth) |
| V7 | Event tables — `event_outbox`, `processed_events` |
| V8 | Add `span_id` column to `event_outbox` (enables W3C `traceparent` propagation; matches CHANGELOG V8) |

### 9.5 Correlation IDs & Request Tracing

`CorrelationIdFilter` (`filter/CorrelationIdFilter.java`) — a `OncePerRequestFilter` at
`HIGHEST_PRECEDENCE` that:
- Reads `X-Correlation-Id` from the incoming request; generates a `UUID` v4 if absent.
- Stores it in a `ThreadLocal` (MDC) so all log lines for the request are correlated.
- Adds it to the response header `X-Correlation-Id` (also in the CORS exposed-headers list
  in `SecurityConfig`).
- If an active OTEL span exists, the trace ID is used as the correlation ID instead.

### 9.6 Exception Handling

`GlobalExceptionHandler` (`exception/GlobalExceptionHandler.java`) — `@RestControllerAdvice`:
- Maps `EntityNotFoundException` → 404, `IllegalStateException` → 409,
  `InvalidPaymentStateException` / `PaymentException` → 400,
  `AccessDeniedException` → 403, `JwtException` → 401,
  `MethodArgumentNotValidException` → 400 with field errors.
- All errors return `ErrorResponse` (status, error, message, code, timestamp, path).

### 9.7 Async Processing

`AsyncConfig` (`config/AsyncConfig.java`) — a `ThreadPoolTaskExecutor` named
`"notification-task-executor"` with configurable pool size (core `app.async.pool-size`,
default 4, max 16, queue 100) for sending emails off the request thread. The in-process
notification path (`NotificationEventHandler`) is `@Async`-annotated.

---

## 10. Infrastructure & Deployment

### 10.1 Docker Image

`Dockerfile` — 3-stage build:

1. **`dependencies`** (`eclipse-temurin:21-jdk-alpine`): copies `pom.xml` + Maven wrapper,
   runs `dependency:resolve` to populate the layer cache. Invalidates only when `pom.xml`
   changes.
2. **`builder`** (`extends dependencies`): copies `src/`, runs `mvnw package -DskipTests`,
   copies the OTel javaagent (`target/agent/`) + renames JAR to `app.jar`.
3. **`runtime`** (`eclipse-temurin:21-jre-alpine`): creates non-root `bookstore` user,
   copies JAR + agent, sets `JAVA_TOOL_OPTIONS` (ZGC, OOM-exit, container support),
   `ENV` block for OTel defaults (service name `bookstore-api`, traces → Tempo via OTLP gRPC
   on `:4317`), exposes ports 8080 / 8081, `HEALTHCHECK` on `/actuator/health/liveness`,
   `STOPSIGNAL SIGTERM`, `ENTRYPOINT` uses `exec` form so Java is PID 1 for graceful shutdown.

### 10.2 Docker Compose (Local Dev)

`docker-compose.yml` brings up 7 services:
PostgreSQL 16 (port **5433** → 5432 to avoid conflict), Redis 7 (6379), Kafka 4.0.0 in
**KRaft mode** (9092 + 9093, single-node), Kafka-UI (8082), Prometheus (9090), Tempo
(3200/4317/4318), Grafana (3000, admin/admin). Volumes are named for persistence. All have
healthchecks.

### 10.3 Kubernetes

The `k8s/` directory contains 13 base manifests + 3 environment overlays + a monitoring
sub-chart:

**Base manifests:**
| File | Resource | Notes |
|---|---|---|
| `namespace.yaml` | Namespace `bookstore` | PodSecurity labels (baseline) |
| `serviceaccount.yaml` | ServiceAccount | Minimal Role/RoleBinding |
| `configmap.yaml` | ConfigMap | All Spring env vars (§2.1), OTel, Kafka, DB pool, inventory thresholds |
| `secret.yaml` | Secret + ExternalSecret | Dev placeholder + commented prod ExternalSecret |
| `priorityclass.yaml` | PriorityClass | `bookstore-critical`, `global` scheduling priority |
| `pdb.yaml` | PodDisruptionBudget | `minAvailable: 3` (base; overridden per-env) |
| `deployment.yaml` | Deployment | 3 replicas, RollingUpdate (maxSurge 1, maxUnavailable 0), anti-affinity across nodes/zones, init container (Flyway), 3 probes, non-root seccomp RuntimeDefault, resource limits 2 vCPU / 2 Gi |
| `service.yaml` | Service | ClusterIP, ports 8080 + 8081 (management) |
| `ingress.yaml` | Ingress (NGINX) | TLS 1.3, HSTS, security headers, 10 MB body limit |
| `hpa.yaml` | HorizontalPodAutoscaler | CPU target 70%, 3–30 replicas |
| `networkpolicy.yaml` | 5 NetworkPolicies | Default deny ingress/egress + allow lists for ingress-controller, Prometheus, DNS, external Postgres/Redis/Kafka, OTel collector, intra-namespace |
| `resourcequota.yaml` | ResourceQuota | Namespace-level limits |
| `podsecurity.yaml` | PodSecurityPolicy / PSA enforcement | `baseline` / `restricted` |
| `kustomization.yaml` | Kustomization | Base aggregation, image substitution (`your-registry/bookstore-api`) |

**Overlays** (`k8s/overlays/{dev,staging,prod}/kustomization.yaml`) patch the base:
| Env | replicas | CPU req/lim | Mem req/lim | Profile | CORS origin |
|---|---|---|---|---|---|
| dev | 1 | 500 m / 1 | 512 Mi / 1 Gi | (dev) | localhost:3000,8080 |
| staging | 2 | 1 / 2 | 1 Gi / 2 Gi | staging | staging.bookstore.example.com |
| prod | 5 | 1 / 2 | 1 Gi / 2 Gi | prod | bookstore.example.com |

Prod overlay additionally sets `DB_POOL_MAX_SIZE=30`, `DB_POOL_MIN_IDLE=10`,
`OTEL_TRACES_SAMPLER_ARG=0.5`, minAvailable PDB = 3, HPA min = 5, and podAntiAffinity weight = 100.

**Monitoring** (`k8s/monitoring/`) — Prometheus, Tempo, and Grafana deployments for
in-cluster observability, mirroring the local compose setup.

### 10.4 CI/CD Pipeline

`.github/workflows` — a **reusable** pipeline architecture (6 workflows):

```
ci.yml (entry point)
 ├── reusable-static-analysis.yml   → Checkstyle, PMD, SpotBugs
 ├── reusable-build.yml             → Maven verify -Pci, JaCoCo gate, upload artifacts
 ├── reusable-security.yml          → OWASP Dependency-Check (fail CVSS ≥ 7)
 ├── manifest-validate              → Kustomize build + kubeconform + Checkov
 ├── docker-validate (on main/release) → Docker build + Trivy CRITICAL/HIGH scan
 ├── quality-gate                   → Aggregates all statuses
 └── reusable-deploy.yml            → Build+push image (cosign sign), kustomize set image,
                                      kubectl apply, rollout status, smoke test, rollback
```

**Deployment triggers (branch → environment):**
- `main` push → **dev** (auto)
- `release/*` push → **staging** (auto)
- `stable/*` push → **prod** (auto)
- `workflow_dispatch` → manual (dev/staging/prod)

Each deployment requires passing **all** quality gates first. Deploy is gated by a
repo variable `DEPLOYMENT_ENABLED == 'true'`. The deploy workflow:
1. Builds + pushes multi-arch image with `docker/build-push-action`.
2. Runs **Trivy** scan (CRITICAL/HIGH, exit code 1).
3. Signs the image with **Cosign** (`sigstore/cosign-installer`).
4. Sets the image tag in the Kustomize overlay (`kustomize edit set image`).
5. Validates with **kubeconform** + **Checkov** (skips `CKV_K8S_12`, `CKV_K8S_21`).
6. `kubectl apply -k` → `rollout status` (5 min timeout) → smoke test (`curl /actuator/health`) →
   rollback on failure.

Java setup is factored into a custom composite action `.github/actions/java-setup/action.yml`
(JDK 21 Temurin, Maven cache, `chmod +x mvnw`).

### 10.5 Configuration Profiles

Spring profiles (from `application.yml` parent + `application-{profile}.yml`):

| Profile | Database | Redis | Kafka | Notifications | Notes |
|---|---|---|---|---|---|
| **dev** | Local Postgres (docker-compose) | Local | Local | kafka (default) | Debug logging, 10 % OTel sampling |
| **staging** | Managed (staging instance) | Managed | Managed | kafka | Prod-like, staging domains |
| **prod** | AWS RDS / external | ElastiCache / external | MSK / Confluent | kafka | 50 % OTel sampling, DB pool 30 min/10 idle |
| **perf** | Local or Testcontainers | Local | Local | in-process | k6 perf testing, minimal logging |
| **test** | Testcontainers PostgreSQL | Testcontainers Redis | Embedded Kafka (`@EmbeddedKafka`) | in-process | Default CI test profile |
| **test-e2e** | Testcontainers | Testcontainers | Embedded Kafka | in-process | Full end-to-end via MockMvc |
| **perf-test** | Testcontainers | Testcontainers | Embedded Kafka | in-process | Performance test suite |

The `in-process` notification mode is auto-selected in test profiles so no Kafka dependency
is needed for unit/integration tests.

---

## 11. Observability

### 11.1 Metrics (Prometheus)

Micrometer + `micrometer-registry-prometheus`. Metrics endpoint exposed at
`/actuator/prometheus` (port 8081). The `prometheus.yml` scrape config targets
`host.docker.internal:8080` in compose mode; in K8s, Prometheus discovers pods via the
`prometheus.io/scrape: "true"` / `prometheus.io/port: "8081"` annotations on the Deployment
(`k8s/deployment.yaml:56`).

**Custom business metrics** (`metrics/BusinessMetrics.java`):
- `bookstore.orders.created` (counter)
- `bookstore.orders.completed` (counter)
- `bookstore.orders.cancelled` (counter)
- `bookstore.books.sold` (counter, `book_id` tag)
- `bookstore.checkout.duration` (timer, in ms)
- `bookstore.payments.completed` / `.failed` / `.processed` / `.refunded` (counters)
- `bookstore.inventory.reservations` / `.releases` / `.finalizations` / `.restores` (counters)

**Messaging metrics** (`metrics/MessagingMetrics.java`):
- `bookstore.kafka.published` / `bookstore.kafka.publish.errors` (counters)
- `bookstore.kafka.consumer.lag` (gauge)
- `bookstore.kafka.dlq.count` (counter)
- `bookstore.kafka.consume.duration` (timer)

### 11.2 Tracing (OpenTelemetry + Tempo)

- **Java agent** injected in the Docker runtime stage (`opentelemetry-javaagent.jar`) —
  auto-instruments Spring Web, JDBC, Redis, Kafka.
- `@WithSpan` annotations on critical service methods (`OrderService.createOrder`,
  `PaymentService.processPayment`, `InventoryService.reserve`, etc.) for manual span creation.
- **Trace propagation across the outbox boundary:** `OutboxPublisherScheduler` injects a
  W3C `traceparent` header (`00-{traceId}-{spanId}-01`) on each `ProducerRecord` using the
  `traceId`/`spanId` stored on the `EventOutbox` row at event-creation time (`CHANGELOG.md`
  §"Trace propagation").
- **Consumer-side continuation:** `AbstractIdempotentConsumer` uses
  `W3CTraceContextPropagator` to continue the incoming trace unless a valid local span is
  already current. (Kafka 4.1.2 removed the 6-arg `ConsumerRecord` constructor — tests use the
  5-arg variant and `record.headers()`.)
- Tempo stores traces (48 h retention in compose; managed in prod). Grafana datasources
  configured in `grafana/datasources/tempo.yml` (OTLP gRPC on `:4317`) and
  `grafana/datasources/prometheus.yml`.

### 11.3 Health & Diagnostics

Spring Boot Actuator (`ActuatorConfig.java`) exposes:
- **`/actuator/health`** (GET, permit all) — `liveness`, `readiness`, `startup` sub-states.
  Includes DB (PostgreSQL), Redis, Kafka connection indicators.
- **`/actuator/info`** (GET, permit all) — build info (version, commit, branch, timestamp).
- **`/actuator/metrics`** — Micrometer metric names + tags.
- **`/actuator/prometheus`** — Prometheus scrape format (admin-only).
- **`/actuator/env`, `/actuator/mappings`, `/actuator/threaddump`, `/actuator/conditions`**
  (admin-only) — debugging.
- **`/actuator/scheduledtasks`** — lists `@Scheduled` jobs (e.g. outbox publisher, cleanup).

K8s probes (`k8s/deployment.yaml:199`):
- **Startup** → `/actuator/health/startup` (90 s failure threshold)
- **Readiness** → `/actuator/health/readiness` (Spring checks DB, Redis, Kafka, disk)
- **Liveness** → `/actuator/health/liveness` (app alive, not dependent-service healthy)

### 11.4 Logging

Logback configured for structured JSON output in `prod`; dev/test use colored console.
MDC includes `X-Correlation-Id` (from `CorrelationIdFilter`), `traceId`,
`spanId` (via OTel Logback encoder). OTel Logback instrumentation is enabled
(`OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_LOG_ATTRIBUTES=true`).

### 11.5 Dashboards

- `grafana/dashboards/bookstore-overview.json` — app-level metrics (orders/minute,
  payment success rate, checkout latency, inventory levels, rate-limit hits, Kafka lag).
- `grafana/dashboards/kubernetes/bookstore-application.json` — pod health, resource usage.
- `grafana/dashboards/kubernetes/kubernetes-cluster.json` — cluster-wide (node, network).

---

## 12. Testing Strategy

### 12.1 Test Pyramid

```
         ┌─────────────────────────────────┐
         │  E2E / Operational  (few)       │
         │  PurchaseFlowE2ETest            │
         │  OperationalReadinessTest       │
         └───────────┬─────────────────────┘
                     │  integration (Testcontainers)
         ┌───────────▼─────────────────────┐
         │  Integration (moderate)          │
         │  OrderWorkflowIntegrationTest   │
         │  KafkaConsumerIntegrationTest   │
         │  RedisCacheIntegrationTest      │
         │  DeadLetterServiceTest          │
         └───────────┬─────────────────────┘
                     │  unit (no container)
         ┌───────────▼─────────────────────┐
         │  Unit (many)                     │
         │  OrderServiceTest, Model tests   │
         │  RedisRateLimiterTest            │
         └─────────────────────────────────┘
```

### 12.2 Test Base Classes

| Abstract class | Scope | Provides |
|---|---|---|
| `AbstractIntegrationTest` | All integration tests | `@SpringBootTest`, Testcontainers PostgreSQL + Redis, `@Sql` data, test JWT helper |
| `AbstractE2ETest` | E2E | `@AutoConfigureMockMvc`, full filter chain, authenticated mock user |
| `AbstractControllerTest` | Controller unit tests | `@WebMvcTest` slice, mocked services |
| `AbstractPerformanceTest` | Perf tests | JMH-style timing harness |
| `AbstractChaosTest` | Chaos tests | Fault-injection utilities, container lifecycle hooks |

`TestDataBuilder` (`builder/TestDataBuilder.java`) — fluent fixture factory for `Order`,
`Payment`, `Inventory`, `Book`, `AppUser`, `Cart` test objects.

### 12.3 Test Suites (by category)

| Suite | Location | Tag | Tooling |
|---|---|---|---|
| Unit | `service/*Test.java`, `model/*Test.java`, `controller/*Test.java` | — | JUnit 5 + Mockito |
| Integration | `service/*IntegrationTest.java`, `repository/`, `config/*IntegrationTest.java` | — | Testcontainers (Postgres, Redis), `@EmbeddedKafka` |
| E2E | `controller/PurchaseFlowE2ETest.java` | `e2e` | MockMVC, full Spring context |
| Security | `security/` (8 test files) | — | Spring Security test, abuse-protection |
| Messaging | `messaging/consumer/*Test.java`, `event/OutboxKafkaIntegrationTest.java` | — | Spring Kafka test, embedded broker |
| Notification | `notification/` | — | Mock mail sender, template engine |
| Chaos | `chaos/` (6 test files) | `chaos` | Custom fault injection |
| Performance | `performance/` (6 sub-packages) | `performance` | JUnit + JMH, Testcontainers |
| Operational | `operational/OperationalReadinessTest.java` | — | Contract/structural assertions |

### 12.4 Chaos Engineering

`chaos/AbstractChaosTest.java` — base class for fault-injection tests that verify
resilience. Six concrete suites:
- `InventoryChaosTest` — concurrent reservation contention
- `KafkaChaosTest` — broker restart, message loss
- `OutboxAndDeadLetterChaosTest` — outbox corruption, DLQ processing
- `PaymentProviderChaosTest` — intermittent provider failure
- `PostgresChaosTest` — connection loss during transaction
- `RedisChaosTest` — cache miss/failure during rate limiting

### 12.5 Performance Testing

Two complementary tools:

1. **JUnit-based** (`performance/` package, tagged `performance`):
   - `PerformanceBaselineTest` — single-request latency baseline
   - `DatabasePerformanceTest` — query throughput under load
   - `InventoryConcurrencyPerformanceTest` — concurrent reservation stress
   - `KafkaOutboxPerformanceTest` — outbox publish throughput
   - `ObservabilityUnderLoadTest` — metrics/tracing correctness under load
   - `RateLimiterHttpPerformanceTest` — rate limiter behavior under concurrent requests
   - `RedisPerformanceTest` — Redis operation latency
   - `LatencyStatistics` — threshold assertion utilities

2. **k6 scenario tests** (`perf/k6/tests/`, run via `perf/scripts/run-k6.ps1`):
   - `01-baseline.js` — health/availability
   - `02-load.js` — normal traffic
   - `03-stress.js` — beyond normal capacity
   - `04-spike.js` — sudden burst
   - `05-endurance.js` — sustained load
   - `06-inventory-contention.js` — concurrent same-item reservation

Run commands:
```
# Unit + integration (default)
./mvnw clean verify -Dspring.profiles.active=test

# Full quality gate (CI)
./mvnw clean verify -Pci -Dspring.profiles.active=test

# Performance suite only
./mvnw verify -Pperformance -Dspring.profiles.active=perf-test

# Chaos suite only
./mvnw verify -Pchaos -Dspring.profiles.active=test
```

---

## 13. Design Notes & Known Limitations

### 13.1 Idempotency is built in, not afterthought

Every domain event is wrapped in a `DomainEventEnvelope` with a unique `eventId` UUID and
a deterministic `correlationId`. The Kafka consumers each check a `ProcessedEvent` row keyed
by `(aggregateId, eventType, consumerName)` before processing — guaranteeing exactly-once
processing semantics despite Kafka's at-least-once delivery. Order cancellation even guards
against double-release (`InventoryService.releaseReservation` checks
`historyRepository.existsByBookIdAndOrderIdAndEventType(...)`).

### 13.2 The dual-write problem is solved by the Outbox

Business writes (Order → Payment → Inventory reservation) and the outbox event write
happen in the same `@Transactional` method. The `OutboxPublisherScheduler` polls PENDING
rows in a separate transaction and only marks `PUBLISHED` after `kafkaTemplate.send().get()`
returns. If Kafka is down, events stay `PENDING` and are retried up to `maxRetries`
(default 5) — then moved to the DLQ. This eliminates the race where a DB commit succeeds but
the Kafka publish fails silently.

### 13.3 Inventory deadlock prevention

`InventoryService.reserveAll()` sorts reservation entries by `bookId` before acquiring
`PESSIMISTIC_WRITE` locks. This global lock ordering guarantees that two concurrent orders
contending for the same set of books can never deadlock — the classic "acquire locks in a
consistent order" pattern.

### 13.4 Two notification pathways prevent duplicate emails

The `kafka` and `in-process` notification handlers are mutually exclusive via
`@ConditionalOnProperty("app.notifications.delivery-mode")`. Only `NotificationEventConsumer`
is active in prod/dev (Kafka mode); only `NotificationEventHandler` is active in test
profiles. The `ORDER_CANCELLED` event type (admin path) maps to the order-status email in
both handlers — a parity that was specifically fixed and noted in the CHANGELOG.

### 13.5 Flyway is manually bootstrapped — and intentionally so

Spring Boot 4 + Flyway 11.x removed the `DatabaseFactory` class that `FlywayAutoConfiguration`
probes for, so auto-config is silently inactive. If a developer naively adds
`spring.flyway.enabled=true` expecting Spring Boot to handle it, nothing happens — migrations
are driven by `FlywayConfig` as a constructor side-effect instead. The K8s init container
(`k8s/deployment.yaml:120`) runs the same JAR with
`-Dspring.flyway.enabled=true -Dspring.flyway.baseline-on-migrate=true` so migrations apply
before the app pod starts. This is the single point of truth for schema evolution.

### 13.6 Rate limiter fails open

`RedisRateLimiter.tryConsume()` catches `RedisConnectionFailureException` /
`RedisSystemException` and **allows the request** (log-warn) when Redis is unreachable.
This is a deliberate availability-over-accuracy decision: a rate-limiting outage should not
cause an auth/capacity denial for all users. In production this is paired with a circuit
breaker and alerting on the fail-open log lines.

### 13.7 Cache errors are swallowed

`CacheConfig`'s `CacheErrorHandler` logs at WARN and continues on any cache get/put/evict
failure. A Redis cache outage will cause cache misses (performance degradation) but not
functional failure — the database is always the source of truth.

### 13.8 No CartItemRepository

`CartItem` is a child of `Cart` (cascade `ALL`). It cannot be queried or updated
independently — every operation goes through `CartService` which loads the parent Cart,
mutates its items collection, and saves. This is a deliberate aggregate-boundary decision
(see `CartController` for the item-level endpoints that route through the cart).

### 13.9 JWT roles are embedded, not refreshed

The JWT is valid for 30 minutes and contains the user's roles at issuance time. There is no
token-refresh endpoint. A role change takes effect only after the user re-authenticates.
This is acceptable for the current `USER`/`ADMIN` dichotomy but would need revisiting if
fine-grained permissions or frequent role changes are introduced.

### 13.10 Kafka consumer group rebalancing

With `listenConcurrency=3` and 3 partitions per topic, each consumer instance handles
exactly one partition. Adding/removing consumer instances (scale-up/down) triggers a
rebalance, which pauses processing briefly. The `max.poll.interval.ms=300000` (5 min) buffer
accommodates slow processing. For very high-throughput scenarios, consider increasing
partitions.

---

## 14. Quick Reference

### 14.1 Running Locally

```bash
# 1. Start dependencies (PostgreSQL:5433, Redis:6379, Kafka:9092, +observability)
docker compose up -d

# 2. Run the app (default profile = dev)
./mvnw spring-boot:run

# 3. Run the full quality gate (tests + static analysis + OWASP)
./mvnw clean verify -Pci -Dspring.profiles.active=test

# 4. Swagger UI
#    http://localhost:8080/swagger-ui.html   |   http://localhost:8080/v3/api-docs
```

### 14.2 Key Environment Variables (production)

| Variable | ConfigMap key | Used by |
|---|---|---|
| `DATASOURCE_URL` | secret | Database connection |
| `DATASOURCE_USERNAME` / `DATASOURCE_PASSWORD` | secret | DB auth |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | secret | Redis cache + rate limiter |
| `KAFKA_BOOTSTRAP_SERVERS` | secret | Kafka producer/consumers |
| `JWT_SECRET` | secret | JWT signing (≥ 32 bytes) |
| `SPRING_PROFILES_ACTIVE` | configmap | Active Spring profile |
| `CORS_ALLOWED_ORIGINS` | configmap | CORS allow-list |
| `NOTIFICATIONS_DELIVERY_MODE` | env | `kafka` or `in-process` |
| `APP_CACHE_ENABLED` | configmap | Enable/disable caching |
| `APP_RATE_LIMITING_ENABLED` | configmap | Enable/disable rate limiting |
| `DB_POOL_MAX_SIZE` / `DB_POOL_MIN_IDLE` | configmap | HikariCP pool sizing |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | configmap | Trace export endpoint |
| `OTEL_TRACES_SAMPLER_ARG` | configmap | Sampling rate |

### 14.3 API Endpoints Summary

| Resource | Auth | CRUD |
|---|---|---|
| `/api/v1/auth/**` | none | register, login |
| `/api/v1/books` | none (GET) / admin (write) | full |
| `/api/v1/cart` | USER | get, add/update/remove items, clear |
| `/api/v1/orders` | USER | create (from cart), list, get, cancel |
| `/api/v1/payments` | USER | process, refund, list, get |
| `/api/v1/admin/**` | ADMIN | admin operations |
| `/actuator/**` | mixed (health/public, prometheus/admin) | diagnostics |

### 14.4 File Reference Index

| Concern | Key files |
|---|---|
| Entry point | `BookstoreApplication.java:28` |
| Order business logic | `OrderService.java:35` (`createOrder:72`, `cancelOwnOrder:147`, `adminCancelOrder:215`) |
| Payment business logic | `PaymentService.java:27` (`processPayment:80`, `refundPayment:169`) |
| Inventory business logic | `InventoryService.java:35` (`reserve:93`, `reserveAll:275`, `finalizeReservation:154`) |
| Security filter chain | `SecurityConfig.java:24` (`securityFilterChain:32`) |
| JWT handling | `config/jwt/JwtUtil.java:21`, `config/jwt/JwtAuthenticationFilter.java:23` |
| Rate limiting | `config/RateLimitingConfig.java:18`, `config/RedisRateLimiter.java:13` |
| Caching | `config/CacheConfig.java:30` |
| Flyway | `config/FlywayConfig.java:21` |
| Outbox publisher | `event/publisher/OutboxPublisherScheduler.java:24` |
| Kafka config | `config/KafkaConfig.java:28`, `config/KafkaTopicConfig.java:13` |
| Consumers | `messaging/consumer/` (4 consumers + `AbstractIdempotentConsumer.java`) |
| Notification (in-process) | `notification/NotificationEventHandler.java` |
| Notification (Kafka) | `notification/NotificationEventConsumer.java` |
| Exception handling | `exception/GlobalExceptionHandler.java`, `exception/ErrorResponse.java` |
| DTOs | `controller/dto/` (20+ request/response classes) |
| Migrations | `resources/db/migration/` (V1–V8) |
| CI/CD | `.github/workflows/` (6 workflow files), `.github/actions/java-setup/action.yml` |
