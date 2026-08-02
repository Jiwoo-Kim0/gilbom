#!/bin/sh

set -eu
set +x
umask 077

RELEASE_RECORD_ROOT=/var/lib/gilbom/releases
MIGRATION_APPROVAL_ROOT=/opt/gilbom/migration-approvals
SAFE_PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
PATH="$SAFE_PATH"
HOME=/root
export PATH HOME
unset CDPATH ENV BASH_ENV

fail() {
  echo "Production migration approval failed: $1" >&2
  exit 1
}

[ "$(id -u)" -eq 0 ] \
  || fail "this command must run as root"
[ "$#" -eq 2 ] \
  || fail "usage: $0 RELEASE_SHA APPROVER"

release_sha="$1"
approver="$2"
printf '%s\n' "$release_sha" | grep -Eq '^[0-9a-f]{40}$' \
  || fail "release SHA must contain 40 lowercase hexadecimal characters"
printf '%s\n' "$approver" | grep -Eq '^[A-Za-z0-9._@+-]{1,64}$' \
  || fail "approver must use 1-64 safe identity characters"

for command in awk chown chmod date find grep jq mktemp mv sha256sum stat
do
  command -v "$command" >/dev/null 2>&1 \
    || fail "required command is missing: $command"
done

plan_directory="$RELEASE_RECORD_ROOT/$release_sha"
plan="$plan_directory/migration-plan.json"
plan_checksum="$plan_directory/migration-plan.sha256"
approval="$MIGRATION_APPROVAL_ROOT/$release_sha.json"

for directory in \
  "$RELEASE_RECORD_ROOT" \
  "$MIGRATION_APPROVAL_ROOT" \
  "$plan_directory"
do
  [ -d "$directory" ] && [ ! -L "$directory" ] \
    || fail "required directory is missing or symlinked: $directory"
  [ "$(stat -c '%U:%G' "$directory")" = root:root ] \
    || fail "required directory must be root:root: $directory"
  [ -z "$(find "$directory" -maxdepth 0 -perm /0022 -print -quit)" ] \
    || fail "required directory is group/world writable: $directory"
done

for file in "$plan" "$plan_checksum"
do
  [ -f "$file" ] && [ ! -L "$file" ] \
    || fail "migration plan evidence is missing or symlinked: $file"
  [ "$(stat -c '%U:%G' "$file")" = root:root ] \
    || fail "migration plan evidence must be root:root: $file"
  [ -z "$(find "$file" -perm /0022 -print -quit)" ] \
    || fail "migration plan evidence is group/world writable: $file"
done

(
  cd "$plan_directory"
  sha256sum -c migration-plan.sha256
) || fail "migration plan checksum verification failed"
plan_sha256="$(sha256sum "$plan" | awk '{print $1}')"
jq -e \
  --arg release_sha "$release_sha" \
  '
    .schemaVersion == 1
    and .releaseSha == $release_sha
    and (.currentVersion | type == "number")
    and (.targetVersion | type == "number")
    and (.targetVersion > .currentVersion)
    and (.migrations | type == "array" and length > 0)
  ' "$plan" >/dev/null \
  || fail "migration plan does not contain pending migrations for this release"

echo "Review this exact migration plan before approval:"
jq . "$plan"
printf 'Type the full release SHA to approve this exact plan: ' >&2
IFS= read -r confirmation
[ "$confirmation" = "$release_sha" ] \
  || fail "approval confirmation did not match the release SHA"

[ ! -e "$approval" ] \
  || fail "immutable migration approval already exists"
temporary="$(mktemp "$MIGRATION_APPROVAL_ROOT/.approval.XXXXXX")"
jq -n \
  --arg release_sha "$release_sha" \
  --arg plan_sha256 "$plan_sha256" \
  --arg approver "$approver" \
  --arg approved_at_utc "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" \
  '
    {
      schemaVersion: 1,
      releaseSha: $release_sha,
      migrationPlanSha256: $plan_sha256,
      approvedBy: $approver,
      approvedAtUtc: $approved_at_utc
    }
  ' > "$temporary"
chown root:root "$temporary"
chmod 0440 "$temporary"
mv -T -- "$temporary" "$approval"

echo "Production migration approval: PASS ($approval)"
