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
- Docker & Docker Compose

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

### Notification delivery mode
Email notifications can be delivered either in-process or through the Kafka event bus:

- `NOTIFICATIONS_DELIVERY_MODE=kafka` (default) — `NotificationEventConsumer` renders and sends
  emails from events consumed off `orders.events` / `payments.events` / `notifications.events`.
- `NOTIFICATIONS_DELIVERY_MODE=in-process` — `NotificationEventHandler` sends emails directly
  after the business transaction commits (no Kafka required; used by the test profiles).

The two paths are mutually exclusive so an email is never sent twice for the same event.

### Run tests
```bash
mvnw.cmd test
```

## API Docs
Swagger UI: http://localhost:8080/swagger-ui.html

## License
MIT
