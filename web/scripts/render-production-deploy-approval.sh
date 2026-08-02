#!/bin/sh

set -eu

fail() {
  echo "Production deploy approval rendering failed: $1" >&2
  exit 1
}

[ "$#" -eq 1 ] \
  || fail "usage: $0 PREFLIGHT_JSON"
preflight="$1"
[ -f "$preflight" ] && [ ! -L "$preflight" ] && [ -s "$preflight" ] \
  || fail "preflight JSON is missing, empty, or symlinked"

jq -e \
  '
    .schemaVersion == 1
    and (.releaseSha | test("^[0-9a-f]{40}$"))
    and (.images.backend.id | test("^sha256:[0-9a-f]{64}$"))
    and (.images.frontend.id | test("^sha256:[0-9a-f]{64}$"))
    and (.artifact.archiveSha256 | test("^[0-9a-f]{64}$"))
    and (.production.currentSha | test("^[0-9a-f]{40}$"))
    and (.production.rollbackTargetSha | test("^[0-9a-f]{40}$"))
    and (.flyway.pendingMigrations | type == "array")
  ' "$preflight" >/dev/null \
  || fail "preflight JSON contract is invalid"

migrations="$(
  jq -r '
    if (.flyway.pendingMigrations | length) == 0 then
      "없음"
    else
      .flyway.pendingMigrations
      | map("V\(.version) \(.file) [\(.sha256)]")
      | join("\n")
    end
  ' "$preflight"
)"

cat <<EOF
운영 배포 승인 요청

Commit SHA: $(jq -r '.releaseSha' "$preflight")
Backend Image ID: $(jq -r '.images.backend.id' "$preflight")
Frontend Image ID: $(jq -r '.images.frontend.id' "$preflight")
Archive SHA-256: $(jq -r '.artifact.archiveSha256' "$preflight")
Critical: $(jq -r '.vulnerabilities.critical' "$preflight")
High: $(jq -r '.vulnerabilities.high' "$preflight") (승인 예외 $(jq -r '.vulnerabilities.exceptedHigh' "$preflight"), 미승인 $(jq -r '.vulnerabilities.unapprovedHigh' "$preflight"))
현재 운영 SHA: $(jq -r '.production.currentSha' "$preflight")
롤백 대상 SHA: $(jq -r '.production.rollbackTargetSha' "$preflight")
Migration 호환성: $(jq -r '.flyway.compatibility' "$preflight")
판정 사유: $(jq -r '.flyway.compatibilityReason' "$preflight")
Flyway: V$(jq -r '.flyway.currentVersion' "$preflight") → V$(jq -r '.flyway.targetVersion' "$preflight")

적용 예정 Flyway Migration:
$migrations
EOF
