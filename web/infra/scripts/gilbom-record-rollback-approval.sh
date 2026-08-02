#!/bin/sh

set -eu
set +x
umask 077

RELEASE_RECORD_ROOT=/var/lib/gilbom/releases
ROLLBACK_APPROVAL_ROOT=/opt/gilbom/rollback-approvals
SAFE_PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

PATH="$SAFE_PATH"
HOME=/root
export PATH HOME
unset CDPATH ENV BASH_ENV

fail() {
  echo "Production rollback approval recording failed: $1" >&2
  exit 1
}

[ "$(id -u)" -eq 0 ] \
  || fail "this command must run as root"
[ "$#" -eq 2 ] \
  || fail "usage: $0 FAILED_RELEASE_SHA ROLLBACK_PREFLIGHT_SHA256"

failed_release_sha="$1"
requested_preflight_sha256="$2"
printf '%s\n' "$failed_release_sha" | grep -Eq '^[0-9a-f]{40}$' \
  || fail "failed release SHA must contain 40 lowercase hexadecimal characters"
printf '%s\n' "$requested_preflight_sha256" | grep -Eq '^[0-9a-f]{64}$' \
  || fail "rollback preflight SHA-256 is invalid"

for command in \
  cat chown chmod date find grep jq mktemp mv rm sha256sum stat tr wc
do
  command -v "$command" >/dev/null 2>&1 \
    || fail "required command is missing: $command"
done

failed_record_dir="$RELEASE_RECORD_ROOT/$failed_release_sha"
manual_recovery="$failed_record_dir/manual-recovery-required.json"
preflight="$failed_record_dir/rollback-preflight.json"
preflight_checksum="$failed_record_dir/rollback-preflight.sha256"
approval="$ROLLBACK_APPROVAL_ROOT/$failed_release_sha.json"

for directory in \
  "$RELEASE_RECORD_ROOT" "$ROLLBACK_APPROVAL_ROOT" "$failed_record_dir"
do
  [ -d "$directory" ] && [ ! -L "$directory" ] \
    || fail "required directory is missing or symlinked: $directory"
  [ "$(stat -c '%U:%G' "$directory")" = root:root ] \
    || fail "required directory must be root:root: $directory"
  [ -z "$(find "$directory" -maxdepth 0 -perm /0022 -print -quit)" ] \
    || fail "required directory is group/world writable: $directory"
done
for file in "$manual_recovery" "$preflight" "$preflight_checksum"
do
  [ -f "$file" ] && [ ! -L "$file" ] && [ -s "$file" ] \
    || fail "required rollback evidence is missing or unsafe: $file"
  [ "$(stat -c '%U:%G' "$file")" = root:root ] \
    || fail "rollback evidence must be root:root: $file"
  [ -z "$(find "$file" -perm /0022 -print -quit)" ] \
    || fail "rollback evidence is group/world writable: $file"
done
(
  cd "$failed_record_dir"
  sha256sum -c rollback-preflight.sha256 >/dev/null
) || fail "rollback preflight checksum verification failed"
actual_preflight_sha256="$(sha256sum "$preflight" | awk '{print $1}')"
[ "$actual_preflight_sha256" = "$requested_preflight_sha256" ] \
  || fail "approval does not match the displayed rollback preflight"

manual_recovery_sha256="$(
  sha256sum "$manual_recovery" | awk '{print $1}'
)"
jq -e \
  --arg failed_release_sha "$failed_release_sha" \
  --arg manual_recovery_sha256 "$manual_recovery_sha256" \
  '
    .schemaVersion == 1
    and .failedReleaseSha == $failed_release_sha
    and .manualRecoverySha256 == $manual_recovery_sha256
    and .flyway.changed == true
    and .automaticRollbackAllowed == false
    and .compatibility
      == "REQUIRES_EXPLICIT_PREVIOUS_BACKEND_COMPATIBILITY_APPROVAL"
    and .databaseMigrationUndoPlanned == false
    and .databaseBackupRestorePlanned == false
  ' "$preflight" >/dev/null \
  || fail "rollback preflight is not eligible for explicit approval"

