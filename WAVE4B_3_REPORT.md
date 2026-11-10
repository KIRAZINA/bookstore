# WAVE4B_3_REPORT — Cache Hygiene, getInventoryStatus Cache, BookController Alignment, GitHub Prep

## 0. Phase 0 — Workspace Hygiene and Backlog Commit

### 0.1 Inspection (captured at session start)

`git status --short`:

```text
 M src/main/java/com/example/bookstore/messaging/consumer/AbstractIdempotentConsumer.java
 M src/main/java/com/example/bookstore/messaging/consumer/InventoryEventConsumer.java
 M src/main/java/com/example/bookstore/messaging/consumer/NotificationEventConsumer.java
 M src/main/java/com/example/bookstore/messaging/consumer/OrderEventConsumer.java
 M src/main/java/com/example/bookstore/messaging/consumer/PaymentEventConsumer.java
 M src/main/java/com/example/bookstore/notification/email/EmailService.java
 M src/main/java/com/example/bookstore/service/InventoryService.java
 M src/test/java/com/example/bookstore/messaging/consumer/AbstractIdempotentConsumerTest.java
 M src/test/java/com/example/bookstore/messaging/consumer/InventoryEventConsumerTest.java
 M src/test/java/com/example/bookstore/messaging/consumer/NotificationEventConsumerTest.java
 M src/test/java/com/example/bookstore/messaging/consumer/PaymentEventConsumerTest.java
 M src/test/java/com/example/bookstore/notification/email/EmailServiceTest.java
?? WAVE4A_REPORT.md
?? WAVE4B_1_REPORT.md
?? WAVE4B_2_REPORT.md
?? WAVE4B_GROUND_TRUTH.md
?? src/main/resources/db/migration/V9__add_inventory_history_idempotency.sql
?? src/test/java/com/example/bookstore/messaging/ConsumerDedup...
```

The console capture truncated the final untracked path. The complete untracked set was
enumerated during verification and consisted of exactly the 4B.1 migration/test files and
the 4B.2 integration test file; all were committed in §0.5.

`git rev-parse --short HEAD` at session start: `eb2d975`.

### 0.2 Wave 4B.1 Verification

| File | Verification |
|---|---|
| `src/main/java/com/example/bookstore/service/InventoryService.java` | `git diff HEAD` = `+136/-5`; batch `@CacheEvict`, explicit `CacheManager` eviction, V9 idempotency wiring. Matches `WAVE4B_1_REPORT.md` §3. |
| `src/main/resources/db/migration/V9__add_inventory_history_idempotency.sql` | 460 bytes; unique partial index for terminal events. Matches §3/§5. |
| `src/test/java/com/example/bookstore/service/InventoryBatchCacheAndRaceIntegrationTest.java` | 10,853 bytes; batch cache + race coverage. Matches §4/§7. |

Result: **verified-committable**.

### 0.3 Wave 4B.2 Verification

All twelve 4B.2 paths in `git status --short` were diffed against `WAVE4B_2_REPORT.md` §7/§12:
five consumers, `EmailService`, five corresponding unit tests, the new
`ConsumerDedupAndSmtpBoundaryIntegrationTest`, and `WAVE4B_2_REPORT.md`. The diffs matched the
reported C17 / SMTP-boundary / duplicate-send scope. Result: **verified-committable**.

### 0.4 Other uncommitted files

Two untracked files were neither 4B.1 nor 4B.2:

- `WAVE4B_GROUND_TRUTH.md` — the mandatory input for this wave (§Prerequisite).
- `WAVE4A_REPORT.md` — a prior-wave documentation artifact.

Both are untracked, non-code documentation. They were not modified, staged, stashed, deleted,
or committed. Because `WAVE4B_GROUND_TRUTH.md` is a required input, blocking on its existence
would make 4B.3 impossible; the exception is recorded here and in §15 rather than hidden. No
foreign **code** was present.

### 0.5 Backlog commits

Explicit-path `git add` only; no `git add -A/.`, no amend/reset/rebase/revert/tag/stash/clean.

