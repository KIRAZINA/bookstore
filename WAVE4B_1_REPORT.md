# WAVE4B_1_REPORT — InventoryService Batch Self-Invocation + Cache Eviction + History Race Constraint

## 1. Scope (Var B confirmed: includes one migration)

In scope:

- OBS-2 batch self-invocation fix.
- Inventory cache eviction for batch paths.
- `inventory_history` unique partial index for terminal events.
- `getInventoryStatus` cache-bypass treatment.
- Red-first test class, including the concurrent release race test.
- PaymentService T5–T11 and canonical regression execution.

Out of scope and not modified:

- `PaymentService.java`, `OrderService.java`, controllers, models, enums, config, `CacheConfig`.
- C17 / `AbstractIdempotentConsumer`.
- SMTP / EmailService / NotificationEventConsumer.
- `OutboxPublisherScheduler`.
- BookController list-vs-single availability alignment.
- Existing Flyway migrations V1–V8.

## 2. Baseline

- HEAD: `eb2d975`
- Pre-existing untracked files: `RUNBOOK_S16_JWT_SECRET_ROTATION.md`, `WAVE4B_GROUND_TRUTH.md`
- Changed files for this wave: see §11.
- Migration directory before this wave: V1–V8; next version: **V9**.
- `inventory_history` columns verified from:
  - `V6__Create_inventory_tables.sql:22-39`: `book_id`, `order_id`, `event_type`.
  - `InventoryHistory.java`: `bookId`, `orderId`, `InventoryEventType eventType`.
- Event values verified from `InventoryEventType.java`: `RESERVED`, `RELEASED`, `FINALIZED`, `ADJUSTED`, `RESTORED`, `CREATED`.
- No `git stash`, `git reset`, `git checkout`, or `git restore` was used.
- **No Maven execution was possible in this environment.** Every Maven invocation was rejected by the action reviewer with: “This action has too much or invalid context for automatic review.” This affects red/green evidence; see §4 and §8.

## 3. Chosen self-invocation/cache mechanism (Option 1/2/3) + cache eviction strategy + getInventoryStatus treatment

### Mechanism

**Option 1** was chosen: move `@Transactional` + `@CacheEvict` to the batch method and inline the per-item mutation so the batch path does not rely on bypassed inner-method annotations.

Batch methods changed:

- `releaseAllReservationsForOrder`
- `finalizeAllReservationsForOrder`
- `restoreAllStockForOrder`
- `reserveAll`
- `initializeInventory`

All five now have:

```java
@Transactional(propagation = Propagation.REQUIRED)
```

No `REQUIRES_NEW` was added to any batch method.

`releaseAllReservationsForOrder`, `finalizeAllReservationsForOrder`, and `restoreAllStockForOrder` now inline the per-item mutation and retain `@Retryable(retryFor = OptimisticLockingFailureException.class, maxAttempts = 3, ...)`. Retry now retries the whole batch; transaction rollback prevents partial commits. `reserveAll` keeps its broader lock/optimistic retry set.

The original single-item methods (`reserve`, `releaseReservation`, `finalizeReservation`, `restoreStock`) remain unchanged for external/proxy callers.

### Cache eviction strategy

- Batch paths (`reserveAll`, `releaseAllReservationsForOrder`, `finalizeAllReservationsForOrder`, `restoreAllStockForOrder`):

```java
@CacheEvict(cacheNames = {"inventory", "inventoryAvailable"}, allEntries = true)
```

- `initializeInventory`: per-key eviction:

```java
@CacheEvict(cacheNames = {"inventory", "inventoryAvailable"}, key = "#bookId")
```

- Batch methods also perform explicit `CacheManager` eviction after their mutation/event work. This is a belt-and-suspenders measure: if the proxy-visible annotation advice is not applied, the method body still clears `inventory` and `inventoryAvailable`.
- `initializeInventory` also evicts the created book's keys explicitly through `CacheManager`.
- Single-book mutations retain per-key eviction, unchanged.
- No `redisTemplate.keys()` was introduced.
- `books` / `bookById` are not evicted from InventoryService.
- No `EntityManager.clear()` or global persistence side effect was introduced.

### `getInventoryStatus`

`getInventoryStatus` remains a read-only cache-bypass path and was **deferred to 4B.3**. Reason: the batch Option 1 pattern applies to mutation methods; `getInventoryStatus` is a read that self-invokes `@Cacheable getAvailableQuantity`. Fixing it safely requires either self-injection or a separate cache-read delegate, which is broader than the batch/cache scope of 4B.1. It is not silently ignored; it is recorded as `DEFERRED` in §12.

## 4. Red evidence (R1/R2 verbatim)

**NOT CAPTURED.** The new test class was created before the production fix:

```text
src/test/java/com/example/bookstore/service/InventoryBatchCacheAndRaceIntegrationTest.java
```

The intended red command was:

```bash
./mvnw -Dtest=InventoryBatchCacheAndRaceIntegrationTest test
```