approval_input="$(mktemp)"
temporary=""
cleanup() {
  rm -f -- "$approval_input" "$temporary" >/dev/null 2>&1 || true
}
trap cleanup EXIT HUP INT TERM
cat > "$approval_input"
[ "$(wc -c < "$approval_input" | tr -d ' ')" -le 4096 ] \
  || fail "approval input is too large"
jq -e \
  '
    type == "object"
    and (keys | sort == ["approvedAtUtc", "approvedBy"])
    and (.approvedBy | type == "string")
    and (.approvedBy | length >= 1 and length <= 64)
    and (.approvedBy | explode | all(. >= 32 and . != 127))
    and (.approvedAtUtc | test(
      "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$"
    ))
  ' "$approval_input" >/dev/null \
  || fail "approval input is invalid"

approved_by="$(jq -r '.approvedBy' "$approval_input")"
approved_at_utc="$(jq -r '.approvedAtUtc' "$approval_input")"
approved_epoch="$(date -u -d "$approved_at_utc" '+%s')" \
  || fail "approval timestamp cannot be parsed"
now_epoch="$(date -u '+%s')"
approval_age_seconds="$((now_epoch - approved_epoch))"
[ "$approval_age_seconds" -ge -60 ] && [ "$approval_age_seconds" -le 300 ] \
  || fail "approval timestamp is outside the five-minute recording window"

rollback_target_sha="$(jq -r '.rollbackTargetSha' "$preflight")"
flyway_current_version="$(jq -r '.flyway.currentVersion' "$preflight")"
if [ -e "$approval" ]; then
  [ -f "$approval" ] && [ ! -L "$approval" ] \
    || fail "immutable rollback approval is unsafe"
  [ "$(stat -c '%U:%G' "$approval")" = root:root ] \
    || fail "immutable rollback approval must be root:root"
  [ -z "$(find "$approval" -perm /0022 -print -quit)" ] \
    || fail "immutable rollback approval is group/world writable"
  jq -e \
    --arg failed_release_sha "$failed_release_sha" \
    --arg rollback_target_sha "$rollback_target_sha" \
    --arg manual_recovery_sha256 "$manual_recovery_sha256" \
    --arg preflight_sha256 "$actual_preflight_sha256" \
    --argjson flyway_current_version "$flyway_current_version" \
    '
      .schemaVersion == 1
      and .failedReleaseSha == $failed_release_sha
      and .rollbackTargetSha == $rollback_target_sha
      and .manualRecoverySha256 == $manual_recovery_sha256
      and .rollbackPreflightSha256 == $preflight_sha256
      and .flywayCurrentVersion == $flyway_current_version
      and .compatibility == "APPROVED_PREVIOUS_BACKEND_COMPATIBLE"
    ' "$approval" >/dev/null \
    || fail "immutable rollback approval conflicts with this retry"
  cat "$approval"
  exit 0
fi

temporary="$(mktemp "$ROLLBACK_APPROVAL_ROOT/.approval.XXXXXX")"
jq -n -S \
  --arg failed_release_sha "$failed_release_sha" \
  --arg rollback_target_sha "$rollback_target_sha" \
  --arg manual_recovery_sha256 "$manual_recovery_sha256" \
  --arg preflight_sha256 "$actual_preflight_sha256" \
  --argjson flyway_current_version "$flyway_current_version" \
  --arg approved_by "$approved_by" \
  --arg approved_at_utc "$approved_at_utc" \
  '
    {
      schemaVersion: 1,
      failedReleaseSha: $failed_release_sha,
      rollbackTargetSha: $rollback_target_sha,
      manualRecoverySha256: $manual_recovery_sha256,
      rollbackPreflightSha256: $preflight_sha256,
      flywayCurrentVersion: $flyway_current_version,
      compatibility: "APPROVED_PREVIOUS_BACKEND_COMPATIBLE",
      approvedBy: $approved_by,
      approvedAtUtc: $approved_at_utc
    }
  ' > "$temporary"
chown root:root "$temporary"
chmod 0440 "$temporary"
mv -T -- "$temporary" "$approval"
temporary=""
cat "$approval"
