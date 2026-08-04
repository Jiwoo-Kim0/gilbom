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
  "stage('Flyway V1-V42')" \
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
  "stage('Sign release image archive with Cosign')" \
  "stage('Create release source archive')" \
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
  "MFA_TOTP_SECRET_ENCRYPTION_ENABLED = 'true'" \
  "CI must enable TOTP encryption without relying on an ignored local .env"
assert_contains Jenkinsfile \
  "MFA_TOTP_SECRET_ACTIVE_KEY_VERSION = '1'" \
  "CI must select the generated TOTP keyring version explicitly"
assert_contains scripts/auth-security-integration-test.py \
  '"MFA_TOTP_SECRET_ENCRYPTION_ENABLED=true"' \
  "authentication integration runner must fail closed with TOTP encryption enabled"
assert_contains scripts/auth-security-integration-test.py \
  '"MFA_TOTP_SECRET_ACTIVE_KEY_VERSION=1"' \
  "authentication integration runner must select its mounted keyring version"
assert_contains Jenkinsfile \
  'MQTT_E2E_PUBLISHED_HOST=docker-builder' \
  "MQTT Broker E2E must reach the TLS DinD broker through docker-builder"
assert_contains Jenkinsfile \
  'MQTT_PUBLIC_HOSTNAME=docker-builder' \
  "MQTT Broker certificate SAN must include the DinD access hostname"
assert_contains Jenkinsfile \
  'COUNT(*) = 42' \
  "Flyway history gate must require all 42 migrations"
assert_contains Jenkinsfile \
  'MAX(version::INTEGER) = 42' \
  "Flyway history gate must require V42 as the maximum migration"
assert_contains Jenkinsfile \
  '.artifacts/security-regression/**' \
  "security regression evidence must be archived"
assert_contains Jenkinsfile \
  'python3 scripts/generate-security-regression-baseline.py' \
  "security regression gates must be aggregated before release preparation"
assert_contains scripts/run-backend-ci-task.sh \
  'test|securityAcceptanceTest|mqttBrokerE2E' \
  "Backend CI runner must allow the dedicated MQTT Broker E2E task"
assert_contains scripts/run-backend-ci-task.sh \
  'ssl://127.0.0.1:*|ssl://localhost:*' \
  "local MQTT Broker E2E must preserve the certificate hostname through host networking"
assert_contains scripts/run-mqtt-broker-e2e-ci.sh \
  'MQTT_E2E_CA_CERTIFICATE_HOST="$CA_CERTIFICATE"' \
  "MQTT Broker E2E must mount the generated TLS CA into the test runner"
assert_contains scripts/run-mqtt-broker-e2e-ci.sh \
  'run_mqtt_acl_probe' \
  "MQTT Broker E2E must exercise the real Broker ACL"
assert_contains Jenkinsfile \
  '.acl.crossRobotPublishDenied == true' \
  "MQTT Broker E2E gate must require cross-robot publish denial"
assert_contains Jenkinsfile \
  '.acl.reverseDirectionSubscribeDenied == true' \
  "MQTT Broker E2E gate must require reverse-direction subscribe denial"
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
assert_contains scripts/create-release-source-archive.sh \
  '"$RELEASE_SHA:$PROJECT_PREFIX"' \
  "source archive must be built from the exact release SHA and project tree"
assert_contains scripts/create-release-source-archive.sh \
  'runtime[.]env|image[.]env' \
  "source archive does not reject tracked runtime state"
assert_contains Jenkinsfile \
  'sh scripts/create-release-source-archive.sh' \
  "release source archive is not created before complete manifest signing"
assert_contains Jenkinsfile \
  "credentialsId: 'gilbom-cosign-private-key'" \
  "Cosign private key must come from a Jenkins Secret File credential"
assert_contains Jenkinsfile \
  "credentialsId: 'gilbom-cosign-password'" \
  "Cosign password must come from a Jenkins Secret Text credential"
assert_contains Jenkinsfile \
  'sh scripts/sign-release-image-archive.sh' \
  "release archive is not signed after security validation"
assert_contains scripts/sign-release-image-archive.sh \
  'tampered archive was incorrectly accepted' \
  "Cosign tampered-archive negative test is missing"
assert_contains scripts/sign-release-image-archive.sh \
  'wrong public key was incorrectly accepted' \
  "Cosign wrong-key negative test is missing"
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

sh scripts/test-create-release-source-archive.sh
echo "CI stages 4-8 release contract: PASS"
