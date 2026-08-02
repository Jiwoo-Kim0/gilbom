#!/bin/sh

set -eu
set +x
umask 077

RELEASE_RECORD_ROOT=/var/lib/gilbom/releases
DEPLOY_APPROVAL_ROOT=/opt/gilbom/deploy-approvals
MIGRATION_APPROVAL_ROOT=/opt/gilbom/migration-approvals
SAFE_PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

PATH="$SAFE_PATH"
HOME=/root
export PATH HOME
unset CDPATH ENV BASH_ENV

fail() {
  echo "Production deploy approval recording failed: $1" >&2
  exit 1
}

[ "$(id -u)" -eq 0 ] \
  || fail "this command must run as root"
[ "$#" -eq 2 ] \
  || fail "usage: $0 RELEASE_SHA PREFLIGHT_SHA256"

release_sha="$1"
requested_preflight_sha256="$2"
printf '%s\n' "$release_sha" | grep -Eq '^[0-9a-f]{40}$' \
  || fail "release SHA must contain 40 lowercase hexadecimal characters"
printf '%s\n' "$requested_preflight_sha256" | grep -Eq '^[0-9a-f]{64}$' \
  || fail "preflight SHA-256 is invalid"

for command in \
  cat chown chmod date find grep install jq mktemp mv rm sha256sum stat tr wc
do
  command -v "$command" >/dev/null 2>&1 \
    || fail "required command is missing: $command"
done

release_record_dir="$RELEASE_RECORD_ROOT/$release_sha"
preflight="$release_record_dir/deploy-preflight.json"
preflight_checksum="$release_record_dir/deploy-preflight.sha256"
migration_plan="$release_record_dir/migration-plan.json"
deploy_approval="$DEPLOY_APPROVAL_ROOT/$release_sha.json"
migration_approval="$MIGRATION_APPROVAL_ROOT/$release_sha.json"

for directory in \
  "$RELEASE_RECORD_ROOT" \
  "$DEPLOY_APPROVAL_ROOT" \
  "$MIGRATION_APPROVAL_ROOT" \
  "$release_record_dir"
do
  [ -d "$directory" ] && [ ! -L "$directory" ] \
    || fail "required directory is missing or symlinked: $directory"
  [ "$(stat -c '%U:%G' "$directory")" = root:root ] \
    || fail "required directory must be root:root: $directory"
  [ -z "$(find "$directory" -maxdepth 0 -perm /0022 -print -quit)" ] \
    || fail "required directory is group/world writable: $directory"
done
for file in "$preflight" "$preflight_checksum" "$migration_plan"
do
  [ -f "$file" ] && [ ! -L "$file" ] && [ -s "$file" ] \
    || fail "required preflight evidence is missing or unsafe: $file"
  [ "$(stat -c '%U:%G' "$file")" = root:root ] \
    || fail "preflight evidence must be root:root: $file"
  [ -z "$(find "$file" -perm /0022 -print -quit)" ] \
    || fail "preflight evidence is group/world writable: $file"
done
(
  cd "$release_record_dir"
  sha256sum -c deploy-preflight.sha256 >/dev/null
  sha256sum -c migration-plan.sha256 >/dev/null
) || fail "preflight evidence checksum verification failed"
actual_preflight_sha256="$(sha256sum "$preflight" | awk '{print $1}')"
[ "$actual_preflight_sha256" = "$requested_preflight_sha256" ] \
  || fail "approval does not match the displayed preflight"

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
jq -e \
  --arg release_sha "$release_sha" \
  '
    .schemaVersion == 1
    and .releaseSha == $release_sha
    and (.flyway.compatibility | startswith("COMPATIBLE_"))
    and .vulnerabilities.critical == 0
    and .vulnerabilities.unapprovedHigh == 0
  ' "$preflight" >/dev/null \
  || fail "preflight does not permit deployment"

pending_migration_count="$(jq -r '.flyway.pendingMigrations | length' "$preflight")"

approved_by="$(jq -r '.approvedBy' "$approval_input")"
approved_at_utc="$(jq -r '.approvedAtUtc' "$approval_input")"
approved_epoch="$(date -u -d "$approved_at_utc" '+%s')" \
  || fail "approval timestamp cannot be parsed"
now_epoch="$(date -u '+%s')"
approval_age_seconds="$((now_epoch - approved_epoch))"
[ "$approval_age_seconds" -ge -60 ] && [ "$approval_age_seconds" -le 300 ] \
  || fail "approval timestamp is outside the five-minute recording window"
artifact_manifest_sha256="$(jq -r '.artifact.sha256SumsSha256' "$preflight")"
current_sha="$(jq -r '.production.currentSha' "$preflight")"
migration_plan_sha256="$(jq -r '.flyway.migrationPlanSha256' "$preflight")"

