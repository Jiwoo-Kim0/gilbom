#!/bin/sh

set -eu

fail() {
  echo "Production rollback drill contract failed: $1" >&2
  exit 1
}

for file in \
  Jenkinsfile.rollback-drill \
  compose.rollback-drill.yaml \
  test/rollback-drill/backend/Dockerfile \
  test/rollback-drill/backend/entrypoint.sh \
  test/rollback-drill/backend/healthcheck.sh \
  test/rollback-drill/backend/compatibility-probe.sh \
  test/rollback-drill/frontend/Dockerfile \
  test/rollback-drill/frontend/nginx.conf \
  scripts/run-production-rollback-drill.sh \
  scripts/cleanup-production-rollback-drill.sh \
  scripts/verify-production-rollback-drill-evidence.sh \
  scripts/test-production-rollback-drill-evidence.sh
do
  [ -s "$file" ] || fail "required file is missing or empty: $file"
done

assert_contains() {
  file="$1"
  text="$2"
  message="$3"
  grep -Fq "$text" "$file" || fail "$message"
}

assert_contains Jenkinsfile.rollback-drill \
  "stage('Verify isolated DinD boundary')" \
  "Jenkins DinD isolation stage is missing"
assert_contains Jenkinsfile.rollback-drill \
  'tcp://docker-builder:2376' \
  "Jenkins drill does not pin the DinD TLS endpoint"
assert_contains Jenkinsfile.rollback-drill \
  'test ! -S /var/run/docker.sock' \
  "Jenkins drill does not reject a Docker Socket mount"
assert_contains Jenkinsfile.rollback-drill \
  'scripts/run-production-rollback-drill.sh' \
  "Jenkins drill does not execute the scenario runner"
assert_contains Jenkinsfile.rollback-drill \
  "artifacts: '.artifacts/rollback-drill/**'" \
  "Jenkins drill evidence is not archived"

if grep -Eq \
    'withCredentials|credentialsId|sshUserPrivateKey|i15d101[.]p[.]ssafy[.]io' \
    Jenkinsfile.rollback-drill; then
  fail "rollback drill Jenkins Job references production access"
fi
if grep -Eq '(^|[[:space:]])(ssh|scp)([[:space:]]|$)' \
    scripts/run-production-rollback-drill.sh \
    scripts/cleanup-production-rollback-drill.sh; then
  fail "rollback drill must not contact an SSH target"
fi

assert_contains compose.rollback-drill.yaml \
  'name: ${COMPOSE_PROJECT_NAME:?COMPOSE_PROJECT_NAME is required}' \
  "Compose project isolation is not mandatory"
assert_contains compose.rollback-drill.yaml \
  'internal: true' \
  "drill data network is not internal"
for volume in postgres_data robot_images report_pdf_data
do
  assert_contains compose.rollback-drill.yaml \
    "$volume:" \
    "required persistent Volume is missing: $volume"
done
if grep -Eq '^[[:space:]]+ports:' compose.rollback-drill.yaml; then
  fail "rollback drill must not publish a host port"
fi

for marker in \
  'run_scenario_a' \
  'run_scenario_b' \
  'run_scenario_c' \
  'remove_previous_images' \
  'load_previous_artifact' \
  'AUTOMATIC_ROLLBACK_SUCCEEDED' \
  'ROLLBACK_BLOCKED_EXPLICIT_COMPATIBILITY_APPROVAL_REQUIRED' \
  'INCOMPATIBLE_PREVIOUS_BACKEND' \
  'stop frontend' \
  'verify_persistent_data'
do
  assert_contains scripts/run-production-rollback-drill.sh \
    "$marker" \
    "required rollback drill marker is missing: $marker"
done

if grep -Eiq \
    'flyway[[:space:]-]+undo|pg_restore|restore-db' \
    scripts/run-production-rollback-drill.sh \
    test/rollback-drill/backend/entrypoint.sh; then
  fail "rollback drill contains a forbidden database undo or restore command"
fi

COMPOSE_PROJECT_NAME=gilbom-rollback-drill-contract \
BACKEND_IMAGE=gilbom-rollback-drill/backend:contract \
FRONTEND_IMAGE=gilbom-rollback-drill/frontend:contract \
  docker compose --file compose.rollback-drill.yaml config --quiet

sh -n \
  scripts/run-production-rollback-drill.sh \
  scripts/cleanup-production-rollback-drill.sh \
  scripts/verify-production-rollback-drill-evidence.sh \
  scripts/test-production-rollback-drill-evidence.sh \
  test/rollback-drill/backend/entrypoint.sh \
  test/rollback-drill/backend/healthcheck.sh \
  test/rollback-drill/backend/compatibility-probe.sh
sh scripts/test-production-rollback-drill-evidence.sh

echo "Production rollback drill contract: PASS"
