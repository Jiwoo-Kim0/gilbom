#!/bin/sh

set -eu

fail() {
  echo "CI release contract verification failed: $1" >&2
  exit 1
}

assert_contains() {
  file="$1"
  text="$2"
  message="$3"
  grep -Fq -- "$text" "$file" || fail "$message"
}

line_of() {
  pattern="$1"
  grep -n -F "$pattern" Jenkinsfile | head -1 | cut -d: -f1
}

previous=0
for stage in \
  "stage('Repository policy')" \
  "stage('Git history Secret scan')" \
  "stage('Checkout workspace Secret scan')" \
  "stage('Secret boundary')" \
  "stage('Supported runtime versions')" \
  "stage('Production Compose contract')" \
  "stage('OpenAPI lint')" \
  "stage('Backend dependency scan')" \
  "stage('Frontend dependency scan')" \
  "stage('Image scanner readiness')" \
  "stage('Artifact transfer readiness')" \
  "stage('Prepare ephemeral credentials')" \
  "stage('Backend full test')" \
  "stage('Backend security acceptance')" \
  "stage('Frontend test')" \
  "stage('Frontend lint')" \
  "stage('Frontend production build')" \
  "stage('Build full-SHA images')" \
  "stage('Backend runtime image')" \
  "stage('Frontend runtime image')" \
  "stage('Start integration stack')" \
  "stage('Authentication temporary DB integration')" \
  "stage('Actual TLS MQTT Broker E2E')" \
  "stage('Flyway V1-V39')" \
  "stage('PostGIS RBAC and Spring Session')" \
  "stage('CSRF permission and append-only audit')" \
  "stage('Integration smoke')" \
  "stage('Graceful shutdown')" \
  "stage('Report LLM fallback and resilience suite')" \
  "stage('Image layer history and log Secret scan')" \
  "stage('Required image SBOM and CVE scan')" \
  "stage('Security regression baseline evidence')" \
  "stage('Prepare release SBOM and vulnerability artifacts')" \
  "stage('Create release image archive')" \
  "stage('Build metadata and release integrity')"
do
  current="$(line_of "$stage")"
  test -n "$current" || fail "missing Jenkins stage: $stage"
  test "$current" -gt "$previous" || fail "Jenkins stage order is invalid: $stage"
  previous="$current"
done

assert_contains Jenkinsfile \
  'IMAGE_SCANNER_MODE=required' \
  "release scanner must be required"
assert_contains Jenkinsfile \
  'sh scripts/scan-image-security.sh prepare' \
  "Trivy databases must be prepared before expensive tests"
assert_contains Jenkinsfile \
  'TRIVY_REQUIRE_PREPARED=true' \
  "required image scan must consume fail-fast scanner readiness"
assert_contains Jenkinsfile \
  'GILBOM_SECURITY_TEST_PUBLISHED_HOST=docker-builder' \
  "authentication integration must reach published DinD ports through docker-builder"
assert_contains Jenkinsfile \
  'MQTT_E2E_PUBLISHED_HOST=docker-builder' \
  "MQTT Broker E2E must reach the TLS DinD broker through docker-builder"
assert_contains Jenkinsfile \
  'MQTT_PUBLIC_HOSTNAME=docker-builder' \
  "MQTT Broker certificate SAN must include the DinD access hostname"
assert_contains Jenkinsfile \
  'COUNT(*) = 39' \
  "Flyway history gate must require all 39 migrations"
assert_contains Jenkinsfile \
  'MAX(version::INTEGER) = 39' \
  "Flyway history gate must require V39 as the maximum migration"
assert_contains Jenkinsfile \
  '.artifacts/security-regression/**' \
  "security regression evidence must be archived"
assert_contains Jenkinsfile \
  'python3 scripts/generate-security-regression-baseline.py' \
  "security regression gates must be aggregated before release preparation"
assert_contains scripts/run-backend-ci-task.sh \
  'test|securityAcceptanceTest|mqttBrokerE2E' \
  "Backend CI runner must allow the dedicated MQTT Broker E2E task"
assert_contains scripts/run-mqtt-broker-e2e-ci.sh \
  'MQTT_E2E_CA_CERTIFICATE_HOST="$CA_CERTIFICATE"' \
  "MQTT Broker E2E must mount the generated TLS CA into the test runner"
assert_contains backend/build.gradle \
  'MQTT Broker E2E must execute without skipped tests' \
  "MQTT Broker E2E must fail closed when no real test executes"
assert_contains Jenkinsfile \
  'test "$BACKEND_IMAGE" = "gilbom-backend:$RELEASE_SHA"' \
  "Backend build must use the full release SHA"
assert_contains Jenkinsfile \
  'test "$FRONTEND_IMAGE" = "gilbom-frontend:$RELEASE_SHA"' \
  "Frontend build must use the full release SHA"
assert_contains scripts/prepare-release-security-artifacts.sh \
  '"$RELEASE_DIR/sbom/backend.sbom.json"' \
  "Backend release SBOM path is not contracted"
assert_contains scripts/prepare-release-security-artifacts.sh \
  '"$RELEASE_DIR/vulnerabilities/backend.txt"' \
  "Backend vulnerability report path is not contracted"
assert_contains scripts/create-release-image-archive.sh \
  'docker save "$BACKEND_IMAGE" "$FRONTEND_IMAGE"' \
  "release image archive must contain both SHA images"
assert_contains scripts/create-release-image-archive.sh \
  'sha256sum images.tar.zst > images.tar.zst.sha256' \
  "image archive checksum is missing"
assert_contains scripts/generate-build-metadata.sh \
  'jq empty "$RELEASE_DIR/build-metadata.json"' \
  "build metadata JSON validation is missing"
assert_contains scripts/generate-build-metadata.sh \
  'sha256sum > SHA256SUMS' \
  "whole-release SHA256SUMS generation is missing"

if grep -Eiq \
    'gilbom-(backend|frontend):(local|latest|[0-9a-f]{1,39})([^0-9a-f]|$)' \
    scripts/prepare-release-security-artifacts.sh \
    scripts/create-release-image-archive.sh \
    scripts/generate-build-metadata.py; then
  fail "release scripts contain a forbidden local, latest, or short-SHA image tag"
fi

echo "CI stages 4-8 release contract: PASS"
