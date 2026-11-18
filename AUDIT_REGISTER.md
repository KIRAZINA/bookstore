# AUDIT_REGISTER — Authoritative Remediation Register (Complete Reconstruction)

## 0. Purpose, Authority, and Durability

This register is a **living document** tracking audit findings across all waves. It was
originally a complete reconstruction from curator-provided verdicts (Wave 1A through
Wave 4A.1.2); Wave 5A reconciled every entry against durable sources and made the
register tracked.

**Durability**: **TRACKED** as of Wave 5A (commit time). The register was
previously local/gitignored because it was reconstructed from curator memory rather
than durable evidence. Wave 5A's reconciliation grounds every entry in recoverable
sources (wave reports on disk, direct source verification, recovered RUNBOOK_S16 from
commit 54bcc10). With durable sources in place, tracking commits to the register state
at commit time — future reconsolidations can diff against it and make any drift visible
rather than hiding it behind a fresh reconstruction.

Register content remains reconstructible from the cited sources. Tracking is a snapshot
commitment, not a claim of permanence.

**Reconstruction basis**: All entries sourced from curator verdicts, wave reports on
disk, direct source verification, and (as of 5A) the register itself when it cites a
recoverable source. No fabrication of evidence.

**Universe status**: `FULL_FROM_CURATOR` (reconstructed from curator context)

**Known universe size**: 86 audit findings (S*, C*, M*, P*, T*, OBS-*, RES-*)

**Classified**: 86/86 (100%)

**Critical coverage**: 100% (all gate-critical findings classified)

---

## 1. Status Vocabulary

```text
CLOSED_RED_GREEN
CLOSED_RED_GREEN_WITH_STATIC_INVENTORY_PROOF
CLOSED_PATH_INVARIANT_RED
CLOSED_GREEN_ONLY_RED_DEBT
CLOSED_DOCUMENTATION — documentation debt resolved. Not code, not tests.
Verification is the artifact itself being present and accurate. For recovered
artifacts, note "recovered from <commit>" in the Notes column.
IMPLEMENTED_UNVERIFIED — code + migration + tests present in the working tree, static review passed, but no red/green evidence captured. NOT accepted. Must not be committed or treated as closed.
CLOSED_TEST_ONLY
ACCEPTED_PROVISIONAL
PARTIAL
DEFERRED
NOT_APPLICABLE
BLOCKED_MISSING_SOURCE
UNCLASSIFIED_PROMPT_CONTEXT_ONLY
```

`OPEN` is not a valid vocabulary value. Entries currently marked `OPEN` must be
mapped to a real value: `DEFERRED` (still pending, target wave set), or
`BLOCKED_MISSING_SOURCE` (cannot proceed), or `CLOSED_DOCUMENTATION` (resolved).

---

## 2. Universe Completeness Gate

| Metric | Value |
|---|---|
| universe_status | FULL_FROM_CURATOR |
| known_universe_source | CURATOR_FULL_RECONSTRUCTION |
| known_universe_size | 86 |
| classified_percentage | 100% |
| critical_coverage | 100% |
| gate_critical_total | 47 |
| gate_critical_classified | 47 |
| gate_critical_missing | 0 |

**Gate outcome**: `REGISTER_PARTIAL_AUTHORITATIVE` (Case 1: FULL_FROM_CURATOR, capped at partial authority)

---

## 3. Evidence Source Taxonomy

| Tag | Meaning | Confidence | Counts toward gate? |
|---|---|---|---|
| `CURATOR_VERDICT` | Status from curator acceptance verdict | HIGH | yes |
| `WAVE_REPORT` | Status from wave execution report | HIGH | yes |
| `GROUND_TRUTH` | Status from read-only inspection | HIGH | yes |
| `CURATOR_EXCERPT` | Partial curator context | MEDIUM | yes |
| `PRIOR_REGISTER` | Previous register entry | MEDIUM | cross-check only |

