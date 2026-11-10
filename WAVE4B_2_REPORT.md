# WAVE4B_2_REPORT — Atomic Dedup, SMTP-out-of-tx, Email Duplicate-Send

## 1. Scope

Wave 4B.2 only. Implemented as one design, three coupled parts:

1. **C17** — dedup row and `doProcess` commit atomically inside one
   `TransactionTemplate(PROPAGATION_REQUIRED)`.
2. **P13 / P25 prevention** — SMTP executes with no active transaction because
   `EmailService` wraps the send in `TransactionTemplate(PROPAGATION_NOT_SUPPORTED)`.
3. **NEW_DEFECT_EMAIL_DUPLICATE_ON_LOG_SAVE** — the `emailLog` write runs in an
   independent `TransactionTemplate(PROPAGATION_REQUIRES_NEW)` and its failure is logged at
   WARN and swallowed, so it can never cause a resend.

No production file outside the allowed list was changed. No schema migration was added. No
`PaymentService`, `OrderService`, `InventoryService`, controller, model, enum, config, outbox,
`pom.xml`, or `application*.yml` file was changed by this wave.

**Important process note.** The workspace already contained an uncommitted, partially complete
4B.2 implementation (plus uncommitted 4B.1 changes) when this session started. The zero-revert rule
(`no git stash/reset/checkout/restore`) forbids reconstructing the pre-fix baseline, so R1/R3 red
evidence could not be captured by execution. This report states that honestly in §6 instead of
fabricating red output. The implementation was validated, extended (new integration test,
`save`/transaction-template alignment), and proven green.

## 2. Baseline

- HEAD at session start: `eb2d975` ("Keep curator audit register local and untracked").
- The 4B.2 implementation was already present as **uncommitted working-tree changes**; it is not
  part of HEAD.
- Pre-existing, out-of-scope working-tree content inherited from Wave 4B.1:
  `src/main/java/com/example/bookstore/service/InventoryService.java`,
  `src/main/resources/db/migration/V9__add_inventory_history_idempotency.sql`,
  `src/test/java/com/example/bookstore/service/InventoryBatchCacheAndRaceIntegrationTest.java`.
  These were **not** modified by this wave.

### 2.1 HEAD `AbstractIdempotentConsumer` (defective baseline)

From `git show HEAD:src/main/java/com/example/bookstore/messaging/consumer/AbstractIdempotentConsumer.java`:

- `:57` — `@Transactional` on `protected void processEvent(...)`.
- `:58` — `processEvent` is called unqualified from `onXxxEvent` in every subclass, so the call is a
  self-invocation and the Spring AOP proxy is bypassed. The annotation creates **no** transaction.
- `:65` — `processedEventRepository.saveAndFlush(new ProcessedEvent(eventId, consumerGroup));`
  runs in auto-commit mode. The flush is therefore an immediate commit of the dedup row.
- `:66-70` — `catch (DataIntegrityViolationException)` treats a duplicate insert as "already
  processed" and returns.
- `:72-86` — `doProcess` runs **after** the dedup row is already committed; a failure records a
  metric and rethrows, but the dedup row survives. Kafka redelivery then hits the duplicate branch
  and the event is lost.

### 2.2 HEAD `EmailService` (defective baseline)

From `git show HEAD:src/main/java/com/example/bookstore/notification/email/EmailService.java`:

- `:17` — `MAX_RETRIES = 3`.
- `:30-32` — the `EmailLog` is created and **saved before** the SMTP send.
- `:35-60` — a three-attempt send loop; `emailLogRepository.save(...)` at `:41` is inside the same
  `try` as `emailSender.send(...)` at `:37`.
- `:44-49` — the catch saves the log again at `:49`; that save is not itself guarded, so a
  log-save failure propagates.
- `:62-67` — after exhausting retries the failure is **swallowed**: status FAILED, metric recorded,
  no rethrow.

