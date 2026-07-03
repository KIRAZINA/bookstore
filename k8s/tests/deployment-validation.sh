#!/usr/bin/env bash
# ============================================================
# Deployment Validation Tests
# ============================================================
# Validates Kubernetes manifests, Docker images, and
# deployment configuration before production rollout.
#
# Usage:
#   ./k8s/tests/deployment-validation.sh
# ============================================================
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

PASS=0
FAIL=0
TOTAL=0

check() {
    local name="$1"
    local result="$2"
    TOTAL=$((TOTAL + 1))
    if [ "$result" -eq 0 ]; then
        echo -e "${GREEN}[PASS]${NC} $name"
        PASS=$((PASS + 1))
    else
        echo -e "${RED}[FAIL]${NC} $name"
        FAIL=$((FAIL + 1))
    fi
}

echo "============================================="
echo "  Bookstore Deployment Validation Tests"
echo "============================================="
echo ""

# === 1. Kustomize Build Validation ===
echo "--- Kustomize Build Tests ---"

# Test 1: Base kustomization builds successfully
kustomize build k8s/ > /dev/null 2>&1
check "Base kustomization builds" $?

# Test 2: Dev overlay builds
kustomize build k8s/overlays/dev/ > /dev/null 2>&1
check "Dev overlay builds" $?

# Test 3: Staging overlay builds
kustomize build k8s/overlays/staging/ > /dev/null 2>&1
check "Staging overlay builds" $?

# Test 4: Prod overlay builds
kustomize build k8s/overlays/prod/ > /dev/null 2>&1
check "Prod overlay builds" $?

# Test 5: Monitoring kustomization builds
kustomize build k8s/monitoring/ > /dev/null 2>&1
check "Monitoring kustomization builds" $?

# === 2. Manifest Content Validation ===
echo ""
echo "--- Manifest Content Tests ---"

BASE=$(kustomize build k8s/)

# Test 6: Deployment has required replicas
echo "$BASE" | yq eval '. | select(.kind == "Deployment")' | grep -q "replicas: 3"
check "Deployment has 3 replicas" $?

# Test 7: Deployment has startup probe
echo "$BASE" | yq eval '. | select(.kind == "Deployment")' | grep -q "startupProbe"
check "Deployment has startup probe" $?

# Test 8: Deployment has readiness probe
echo "$BASE" | yq eval '. | select(.kind == "Deployment")' | grep -q "readinessProbe"
check "Deployment has readiness probe" $?

# Test 9: Deployment has liveness probe
echo "$BASE" | yq eval '. | select(.kind == "Deployment")' | grep -q "livenessProbe"
check "Deployment has liveness probe" $?

# Test 10: Deployment has non-root security context
echo "$BASE" | yq eval '. | select(.kind == "Deployment")' | grep -q "runAsNonRoot: true"
check "Deployment runs as non-root" $?

# Test 11: Deployment has read-only root filesystem
echo "$BASE" | yq eval '. | select(.kind == "Deployment")' | grep -q "readOnlyRootFilesystem: true"
check "Deployment has read-only root filesystem" $?

# Test 12: Deployment has resource limits
echo "$BASE" | yq eval '. | select(.kind == "Deployment")' | grep -q "limits"
check "Deployment has resource limits" $?

# Test 13: Service has management port
echo "$BASE" | yq eval '. | select(.kind == "Service")' | grep -q "8081"
check "Service has management port 8081" $?

# Test 14: Ingress has TLS configured
echo "$BASE" | yq eval '. | select(.kind == "Ingress")' | grep -q "tls"
check "Ingress has TLS configured" $?

# Test 15: HPA exists
echo "$BASE" | yq eval '. | select(.kind == "HorizontalPodAutoscaler")' | grep -q "HorizontalPodAutoscaler"
check "HPA defined" $?

# Test 16: PDB exists
echo "$BASE" | yq eval '. | select(.kind == "PodDisruptionBudget")' | grep -q "PodDisruptionBudget"
check "PDB defined" $?

# Test 17: Network policies exist
echo "$BASE" | yq eval '. | select(.kind == "NetworkPolicy")' | grep -q "NetworkPolicy"
check "Network policies defined" $?

# Test 18: ResourceQuota exists
echo "$BASE" | yq eval '. | select(.kind == "ResourceQuota")' | grep -q "ResourceQuota"
check "ResourceQuota defined" $?

# Test 19: ServiceAccount exists
echo "$BASE" | yq eval '. | select(.kind == "ServiceAccount")' | grep -q "ServiceAccount"
check "ServiceAccount defined" $?

# === 3. Docker Validation ===
echo ""
echo "--- Docker Image Tests ---"