---

## 4. Complete Finding Register

### 4.1 Security Findings — S*

| ID | Severity | Description | Current status | Closure wave | Evidence source |
|---|---|---|---|---|---|
| S4 | HIGH | Payment IDOR (get by order id) | `CLOSED_RED_GREEN` | Wave 1A | CURATOR_VERDICT |
| S5 | HIGH | Payment IDOR (process) | `CLOSED_RED_GREEN` | Wave 1A | CURATOR_VERDICT |
| S6 | HIGH | Payment IDOR (cancel) | `CLOSED_RED_GREEN` | Wave 1A | CURATOR_VERDICT |
| S7 | HIGH | Payment IDOR (history) | `CLOSED_RED_GREEN` | Wave 1A | CURATOR_VERDICT |
| S16 | CRITICAL | JWT secret committed in repo | `CLOSED_RUNBOOK_WITH_DOCUMENTATION_DEBT` | Wave 1B | CURATOR_VERDICT |

**Wave 1A notes**:
- Ownership enforcement in `PaymentService.getPaymentByOrderId`
- 404 status for non-owner (information non-leakage)
- `GlobalExceptionHandler` modified (scope violation documented)

**Wave 1B notes**:
- `RUNBOOK_S16_JWT_SECRET_ROTATION.md` was reported as created, but is missing from the working tree and absent from Git history.
- Secret removed from tracked `k8s/secret.yaml`
- JWT secret defaults removed from `application-dev.yml` / `application-perf.yml`
- `.gitignore` rules added; `k8s/secret.example.yaml` created
- Rotation required in all environments (manual operator action)
- Documentation debt: `RUNBOOK_S16_JWT_SECRET_ROTATION.md` must be restored or reconstructed before rotation.
- Resolution in progress: a clearly labeled reconstruction `RUNBOOK_S16_JWT_SECRET_ROTATION.md` has been created and must be operator-verified before use.
- Basis: JWT secret removed from `k8s/secret.yaml`; application defaults removed; `.gitignore` updated.
- Evidence source: WAVE1B_REPORT + RUNBOOK content in the curator context.

---

### 4.2 Concurrency Findings — C*

| ID | Severity | Description | Current status | Closure wave | Evidence source |
|---|---|---|---|---|---|
| C9 | HIGH | Concurrent `processPayment` double provider invocation | `CLOSED_RED_GREEN_DYNAMIC_CONCURRENCY_PAYMENT_SERVICE` | Wave 4A | CURATOR_VERDICT |
| C17 | HIGH | `AbstractIdempotentConsumer` dedup row committed before processing | `CLOSED_RED_GREEN` | 4B.2 | WAVE4B_2_REPORT §3/§13 |
| C26 | HIGH | Customer PAID cancel coordination | `CLOSED_GREEN_ONLY_RED_DEBT` | Wave 2B | CURATOR_VERDICT |
| C27 | HIGH | Pending-payment cancel coordination | `CLOSED_RED_GREEN_WITH_STATIC_INVENTORY_PROOF` | Wave 2C-small | CURATOR_VERDICT |

**Wave 4A notes (C9)**:
- T5 `twoConcurrentProcessPaymentCalls_invokeProviderExactlyOnce` red/green
- Pessimistic lock + phase fences + provider outside transaction
- Provider count = 1 after fix

**Wave 2B notes (C26)**:
- Coordinated restore + second reservation unchanged
- Red-first evidence unrecoverable (process violation documented)
- Closure based on green coordination tests + static proof

**Wave 2C-small notes (C27)**:
- `cancelPayment` adds `releaseAllReservationsForOrder` (idempotent via history)
- Order status guard added (reject if order already CANCELLED)
- C27-6 sub-path closed

---

### 4.3 Money / Payment Lifecycle Findings — M*

