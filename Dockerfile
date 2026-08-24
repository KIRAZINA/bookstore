# ============================================================
# Stage 1: Dependency resolution layer (cached independently)
# ============================================================
# This stage only invalidates when pom.xml or Maven wrapper changes,
# keeping dependency downloads out of the main build cache
FROM eclipse-temurin:24-jdk-alpine AS dependencies

WORKDIR /build

# Copy only build descriptors first — maximizes Docker layer cache
# so dependency downloads are reused across builds unless pom.xml changes
COPY mvnw mvnw.cmd ./
COPY .mvn .mvn
COPY pom.xml ./

RUN ./mvnw dependency:resolve -B --no-transfer-progress && \
    ./mvnw dependency:resolve-plugins -B --no-transfer-progress

# ============================================================
# Stage 2: Build application artifact
# ============================================================
FROM dependencies AS builder

WORKDIR /build

# Copy all source code
COPY src src

# Build the application JAR
# -DskipTests: tests run separately in CI pipeline
# -B: batch mode for cleaner logs
RUN ./mvnw clean package -DskipTests -B --no-transfer-progress && \
    # Copy OTEL Java agent for runtime stage
    mkdir -p /build/agent && \
    cp target/agent/opentelemetry-javaagent.jar /build/agent/ && \
    # Rename JAR to predictable name for runtime
    cp target/*.jar /build/app.jar

# ============================================================
# Stage 3: Production runtime image
# ============================================================
FROM eclipse-temurin:24-jre-alpine AS runtime

# === Security & OS hardening ===
RUN addgroup -S bookstore && \
    adduser -S bookstore -G bookstore && \
    # Install only runtime-essential packages
    apk add --no-cache --upgrade \
        # Timezone support for JVM
        tzdata \
        # SSL/TLS certificate authorities
        ca-certificates \
    && \
    # Remove package manager artifacts to minimize image size
    rm -rf /var/cache/apk/*

# === Application user (non-root) ===
USER bookstore

# === Working directory ===
WORKDIR /app

# === Copy application artifact & OTEL agent ===
COPY --from=builder --chown=bookstore:bookstore /build/app.jar /app/app.jar
COPY --from=builder --chown=bookstore:bookstore /build/agent/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar

# === Runtime environment defaults ===
# All values can be overridden at container runtime via environment variables
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="" \
    OTEL_JAVAAGENT_ENABLED=true \
    OTEL_SERVICE_NAME=bookstore-api \
    OTEL_TRACES_EXPORTER=otlp \
    OTEL_METRICS_EXPORTER=none \
    OTEL_LOGS_EXPORTER=none \
    OTEL_EXPORTER_OTLP_PROTOCOL=grpc \
    OTEL_EXPORTER_OTLP_ENDPOINT=http://tempo:4317 \
    OTEL_TRACES_SAMPLER=parentbased_traceidratio \
    OTEL_TRACES_SAMPLER_ARG=0.1 \
    OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_LOG_ATTRIBUTES=true \
    OTEL_JAVA_GLOBAL_AUTOCONFIGURE_ENABLED=true

# === JVM Optimization for Containers ===
# -XX:+UseContainerSupport (default in JDK 21): detects container memory/CPU limits
#   Automatically sets -Xmx, -Xms based on container memory limits (up to 25% of limit)
# -XX:+HeapDumpOnOutOfMemoryError: captures heap dump for post-mortem analysis
# -XX:HeapDumpPath: writes dump to /tmp (writeable emptyDir in K8s)
# -XX:+ExitOnOutOfMemoryError: exits JVM on OOM so orchestrator restarts pod
# -XX:+UseZGC: ultra-low-latency garbage collector (<1ms pause times)
#   ZGC scales well across heap sizes (512MB to 16TB) with minimal CPU overhead
# -XX:-OmitStackTraceInFastThrow: preserves full exception stack traces
# -XX:+AlwaysPreTouch: touches all heap pages at startup for consistent performance
#   Prevents runtime page faults that could cause latency spikes
# -Dsun.net.inetaddr.ttl: DNS cache TTL for container environments (30s)
#   Prevents stale DNS lookups when pods restart with new IPs
# -Djava.security.egd: faster SecureRandom initialization in containers
# --add-opens: required by some instrumentation agents (OTel, etc.)
ENV JAVA_TOOL_OPTIONS="\
    -XX:+UseContainerSupport \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=/tmp/heapdump.hprof \
    -XX:+ExitOnOutOfMemoryError \
    -XX:+UseZGC \
    -XX:-OmitStackTraceInFastThrow \
    -XX:+AlwaysPreTouch \
    -Dsun.net.inetaddr.ttl=30 \
    -Djava.security.egd=file:/dev/./urandom"

# === Ports ===
# 8080: Application traffic
# 8081: Management/Actuator (when management.server.port is set)
EXPOSE 8080
EXPOSE 8081

# === Health check ===
# Uses Spring Boot Actuator liveness endpoint
# --no-verbose: reduce log noise
# --tries=1: fail fast — Kubernetes handles retry via probe parameters
# --spider: HEAD request only, no body download
HEALTHCHECK --interval=30s --timeout=5s --retries=3 --start-period=90s \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health/liveness || exit 1

# === Graceful shutdown ===
# exec form ensures Java receives SIGTERM directly (PID 1)
# Without exec, the shell wrapper would intercept and not forward the signal
# Spring Boot handles SIGTERM by initiating graceful shutdown
STOPSIGNAL SIGTERM

# === Entrypoint ===
# exec replaces shell with Java process so:
# 1. Java PID 1 receives kubernetes SIGTERM directly
# 2. Graceful shutdown drains requests before exit
# 3. Prometheus metrics flush on shutdown
# 4. ${0} ${@} passes CMD arguments from Docker/Kubernetes
#    This allows K8s to override the default command for
#    init containers (e.g., Flyway migration)
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -javaagent:/app/opentelemetry-javaagent.jar -jar /app/app.jar ${0} ${@}"]
