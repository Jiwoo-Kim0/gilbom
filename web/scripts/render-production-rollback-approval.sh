#!/bin/sh

set -eu

fail() {
  echo "Production rollback approval rendering failed: $1" >&2
  exit 1
}

[ "$#" -eq 1 ] \
  || fail "usage: $0 ROLLBACK_PREFLIGHT_JSON"
preflight="$1"
[ -f "$preflight" ] && [ ! -L "$preflight" ] && [ -s "$preflight" ] \
  || fail "rollback preflight JSON is missing, empty, or symlinked"

jq -e \
  '
    .schemaVersion == 1
    and (.failedReleaseSha | test("^[0-9a-f]{40}$"))
    and (.rollbackTargetSha | test("^[0-9a-f]{40}$"))
    and .flyway.changed == true
    and .automaticRollbackAllowed == false
    and .compatibility
      == "REQUIRES_EXPLICIT_PREVIOUS_BACKEND_COMPATIBILITY_APPROVAL"
    and .databaseMigrationUndoPlanned == false
    and .databaseBackupRestorePlanned == false
  ' "$preflight" >/dev/null \
  || fail "rollback preflight JSON contract is invalid"

cat <<EOF
운영 Rollback 호환성 승인 요청

실패 Release SHA: $(jq -r '.failedReleaseSha' "$preflight")
Rollback 대상 SHA: $(jq -r '.rollbackTargetSha' "$preflight")
이전 Backend Image ID: $(jq -r '.rollbackTarget.backendImageId' "$preflight")
이전 Frontend Image ID: $(jq -r '.rollbackTarget.frontendImageId' "$preflight")
Flyway: V$(jq -r '.flyway.beforeFailedDeployVersion' "$preflight") → V$(jq -r '.flyway.currentVersion' "$preflight")
자동 Rollback: 차단됨

승인 의미:
- 이전 Backend가 현재 V$(jq -r '.flyway.currentVersion' "$preflight") 스키마와 호환됨을 확인함
- DB Migration undo를 수행하지 않음
- DB Backup 자동 복원을 수행하지 않음
- 승인 후 이전 SHA를 동일 root 배포기로 실행함
EOF
