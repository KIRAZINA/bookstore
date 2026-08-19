# Changelog

All notable changes to the Bookstore API are documented in this file.

## Unreleased

### Trace propagation

- `event_outbox` gains a `span_id` column (`V8__Add_outbox_span_id.sql`); the OpenTelemetry span id is captured at event creation time (`EventOutbox.spanId`).
- `OutboxPublisherScheduler` now publishes via `ProducerRecord` and attaches a W3C `traceparent` header (`00-{traceId}-{spanId}-01`) when a valid 32-hex trace id / 16-hex span id is available.
- `AbstractIdempotentConsumer.processEvent(String, Headers)` continues the incoming trace with `W3CTraceContextPropagator` unless a valid local (non-remote) span is already current; consumers now receive `ConsumerRecord<String, String>` and pass `record.headers()`.
- Kafka 4.1.2 note: the 6-arg `ConsumerRecord` constructor was removed; tests use the 5-arg variant and `record.headers()`.

### Notification delivery mode and consumer audit logging

- New `app.notifications.delivery-mode` property: `kafka` (default, env-overridable via `NOTIFICATIONS_DELIVERY_MODE`) or `in-process` (test profiles). `NotificationEventHandler` (in-process) and `NotificationEventConsumer` (kafka) are mutually exclusive, so an email is never sent by both paths for the same event.
- `NotificationEventConsumer` subscribes to `orders.events`, `payments.events`, and `notifications.events`.
- Order/Payment/Inventory consumers inject `AuditService` and record `EVENT_CONSUMED` (`AuditService.logEventConsumed`).
- Kafka-mode parity fix: `ORDER_CANCELLED` (published by admin cancellation) now maps to the order-status-changed email, matching the in-process handler that already fired for it.

### Flyway single-migration proof

Empirically verified on a clean PostgreSQL container:

- Startup log shows exactly one migration pass — `Migrating schema "public" to version "1 - Initial schema"` through `"8 - Add outbox span id"`, then `Successfully applied 8 migrations to schema "public", now at version v8` (no second pass).
- `flyway_schema_history` contains exactly versions 1-8, each applied once with `success = true` (asserted in `DatabaseFlywayIntegrationTest`).
- No `Flyway` engine bean exists in the context; `FlywayConfig` runs migration as its construction side effect and is the sole trigger. Spring Boot's `FlywayAutoConfiguration` is inactive because Flyway 11.x no longer ships the `DatabaseFactory` class its condition requires.

### Dead config and template hardening

- Removed unused keys `app.inventory.cache-ttl-seconds` and `app.outbox.batch-size` from `application.yml`, `application-dev.yml`, `application-perf.yml`, `application-perf-test.yml`, and the orphaned `APP_INVENTORY_CACHE_TTL_SECONDS` / `APP_OUTBOX_BATCH_SIZE` env vars from `k8s/configmap.yaml`.
- `EmailTemplateEngine.render` neutralizes unsupplied placeholders and now logs a `WARN` naming the missing placeholders, so a blank field can be traced back to a caller bug instead of silently hiding it.

### Dependency and security remediation

- Spring Boot **4.0.6 -> 4.0.7** (spring-framework 7.0.8, spring-security 7.0.6, jackson 2.21.4).
- Patch overrides for newly published CVEs: **netty 4.2.17.Final**, **Tomcat 11.0.25**, **PostgreSQL driver 42.7.12**, **swagger-ui 5.32.14**.
- OWASP Dependency Check `assemblyAnalyzerEnabled=false` (pure-Java project; no dotnet prerequisite).
- OWASP gate (fail on CVSS >= 7) passes. Residual findings are informational (MEDIUM, below threshold): jackson-databind 2.21.4 (CVE-2026-54515, 5.3), kafka-clients 4.1.2 (CVE-2026-41115, 4.3), log4j-api 2.25.4 (CVE-2026-49844, 5.9).

### Documentation

- `PROJECT_ARCHITECTURE.md` rewritten in engineering voice: the audit-style section (idiosyncrasies / technical-debt list) is now "Design Notes & Known Limitations" describing the system as designed; the file-tree section reflects the committed repository only.

### Verification

- `mvnw clean verify -Pci -Dspring.profiles.active=test` is green: all tests pass, JaCoCo coverage checks met, 0 checkstyle violations, PMD/SpotBugs clean, OWASP passes.