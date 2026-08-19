# Bookstore API

REST API for an online bookstore built with Spring Boot 4, Java 21, PostgreSQL, Redis, and Kafka.

## Features
- User authentication & authorization (JWT, RBAC)
- Book catalog with Redis caching
- Shopping cart and order management
- Payment processing
- Inventory management with optimistic locking
- Event-driven architecture (Kafka, Outbox Pattern)
- Distributed rate limiting
- Kubernetes deployment
- Observability (Prometheus, Grafana, OpenTelemetry)

## Tech Stack
- Java 21
- Spring Boot 4.0
- Spring Security
- Spring Data JPA
- PostgreSQL 16
- Redis 7
- Apache Kafka
- Flyway
- Testcontainers
- Kubernetes
- Prometheus + Grafana
- OpenTelemetry

## Quick Start

### Prerequisites
- Java 21+
- Docker Desktop (or equivalent Docker runtime) — required for PostgreSQL, Redis, and Kafka via `docker-compose.yml`
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

The two paths are mutually exclusive so an email is never sent twice for the same event.

### Run tests
Full quality gate (tests, JaCoCo coverage, checkstyle, PMD, SpotBugs, OWASP dependency check):

```bash
# Windows
mvnw.cmd clean verify -Pci -Dspring.profiles.active=test

# macOS/Linux
./mvnw clean verify -Pci -Dspring.profiles.active=test
```

Integration tests use Testcontainers and require Docker to be running. For just the unit/integration tests (no static analysis), use `mvnw test` instead.

## Configuration

Only the variables you're likely to need up front:

- `JWT_SECRET` — HMAC secret for JWT signing. Required in production (no default there) and must be at least 32 bytes (256 bits) or the app fails fast. Dev/test profiles ship a placeholder.
- `NOTIFICATIONS_DELIVERY_MODE` — `kafka` (default) or `in-process`; see "Notification delivery mode" above.

All other settings have sensible defaults in `application.yml` / `application-dev.yml`. Kubernetes deployments configure everything via `k8s/configmap.yaml` and `k8s/secret.yaml` (dev placeholders only; production uses an ExternalSecret).

## API Docs

- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs

## More documentation

- [Project architecture](PROJECT_ARCHITECTURE.md) — design, domain model, deployment
- [Changelog](CHANGELOG.md) — what changed and why

## License
MIT