Consequence: HEAD had no reliable "SMTP failure → Kafka retry" contract (the service swallowed the
failure), and it retried SMTP three times in-process. The 4B.2 design deliberately removes the
in-service retry because Kafka `DefaultErrorHandler` is the single retry authority.

### 2.3 Listener annotation check

`rg '@Transactional|@Retryable' src/main/java/com/example/bookstore/messaging/consumer/` finds **no**
annotation on any `@KafkaListener` method and no `@Transactional`/`@Retryable` left in the package.
Therefore the dedup template cannot accidentally join a listener-level transaction, and there is no
double-retry.

### 2.4 `EmailLog` shape

`EmailLog` is constructed as `new EmailLog(to, subject, body, emailType)` and exposes
`setStatus(EmailStatus)`, `setSentAt(LocalDateTime)`, `setRetryCount(int)`,
`setErrorMessage(String)`. Status values used: `EmailStatus.SENT`, `EmailStatus.FAILED`. No new enum
value was added.

### 2.5 Consumer `doProcess` shape

- `NotificationEventConsumer.doProcess` (`:69-83`) calls `emailService.sendEmail(...)` directly via
  `handleOrderCreated` / `handleOrderStatusChanged` / `handlePaymentCompleted` /
  `handlePaymentFailed` / `handlePaymentRefunded` (`:85-147`). There is **no** try/catch, so SMTP
  failure propagates. No consumer-side change was needed.
- `InventoryEventConsumer`, `OrderEventConsumer`, `PaymentEventConsumer` contain **zero** `try`/
  `catch` blocks. Their service calls propagate normally.

## 3. C17 Fix — `TransactionTemplate(PROPAGATION_REQUIRED)`

`src/main/java/com/example/bookstore/messaging/consumer/AbstractIdempotentConsumer.java`:

- `:56` — `private final TransactionTemplate dedupTemplate;`
- `:58-66` — constructor receives `PlatformTransactionManager` and builds the template.
- `:77-81` — `createDedupTemplate(...)` sets `TransactionDefinition.PROPAGATION_REQUIRED`.
- `:88` — `processEvent` has **no** `@Transactional`; it is a plain protected method.
- `:93-107` — the single transactional unit:
  - `:94-98` — `existsByEventIdAndConsumerGroup(eventId, consumerGroup)` → skip when present.
  - `:100` — `save(new ProcessedEvent(...))` (**`save`, not `saveAndFlush`**). The flush is owned by
    the template and happens at commit.
  - `:101-106` — `doProcess(envelope)` plus success metric inside the same callback.
- `:113` — `dedupTemplate.executeWithoutResult(...)`.
- `:115-119` — failure metrics and rethrow; the template rolls the transaction back, so the dedup
  row is not committed and Kafka can reprocess.

Why `TransactionTemplate` and not `@Transactional`: the defect is self-invocation, and a
method-level annotation is silently inert on a self-call. A programmatically constructed template is
proxy-free by construction.

Why `PROPAGATION_REQUIRED` and not `REQUIRES_NEW`: dedup and processing must share one transaction.
`REQUIRES_NEW` would commit the dedup row in a different transaction and reintroduce the split this
wave exists to remove.

Why `save` and not `saveAndFlush`: inside a transaction, the template controls the flush/commit
boundary. `saveAndFlush` would force an early flush and is no longer used anywhere in the consumer
package.

`isActualTransactionActive()` proof: R2b (`doProcess_RunsInsideDedupTransaction`) runs a real
subclass through the real template and asserts `true` **inside** `doProcess`. This is the
self-invocation proof.

Test-compatibility affordance (disclosed): the constructor treats a `null`
`PlatformTransactionManager` as "no template" (`:65`, `:110-111`) so legacy unit tests that do not
exercise transaction semantics keep compiling. **Production wiring always supplies a transaction
manager** (Spring constructor injection), so the production path always executes the template.
The four concrete consumers retain a package-private legacy constructor for the same reason
(`InventoryEventConsumer:35-42`, `OrderEventConsumer:35-42`, `PaymentEventConsumer:35-42`,
`NotificationEventConsumer:43-51`). This is a test seam, not a production branch.

