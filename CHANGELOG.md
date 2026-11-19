# Changelog

All notable changes to the Bookstore API are documented in this file.

## Unreleased

### Refund record integrity

- `payment` gains three nullable columns via `V12__add_refund_record_columns.sql`: `refunded_amount`, `refunded_at`, `refund_transaction_id`.
- The refund path no longer overwrites `transaction_id` with the provider's refund id. The original payment transaction id is preserved; the provider's refund id is stored separately.
- Successful refunds record the full payment amount and refund timestamp; failed refunds leave all three refund fields `NULL`. No backfill of historical payments and no CHECK constraint — the rule is enforced in code.

### Transaction boundaries, outbox and history integrity

- Payment processing and refunds use dedicated phase fences: `REQUIRES_NEW` for the committed before/after phases and a narrow `NOT_SUPPORTED` fence around the provider call. The provider always observes `isActualTransactionActive() == false`.
- `V9` adds inventory-history idempotency; `V10` adds outbox claim columns; `V11` adds partial unique indexes enforcing once-per-lifetime order/payment history transitions.
- The outbox publisher uses a two-phase claim with a fenced success mark and a stale-claim reaper; Kafka sending happens outside the database transaction.
- Kafka consumers use transactional deduplication; email SMTP calls execute outside the database transaction.

### Notification delivery mode and consumer audit logging

- New `app.notifications.delivery-mode` property: `kafka` (default, env-overridable via `NOTIFICATIONS_DELIVERY_MODE`) or `in-process` (test profiles). `NotificationEventHandler` (in-process) and `NotificationEventConsumer` (kafka) are mutually exclusive, so an email is never sent by both paths for the same event.
- `NotificationEventConsumer` subscribes to `orders.events`, `payments.events`, and `notifications.events`.
- Order/Payment/Inventory consumers inject `AuditService` and record `EVENT_CONSUMED` (`AuditService.logEventConsumed`).
- Kafka-mode parity fix: `ORDER_CANCELLED` (published by admin cancellation) now maps to the order-status-changed email, matching the in-process handler that already fired for it.

### Test harness and diagnostics

- `PurchaseFlowE2ETest` now runs against Testcontainers PostgreSQL and Embedded Kafka instead of H2. Three legacy methods remain intentionally disabled with non-datasource reasons; the six active E2E tests pass on PostgreSQL.
- Added `SynchronizationDiagnosticTest` to characterize the provider boundary. In Spring 7.0.8, `NOT_SUPPORTED` clears the caller's synchronizations and installs a fresh, empty synchronization scope, so `isSynchronizationActive()` reads `true` with zero registered synchronizations. The `isActualTransactionActive() == false` invariant remains the correctness gate; the flag is diagnostic only.
- Repaired stale unit tests (`PaymentServiceTest`, `OrderServiceTest`, `NotificationEventIntegrationTest`, `DatabaseFlywayIntegrationTest`) so their expectations match current production semantics.

### Performance

- The catalog list endpoint now uses a cache-aware batch availability lookup: cached values are reused (keeping list and single-book responses field-for-field aligned) and only cache misses are fetched in one query. Removes the per-book query from the list path (`BookCatalogNPlusOneTest` green).

### Trace propagation

- `event_outbox` gains a `span_id` column (`V8__Add_outbox_span_id.sql`); the OpenTelemetry span id is captured at event creation time (`EventOutbox.spanId`).
- `OutboxPublisherScheduler` now publishes via `ProducerRecord` and attaches a W3C `traceparent` header (`00-{traceId}-{spanId}-01`) when a valid 32-hex trace id / 16-hex span id is available.
- `AbstractIdempotentConsumer.processEvent(String, Headers)` continues the incoming trace with `W3CTraceContextPropagator` unless a valid local (non-remote) span is already current; consumers now receive `ConsumerRecord<String, String>` and pass `record.headers()`.
- Kafka 4.1.2 note: the 6-arg `ConsumerRecord` constructor was removed; tests use the 5-arg variant and `record.headers()`.

### Flyway migrations

- Flyway manages schema versions V1 through V12: initial schema, demo data, order/payment/email/inventory/event tables, outbox span id, inventory-history idempotency, outbox claim columns, history idempotency constraints, and refund record columns.
- Verified on a clean PostgreSQL container: a single migration pass, each version applied exactly once with `success = true` (asserted in `DatabaseFlywayIntegrationTest`), and no second `Flyway` engine bean.
- Do not edit an already-applied migration; its checksum is validated at startup.

### Dead code and documentation cleanup

- Removed zero-reference code: unused event constants and loggers, unused repository queries, unused entity/DTO methods and constructors, unused accessors, redundant branches and imports, and the unused `TestDataBuilder` test helper.
- Deduplicated cache eviction in `BookService` and the single-item/batch release and finalize paths in `InventoryService`.
- Cleaned PMD style findings (fully-qualified names, parameter reassignment, missing braces, unused locals) and split `OrderService.updateOrderStatus` validation into focused helpers.
- Scoped SpotBugs to production sources: concurrency test fixtures deliberately ignore latch results and use static capture hooks; those findings are excluded by source pattern in `spotbugs-exclude.xml`.
- Audit, wave, and runbook documents were archived outside the repository. The project keeps `README.md` and `CHANGELOG.md` as its documentation, plus generated static-analysis/CI configuration.
- Removed obsolete `.gitignore` rules for the archived audit artifacts.

### Dependency and security remediation

- Spring Boot **4.0.6 -> 4.0.7** (spring-framework 7.0.8, spring-security 7.0.6, jackson 2.21.4).
- Patch overrides for newly published CVEs: **netty 4.2.17.Final**, **Tomcat 11.0.25**, **PostgreSQL driver 42.7.12**, **swagger-ui 5.32.14**.
- OWASP Dependency Check `assemblyAnalyzerEnabled=false` (pure-Java project; no dotnet prerequisite).
- **OWASP gate currently fails** (fail on CVSS >= 7) on advisories published after the 4.0.7 bump: spring-core 7.0.8 (CVE-2026-47884/47889/47890/47891/47892/47893/59282/59283/59313 and related, CVSS 7.5–9.8), spring-security-core 7.0.6 (CVE-2026-47841, 41707, 59270; CVSS 7.4–9.1), and DOMPurify 3.4.13 bundled in swagger-ui 5.32.14 (CVE-2026-116803/116804, CVSS 10.0). These are real upstream advisories; they are documented rather than suppressed and require a dependency upgrade.

### Verification

- `mvnw test`: **green** — 576 tests, 0 failures, 0 errors, 3 skipped (the intentionally disabled E2E methods).
- `mvnw clean verify -Pci -Dspring.profiles.active=test`: tests, JaCoCo, Checkstyle (0 violations), PMD, CPD and SpotBugs all pass. The gate then **fails at OWASP dependency-check** on newly published high/critical advisories in spring-core, spring-security-core and the DOMPurify copy bundled in swagger-ui (see "Dependency and security remediation"). No CVEs are suppressed to make the gate green; a dependency upgrade is required.
