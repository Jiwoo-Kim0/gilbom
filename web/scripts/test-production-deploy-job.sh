#!/bin/sh

set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf -- "$TEMP_DIR"' EXIT HUP INT TERM

fail() {
  echo "Production deploy Job self-test failed: $1" >&2
  exit 1
}

release_sha=2222222222222222222222222222222222222222
current_sha=1111111111111111111111111111111111111111
backend_id="sha256:$(printf 'a%.0s' $(seq 1 64))"
frontend_id="sha256:$(printf 'b%.0s' $(seq 1 64))"
digest="$(printf 'c%.0s' $(seq 1 64))"

jq -n \
  --arg release_sha "$release_sha" \
  --arg current_sha "$current_sha" \
  --arg backend_id "$backend_id" \
  --arg frontend_id "$frontend_id" \
  --arg digest "$digest" \
  '
    {
      schemaVersion: 1,
      releaseSha: $release_sha,
      generatedAtUtc: "2026-08-02T04:00:00Z",
      artifact: {
        directory: ("/opt/gilbom/artifacts/" + $release_sha),
        sha256SumsSha256: $digest,
        archiveSha256: $digest
      },
      images: {
        backend: {
          name: ("gilbom-backend:" + $release_sha),
          id: $backend_id
        },
        frontend: {
          name: ("gilbom-frontend:" + $release_sha),
          id: $frontend_id
        }
      },
      vulnerabilities: {
        critical: 0,
        high: 0,
        exceptedHigh: 0,
        unapprovedHigh: 0
      },
      production: {
        currentSha: $current_sha,
        rollbackTargetSha: $current_sha
      },
      flyway: {
        currentVersion: 35,
        targetVersion: 36,
        migrationPlanSha256: $digest,
        pendingMigrations: [
          {
            version: 36,
            file: "V36__release_test.sql",
            sha256: $digest
          }
        ],
        compatibility: "COMPATIBLE_FORWARD_MIGRATION_REQUIRES_APPROVAL",
        compatibilityReason: "Forward migration approval is required."
      }
    }
  ' > "$TEMP_DIR/preflight.json"

sh "$PROJECT_DIR/scripts/render-production-deploy-approval.sh" \
  "$TEMP_DIR/preflight.json" > "$TEMP_DIR/summary.txt"
for label in \
  'Commit SHA:' \
  'Backend Image ID:' \
  'Frontend Image ID:' \
  'Archive SHA-256:' \
  'Critical:' \
  'High:' \
  '적용 예정 Flyway Migration:' \
  '현재 운영 SHA:' \
  '롤백 대상 SHA:' \
  'Migration 호환성:'
do
  grep -Fq "$label" "$TEMP_DIR/summary.txt" \
    || fail "approval summary is missing: $label"
done

preflight_sha256="$(sha256sum "$TEMP_DIR/preflight.json" | awk '{print $1}')"
jq -n \
  --arg release_sha "$release_sha" \
  --arg current_sha "$current_sha" \
  --arg preflight_sha256 "$preflight_sha256" \
  --arg digest "$digest" \
  '
    {
      schemaVersion: 1,
      releaseSha: $release_sha,
      preflightSha256: $preflight_sha256,
      artifactManifestSha256: $digest,
      currentSha: $current_sha,
      migrationPlanSha256: $digest,
      approvedBy: "jenkins-reviewer",
      approvedAtUtc: "2026-08-02T04:01:00Z"
    }
  ' > "$TEMP_DIR/approval.json"
jq -n \
  --arg release_sha "$release_sha" \
  --arg current_sha "$current_sha" \
  --arg backend_id "$backend_id" \
  --arg frontend_id "$frontend_id" \
  --arg digest "$digest" \
  --slurpfile approval "$TEMP_DIR/approval.json" \
  '
    {
      schemaVersion: 1,
      releaseSha: $release_sha,
      previousSha: $current_sha,
      artifact: {sha256SumsSha256: $digest},
      images: {
        backend: {id: $backend_id},
        frontend: {id: $frontend_id}
      },
      approval: $approval[0],
      health: {
        backendReadiness: "PASS",
        frontend: "PASS",
        frontendToBackend: "PASS",
        mqtt: "PASS"
      }
    }
  ' > "$TEMP_DIR/manifest.json"
jq -n \
  '{
    schemaVersion: 1,
    tlsVerification: "PASS",
    checks: {},
    status: "PASS"
  }' > "$TEMP_DIR/smoke.json"

sh "$PROJECT_DIR/scripts/verify-production-release-evidence.sh" \
  "$TEMP_DIR/preflight.json" \
  "$TEMP_DIR/approval.json" \
  "$TEMP_DIR/manifest.json" \
  "$TEMP_DIR/smoke.json"

jq '.approval.approvedBy = "different-reviewer"' \
  "$TEMP_DIR/manifest.json" > "$TEMP_DIR/stale-manifest.json"
if sh "$PROJECT_DIR/scripts/verify-production-release-evidence.sh" \
    "$TEMP_DIR/preflight.json" \
    "$TEMP_DIR/approval.json" \
    "$TEMP_DIR/stale-manifest.json" \
    "$TEMP_DIR/smoke.json" >/dev/null 2>&1; then
  fail "mismatched approver evidence was accepted"
fi

echo "Production deploy Job self-test: PASS"