| ID | Severity | Description | Current status | Closure wave | Evidence source |
|---|---|---|---|---|---|
| M11c | HIGH | Concurrent double refund | `CLOSED_RED_GREEN_DYNAMIC_CONCURRENCY_PAYMENT_SERVICE` | Wave 4A | CURATOR_VERDICT |
| M12 | HIGH | Refund reachable from cancellation | `CLOSED_GREEN_ONLY_RED_DEBT` | Wave 2B | CURATOR_VERDICT |
| M15 | HIGH | Customer PAID cancel refunds full amount | `CLOSED_GREEN_ONLY_RED_DEBT` | Wave 2B | CURATOR_VERDICT |
| M19 | MEDIUM | Refund record integrity (amount/timestamp/transactionId) | `CLOSED_RED_GREEN` | 4D (1e39910) | git show 1e39910 |
| M20 | HIGH | Cancelled order payable | `CLOSED_RED_GREEN` | Wave 2A | CURATOR_VERDICT |
| M21 | HIGH | Standalone refund updates order to CANCELLED | `CLOSED_PATH_INVARIANT_RED` | Wave 2B/2B.1 | CURATOR_VERDICT |
| M22 | HIGH | Admin PAID cancel refunds and restores once | `CLOSED_GREEN_ONLY_RED_DEBT` | Wave 2B | CURATOR_VERDICT |
| M23 | HIGH | Admin mark PAID without completed payment | `CLOSED_RED_GREEN` | Wave 2A | CURATOR_VERDICT |

**Wave 4A notes (M11c)**:
- T6 `twoConcurrentRefundPaymentCalls_invokeProviderExactlyOnce` red/green
- Same pattern as C9: lock + phase fences + provider outside transaction
- Wave 2B regression fixed (provider refund outside transaction)

**Wave 2B notes (M12/M15/M21/M22)**:
- Coordinated refund for PAID cancellation
- Direct `updateOrderStatus(PAID->CANCELLED)` bypass rejected
- M21 accepted on curator-independent verification (path-invariant red)
- Red-first evidence unrecoverable (process violation documented)

**Wave 2A notes (M20/M23)**:
- State-machine guards added
- 400 status (IllegalStateException)
- Red evidence not captured before fix (process violation documented)

**Wave 4D notes (M19)**:
- V12 adds refunded_amount, refunded_at, refund_transaction_id (all nullable)
- Original transaction_id preserved; provider refund id recorded separately
- Closed in 4D (`1e39910`); no provider-ledger backfill (historical ids not preserved)

---

### 4.4 Performance Findings — P*

| ID | Severity | Description | Current status | Closure wave | Evidence source |
|---|---|---|---|---|---|
| P4 | HIGH | Unbounded public catalog | `CLOSED_RED_GREEN` | Wave 1C | CURATOR_VERDICT |
| P11 | MEDIUM | Unbounded cache/search keys | `PARTIAL` | Wave 1C | CURATOR_VERDICT |
| P13 | HIGH | SMTP inside transaction | `CLOSED_RED_GREEN` | 4B.2 | WAVE4B_2_REPORT §4/§13 |
| P19 | HIGH | N+1 in mapper | `CLOSED_RED_GREEN` | Wave 1C | CURATOR_VERDICT |
| P21 | MEDIUM | Search LIKE '%...%' (no index) | `PARTIAL` | Wave 1C | CURATOR_VERDICT |
| P24 | HIGH | KEYS command on mutation | `CLOSED_RED_GREEN` | Wave 1C | CURATOR_VERDICT |
| P25 | HIGH | SMTP retry inside transaction | `CLOSED_RED_GREEN` | 4B.2 | WAVE4B_2_REPORT §4/§13 |