It could not be executed because the environment’s action reviewer rejected all Maven commands. Therefore:

- R1 stale-cache red: **NOT CAPTURED**
- R2 stale-cache red: **NOT CAPTURED**
- R3 pre-fix race result: **NOT CAPTURED**
- R4 pre-fix green-invariant: **NOT CAPTURED**

No red result is claimed. Static reasoning from the pre-fix source confirms R1/R2 would fail because `reserveAll`, `releaseAllReservationsForOrder`, `finalizeAllReservationsForOrder`, and `restoreAllStockForOrder` had no proxy-visible `@CacheEvict` (former annotations were on inner methods bypassed by self-invocation).

## 5. Production fix (InventoryService diff summary)

`src/main/java/com/example/bookstore/service/InventoryService.java`:

- Added `Propagation` import.
- `initializeInventory`:
  - `@Transactional(propagation = Propagation.REQUIRED)`
  - `@CacheEvict(cacheNames = {"inventory", "inventoryAvailable"}, key = "#bookId")`
- `releaseAllReservationsForOrder`:
  - `@Transactional(propagation = Propagation.REQUIRED)`
  - `@Retryable(retryFor = OptimisticLockingFailureException.class, maxAttempts = 3, ...)`
  - `@CacheEvict(cacheNames = {"inventory", "inventoryAvailable"}, allEntries = true)`
  - Inlined per-item release, preserving the `RELEASED` history guard, validation, history recording, metrics, and release event.
- `finalizeAllReservationsForOrder`:
  - same transaction/retry/cache annotations
  - Inlined per-item finalize, preserving validation, history, metrics, and finalize event.
- `restoreAllStockForOrder`:
  - same transaction/retry/cache annotations
  - Inlined per-item restore, preserving the `RESTORED` history guard, history recording, and metrics.
- `reserveAll`:
  - `@Transactional(propagation = Propagation.REQUIRED)`
  - Existing `@Retryable` retained.
  - Added `@CacheEvict(..., allEntries = true)`.
- Added an optional `CacheManager` field and explicit cache-eviction helpers (`evictInventoryCaches`, `evictInventoryCacheEntries`). Batch methods and `initializeInventory` call them in addition to the annotations.
- Single-item methods and their per-key eviction remain unchanged.
- No `PaymentService` or `OrderService` change.
- No `InventoryHistoryRepository` signature change or new query was required.

## 6. Schema migration

- File: `src/main/resources/db/migration/V9__add_inventory_history_idempotency.sql`
- Exactly one new migration.
- Existing migrations V1–V8 untouched.
- No data deletion or rewrite.

Exact SQL:

```sql
-- Enforce idempotent inventory history for terminal mutation events.
-- Prevents concurrent duplicate release, restore, or finalize rows for the
-- same book+order pair. RESERVED is intentionally excluded because a book can
-- be re-reserved for an order after a release.
CREATE UNIQUE INDEX IF NOT EXISTS uq_inventory_history_idempotency
    ON inventory_history (book_id, order_id, event_type)
    WHERE event_type IN ('RELEASED', 'RESTORED', 'FINALIZED');
```

Verification:

- Table: `inventory_history` (`V6__Create_inventory_tables.sql:22`).
- Columns: `book_id`, `order_id`, `event_type` (`V6__Create_inventory_tables.sql:23-26`).
- Event literals: `RELEASED`, `RESTORED`, `FINALIZED` from `InventoryEventType.java`.
- `RESERVED` is excluded because a book can legitimately be re-reserved for the same order after a release.
- Standard `CREATE UNIQUE INDEX`, not `CONCURRENTLY`. Flyway migrations run transactionally by default; `CONCURRENTLY` is not used.
- `IF NOT EXISTS` makes the migration idempotent at the index level.

### Duplicate precheck

- Test added in `InventoryBatchCacheAndRaceIntegrationTest.duplicatePrecheck_NoDuplicateTerminalHistoryRows`:
  - executes the required `GROUP BY ... HAVING COUNT(*) > 1` query;
  - asserts the idempotency index exists in `pg_indexes`.
- **Not executed** because Maven/Testcontainers execution is blocked in this environment.
- No production duplicate precheck was run. A production rollout must run the query before applying V9 and must return `BLOCKED` if duplicates exist; no deletion is permitted.

## 7. Concurrent race test (R3) — exactly-once proof

Test added:

```text
concurrentBatchRelease_RestoresExactlyOnce
```

Design:

- Reserve 5 units for a synthetic order via `reserveAll`.
- Launch 10 daemon threads, each calling `releaseAllReservationsForOrder` for the same book/order through a `TransactionTemplate(REQUIRED)`.
- Wait on a start latch and a completion latch.
- Catch loser exceptions.
- Assert final state:
  - `availableQuantity == 10`
  - `reservedQuantity == 0`
  - exactly one `RELEASED` history row for `(book, order)`.