```text
7961782 feat(step-4b.1): inventory history idempotency + cache eviction
4988ae4 test(step-4b.1): batch cache + race integration coverage
a99e27c feat(step-4b.2): atomic dedup + SMTP out-of-transaction + email duplicate-send fix
82a83bc docs(step-4b.2): add WAVE4B_2_REPORT
```

### 0.6 Backlog provenance

```text
82a83bc docs(step-4b.2): add WAVE4B_2_REPORT
a99e27c feat(step-4b.2): atomic dedup + SMTP out-of-transaction + email duplicate-send fix
4988ae4 test(step-4b.1): batch cache + race integration coverage
7961782 feat(step-4b.1): inventory history idempotency + cache eviction
eb2d975 Keep curator audit register local and untracked
764538a Clean up project artifacts and finalize payment transaction hardening
5cc4467 Remove wave reports from repository tracking
fa7a989 Remove PROJECT_ARCHITECTURE.md from repository tracking
7b2f7e7 Wave 2B.1: align admin/refund tests + evidence reclassification
a55c8cd docs: reconcile PROJECT_ARCHITECTURE.md with source
```

Gate satisfied: workspace contained only `WAVE4A_REPORT.md` and `WAVE4B_GROUND_TRUTH.md`
before Step 1.

## 1. Scope

Ground truth confirmed all three defects (`WAVE4B_GROUND_TRUTH.md` §13):

- `NEW_DEFECT_INVENTORY_BATCH_CACHE_BYPASS` → **D-CACHE-1**
- `NEW_DEFECT_GET_INVENTORY_STATUS_CACHE_BYPASS` (branch **(a) bypasses cache**) → **D-CACHE-2**
- `NEW_DEFECT_BOOK_AVAILABILITY_READ_INCONSISTENCY` → **D-CONTROLLER-1**

Production files changed:

- `src/main/java/com/example/bookstore/service/InventoryService.java` — D-CACHE-2 only.
- `src/main/java/com/example/bookstore/controller/BookController.java` — D-CONTROLLER-1 only.

Not changed (not required by ground truth): `BookService.java`, `InventoryController.java`,
`CacheConfig.java`. No schema migration. No payment/order/cart/consumer/email/config/security/
Kafka/outbox/model/enum/entity/pom/yml changes.

Tests created:

- `src/test/java/com/example/bookstore/cache/CrossServiceCacheHygieneIntegrationTest.java`
- `src/test/java/com/example/bookstore/service/InventoryStatusCacheIntegrationTest.java`
- `src/test/java/com/example/bookstore/controller/BookControllerAlignmentIntegrationTest.java`

## 2. Baseline

- HEAD after Phase 0: `82a83bc`.
- Cache annotations (`@EnableCaching` at `CacheConfig.java:31`):

| Site | Annotation |
|---|---|
| `BookService.java:60` | `@Cacheable(cacheNames = "books", key = "'allBooks'")` |
| `BookService.java:65` | `@Cacheable(cacheNames = "books", key = "'category-' + #category")` |
| `BookService.java:70` | `@Cacheable(cacheNames = "bookById", key = "#id")` |
| `BookService.java:76` | `@Cacheable(cacheNames = "books", key = "T(...BookService).searchCacheKey(#search)")` |
| `InventoryAdminService.java:65` | `@CacheEvict({"inventory","inventoryAvailable"}, key="#bookId")` |
| `InventoryService.java:74` | `@Cacheable(cacheNames = "inventory", key = "#bookId")` |
| `InventoryService.java:84` | `@Cacheable(cacheNames = "inventoryAvailable", key = "#bookId")` |
| `InventoryService.java:112,139,173,201,232` | single-item `@CacheEvict(..., key="#bookId")` |
| `InventoryService.java:300,340,374,412` | batch `@CacheEvict(..., allEntries=true)` |

`CacheConfig` names/TTLs: `books` 10m, `bookById` 30m, `inventory` 1m,
`inventoryAvailable` 30s. Values use `GenericJackson2JsonRedisSerializer`; keys use
`StringRedisSerializer`.