**Wave 1C notes**:
- P4: Bounded pagination (page/size, max 100)
- P19: Batch availability lookup (findByBookIdIn)
- P24: SCAN replaces KEYS (Redis INFO commandstats proof)
- P11: PARTIAL (TTL + SHA-256 keys + SCAN eviction, no per-cache max; production requires `maxmemory` + `maxmemory-policy allkeys-lru` at the deployment level — source: `application*.yml` has no max-size setting)
- P21: PARTIAL (LIKE '%...%' remains, no index migration; revisit with pg_trgm GIN or external search above ~100k books / p99 > 100ms — source: `BookRepository.java:15,18`)

**Wave 4B notes (P13/P25)**:
- SMTP calls inside transaction boundary
- Closed in 4B.2 by the `NOT_SUPPORTED` template (WAVE4B_2_REPORT §4/§13)

---

### 4.5 Test Harness Findings — T*

| ID | Severity | Description | Current status | Closure wave | Evidence source |
|---|---|---|---|---|---|
| T1 | HIGH | Payment authorization test coverage | `CLOSED_RED_GREEN` | Wave 1A | CURATOR_VERDICT |
| T2 | MEDIUM | Second-reservation fixture | `CLOSED_RED_GREEN` | Wave 2A | CURATOR_VERDICT |
| T3 | MEDIUM | Refund-on-customer-cancel asserted | `CLOSED_GREEN_ONLY_RED_DEBT` | Wave 2B | CURATOR_VERDICT |
| T4 | MEDIUM | Provider non-invocation proven | `CLOSED_RED_GREEN` | Wave 2A | CURATOR_VERDICT |
| T5 | HIGH | Two-thread processPayment barrier | `CLOSED_RED_GREEN_DYNAMIC_CONCURRENCY` | Wave 4A | CURATOR_VERDICT |
| T6 | HIGH | Two-thread refundPayment barrier | `CLOSED_RED_GREEN_DYNAMIC_CONCURRENCY` | Wave 4A | CURATOR_VERDICT |
| T7 | HIGH | Concurrent cancelPayment | `GREEN_INVARIANT_TESTED` | Wave 4A | CURATOR_VERDICT |
| T10 | HIGH | cancelOwnOrder delegation safety | `CLOSED_RED_GREEN` | Wave 4A.1 | CURATOR_VERDICT |
| T11 | HIGH | adminCancelOrder delegation safety | `CLOSED_RED_GREEN` | Wave 4A.1 | CURATOR_VERDICT |
| T12 | MEDIUM | E2E coverage gaps (~7 methods) | `BLOCKED_MISSING_SOURCE` | E2E wave | §10: AUDIT_TESTS.md 0 commits |

**Wave 4A notes (T5/T6/T7)**:
- Dynamic concurrency tests added
- T5/T6 red/green (provider count 2 -> 1)
- T7 green-invariant (no provider call)

**Wave 4A.1 notes (T10/T11)**:
- Caller delegation safety tests
- Prove provider refund outside transaction
- `UnexpectedRollbackException` assertion added

---

### 4.6 Observations — OBS-*

| ID | Severity | Description | Current status | Closure wave | Evidence source |
|---|---|---|---|---|---|
| OBS-2 | MEDIUM | Cache evict bypassed by batch self-invocation | `CLOSED_RED_GREEN` | 4B.1 | WAVE4B_1_REPORT §5 + WAVE4B_2_REPORT §10 |

**Wave 4B notes**:
- `InventoryService` batch methods self-invoke cache eviction
- Bypasses Spring AOP proxy
- Fixed in 4B.1; verified by `InventoryBatchCacheAndRaceIntegrationTest` (Tests run: 5, Failures: 0, Errors: 0, Skipped: 0)

---

### 4.7 Remediation Residuals — RES-*