Execution status: **NOT RUN** because Maven execution is blocked. The test is green-invariant authoritative and is ready for the curator/operator to run.

## 8. Green evidence (new tests)

**NOT CAPTURED.** The intended command was:

```bash
./mvnw -Dtest=InventoryBatchCacheAndRaceIntegrationTest test
```

All Maven invocations were rejected by the environment’s action reviewer. No green result is claimed.

## 9. PaymentService + canonical regression (T5–T11) counts

**NOT CAPTURED.** The intended command was:

```bash
./mvnw -Dtest=InventoryBatchCacheAndRaceIntegrationTest,InventoryConcurrencyIntegrationTest,InventoryServiceUnitTest,PaymentConcurrencyIntegrationTest,PaymentTransactionBoundaryIntegrationTest,PaymentIntegrationTest,PaymentServiceIntegrationTest,PaymentControllerIntegrationTest,OrderPaymentStateMachineIntegrationTest,OrderServiceIntegrationTest test
```

No Surefire counts are available. PaymentService and OrderService were not modified; the required regression must be run by the curator/operator before accepting this wave.

## 10. KNOWN_RESIDUAL: BookController list-vs-single (deferred to 4B.3)

`NEW_DEFECT_BOOK_AVAILABILITY_READ_INCONSISTENCY` remains known and untouched:

- `BookController.toBookResponse` uses cached `inventoryService.getAvailableQuantity` (`BookController.java:143`).
- `BookController.toBookResponses` reads `inventoryRepository.findByBookIdIn` directly (`BookController.java:157-166`).

The 4B.1 cache-eviction fix narrows the stale window, but source alignment is deferred to 4B.3.

## 11. Scope compliance (git diff --name-only)

Changed/created by this wave:

```text
src/main/java/com/example/bookstore/service/InventoryService.java
src/main/resources/db/migration/V9__add_inventory_history_idempotency.sql
src/test/java/com/example/bookstore/service/InventoryBatchCacheAndRaceIntegrationTest.java
WAVE4B_1_REPORT.md
```

Pre-existing untracked files not modified by this wave:

```text
RUNBOOK_S16_JWT_SECRET_ROTATION.md
WAVE4B_GROUND_TRUTH.md
```

Not modified:

- `PaymentService.java`
- `OrderService.java`
- `InventoryHistoryRepository.java`
- Controllers, models, enums, `CacheConfig`, config, `GlobalExceptionHandler`
- `pom.xml`, `application*.yml`
- Existing migrations V1–V8
- H2 tests / test resources

## 12. Final classification

| Item | Status |
|---|---|
| OBS-2 batch self-invocation | `IMPLEMENTED_UNVERIFIED` — code fixed, verification blocked |
| INVENTORY_BATCH_CACHE_BYPASS | `IMPLEMENTED_UNVERIFIED` — batch eviction added, verification blocked |
| INVENTORY_HISTORY_RACE | `IMPLEMENTED_UNVERIFIED` — unique partial index added, duplicate precheck/race test not run |
| GET_INVENTORY_STATUS_CACHE_BYPASS | `DEFERRED` to 4B.3 |
| BOOK_AVAILABILITY_READ_INCONSISTENCY | `KNOWN_RESIDUAL` (deferred 4B.3) |
| C17 | `DEFERRED` (4B.2 merged with SMTP) |

## 13. Blockers / residuals

- **BLOCKED_ENVIRONMENT — red/green verification unavailable.** Every Maven command was rejected by the environment’s action reviewer, so R1/R2 red, R3/R4 results, duplicate precheck execution, PaymentService T5–T11, and canonical regression could not be captured.
- **R1 follow-up:** the operator observed `batchReserve_EvictsAvailabilityCache` failing with cached `10` instead of `7`, while `batchRelease_EvictsAvailabilityCache` passed. The source already had `@CacheEvict` on `reserveAll`, but Maven reported “Nothing to compile,” so the compiled class was stale. The source timestamp is now newer than the stale class, and explicit `CacheManager` eviction was added to the batch method body so R1 no longer depends solely on proxy annotation advice.
- R1 still requires an operator rerun after a forced clean compile.
- Registry entry: `RES-4B1-VERIFICATION-BLOCKED = OPEN`.
- Registry entry: `RES-4B1-V9-MIGRATION-UNVERIFIED = OPEN / DO_NOT_APPLY_TO_SHARED_DB`.
- V9 must not be applied to any shared/persistent database until the duplicate precheck returns zero rows and R3 passes. On a fresh Testcontainers database V9 is safe to apply.
- Curator acceptance is withheld until the operator runs the Step 5 commands and returns Surefire counts.
- The implementation and migration are present and ready for test execution.
- The production duplicate precheck must be run before applying V9; if duplicates exist, do not delete them and return `BLOCKED`.
- `getInventoryStatus` cache bypass remains deferred to 4B.3.
- `BookController` list-vs-single availability inconsistency remains `KNOWN_RESIDUAL`.
- C17 remains deferred to 4B.2.
