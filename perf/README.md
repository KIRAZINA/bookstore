# Phase 4: Performance, Load & Stress Testing

Enterprise performance validation for the Bookstore API. The goal is **characterization**, not maximizing RPS.

## Architecture & Critical Paths

```
Client → Spring Boot API → PostgreSQL (orders, inventory, outbox)
                        → Redis (cache, rate limiting)
                        → Kafka (domain events via outbox pattern)
```

| Operation | Path | Expected bottleneck |
|-----------|------|---------------------|
| Browse | `GET /api/v1/books`, search, category | Redis cache, DB read |
| Checkout | cart → order → payment | Inventory pessimistic lock, DB pool |
| Auth | `POST /api/v1/auth/login` | BCrypt, JWT |
| Events | Outbox → Kafka | Poll interval, producer acks |
| Rate limit | Redis Lua script | Redis latency |

## Testing Strategy

### Layer 1 — JVM Baseline (Testcontainers)

Runs against **PostgreSQL + Redis + Embedded Kafka** with rate limiting disabled.

```bash
mvn test -Pperformance
```

Optional tuning:

```bash
mvn test -Pperformance -Dperf.iterations=100 -Dperf.warmup=10
mvn test -Pperformance -Dperf.kafka.events=200 -Dperf.redis.ops=1000
```

| Test class | Section |
|------------|---------|
| `PerformanceBaselineTest` | §1 Baseline latency |
| `InventoryConcurrencyPerformanceTest` | §6 Inventory contention |
| `KafkaOutboxPerformanceTest` | §7 Outbox/Kafka throughput |
| `RedisPerformanceTest` | §8 Redis cache & rate limiter |
| `DatabasePerformanceTest` | §9 Connection pool & queries |
| `RateLimiterHttpPerformanceTest` | §10 HTTP rate limiting |
| `ObservabilityUnderLoadTest` | §11 Metrics under load |

Performance tests are **excluded from default** `mvn test` via JUnit tag `performance`.

### Layer 2 — k6 External Load Tests

Requires a running application with docker-compose infrastructure.

#### 1. Start infrastructure

```bash
docker-compose up -d
```

#### 2. Start application (perf profile, rate limiting off for capacity tests)

```powershell
$env:JWT_SECRET = "perf-jwt-secret-that-is-at-least-256-bits-long-for-hs256"
$env:APP_RATE_LIMITING_ENABLED = "false"
mvn spring-boot:run "-Dspring-boot.run.profiles=perf"
```

#### 3. Install k6

- Windows: `choco install k6` or [k6 releases](https://github.com/grafana/k6/releases)
- macOS: `brew install k6`

#### 4. Run tests

```bash
k6 run perf/k6/tests/01-baseline.js
k6 run perf/k6/tests/02-load.js
k6 run perf/k6/tests/03-stress.js
k6 run perf/k6/tests/04-spike.js
k6 run perf/k6/tests/05-endurance.js
k6 run perf/k6/tests/06-inventory-contention.js
```

PowerShell helper:

```powershell
.\perf\scripts\run-k6.ps1 -Test baseline
.\perf\scripts\run-k6.ps1 -Test load
```

### Layer 3 — Kubernetes (§12)

Apply dev overlay and run load test against ingress:

```bash
kubectl apply -k k8s/overlays/dev
k6 run -e BASE_URL=https://api.bookstore.example.com perf/k6/tests/02-load.js
```

Monitor HPA: `kubectl get hpa -w`

## Workload Definitions

### Browse (70% of mixed load)

- List all books
- Search by title
- Filter by category

Represents catalog browsing — cache-friendly, read-heavy.

### Checkout (30% of mixed load)

1. Register/login (unique user per iteration)
2. Add item to cart
3. Create order (inventory reservation)
4. Process payment

Represents revenue-critical path — write-heavy, lock contention.

### Inventory contention

200 VUs, 100 stock units — validates overselling prevention under peak demand.

## Metrics Collection

| Layer | Source |
|-------|--------|
| Application | `/actuator/prometheus` (port 8081 in perf profile) |
| JVM | `jvm_memory_*`, `jvm_gc_*` |
| HTTP | `http_server_requests_seconds` |
| Business | `bookstore.orders.created`, `bookstore.checkout.duration` |
| Database | HikariCP MXBean / `hikaricp_connections_*` |
| Redis | Redis INFO, rate limit counters |
| Kafka | Consumer lag via Kafka UI (`localhost:8082`) |
| k6 | Built-in `http_req_duration`, `http_req_failed` |

### Grafana dashboards

- Application: `grafana/dashboards/kubernetes/bookstore-application.json`
- Cluster: `grafana/dashboards/kubernetes/kubernetes-cluster.json`

Access: http://localhost:3000 (admin/admin)

## Rate Limiting Note

Production rate limits (per IP / minute):

| Group | Limit |
|-------|-------|
| auth | 5 |
| checkout | 20 |
| api | 100 |

**Disable for capacity testing**: `APP_RATE_LIMITING_ENABLED=false`

**Enable for rate limit validation**: run `06-inventory-contention.js` with limits on, or `RateLimiterHttpPerformanceTest`.

## Performance Objectives (evaluate after measurement)

| Objective | How to verify |
|-----------|---------------|
| Stable latency | p95 drift < 2x baseline under 100 VUs |
| Predictable throughput | Linear scaling to DB pool limit |
| Graceful degradation | 429/503 before crash, no OOM |
| Fast recovery | Spike test returns to baseline < 3 min |
| No data corruption | Inventory total = available + reserved |
| No overselling | Contention test: successes ≤ stock |
| No message loss | Outbox drain completes, no stuck PENDING |

## Report

Fill in measured results in [REPORT.md](REPORT.md) after running tests.

## File Structure

```
perf/
├── README.md
├── REPORT.md
├── k6/
│   ├── lib/common.js
│   └── tests/
│       ├── 01-baseline.js
│       ├── 02-load.js
│       ├── 03-stress.js
│       ├── 04-spike.js
│       ├── 05-endurance.js
│       └── 06-inventory-contention.js
└── scripts/
    ├── run-k6.ps1
    └── run-java-baseline.ps1
```