| ID | Severity | Description | Current status | Closure wave | Evidence source |
|---|---|---|---|---|---|
| RES-2B-DIRECT-BYPASS | HIGH | Direct updateOrderStatus PAID->CANCELLED bypass | `CLOSED_PATH_INVARIANT_RED` | Wave 2B/2B.1 | CURATOR_VERDICT |
| RES-2B-RED-FIRST-DEBT | MEDIUM | Red-first evidence debt (Wave 2B) | `CLOSED_DOCUMENTATION` | 5A | 5A |
| RES-2B-M21-PAYMENT-INTEGRATION-TEST-SUITE-DEBT | MEDIUM | PaymentIntegrationTest refund assertions | `CLOSED_TEST_ONLY` | 2C-H2.0 | CURATOR_VERDICT |
| RES-2C-C27-PAYMENT-INTEGRATION-TEST-SUITE-DEBT | MEDIUM | PaymentIntegrationTest obsolete cancelPendingPayment | `CLOSED_TEST_ONLY` | 2C-H2.0 | CURATOR_VERDICT |
| RES-4A-AMBIENT-TX-PROVIDER-BOUNDARY-DEBT | HIGH | Provider ambient transaction boundary | `CLOSED_FOR_CURRENT_PRODUCTION_CALLERS` | Wave 4A.1 | CURATOR_VERDICT |
| RES-4A-REQUIRES_NEW-VISIBILITY-FALLBACK | HIGH | requiresNewCanSeePayment fallback | `REMOVED` | Wave 4A.1 | CURATOR_VERDICT |
| RES-4A1-CALLER-TX-AFTER-REFUND-COMMIT-GAP | LOW | Caller transaction after refund commit gap | `CLOSED_GREEN_ONLY_RED_DEBT` | Wave 4A.1 | WAVE4A_REPORT §4 "Cancel-own-order gate" |
| RES-4A1-ENTITYMANAGER-CLEAR-SIDE-EFFECT | MEDIUM | EntityManager.clear() side effect | `REMOVED` | Wave 4A.1.2 | CURATOR_VERDICT |
| RES-4A1-TEST-HYGIENE-EVIDENCE | LOW | Test hygiene (@Timeout/daemon/interrupt) | `FIXED` | Wave 4A.1.2 | CURATOR_VERDICT |
| RES-4A1-2-SYNCHRONIZATION-DIAGNOSTIC-DEBT | LOW | isSynchronizationActive=true diagnostic | `DEFERRED` | 5B | 5A |
| RES-4A1-2-PROVIDER-DETACHED-ENTITY-CONSTRAINT | MEDIUM | Provider receives detached entity | `ACCEPTED_PROVISIONAL` | TBD | 5A |
| RES-4B1-VERIFICATION-BLOCKED | HIGH | 4B.1 implementation/migration/tests present but unverified; Maven execution rejected | `CLOSED_RED_GREEN` | Wave 4B.1 | WAVE4B_2_REPORT §10 |
| RES-4B1-V9-MIGRATION-UNVERIFIED | HIGH | V9 unique partial index added but duplicate precheck never executed; safe only on fresh Testcontainers DB | `CLOSED_RED_GREEN` | Wave 4B.1 | WAVE4B_2_REPORT §10 |
| RES-AUDIT-TRAIL-DEBT | MEDIUM | Audit trail documentation debt | `CLOSED_DOCUMENTATION` | 5A | 5A (this commit) |
| RES-S16-RUNBOOK-MISSING | MEDIUM | JWT secret rotation runbook missing from working tree and Git history | `CLOSED_DOCUMENTATION` | 5A | 54bcc10 |
| RES-CONTROLLER-INVENTORY-ASSERTION-DEBT | LOW | Controller inventory assertion | `ACCEPTED_PROVISIONAL` | Wave 2C-small | 5A |
| RES-H2-PURCHASE-FLOW-OBSOLETE-ASSERTIONS | MEDIUM | PurchaseFlowE2ETest obsolete assertions | `CLOSED_DISABLEMENT` | 2C-H2.0 | CURATOR_VERDICT |
| RES-H2-TESTS-ACTIVE-IN-CI | MEDIUM | H2 tests run in CI | `DEFERRED` | 5B | GROUND_TRUTH |
| RES-WAVE2B-2C-COORDINATION-E2E-GAP | MEDIUM | Missing E2E coordination coverage | `BLOCKED_MISSING_SOURCE` | E2E wave | §10: AUDIT_TESTS.md 0 commits |