Pre-fix bodies:

```java
public InventoryStatus getInventoryStatus(Long bookId) {
    int available = getAvailableQuantity(bookId); // self-invocation: bypasses @Cacheable
    return validator.determineStatus(available);
}
```

```java
private BookResponse toBookResponse(Book book) {
    int avail = inventoryService.getAvailableQuantity(book.getId()); // cached service path
    ...
}

private List<BookResponse> toBookResponses(List<Book> books) {
    List<Long> ids = books.stream().map(Book::getId).toList();
    Map<Long, Integer> availMap = new HashMap<>();
    for (Inventory inv : inventoryRepository.findByBookIdIn(ids)) { // direct DB path
        availMap.put(inv.getBookId(), inv.getAvailableQuantity());
    }
    ...
}
```

## 3. D-CACHE-1 Design and Evidence

- Writer: `InventoryService` batch paths — `reserveAll`,
  `releaseAllReservationsForOrder`, `finalizeAllReservationsForOrder`,
  `restoreAllStockForOrder`, `initializeInventory`.
- Reader: `BookService.getAvailableQuantity` → `InventoryService.getAvailableQuantity`
  (also `CartService`, `OrderService`, `BookController`).
- Cache: `inventoryAvailable`, key = book id, TTL 30s.
- Consequence: stale availability in cart/order prechecks and API responses.

Status: **ALREADY_CLOSED_PRIOR**. Wave 4B.1 already placed `@CacheEvict` and explicit
`CacheManager` eviction on every batch path (`InventoryService.java:300,340,374,412` plus
`evictInventoryCaches()` / `evictInventoryCacheEntries()`). 4B.3 therefore makes **no
production change** for this defect; it adds cross-service guard tests that read through
`BookService`, the actual cross-service consumer.

## 4. D-CACHE-2 Design and Evidence

Ground truth branch: **(a) bypasses the cache entirely**. `getInventoryStatus` called
`getAvailableQuantity` on `this`, so the `@Cacheable` advice never ran and every status read
hit the database.

Fix (minimal, inside `InventoryService`):

```java
@Autowired
@Lazy
private InventoryService self;

public InventoryStatus getInventoryStatus(Long bookId) {
    InventoryService target = self != null ? self : this;
    int available = target.getAvailableQuantity(bookId);
    return validator.determineStatus(available);
}
```

`self` is resolved through the Spring proxy, so the shared `inventoryAvailable` cache and its
30s TTL are used exactly like every other reader. The `self != null ? self : this` fallback
keeps direct unit construction (`new InventoryService(...)`) working.

## 5. D-CONTROLLER-1 Design and Evidence

Ground truth: `BookController.toBookResponses` (list/category/search) read
`inventoryRepository.findByBookIdIn` directly, while `toBookResponse` (single/get/add/update)
read the cached `inventoryService.getAvailableQuantity`. The two endpoints could disagree
whenever the cache was not perfectly in sync.

Fix: one DTO mapping path.

```java
private List<BookResponse> toBookResponses(List<Book> books) {
    if (books == null || books.isEmpty()) {
        return List.of();
    }
    return books.stream().map(this::toBookResponse).toList();
}
```

`InventoryRepository`, `Inventory`, `HashMap`, `Map`, and `Collectors` were removed from
`BookController`. `toBookResponse` remains the single source of truth for every book DTO.

Trade-off (recorded in §15): a list page now performs one cached availability read per book
instead of one bulk repository query. Page size is capped at 100, and this is the explicit
"one service path for both endpoints" contract.

## 6. Red Evidence

### D-CACHE-1 — `ALREADY_CLOSED_PRIOR`

Not capturable without reverting 4B.1, which is forbidden. The final guard class passes
against the post-4B.1 HEAD and against the 4B.3 worktree because neither 4B.3 production change
touches the batch/reader paths. An initial guard run failed only on the **test's** assumption
that eviction was synchronous; Spring Data Redis 4.0.6 evicts through
`AsynchronousCacheWriterDelegate`, so the tests were corrected to await eviction with a bounded
condition poll. That was a test-synchronization artifact, not a product defect.