if [ -e "$deploy_approval" ]; then
  [ -f "$deploy_approval" ] && [ ! -L "$deploy_approval" ] \
    || fail "immutable deployment approval is unsafe"
  [ "$(stat -c '%U:%G' "$deploy_approval")" = root:root ] \
    || fail "immutable deployment approval must be root:root"
  [ -z "$(find "$deploy_approval" -perm /0022 -print -quit)" ] \
    || fail "immutable deployment approval is group/world writable"
  jq -e \
    --arg release_sha "$release_sha" \
    --arg preflight_sha256 "$actual_preflight_sha256" \
    --arg artifact_manifest_sha256 "$artifact_manifest_sha256" \
    --arg current_sha "$current_sha" \
    --arg migration_plan_sha256 "$migration_plan_sha256" \
    '
      .schemaVersion == 1
      and .releaseSha == $release_sha
      and .preflightSha256 == $preflight_sha256
      and .artifactManifestSha256 == $artifact_manifest_sha256
      and .currentSha == $current_sha
      and .migrationPlanSha256 == $migration_plan_sha256
      and (.approvedBy | type == "string")
      and (.approvedBy | length >= 1 and length <= 64)
      and (.approvedBy | explode | all(. >= 32 and . != 127))
      and (.approvedAtUtc | test(
        "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$"
      ))
    ' "$deploy_approval" >/dev/null \
    || fail "immutable deployment approval conflicts with this retry"
  if [ "$pending_migration_count" -gt 0 ]; then
    [ -f "$migration_approval" ] && [ ! -L "$migration_approval" ] \
      || fail "immutable migration approval is missing or unsafe"
    [ "$(stat -c '%U:%G' "$migration_approval")" = root:root ] \
      || fail "immutable migration approval must be root:root"
    [ -z "$(find "$migration_approval" -perm /0022 -print -quit)" ] \
      || fail "immutable migration approval is group/world writable"
    jq -e \
      --arg release_sha "$release_sha" \
      --arg migration_plan_sha256 "$migration_plan_sha256" \
      '
        .schemaVersion == 1
        and .releaseSha == $release_sha
        and .migrationPlanSha256 == $migration_plan_sha256
        and .source == "jenkins-production-deploy-approval"
      ' "$migration_approval" >/dev/null \
      || fail "immutable migration approval conflicts with this retry"
  fi
  cat "$deploy_approval"
  exit 0
fi

if [ "$pending_migration_count" -gt 0 ]; then
  [ ! -e "$migration_approval" ] \
    || fail "orphaned immutable migration approval already exists"
fi

temporary="$(mktemp "$DEPLOY_APPROVAL_ROOT/.approval.XXXXXX")"
jq -n -S \
  --arg release_sha "$release_sha" \
  --arg preflight_sha256 "$actual_preflight_sha256" \
  --arg artifact_manifest_sha256 "$artifact_manifest_sha256" \
  --arg current_sha "$current_sha" \
  --arg migration_plan_sha256 "$migration_plan_sha256" \
  --arg approved_by "$approved_by" \
  --arg approved_at_utc "$approved_at_utc" \
  '
    {
      schemaVersion: 1,
      releaseSha: $release_sha,
      preflightSha256: $preflight_sha256,
      artifactManifestSha256: $artifact_manifest_sha256,
      currentSha: $current_sha,
      migrationPlanSha256: $migration_plan_sha256,
      approvedBy: $approved_by,
      approvedAtUtc: $approved_at_utc
    }
  ' > "$temporary"
chown root:root "$temporary"
chmod 0440 "$temporary"
mv -T -- "$temporary" "$deploy_approval"
temporary=""

if [ "$pending_migration_count" -gt 0 ]; then
  temporary="$(mktemp "$MIGRATION_APPROVAL_ROOT/.approval.XXXXXX")"
  jq -n -S \
    --arg release_sha "$release_sha" \
    --arg plan_sha256 "$migration_plan_sha256" \
    --arg approved_by "$approved_by" \
    --arg approved_at_utc "$approved_at_utc" \
    '
      {
        schemaVersion: 1,
        releaseSha: $release_sha,
        migrationPlanSha256: $plan_sha256,
        approvedBy: $approved_by,
        approvedAtUtc: $approved_at_utc,
        source: "jenkins-production-deploy-approval"
      }
    ' > "$temporary"
  chown root:root "$temporary"
  chmod 0440 "$temporary"
  mv -T -- "$temporary" "$migration_approval"
  temporary=""
fi

cat "$deploy_approval"
