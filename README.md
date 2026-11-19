# Bookstore API

REST API for an online bookstore built with Spring Boot 4, Java 21, PostgreSQL, Redis, and Kafka.

## Features
- User authentication & authorization (JWT, RBAC)
- Book catalog with Redis caching
- Shopping cart and order management
- Payment processing, including refunds with preserved transaction ids and recorded refund amount/timestamp
- Inventory management with optimistic locking
- Event-driven architecture (Kafka, transactional outbox with two-phase claim)
- Distributed rate limiting
- Kubernetes deployment
- Observability (Prometheus, Grafana, OpenTelemetry)

## Tech Stack
- Java 21
- Spring Boot 4.0.7
- Spring Security
- Spring Data JPA
- PostgreSQL 16
- Redis 7
- Apache Kafka
- Flyway (V1–V12 migrations)
- Testcontainers
- JUnit 5, Mockito, Awaitility, Embedded Kafka
- Kubernetes
- Prometheus + Grafana
- OpenTelemetry

## Quick Start

### Prerequisites
- Java 21+
- Docker Desktop (or equivalent Docker runtime) — required for PostgreSQL, Redis, and Kafka via `docker-compose.yml`, and for the Testcontainers-based integration tests
- No separate Maven install needed; the wrapper (`mvnw` / `mvnw.cmd`) is checked in

### Start dependencies
```bash
docker compose up -d
```

### Start application
```bash
# Windows
mvnw.cmd spring-boot:run

# macOS/Linux
./mvnw spring-boot:run
```

The API runs at `http://localhost:8080`. Swagger UI: `http://localhost:8080/swagger-ui.html`.

### Notification delivery mode
Email notifications can be delivered either in-process or through the Kafka event bus:

- `NOTIFICATIONS_DELIVERY_MODE=kafka` (default) — `NotificationEventConsumer` renders and sends
  emails from events consumed off `orders.events` / `payments.events` / `notifications.events`.
- `NOTIFICATIONS_DELIVERY_MODE=in-process` — `NotificationEventHandler` sends emails directly
  after the business transaction commits (no Kafka required; used by the test profiles).

The two paths are mutually exclusive so an email is never sent twice for the same event. Delivery is at-least-once; recipients may see a duplicate after a retry.

### Run tests
Full quality gate (tests, JaCoCo coverage, checkstyle, PMD, SpotBugs, OWASP dependency check):

```bash
# Windows
mvnw.cmd clean verify -Pci -Dspring.profiles.active=test

# macOS/Linux
./mvnw clean verify -Pci -Dspring.profiles.active=test
```

Integration tests use Testcontainers and require Docker to be running. For tests only (no static analysis), use `mvnw test` instead. The full suite runs 576 tests; three E2E methods are intentionally disabled with non-datasource reasons.

The `verify -Pci` gate currently stops at OWASP dependency-check on advisories published after the last dependency bump. Tests, coverage, Checkstyle, PMD, CPD and SpotBugs pass; see the CHANGELOG for the affected dependencies.

## Configuration

Only the variables you're likely to need up front:

- `JWT_SECRET` — HMAC secret for JWT signing. Required in production (no default there) and must be at least 32 bytes (256 bits) or the app fails fast. Dev/test profiles ship a placeholder.
- `NOTIFICATIONS_DELIVERY_MODE` — `kafka` (default) or `in-process`; see "Notification delivery mode" above.

All other settings have sensible defaults in `application.yml` / `application-dev.yml`. Kubernetes deployments configure everything via `k8s/configmap.yaml` and `k8s/secret.yaml` (dev placeholders only; production uses an ExternalSecret).

Flyway owns the database schema; migrations live in `src/main/resources/db/migration/` (currently V1–V12). Do not edit an already-applied migration — its checksum is validated at startup.

## Known limitations
- **Email delivery is at-least-once.** The outbox and Kafka provide at-least-once delivery without a distributed transaction, so duplicate emails are possible after a retry.
- **No per-cache size limit.** The application sets TTLs and hashed keys but does not cap cache size. Production Redis must set `maxmemory` with `maxmemory-policy allkeys-lru`.
- **Catalog search uses `LIKE '%term%'`.** A leading wildcard cannot use a B-tree index. Revisit with a `pg_trgm` GIN index or a search engine if the catalog exceeds roughly 100k books or search p99 exceeds 100 ms.

## API Docs

- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs

## More documentation

- [Changelog](CHANGELOG.md) — what changed and why

## License
MIT
