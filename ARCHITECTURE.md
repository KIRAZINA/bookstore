# Bookstore API - Target Architecture Refactoring

> **Role:** Senior Software Architect  
> **Scope:** Security, data integrity, API contract, concurrency, and domain boundaries  
> **Audience:** Backend engineers, QA, tech lead

---

## 1) Architecture Decision Summary

This refactoring resolves six critical design flaws and defines a migration path that is safe for production.

- **Financial integrity:** all money fields use `BigDecimal` (no floating-point types in domain or DB).
- **Security hardening:** migrate to stateless JWT security (removes session + CSRF mismatch risk).
- **Concurrency safety:** protect stock updates with optimistic locking (`@Version`) and conflict handling.
- **REST compliance:** all state-changing endpoints accept validated request DTOs via `@RequestBody`.
- **Domain isolation:** controllers never expose JPA entities; response DTOs define external API contracts.
- **Business rule clarity:** explicit cart deletion semantics and price-freezing policy.

---

## 2) Critical Refactoring Decisions

### 2.1 Financial Data Integrity (CRITICAL)

#### Problem
- `double` introduces rounding errors in financial calculations.
- Totals and subtotals can drift after repeated operations.

#### Decision
- Replace:
  - `Book.price: double -> BigDecimal`
  - `OrderItem.price/unitPrice: double -> BigDecimal`
  - `Order.totalPrice: double -> BigDecimal`
- Use DB column definitions with precision/scale, e.g. `DECIMAL(19,4)`.
- Standardize arithmetic:
  - multiplication/addition in domain service
  - explicit `RoundingMode` only at formatting boundaries (never hidden in business logic).

#### Design Rule
- Money is always `BigDecimal` in entity/service/repository/DTO.
- Never convert to `double` in serialization, mapping, or tests.

---

### 2.2 Security Vulnerability (Session + CSRF Disabled)

#### Problem
- Current model uses cookie session auth (`JSESSIONID`) while CSRF is disabled.
- CORS is not CSRF protection.

#### Decision (Chosen Path)
- Migrate to **stateless JWT Bearer authentication**.
- Disable session creation: `SessionCreationPolicy.STATELESS`.
- Keep CSRF disabled only because auth token is sent in `Authorization` header (not cookie session).

#### Required Components
- `JwtUtil` for token issue/validation.
- `JwtAuthenticationFilter` before `UsernamePasswordAuthenticationFilter`.
- `/api/auth/login` returns access token payload (`LoginResponse` DTO).
- Role claims mapped to Spring Security authorities.

#### Transitional Note
- During migration window, session login endpoint should be deprecated and removed after client cutover.

---

### 2.3 Concurrency and Race Conditions in Checkout

#### Problem
- `createOrder()` currently checks stock and decrements without conflict protection.
- Concurrent checkouts can oversell inventory.

#### Decision
- Apply **Optimistic Locking** on `Book` with `@Version`.
- When concurrent stock update conflict occurs, return deterministic conflict response (`409 CONFLICT`) with retry guidance.

#### Service Pattern
1. Load cart items.
2. Validate stock for each book.
3. Decrement stock.
4. Persist order + order items in one transaction.
5. Clear cart.
6. On `OptimisticLockException`, fail request with retryable error contract.

#### Optional High-Contention Alternative
- Add repository method with pessimistic lock for flash-sale scenarios:
  - `@Lock(PESSIMISTIC_WRITE)` on book fetch used only for designated endpoints.

---

### 2.4 REST API Anti-pattern Elimination

#### Problem
- Mutating operations currently use query params (`POST /api/cart/add?bookId=...`).

#### Decision
- All mutating endpoints accept strict body DTOs with bean validation.

#### Refactored Endpoint Contract
- `POST /api/cart/items` with `AddToCartRequest`
- `PUT /api/cart/items/{bookId}` with `UpdateCartItemRequest`
- `DELETE /api/cart/items/{bookId}`
- `PUT /api/books/{id}/stock` with `UpdateStockRequest`
- `POST /api/books` with `BookRequest`

#### Validation Rule
- Controller-level `@Valid` mandatory on all request DTOs.

---

### 2.5 Domain Model Leakage Prevention

#### Problem
- Controllers expose entities directly (`Book`, `Order`).
- Risks:
  - accidental field leakage
  - schema-coupled API
  - difficult backward compatibility

#### Decision
- Introduce strict response DTO layer.
- Entities remain internal persistence models.

#### DTO Boundaries
- Responses: `BookResponse`, `CartResponse`, `CartItemResponse`, `OrderResponse`, `OrderItemResponse`, `LoginResponse`.
- Requests: all mutating operations use dedicated request DTOs.

