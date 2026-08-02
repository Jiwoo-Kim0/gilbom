#!/bin/sh

set -eu

fail() {
  echo "Production rollback evidence verification failed: $1" >&2
  exit 1
}

[ "$#" -eq 4 ] \
  || fail "usage: $0 PREFLIGHT APPROVAL MANIFEST EXTERNAL_SMOKE"

preflight="$1"
approval="$2"
manifest="$3"
external_smoke="$4"
for file in "$preflight" "$approval" "$manifest" "$external_smoke"
do
  [ -f "$file" ] && [ ! -L "$file" ] && [ -s "$file" ] \
    || fail "required evidence is missing, empty, or symlinked: $file"
  jq empty "$file" \
    || fail "evidence is not valid JSON: $file"
done

jq -e \
  --slurpfile preflight "$preflight" \
  --slurpfile approval "$approval" \
  '
    .schemaVersion == 1
    and .status == "MANUALLY_APPROVED_ROLLBACK_SUCCEEDED"
    and .failedReleaseSha == $preflight[0].failedReleaseSha
    and .failedReleaseSha == $approval[0].failedReleaseSha
    and .rollbackTargetSha == $preflight[0].rollbackTargetSha
    and .rollbackTargetSha == $approval[0].rollbackTargetSha
    and .decision == "APPROVED_PREVIOUS_BACKEND_COMPATIBLE"
    and .approval.rollbackPreflightSha256
      == $approval[0].rollbackPreflightSha256
    and .flyway.beforeRollbackVersion
      == $preflight[0].flyway.currentVersion
    and .flyway.afterRollbackVersion
      == $preflight[0].flyway.currentVersion
    and .health.backendReadiness == "PASS"
    and .health.frontend == "PASS"
    and .health.internalHttps == "PASS"
    and .databaseRecovery.migrationUndoPerformed == false
    and .databaseRecovery.backupRestorePerformed == false
  ' "$manifest" >/dev/null \
  || fail "rollback manifest does not match preflight and approval"
jq -e \
  '.status == "PASS" and .tlsVerification == "PASS"' \
  "$external_smoke" >/dev/null \
  || fail "external HTTPS smoke evidence is not PASS"

echo "Production rollback evidence verification: PASS"
