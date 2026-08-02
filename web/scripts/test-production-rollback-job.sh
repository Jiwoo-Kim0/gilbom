#!/bin/sh

set -eu

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf -- "$TEMP_DIR"' EXIT HUP INT TERM

fail() {
  echo "Production rollback Job self-test failed: $1" >&2
  exit 1
}

failed_sha=2222222222222222222222222222222222222222
target_sha=1111111111111111111111111111111111111111
digest="$(printf 'c%.0s' $(seq 1 64))"
backend_id="sha256:$(printf 'a%.0s' $(seq 1 64))"
frontend_id="sha256:$(printf 'b%.0s' $(seq 1 64))"

jq -n \
  --arg failed_sha "$failed_sha" \
  --arg target_sha "$target_sha" \
  --arg digest "$digest" \
  --arg backend_id "$backend_id" \
  --arg frontend_id "$frontend_id" \
  '
    {
      schemaVersion: 1,
      failedReleaseSha: $failed_sha,
      rollbackTargetSha: $target_sha,
      generatedAtUtc: "2026-08-02T07:00:00Z",
      manualRecoverySha256: $digest,
      rollbackTarget: {
        metadataSha256: $digest,
        backendImageId: $backend_id,
        frontendImageId: $frontend_id
      },
      flyway: {
        beforeFailedDeployVersion: 35,
        currentVersion: 36,
        changed: true
      },
      compatibility:
        "REQUIRES_EXPLICIT_PREVIOUS_BACKEND_COMPATIBILITY_APPROVAL",
      automaticRollbackAllowed: false,
      databaseMigrationUndoPlanned: false,
      databaseBackupRestorePlanned: false
    }
  ' > "$TEMP_DIR/preflight.json"

sh "$PROJECT_DIR/scripts/render-production-rollback-approval.sh" \
  "$TEMP_DIR/preflight.json" > "$TEMP_DIR/summary.txt"
for text in \
  '실패 Release SHA:' \
  'Rollback 대상 SHA:' \
  '이전 Backend Image ID:' \
  '이전 Frontend Image ID:' \
  '자동 Rollback: 차단됨' \
  'DB Migration undo를 수행하지 않음'
do
  grep -Fq "$text" "$TEMP_DIR/summary.txt" \
    || fail "approval summary is missing: $text"
done

jq -n \
  --arg failed_sha "$failed_sha" \
  --arg target_sha "$target_sha" \
  --arg digest "$digest" \
  '
    {
      schemaVersion: 1,
      failedReleaseSha: $failed_sha,
      rollbackTargetSha: $target_sha,
      manualRecoverySha256: $digest,
      rollbackPreflightSha256: $digest,
      flywayCurrentVersion: 36,
      compatibility: "APPROVED_PREVIOUS_BACKEND_COMPATIBLE",
      approvedBy: "CHAEUN KIM",
      approvedAtUtc: "2026-08-02T07:01:00Z"
    }
  ' > "$TEMP_DIR/approval.json"

jq -n \
  --arg failed_sha "$failed_sha" \
  --arg target_sha "$target_sha" \
  --slurpfile approval "$TEMP_DIR/approval.json" \
  '
    {
      schemaVersion: 1,
      status: "MANUALLY_APPROVED_ROLLBACK_SUCCEEDED",
      failedReleaseSha: $failed_sha,
      rollbackTargetSha: $target_sha,
      rolledBackAtUtc: "2026-08-02T07:02:00Z",
      decision: "APPROVED_PREVIOUS_BACKEND_COMPATIBLE",
      approval: $approval[0],
      flyway: {
        beforeRollbackVersion: 36,
        afterRollbackVersion: 36
      },
      backupBundle: "/opt/gilbom/backups/fixture",
      health: {
        backendReadiness: "PASS",
        frontend: "PASS",
        internalHttps: "PASS"
      },
      databaseRecovery: {
        migrationUndoPerformed: false,
        backupRestorePerformed: false
      }
    }
  ' > "$TEMP_DIR/manifest.json"

jq -n \
  '{
    schemaVersion: 1,
    checkedAtUtc: "2026-08-02T07:02:30Z",
    baseUrl: "https://i15d101.p.ssafy.io",
    checks: [],
    tlsVerification: "PASS",
    status: "PASS"
  }' > "$TEMP_DIR/smoke.json"

sh "$PROJECT_DIR/scripts/verify-production-rollback-evidence.sh" \
  "$TEMP_DIR/preflight.json" \
  "$TEMP_DIR/approval.json" \
  "$TEMP_DIR/manifest.json" \
  "$TEMP_DIR/smoke.json" >/dev/null

jq '.databaseRecovery.migrationUndoPerformed = true' \
  "$TEMP_DIR/manifest.json" > "$TEMP_DIR/unsafe-manifest.json"
if sh "$PROJECT_DIR/scripts/verify-production-rollback-evidence.sh" \
    "$TEMP_DIR/preflight.json" \
    "$TEMP_DIR/approval.json" \
    "$TEMP_DIR/unsafe-manifest.json" \
    "$TEMP_DIR/smoke.json" >/dev/null 2>&1; then
  fail "evidence verifier accepted a migration undo"
fi

echo "Production rollback Job self-test: PASS"