# Test 20: Dockerfile exists
[ -f Dockerfile ]
check "Dockerfile exists" $?

# Test 21: Dockerfile has multi-stage build
grep -q "FROM.*AS " Dockerfile
check "Dockerfile uses multi-stage build" $?

# Test 22: Dockerfile has non-root user
grep -q "USER " Dockerfile
check "Dockerfile has USER directive" $?

# Test 23: Dockerfile has HEALTHCHECK
grep -q "HEALTHCHECK" Dockerfile
check "Dockerfile has HEALTHCHECK" $?

# Test 24: Dockerfile has STOPSIGNAL
grep -q "STOPSIGNAL" Dockerfile
check "Dockerfile has STOPSIGNAL" $?

# Test 25: Dockerfile uses JRE (not JDK) for runtime
grep -q "21-jre" Dockerfile
check "Dockerfile uses JRE for runtime" $?

# === 4. Configuration Validation ===
echo ""
echo "--- Configuration Tests ---"

# Test 26: application-prod.yml exists
[ -f src/main/resources/application-prod.yml ]
check "application-prod.yml exists" $?

# Test 27: application-dev.yml exists
[ -f src/main/resources/application-dev.yml ]
check "application-dev.yml exists" $?

# Test 28: application-staging.yml exists
[ -f src/main/resources/application-staging.yml ]
check "application-staging.yml exists" $?

# Test 29: ConfigMap has all required keys
grep -q "SPRING_PROFILES_ACTIVE" k8s/configmap.yaml
check "ConfigMap has SPRING_PROFILES_ACTIVE" $?

grep -q "OTEL_EXPORTER_OTLP_ENDPOINT" k8s/configmap.yaml
check "ConfigMap has OTEL endpoint" $?

# Test 30: Secret template exists
[ -f k8s/secret.yaml ]
check "Secret template exists" $?

# === 5. CI/CD Validation ===
echo ""
echo "--- CI/CD Pipeline Tests ---"

# Test 31: CI workflow exists
[ -f .github/workflows/ci.yml ]
check "CI workflow exists" $?

# Test 32: Deploy workflow exists
[ -f .github/workflows/reusable-deploy.yml ]
check "Deploy workflow exists" $?

# Test 33: Build workflow exists
[ -f .github/workflows/reusable-build.yml ]
check "Build workflow exists" $?

# Test 34: Security scan exists
[ -f .github/workflows/reusable-security.yml ]
check "Security workflow exists" $?

# Test 35: Static analysis exists
[ -f .github/workflows/reusable-static-analysis.yml ]
check "Static analysis workflow exists" $?

# Check 36: Dependabot configured
[ -f .github/dependabot.yml ]
check "Dependabot configured" $?

# === 6. Observability Validation ===
echo ""
echo "--- Observability Tests ---"

# Test 37: Prometheus config exists
[ -f k8s/monitoring/prometheus-config.yaml ]
check "Prometheus config exists" $?

# Test 38: Grafana datasources config exists
grep -q "prometheus" grafana/datasources/prometheus.yml
check "Grafana Prometheus datasource configured" $?

grep -q "tempo" grafana/datasources/tempo.yml
check "Grafana Tempo datasource configured" $?

# Test 39: Grafana K8s dashboard exists
[ -f grafana/dashboards/kubernetes/kubernetes-cluster.json ]
check "Grafana K8s cluster dashboard exists" $?

[ -f grafana/dashboards/kubernetes/bookstore-application.json ]
check "Grafana application dashboard exists" $?

# Test 40: Monitoring namespace has PodSecurity labels
grep -q "pod-security.kubernetes.io/enforce" k8s/monitoring/namespace.yaml
check "Monitoring namespace has PodSecurity labels" $?

# === 7. Topology & Architecture ===
echo ""
echo "--- Architecture Tests ---"

# Test 41: Kustomize overlays exist
[ -d k8s/overlays/dev ]
check "Dev overlay exists" $?

[ -d k8s/overlays/staging ]
check "Staging overlay exists" $?

[ -d k8s/overlays/prod ]
check "Prod overlay exists" $?

# Test 42: Environment profiles exist
[ -f src/main/resources/application-dev.yml ]
check "Dev profile exists" $?

[ -f src/main/resources/application-staging.yml ]
check "Staging profile exists" $?

[ -f src/main/resources/application-prod.yml ]
check "Prod profile exists" $?

# === Summary ===
echo ""
echo "============================================="
echo -e "  Results: ${GREEN}$PASS passed${NC}, ${RED}$FAIL failed${NC}, $TOTAL total"
echo "============================================="

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