## 4. P13 / P25 Prevention — `TransactionTemplate(PROPAGATION_NOT_SUPPORTED)`

`src/main/java/com/example/bookstore/notification/email/EmailService.java`:

- `:46-47` — `smtpTemplate` = `new TransactionTemplate(transactionManager)` with
  `PROPAGATION_NOT_SUPPORTED`.
- `:72-77` — `sendEmail` executes `emailSender.send(to, subject, body)` inside
  `smtpTemplate.executeWithoutResult(...)`. `NOT_SUPPORTED` suspends the caller transaction; the
  SMTP call therefore observes `TransactionSynchronizationManager.isActualTransactionActive() ==
  false` even when invoked from `NotificationEventConsumer.doProcess` inside the dedup transaction.
- `:73-77` — the SMTP call is **not** wrapped in try/catch. A send failure propagates, rolls back
  the dedup transaction, leaves no dedup row, and lets Kafka retry.

Why not `@Transactional(NOT_SUPPORTED)` on the method: the same proxy-fragility argument that
removed `@Transactional` from `processEvent`. The project uses an `EmailSender` SPI rather than
calling `JavaMailSender` directly; production wiring uses `SmtpEmailSender`, whose `send(...)` calls
`JavaMailSender.send(message)` (`SmtpEmailSender.java:34`) inside the `NOT_SUPPORTED` callback. Its
only catch converts `MessagingException` to `IllegalStateException` and rethrows
(`SmtpEmailSender.java:36-38`), so SMTP failure still propagates to the consumer.

## 5. Email Duplicate-Send Fix — `REQUIRES_NEW` + swallow

`EmailService.java`:

- `:49-50` — `emailLogTemplate` = `new TransactionTemplate(transactionManager)` with
  `PROPAGATION_REQUIRES_NEW`.
- `:79-88` — the `EmailLog` is built and persisted only **after** the SMTP send succeeds. The save
  runs inside `emailLogTemplate`, i.e. in an independent transaction that survives a later consumer
  rollback.
- `:89-94` — the whole log-save block is caught; it logs at WARN and **does not rethrow**.

Why rethrow is wrong: after C17, a rethrow would roll back the dedup transaction, leaving no dedup
row. Kafka would retry the event and `sendEmail` would call SMTP again for an email the user already
received. Swallowing converts "log write failed" into "email delivered, log incomplete", which is
strictly preferable to a duplicate email.

Why `REQUIRES_NEW` is not the reason for the swallow: `REQUIRES_NEW` provides isolation from the
consumer transaction's eventual rollback; the swallow specifically prevents a resend.

## 6. Red Evidence — honest status

**RED STATUS: NOT CAPTURED BY EXECUTION.** The pre-fix production code is HEAD `eb2d975`, but the
workspace already contained the uncommitted 4B.2 fix before this session. The task forbids
`git stash/reset/checkout/restore`, so reverting to HEAD to run R1/R3 red is prohibited. The
following is what would be red at HEAD, derived from the HEAD source cited in §2, plus the actual
current status. No fabricated red output is claimed.