### D-CACHE-2 — `RED_CAPTURED`

Captured with the final tests by temporarily restoring the pre-fix self-invocation:

```text
[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 34.65 s <<< FAILURE! -- in com.example.bookstore.service.InventoryStatusCacheIntegrationTest
[ERROR] com.example.bookstore.service.InventoryStatusCacheIntegrationTest.getInventoryStatus_readsThroughWarmAvailabilityCache -- Time elapsed: 0.102 s <<< FAILURE!
org.opentest4j.AssertionFailedError: getInventoryStatus must read the warm availability cache instead of bypassing it ==> expected: <AVAILABLE> but was: <OUT_OF_STOCK>
    at com.example.bookstore.service.InventoryStatusCacheIntegrationTest.getInventoryStatus_readsThroughWarmAvailabilityCache(InventoryStatusCacheIntegrationTest.java:94)
[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0
```

The cache was warmed to 20 through the public read, the database was changed to 0 out of band,
and the pre-fix method returned the database value (`OUT_OF_STOCK`). The fix was then re-applied.

### D-CONTROLLER-1 — `RED_CAPTURED`

```text
[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 12.32 s <<< FAILURE! -- in com.example.bookstore.controller.BookControllerAlignmentIntegrationTest
[ERROR] com.example.bookstore.controller.BookControllerAlignmentIntegrationTest.listAndSingleBook_agreeOnEveryField_whenAvailabilityCacheIsStale -- Time elapsed: 0.470 s <<< FAILURE!
org.opentest4j.AssertionFailedError: list and single-book responses must agree field-for-field ==> expected: <{"author":"Alignment Author","availableQuantity":10,"category":"Alignment","id":8,"inventoryStatus":"LOW_STOCK","price":14.99,"stock":10,"title":"Alignment Test Book"}> but was: <{"author":"Alignment Author","availableQuantity":4,"category":"Alignment","id":8,"inventoryStatus":"LOW_STOCK","price":14.99,"stock":4,"title":"Alignment Test Book"}>
    at com.example.bookstore.controller.BookControllerAlignmentIntegrationTest.listAndSingleBook_agreeOnEveryField_whenAvailabilityCacheIsStale(BookControllerAlignmentIntegrationTest.java:132)
```

The single endpoint served the cached `10`; the list endpoint served the database `4`.

## 7. Production Fix — File-by-File

| File | Lines | Change |
|---|---:|---|
| `InventoryService.java` | +14 / −1 | `@Lazy` self-proxy field + `getInventoryStatus` routes through the proxy. No batch/history method touched. |
| `BookController.java` | +4 / −30 | Removed `InventoryRepository` dependency; `toBookResponses` delegates to `toBookResponse`. |
| `CrossServiceCacheHygieneIntegrationTest.java` | 154 (new) | Cross-service batch guard tests. |
| `InventoryStatusCacheIntegrationTest.java` | 114 (new) | Cache-bypass red/green + eviction guard. |
| `BookControllerAlignmentIntegrationTest.java` | 148 (new) | List-vs-single field equality. |

`CacheConfig`, `BookService`, `InventoryController`, migrations, and all 4B.1/4B.2 files are
untouched.

## 8. Green Evidence (New Tests)

Command:

```text
./mvnw -Dtest=CrossServiceCacheHygieneIntegrationTest,InventoryStatusCacheIntegrationTest,BookControllerAlignmentIntegrationTest test
```

| Class | Tests | Result |
|---|---:|---|
| `CrossServiceCacheHygieneIntegrationTest` | 4 | PASS |
| `InventoryStatusCacheIntegrationTest` | 2 | PASS |
| `BookControllerAlignmentIntegrationTest` | 2 | PASS |
| **Total** | **8** | **8/8 PASS** |

Determinism: the exact three-class suite was run **five consecutive times** after the final
test revision; all five runs were `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0` /
`BUILD SUCCESS`.

## 9. Canonical Regression