#### Mapping
- Mapping implemented in dedicated mapper classes (manual or MapStruct).
- No entity object should cross controller boundary.

---

### 2.6 Business Logic Clarification

#### Cart Lifecycle / `clearCart()`

**Required behavior:** clearing cart must physically delete `CartItem` rows.

- Ensure `Cart.items` is configured with `orphanRemoval = true`.
- Ensure owning side of relation is set correctly.
- `clearCart()` must modify managed collection in a transaction and persist (`cart.getItems().clear()` + save/flush as needed).
- Add integration test validating DB row count reaches zero after clear.

#### Price Freezing Strategy

**Chosen business rule:** freeze price when item is added to cart.

- Add `CartItem.priceAtAdd` (`BigDecimal`).
- At checkout, create `OrderItem.unitPrice` from `CartItem.priceAtAdd`, not from current `Book.price`.
- This protects customers from admin price changes between cart add and checkout.

---

## 3) Target Domain Model (Key Fields)

- `Book`
  - `price: BigDecimal`
  - `stock: int`
  - `version: Long (@Version)`
- `CartItem`
  - `book`
  - `quantity`
  - `priceAtAdd: BigDecimal`
- `OrderItem`
  - `book`
  - `quantity`
  - `unitPrice: BigDecimal`
- `Order`
  - `totalPrice: BigDecimal`
  - `status: OrderStatus`

---

## 4) Refactoring Roadmap (Execution Plan)

## Phase 1 - Money Type Migration (Priority P0)
- Convert entity fields from `double` to `BigDecimal`.
- Update DTOs, services, tests, and JSON contracts.
- Apply DB migration (`DOUBLE -> DECIMAL(19,4)`).
- **Exit criteria:** all financial tests deterministic; no `double` monetary fields remain.

## Phase 2 - DTO Contract Enforcement (P0)
- Replace mutating query params with request bodies.
- Ensure every controller response is a DTO.
- Introduce validation (`@Valid`, constraints).
- **Exit criteria:** no controller returns entity type.

## Phase 3 - Concurrency Hardening (P0)
- Add `@Version` to `Book`.
- Add exception mapping for optimistic lock conflicts.
- Add concurrent checkout test suite.
- **Exit criteria:** no negative stock under concurrent order creation test.

## Phase 4 - Security Migration to JWT (P0)
- Introduce JWT issue/verify filter chain.
- Set stateless session policy.
- Remove or deprecate session login flow.
- Update tests for token-based authentication.
- **Exit criteria:** protected endpoints require valid bearer token; no session dependency.

## Phase 5 - Cart and Price Semantics (P1)
- Implement and test physical cart item removal behavior.
- Add `priceAtAdd` and order pricing from frozen value.
- Document pricing policy in API contract.
- **Exit criteria:** price change after cart add does not alter checkout line price.

## Phase 6 - Stabilization and Hardening (P1)
- Regression tests (unit/integration/security/concurrency).
- Performance baseline for checkout path.
- Update API docs and migration notes for clients.
- **Exit criteria:** green CI + updated docs + backward-compatibility notes published.

---

## 5) Testing Strategy by Risk Area

- **Financial precision tests**
  - rounding and subtotal/total aggregation
  - serialization/deserialization of `BigDecimal`
- **Concurrency tests**
  - parallel checkout for same book stock
  - assert no oversell, assert conflict handling contract
- **Security tests**
  - unauthorized access blocked
  - JWT token validation and expiration
- **REST contract tests**
  - mutating endpoints reject query-only calls
  - body validation returns 400 with field errors
- **Cart lifecycle tests**
  - clear cart deletes persisted `CartItem` rows
- **Price freeze tests**
  - checkout uses `priceAtAdd` despite subsequent catalog price changes

---

## 6) Migration and Rollout Notes

- Use schema migration scripts (Flyway/Liquibase) for type changes and new columns.
- Release in backward-compatible slices where possible:
  1. add new fields + dual-write (temporary),
  2. switch reads,
  3. remove legacy fields.
- Communicate API changes to consumers:
  - endpoint path updates
  - request-body requirements
  - authentication header requirements
- Add monitoring around:
  - auth failures
  - optimistic lock conflicts
  - order creation latency and failure rate

---

## 7) Definition of Done

Architecture refactoring is complete when all below are true:

- No monetary `double` fields in code or schema.
- No session-based protected flow without CSRF.
- No controller entity exposure.
- No mutating endpoint that relies on query params.
- Concurrent checkout cannot produce negative stock.
- Cart clear behavior verified at database level.
- Price freezing behavior documented and verified by tests.