| Test | Pre-fix (HEAD) expected result | Current status | Label |
|---|---|---|---|
| R1 `failureAfterDedup_DoesNotCommitDedupRow_AndRetryProcesses` | **RED**: HEAD `saveAndFlush` (`HEAD:65`) commits the dedup row before `doProcess`; assertion "no row for eventId" would fail (1 row), and the retry would not invoke `doProcess` again (`calls == 1`). | PASS | `RED_NOT_CAPTURED` (inherited fix; revert forbidden) |
| R2a `smtp_RunsOutsideCallerTransaction` | PASS at HEAD only by accident (proxy bypass). | PASS | `GREEN_INVARIANT` |
| R2b `doProcess_RunsInsideDedupTransaction` | **RED** at HEAD: no transaction exists, so `isActualTransactionActive()` is `false`. | PASS | `RED_IN_PRINCIPLE_NOT_CAPTURED` |
| R3 `logSaveFailure_DoesNotResendOrThrow` | HEAD would throw from the unguarded log save (`HEAD:49`) — not a resend, because HEAD's dedup row was already committed and the retry would be skipped. The duplicate-send defect materialises **after C17 alone**; this is exactly why the three fixes are coupled. | PASS | `RED_NOT_CAPTURED`; defect is latent-until-C17 |
| R3b `logSaveFailure_ConsumerDedupCommits` | Same coupling: after C17 without the swallow, dedup would roll back. At HEAD it passes for the wrong reason. | PASS | `GREEN_INVARIANT` |
| R4 `inventoryConsumer_CommitsDedupRow` | PASS | PASS | `GREEN_INVARIANT` |
| R5 `paymentConsumer_CommitsDedupRow` | PASS | PASS | `GREEN_INVARIANT` |

Assertion texts (current):

- R1: `assertFalse(processedEventRepository.existsByEventIdAndConsumerGroup(...), "dedup row must not survive a failed processing transaction")`; `assertEquals(2, consumer.calls.get(), "the retry must invoke doProcess again")`.
- R2a: `assertTrue(recordingEmailSender.transactionActiveFlags.stream().noneMatch(Boolean::booleanValue), "SMTP must not observe an active transaction: " + flags)`.
- R2b: `assertEquals(Boolean.TRUE, consumer.transactionActiveInsideDoProcess, "doProcess must run inside the dedup TransactionTemplate transaction")`.
- R3: `assertEquals(1, sender.sends.get(), "SMTP must be called exactly once even when the email log save fails")`.
- R3b: `assertTrue(processedEventRepository.existsByEventIdAndConsumerGroup(eventId, consumerGroup), "consumer dedup row must commit when only the email log save fails")`.

Test design notes:

- Real Testcontainers PostgreSQL + Redis via `AbstractIntegrationTest`; no H2.
- No `@MockBean` / `@MockitoBean` / `@Mock` for `EmailService` or Kafka. The SMTP boundary is
  observed through a `@TestConfiguration` `@Primary` bean implementing the project's `EmailSender`
  SPI, which records `TransactionSynchronizationManager.isActualTransactionActive()` at send time.
  The log-save failure is injected with a JDK dynamic proxy around `EmailLogRepository` that throws
  only from `save`; it is not a mock of `EmailService`.
- R1/R2b drive the production `AbstractIdempotentConsumer` through test-only subclasses that
  override only `doProcess`; production consumers are not altered by the tests.
- No class-level `@Transactional`; every test has `@Timeout(20, SECONDS)`.

## 7. Production Fix — file-by-file summary

| File | Change | Lines |
|---|---|---:|
| `messaging/consumer/AbstractIdempotentConsumer.java` | Constructor injection of `PlatformTransactionManager`; `dedupTemplate(REQUIRED)`; `@Transactional` removed; `saveAndFlush` → `save`; dedup check + save + `doProcess` in one callback; duplicate detection via `existsByEventIdAndConsumerGroup`; failure rethrows. | +62 / −17 |
| `notification/email/EmailService.java` | Removed the in-service 3-attempt retry and the pre-send log write; added `smtpTemplate(NOT_SUPPORTED)`; added `emailLogTemplate(REQUIRES_NEW)`; log save moved after send and wrapped in WARN-and-swallow; SMTP failure propagates. | +95 / −57 |
| `messaging/consumer/NotificationEventConsumer.java` | Constructor plumbing only: adds `PlatformTransactionManager` and delegates to `super`; `doProcess` body unchanged; send remains direct and uncaught. | +19 / −4 |
| `messaging/consumer/InventoryEventConsumer.java` | Constructor plumbing only (compile-required after the superclass signature change). `doProcess` unchanged. | +18 / −4 |
| `messaging/consumer/OrderEventConsumer.java` | Constructor plumbing only. `doProcess` unchanged. | +18 / −4 |
| `messaging/consumer/PaymentEventConsumer.java` | Constructor plumbing only. `doProcess` unchanged. | +18 / −4 |