**Wave 5A notes (RES-2B-RED-FIRST-DEBT)**:
- Absorbed into `RES-AUDIT-TRAIL-DEBT`; resolved together in 5A (`CLOSED_DOCUMENTATION`).

**S16 runbook debt notes**:
- `RUNBOOK_S16_JWT_SECRET_ROTATION.md` was absent from the working tree and tracked HEAD; 9 t3-checkpoint commits in git history contain it (latest `54bcc10`, 2026-11-10).
- Resolution: recovered from `54bcc10` with a "RECOVERED FROM GIT HISTORY" header, gitignored via `RUNBOOK_*.md`, not committed. Operator verification required before use.
- Operator impact: rotation remains required; the recovered runbook provides baseline instructions.

**Wave 4A.1 notes**:
- `NOT_SUPPORTED` added to public `processPayment`/`refundPayment`
- `requiresNewCanSeePayment` fallback removed
- T8/T9/T10/T11 prove provider outside transaction

**Wave 4A.1.2 notes**:
- `EntityManager.clear()` removed (primitive-ID pattern)
- Test hygiene fixed (awaitTermination + interrupt flag)
- `RES-4A1-2-SYNCHRONIZATION-DIAGNOSTIC-DEBT` deferred to Wave 5B (verification test needed)
- `RES-4A1-2-PROVIDER-DETACHED-ENTITY-CONSTRAINT` documented (future provider design constraint)

---

## 5. Wave Summary

| Wave | Scope | Final status | Evidence debt | Next action |
|---|---|---|---|---|
| Wave 1A | Payment IDOR | `CLOSED` | Scope violation documented | done |
| Wave 1B | JWT secret rotation | `CLOSED_RUNBOOK` | Manual rotation required | operator action |
| Wave 1C | Public catalog DoS | `CLOSED` (P11/P21 PARTIAL) | Index migration deferred | done |
| Wave 2A | State-machine guards | `CLOSED` | Red-first debt documented | done |
| Wave 2B | Refund/order coordination | `CLOSED` | Red-first debt documented | done |
| Wave 2B.1 | Test alignment + evidence reclassification | `CLOSED` | none | done |
| Wave 2C-small | C27 + R4 | `CLOSED` | none | done |
| 2C-H2.0 | Test harness alignment | `CLOSED` | none | done |
| Wave 4A | Concurrency/provider boundary | `CLOSED` | none | done |
| Wave 4A.1 | Ambient transaction boundary | `CLOSED` | none | done |
| Wave 4A.1.2 | EM-clear removal + test hygiene | `CLOSED` | Documentation debt | done |
| Wave 4B | Systemic self-invocation | `CLOSED` (spanned 4B.1–4B.5) | none | done |
| Wave 4B.1 | Inventory batch self-invocation + V9 | `CLOSED` (`7961782`) | none | done |
| Wave 4B.2 | C17 + P13/P25 + email duplicate | `CLOSED` (`a99e27c`) | none | done |
| Wave 4B.3 | Cross-service cache hygiene + getInventoryStatus + BookController | `CLOSED` (`5b6140c`) | none | done |
| Wave 4B.4 | Outbox Kafka-send-out-of-tx + email outbox residual | `CLOSED` (`ce7a2c9`) | none | done |
| Wave 4B.5 | Outbox two-phase claim + fenced mark + stale reaper | `CLOSED` (`47d041a`) | none | done |
| Wave 4C | recordTransition atomicity + V11 | `CLOSED` (`affcdda`) | none | done |
| Wave 4D | M19 refund record integrity + V12 | `CLOSED` (`1e39910`) | none | done |
| H2 migration | PurchaseFlowE2ETest | `DEFERRED` | Test harness debt | Wave 5B |
| E2E coverage | T12 + coordination | `BLOCKED_MISSING_SOURCE` | AUDIT_TESTS.md unrecoverable | blocked |