The prompt's canonical set names `BookServiceIntegrationTest`, which does not exist in this
repository. The closest authoritative equivalents were substituted:
`BookServiceCacheIntegrationTest` and `BookServiceTest`. Every other requested class exists and
ran.

```text
[INFO] Tests run: 129, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Per-class counts:

| Class | Tests | Result |
|---|---:|---|
| `CrossServiceCacheHygieneIntegrationTest` | 4 | PASS |
| `InventoryStatusCacheIntegrationTest` | 2 | PASS |
| `BookControllerAlignmentIntegrationTest` | 2 | PASS |
| `PaymentConcurrencyIntegrationTest` | 3 | PASS |
| `PaymentTransactionBoundaryIntegrationTest` | 4 | PASS |
| `PaymentIntegrationTest` | 6 | PASS |
| `PaymentServiceIntegrationTest` | 10 | PASS |
| `PaymentControllerIntegrationTest` | 5 | PASS |
| `OrderPaymentStateMachineIntegrationTest` | 12 | PASS |
| `OrderServiceIntegrationTest` | 7 | PASS |
| `InventoryBatchCacheAndRaceIntegrationTest` | 5 | PASS |
| `InventoryConcurrencyIntegrationTest` | 4 | PASS |
| `InventoryServiceUnitTest` | 18 | PASS |
| `KafkaConsumerIntegrationTest` | 5 | PASS |
| `AbstractIdempotentConsumerTest` | 7 | PASS |
| `ConsumerDedupAndSmtpBoundaryIntegrationTest` | 7 | PASS |
| `BookServiceCacheIntegrationTest` | 12 | PASS |
| `BookServiceTest` | 10 | PASS |
| `BookControllerIntegrationTest` | 6 | PASS |
| **Total** | **129** | **PASS** |

T5–T11 preservation: `PaymentConcurrencyIntegrationTest`,
`PaymentTransactionBoundaryIntegrationTest`, `PaymentIntegrationTest`,
`PaymentServiceIntegrationTest`, `PaymentControllerIntegrationTest`,
`OrderPaymentStateMachineIntegrationTest`, `OrderServiceIntegrationTest` — all green.

## 10. Wave 4B.1 + 4B.2 Preservation

- 4B.1: `InventoryBatchCacheAndRaceIntegrationTest` — 5/5 green, including batch reserve/release
  cache eviction and concurrent release idempotency.
- 4B.2: `ConsumerDedupAndSmtpBoundaryIntegrationTest` — 7/7 green;
  `AbstractIdempotentConsumerTest` — 7/7 green; `KafkaConsumerIntegrationTest` — 5/5 green.
- `InventoryService` diff contains no change to batch loops, history recording, V9 idempotency,
  or consumer/email transaction boundaries.

## 11. Existing Test Adjustments

None. No existing assertion was changed or deleted; no `@Disabled` was added. No contract
reversal was required because the existing tests did not assert the divergent list/single
behavior.

## 12. Scope Compliance

`git diff --name-only`:

```text
src/main/java/com/example/bookstore/controller/BookController.java
src/main/java/com/example/bookstore/service/InventoryService.java
```

Untracked new files:

```text
WAVE4A_REPORT.md
WAVE4B_GROUND_TRUTH.md
src/test/java/com/example/bookstore/cache/CrossServiceCacheHygieneIntegrationTest.java
src/test/java/com/example/bookstore/controller/BookControllerAlignmentIntegrationTest.java
src/test/java/com/example/bookstore/service/InventoryStatusCacheIntegrationTest.java
```

Both tracked paths are in §1's allowed set. Both pre-existing untracked docs were left
untouched (§0.4). No migration, no forbidden file, no `pom.xml`/yml change.

## 13. GitHub Prep

### 13.1 Pre-commit checks

| # | Check | Result |
|---:|---|---|
| 1 | Working tree matches scope (`git diff --name-only`) | PASS — only `BookController.java`, `InventoryService.java` |
| 2 | Untracked files match scope (`git status --short`) | PASS — three new tests + `WAVE4B_3_REPORT.md`; the two pre-existing docs are declared in §0.4 |
| 3 | No backup/editor files | PASS — empty |
| 4 | No IDE files staged | PASS — empty |
| 5 | No build artifacts staged | PASS — empty |
| 6 | No secrets in diff | PASS — empty; no literal secrets |
| 7 | No debug leftovers | PASS — no `System.out`, `console.log`, `TODO`, `FIXME`, `XXX` added |
| 8 | No `@Disabled` added | PASS — empty |
| 9 | No schema migration touched | PASS — empty |
| 10 | No forbidden files touched | PASS — empty |
| 11 | Report is final | PASS — report written after the final regression run |
| 12 | Build green at HEAD-of-worktree | PASS — 129/129 |

### 13.2 Commits created

- `H_code`: `5b6140c` — `feat(step-4b.3): cross-service cache hygiene + getInventoryStatus cache + BookController alignment`
- `H_docs`: assigned by commit 2; recorded in the curator return and post-commit verification
  because a commit cannot contain its own hash without an amend (forbidden).

Commit 1 message:

```text
feat(step-4b.3): cross-service cache hygiene + getInventoryStatus cache + BookController alignment
Closes: D-CACHE-1, D-CACHE-2, D-CONTROLLER-1. Regression: T5–T11, 4B.1, 4B.2 all green.
```

Commit 2 message:

```text
docs(step-4b.3): add WAVE4B_3_REPORT
Evidence: red-first, green-first, canonical regression, Phase 9 pre-commit checks.
```

### 13.3 Post-commit verification

Captured after both commits and reproduced in the curator return:

```text
git log --oneline -8
git status --short
git show --stat --oneline H_code
git show --stat --oneline H_docs
```

Required final state: `git status --short` empty; both commit stats limited to §1 paths plus
`WAVE4B_3_REPORT.md`.

### 13.4 Push status

**NOT PUSHED.** Awaiting curator approval.

## 14. Final Classification

- D-CACHE-1: **CLOSED** (`ALREADY_CLOSED_PRIOR`; guarded by cross-service tests)
- D-CACHE-2: **CLOSED**
- D-CONTROLLER-1: **CLOSED**
- PaymentService T5–T11: **GREEN_INVARIANT_PRESERVED**
- Wave 4B.1: **GREEN_INVARIANT_PRESERVED**
- Wave 4B.2: **GREEN_INVARIANT_PRESERVED**
- 4B.4 (Outbox + Kafka-in-tx): **DEFERRED — next wave**
- 4C (`recordTransition` + history constraint): **DEFERRED**
- 4D (M19 refund ledger): **DEFERRED**

## 15. Blockers / Residuals

### OPEN

1. **SMTP success + commit failure duplicate** — carried from `WAVE4B_2_REPORT.md` §14;
   deferred to 4B.4.
2. **`WAVE4A_REPORT.md` and `WAVE4B_GROUND_TRUTH.md` remain untracked** — non-code
   documentation inputs, intentionally not committed (§0.4). The curator should decide their
   disposition.
3. **`getInventoryStatus` has no current production callers** — the D-CACHE-2 fix is a
   contract correction for future callers; the test class is the only caller today.

### Observations (not defects)

1. **Asynchronous eviction in Spring Data Redis 4.0.6** — `DefaultRedisCacheWriter` evicts via
   `AsynchronousCacheWriterDelegate`, so eviction is eventually consistent. The new tests
   synchronize with a bounded 2s condition poll. No production change was made; this is the
   framework's cache-consistency model and applies equally to all pre-existing cache readers.
2. **List availability read is now per-book** — `toBookResponses` performs one cached
   `getAvailableQuantity` per book instead of one bulk repository query. This is the required
   single-source-of-truth trade-off; page size is capped at 100. A future wave could add a
   cache-aware bulk availability read inside `InventoryService` if profiling justifies it.
3. **`BookServiceIntegrationTest` is absent** — substituted with `BookServiceCacheIntegrationTest`
   and `BookServiceTest` in §9.