`git diff --stat` for production (excluding the pre-existing 4B.1 `InventoryService.java`):

```text
 .../consumer/AbstractIdempotentConsumer.java       |  62 ++++++++--
 .../messaging/consumer/InventoryEventConsumer.java |  18 ++-
 .../consumer/NotificationEventConsumer.java        |  19 ++-
 .../messaging/consumer/OrderEventConsumer.java     |  18 ++-
 .../messaging/consumer/PaymentEventConsumer.java   |  18 ++-
 .../bookstore/notification/email/EmailService.java |  95 +++++++++-----
```

## 8. Green Evidence — R1–R5

Command:

```powershell
.\mvnw.cmd "-Dtest=ConsumerDedupAndSmtpBoundaryIntegrationTest,...13 classes..." test
```

New class result: `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0` (class time 6.814 s).

| Test | Result | Elapsed |
|---|---|---:|
| R1 `failureAfterDedup_DoesNotCommitDedupRow_AndRetryProcesses` | PASS | 0.058 s |
| R2a `smtp_RunsOutsideCallerTransaction` | PASS | 0.094 s |
| R2b `doProcess_RunsInsideDedupTransaction` | PASS | 0.046 s |
| R3 `logSaveFailure_DoesNotResendOrThrow` | PASS | 0.030 s |
| R3b `logSaveFailure_ConsumerDedupCommits` | PASS | 0.041 s |
| R4 `inventoryConsumer_CommitsDedupRow` | PASS | 0.034 s |
| R5 `paymentConsumer_CommitsDedupRow` | PASS | 0.069 s |

Runtime log confirms both target behaviours: after the forced failure the retry re-enters
`doProcess`, and the log-save failure emits
`Email delivered to ... but log save failed (no retry, no resend)` at WARN while the test passes.

## 9. PaymentService + Canonical Regression

Command (same run, 13 classes): `Tests run: 93, Failures: 0, Errors: 0, Skipped: 0`, total time
`02:29 min`, `BUILD SUCCESS`.

Per-class (Surefire XML):

| Class | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| `concurrency.PaymentConcurrencyIntegrationTest` (T5/T6/T7) | 3 | 0 | 0 | 0 |
| `concurrency.PaymentTransactionBoundaryIntegrationTest` (T8/T9/T10/T11) | 4 | 0 | 0 | 0 |
| `controller.PaymentControllerIntegrationTest` | 5 | 0 | 0 | 0 |
| `messaging.ConsumerDedupAndSmtpBoundaryIntegrationTest` | 7 | 0 | 0 | 0 |
| `messaging.consumer.AbstractIdempotentConsumerTest` | 7 | 0 | 0 | 0 |
| `messaging.consumer.KafkaConsumerIntegrationTest` | 5 | 0 | 0 | 0 |
| `service.InventoryBatchCacheAndRaceIntegrationTest` | 5 | 0 | 0 | 0 |
| `service.InventoryConcurrencyIntegrationTest` | 4 | 0 | 0 | 0 |
| `service.InventoryServiceUnitTest` | 18 (nested included) | 0 | 0 | 0 |
| `service.OrderPaymentStateMachineIntegrationTest` | 12 | 0 | 0 | 0 |
| `service.OrderServiceIntegrationTest` | 7 | 0 | 0 | 0 |
| `service.PaymentIntegrationTest` | 6 | 0 | 0 | 0 |
| `service.PaymentServiceIntegrationTest` | 10 | 0 | 0 | 0 |

T5–T11 identification (test methods):