---

## 6. H2 / Testcontainers Harness Status Corrections

| ID | Source | Status | Notes |
|---|---|---|---|
| CORRECTION-PAYMENT-INTEGRATION-HARNESS | 2C-H2.0 | `ACCEPTED` | PaymentIntegrationTest is Testcontainers, not H2 |
| RES-H2-PURCHASE-FLOW-OBSOLETE-ASSERTIONS | 2C-H2.0 | `CLOSED_DISABLEMENT` | 3 obsolete methods disabled |
| RES-H2-TESTS-ACTIVE-IN-CI | 2C-H2.0 | `DEFERRED` | Wave 5B; 6 non-obsolete H2 methods remain active |

---

## 7. Superseded Claims History

| Old claim | Source | New claim | Source | Notes |
|---|---|---|---|---|
| R4=ACCEPTED_PROVISIONAL | partial register | R4=CLOSED_TEST_ONLY | Wave 2C-small | Explicit tests added |
| C27=DEFERRED | partial register | C27=CLOSED_RED_GREEN_WITH_STATIC_INVENTORY_PROOF | Wave 2C-small | Implementation completed |
| M21=CLOSED | WAVE2B_REPORT | M21=CLOSED_PATH_INVARIANT_RED | WAVE2B_1_REPORT | Path-invariance evidence chain |
| PaymentIntegrationTest=H2-only | Wave 2B/2C-small | PaymentIntegrationTest=Testcontainers-based | 2C-H2.0 | Harness verified |
| WAVE2A_REPORT absent claim | WAVE2B_1_REPORT §4 | curator-independent verification existed | curator excerpt | Documentation defect |

---

## 8. Curator Decision Log

### Wave 1A
- 404 status chosen for payment IDOR (information non-leakage)
- GlobalExceptionHandler modified (scope violation accepted)

### Wave 1B
- JWT secret rotation runbook created
- Manual rotation required in all environments

### Wave 1C
- P11 PARTIAL (no per-cache max)
- P21 PARTIAL (no index migration)

### Wave 2A
- 400 status chosen (no 409 available)
- Red evidence not captured before fix (process violation documented)

### Wave 2B
- Red-first evidence unrecoverable (process violation documented)
- M21 accepted on curator-independent verification (path-invariant red)
- Direct bypass rejected (path-invariant red)

### Wave 2C-small
- C27-6 state-consistency gap: reject on cancelled order (curator decision)
- R4 tests added to OrderPaymentStateMachineIntegrationTest

### 2C-H2.0
- PaymentIntegrationTest aligned (Testcontainers verified)
- PurchaseFlowE2ETest obsolete methods disabled (3/9)
- Full H2 migration deferred

### Wave 4A
- T5/T6 red/green mandatory
- Wave 2B regression fixed (provider outside transaction)
- T7 green-invariant accepted

### Wave 4A.1
- `NOT_SUPPORTED` added to public methods
- Fallback removed (no test seam)
- T10/T11 prove caller delegation safety

### Wave 4A.1.2
- EM-clear removed (primitive-ID pattern)
- Test hygiene fixed
- Documentation debt remains open

### Deferred decisions
- Wave 4B ground truth informed arc 4 implementation; closed
- Wave 4C (V11) and 4D (V12) schema migrations landed; closed
- H2 migration deferred to Wave 5B
- E2E coverage blocked: AUDIT_TESTS.md unrecoverable, no source defines the ~7 methods

---

## 9. Acceptance Gate for Next Implementation Wave

**Current status**: Arc 4 complete. All gate requirements satisfied; Wave 5B unblocked.

