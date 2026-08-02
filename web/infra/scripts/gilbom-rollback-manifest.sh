#!/bin/sh

set -eu
set +x

RELEASE_RECORD_ROOT=/var/lib/gilbom/releases

fail() {
  echo "Production rollback manifest retrieval failed: $1" >&2
  exit 1
}

[ "$(id -u)" -eq 0 ] \
  || fail "this command must run as root"
[ "$#" -eq 1 ] \
  || fail "usage: $0 FAILED_RELEASE_SHA"

failed_release_sha="$1"
printf '%s\n' "$failed_release_sha" | grep -Eq '^[0-9a-f]{40}$' \
  || fail "failed release SHA must contain 40 lowercase hexadecimal characters"

record_dir="$RELEASE_RECORD_ROOT/$failed_release_sha"
if [ -f "$record_dir/approved-rollback-manifest.json" ]; then
  manifest="$record_dir/approved-rollback-manifest.json"
elif [ -f "$record_dir/rollback-manifest.json" ]; then
  manifest="$record_dir/rollback-manifest.json"
elif [ -f "$record_dir/manual-recovery-required.json" ]; then
  manifest="$record_dir/manual-recovery-required.json"
else
  fail "rollback or manual recovery manifest is missing"
fi

[ ! -L "$manifest" ] && [ -s "$manifest" ] \
  || fail "rollback manifest is empty or unsafe"
[ "$(stat -c '%U:%G' "$manifest")" = root:root ] \
  || fail "rollback manifest must be root:root"
[ -z "$(find "$manifest" -perm /0022 -print -quit)" ] \
  || fail "rollback manifest is group/world writable"
jq -e \
  --arg failed_release_sha "$failed_release_sha" \
  '
    .schemaVersion == 1
    and .failedReleaseSha == $failed_release_sha
    and (
      .status == "AUTOMATIC_ROLLBACK_SUCCEEDED"
      or .status == "MANUALLY_APPROVED_ROLLBACK_SUCCEEDED"
      or .status == "MANUAL_RECOVERY_REQUIRED"
    )
  ' "$manifest" >/dev/null \
  || fail "rollback manifest does not match the requested failure"

cat "$manifest"