- T5 `twoConcurrentProcessPaymentCalls_invokeProviderExactlyOnce` — PASS.
- T6 `twoConcurrentRefundPaymentCalls_invokeProviderExactlyOnce` — PASS.
- T7 `twoConcurrentCancelPendingPaymentCalls_releaseInventoryOnce` — PASS.
- T8 `processPaymentCalledWithinCallerTransaction_invokesProviderWithNoActiveTransaction` — PASS.
- T9 `refundPaymentCalledWithinCallerTransaction_invokesProviderWithNoActiveTransaction` — PASS.
- T10 `cancelOwnOrderPaidOrder_refundsOutsideTransactionAndStillCancelsOrder` — PASS.
- T11 `adminCancelOrderPaidOrder_refundsOutsideTransactionAndStillCancelsOrder` — PASS.

No `UnexpectedRollbackException`, no `CannotCreateTransactionException`, and no ERROR-level
nested-transaction conflict appeared in the run.

## 10. Wave 4B.1 Preservation

`InventoryBatchCacheAndRaceIntegrationTest`: `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`.
`InventoryConcurrencyIntegrationTest`: `4 / 0 / 0 / 0`. R4 also proves the Inventory consumer path
still commits its dedup row under the new template. The 4B.1 production files were not touched.

## 11. Existing Test Adjustments

| File | Lines | Reason |
|---|---|---|
| `messaging/consumer/AbstractIdempotentConsumerTest.java` | `17-18, 33-34, 42-43, 63, 74-81, 88, 101, 113, 125-126, 136-137, 158-160` | Mock `PlatformTransactionManager` + `SimpleTransactionStatus`; construct with the new superclass signature; `saveAndFlush` stubs → `save`; duplicate test now stubs `existsByEventIdAndConsumerGroup` instead of `DataIntegrityViolationException`. Assertions retained; one stronger `never().save(...)` assertion added. |
| `messaging/consumer/InventoryEventConsumerTest.java` | `59-61` | `saveAndFlush` stubs/verifications → `save`. |
| `messaging/consumer/NotificationEventConsumerTest.java` | `162-167` | `saveAndFlush` stubs/verifications → `save`. |
| `messaging/consumer/PaymentEventConsumerTest.java` | `59-61` | `saveAndFlush` stubs/verifications → `save`. |
| `notification/email/EmailServiceTest.java` | `32-34, 40-91` | Removed the initial `save` stub; replaced the two in-service retry tests (`retryThenSuccess`, `allRetriesFailed`) with `smtpFailurePropagates`, because 4B.2 deliberately moves retry authority to Kafka and makes SMTP failure propagate once. This is a **contract replacement**, not a silent weakening: the current asserted contract is "exactly one send, exception propagates, no log write, no success metric". The success/log/`SENT` assertions are retained and tightened with an `ArgumentCaptor`. |

No test was `@Disabled`. No assertion was deleted without the behaviour being intentionally
reversed by this wave; the reversed-retry case is called out explicitly above.

## 12. Scope Compliance

`git diff --name-only` (verbatim):

```text
src/main/java/com/example/bookstore/messaging/consumer/AbstractIdempotentConsumer.java
src/main/java/com/example/bookstore/messaging/consumer/InventoryEventConsumer.java
src/main/java/com/example/bookstore/messaging/consumer/NotificationEventConsumer.java
src/main/java/com/example/bookstore/messaging/consumer/OrderEventConsumer.java
src/main/java/com/example/bookstore/messaging/consumer/PaymentEventConsumer.java
src/main/java/com/example/bookstore/notification/email/EmailService.java
src/main/java/com/example/bookstore/service/InventoryService.java
src/test/java/com/example/bookstore/messaging/consumer/AbstractIdempotentConsumerTest.java
src/test/java/com/example/bookstore/messaging/consumer/InventoryEventConsumerTest.java
src/test/java/com/example/bookstore/messaging/consumer/NotificationEventConsumerTest.java
src/test/java/com/example/bookstore/messaging/consumer/PaymentEventConsumerTest.java
src/test/java/com/example/bookstore/notification/email/EmailServiceTest.java
```

Untracked files:

```text
WAVE4B_2_REPORT.md
src/main/resources/db/migration/V9__add_inventory_history_idempotency.sql   (pre-existing 4B.1)
src/test/java/com/example/bookstore/messaging/ConsumerDedupAndSmtpBoundaryIntegrationTest.java
src/test/java/com/example/bookstore/service/InventoryBatchCacheAndRaceIntegrationTest.java       (pre-existing 4B.1)
```

Compliance notes:

- `InventoryService.java`, the V9 migration, and `InventoryBatchCacheAndRaceIntegrationTest.java`
  are pre-existing 4B.1 working-tree changes. They are **not** part of this wave and were not
  modified.
- The three non-Notification consumers were touched only to pass the new
  `PlatformTransactionManager` to `super` (compile-required); their `doProcess` logic is unchanged.
- The four extra test files were updated only because the production contract changed
  (`saveAndFlush` → `save`, SMTP retry removed). `KafkaConsumerIntegrationTest` needed no change.
- No schema migration was added. `processed_events` was not altered.
- No `@Transactional`, `@Retryable`, `saveAndFlush`, `redisTemplate.keys()`, or
  `EntityManager.clear()` was added.

## 13. Final Classification

- C17: **CLOSED** (real PostgreSQL; row absent after forced failure, retry reprocesses and commits once).
- P13 / P25 (latent): **PREVENTED** (`NOT_SUPPORTED`; SMTP observes no active transaction).
- NEW_DEFECT_NOTIFICATION_LOSS_AFTER_DEDUP: **CLOSED** (same mechanism as C17).
- NEW_DEFECT_EMAIL_DUPLICATE_ON_LOG_SAVE: **CLOSED** (`REQUIRES_NEW` + WARN swallow; one send, no throw, dedup committed).
- PaymentService T5–T11: **GREEN_INVARIANT_PRESERVED**.
- Wave 4B.1: **GREEN_INVARIANT_PRESERVED**.
- OutboxPublisherScheduler Kafka-in-tx: **DEFERRED → 4B.4**.
- Cross-service cache: **DEFERRED → 4B.3**.

Red-evidence caveat: R1 and R3 are `RED_NOT_CAPTURED`, not `RED_CAPTURED`. The inherited working
tree already contained the fix and the task forbids reverting to HEAD. R2/R4/R5 are
`GREEN_INVARIANT` and are not claimed as red.

## 14. Blockers / Residuals

### OPEN — SMTP success + consumer-tx commit failure → duplicate on retry

If `emailSender.send` succeeds, the `emailLog` is committed in its own `REQUIRES_NEW` transaction,
`doProcess` returns normally, and the consumer's dedup transaction then **fails to commit**
(network blip between `doProcess` returning and the commit flush, lock timeout, DB restart), Kafka
retries the message. Dedup was not committed, so the retry fires the same SMTP send again →
duplicate email.

This is the accepted trade-off of a non-transactional SMTP call without an outbox. Eliminating it
requires the `OutboxEventPublisher` pattern (write an outbox row inside the dedup transaction, send
the email from a separate worker after commit). Deferred to **4B.4**. It is a known limitation of
the 4B.2 design, **not** a regression introduced here.

### OPEN — test-only null-transaction-manager seam

`AbstractIdempotentConsumer` and the four consumers keep package-private/legacy constructors that
pass `null` instead of a `PlatformTransactionManager`; `EmailService` keeps a three-argument
unit-test constructor. Production Spring wiring always uses the transaction-aware constructors, but
a future production call site could bypass the template. Recommended cleanup: drop these
constructors once the unit tests can supply a stub transaction manager. Not changed in 4B.2 to
avoid expanding the test surface.

### DEFERRED — outbox for the Kafka notification path

Moving the Kafka notification path to `OutboxEventPublisher` + `AFTER_COMMIT` is out of 4B.2 scope
(4B.4).

### Environment notes

- Docker server 29.9.2 and Java 21 were available; Testcontainers PostgreSQL + Redis ran correctly.
- The Maven wrapper occasionally reports a non-zero PowerShell pipeline exit code even when Maven
  prints `BUILD SUCCESS`; all conclusions above are taken from Maven/Surefire output.