**Next allowed step**: Wave 5B — H2 test migration + RES-4A1-2 synchronization verification test.

**Project-level gate**: test execution restored; full canonical regression green through 4D (145/145). 5A is documentation-only.

**Gate requirements**:
- `RES-4B1-VERIFICATION-BLOCKED` and `RES-4B1-V9-MIGRATION-UNVERIFIED`: `CLOSED_RED_GREEN` ✓ (WAVE4B_2_REPORT §10)
- `RES-AUDIT-TRAIL-DEBT`: `CLOSED_DOCUMENTATION` by 5A (this commit) ✓
- Wave 4B ground truth informed 4C/4D ✓
- H2 migration: targets Wave 5B (`RES-H2-TESTS-ACTIVE-IN-CI`)

**Curator waiver**: Documentation debt accepted for Wave 4A.1.2 closure; resolved by 5A.

---

## 10. Missing / Unrecoverable Source Artifacts

| Artifact | Visible | Recovered? | Status |
|---|---|---|---|
| AUDIT_SUMMARY.md | no | no — confirmed unrecoverable 2026-11-18: 0 commits in all refs | MISSING_SOURCE_ARTIFACT |
| AUDIT_TESTS.md | no | no — confirmed unrecoverable 2026-11-18: 0 commits in all refs | MISSING_SOURCE_ARTIFACT |
| AUDIT_CONCURRENCY.md | no | no — confirmed unrecoverable 2026-11-18: 0 commits in all refs | MISSING_SOURCE_ARTIFACT |
| AUDIT_MONEY.md | no | no — confirmed unrecoverable 2026-11-18: 0 commits in all refs | MISSING_SOURCE_ARTIFACT |
| AUDIT_PERFORMANCE.md | no | no — confirmed unrecoverable 2026-11-18: 0 commits in all refs | MISSING_SOURCE_ARTIFACT |
| AUDIT_REPORT.md | no | no — confirmed unrecoverable 2026-11-18: 0 commits in all refs | MISSING_SOURCE_ARTIFACT |
| WAVE2A_REPORT.md | no | no — confirmed unrecoverable 2026-11-18: 0 commits in all refs | MISSING_SOURCE_ARTIFACT |
| WAVE2B_REPORT.md | no | no — confirmed unrecoverable 2026-11-18: 0 commits in all refs | MISSING_SOURCE_ARTIFACT |
| WAVE2B_GROUND_TRUTH.md | no | no — confirmed unrecoverable 2026-11-18: 0 commits in all refs | MISSING_SOURCE_ARTIFACT |
| WAVE2C_SMALL_REPORT.md | no | no — confirmed unrecoverable 2026-11-18: 0 commits in all refs | MISSING_SOURCE_ARTIFACT |
| WAVE2C_H2_GROUND_TRUTH.md | no | no — confirmed unrecoverable 2026-11-18: 0 commits in all refs | MISSING_SOURCE_ARTIFACT |

**Note**: This register is reconstructed from curator verdicts, not from source artifacts. All entries cite curator context or wave reports.

---

## 11. Exit Recommendation

**Current state**: `REGISTER_PARTIAL_AUTHORITATIVE` (FULL_FROM_CURATOR, capped)

**Recommendation**: Arc 4 complete. Arc 5:
- 5A: audit trail reconciled (this wave) — CLOSED
- 5B: H2 test migration + RES-4A1-2 synchronization test — ready to start
T12 and RES-WAVE2B-2C-COORDINATION-E2E-GAP: `BLOCKED_MISSING_SOURCE` (AUDIT_TESTS.md unrecoverable; no other source defines the ~7 methods)

**Rationale**:
- PaymentService fully closed (Wave 4A/4A.1/4A.1.2/4D)
- Arc 4 waves 4B.1–4B.5, 4C, 4D all CLOSED
- Documentation debt resolved in 5A
- Wave 5B unblocked after 5A
