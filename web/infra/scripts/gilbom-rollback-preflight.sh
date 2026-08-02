#!/bin/sh

set -eu
set +x
umask 077

ARTIFACT_ROOT=/opt/gilbom/artifacts
DEPLOY_ROOT=/opt/gilbom/deploy
COMPOSE_PATH="$DEPLOY_ROOT/compose.prod.yaml"
RUNTIME_ENV_PATH="$DEPLOY_ROOT/runtime.env"
IMAGE_ENV_PATH="$DEPLOY_ROOT/image.env"
RELEASE_RECORD_ROOT=/var/lib/gilbom/releases
CURRENT_SHA_PATH="$RELEASE_RECORD_ROOT/current.sha"
SAFE_PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

PATH="$SAFE_PATH"
HOME=/root
export PATH HOME
unset CDPATH ENV BASH_ENV

fail() {
  echo "Production rollback preflight failed: $1" >&2
  exit 1
}

[ "$(id -u)" -eq 0 ] \
  || fail "this command must run as root"
[ "$#" -eq 1 ] \
  || fail "usage: $0 FAILED_RELEASE_SHA"

failed_release_sha="$1"
printf '%s\n' "$failed_release_sha" | grep -Eq '^[0-9a-f]{40}$' \
  || fail "failed release SHA must contain 40 lowercase hexadecimal characters"

for command in \
  chown chmod cmp date docker find grep install jq mktemp mv rm sha256sum \
  stat tr
do
  command -v "$command" >/dev/null 2>&1 \
    || fail "required command is missing: $command"
done

assert_root_directory() {
  path="$1"
  [ -d "$path" ] && [ ! -L "$path" ] \
    || fail "required directory is missing or symlinked: $path"
  [ "$(stat -c '%U:%G' "$path")" = root:root ] \
    || fail "required directory must be root:root: $path"
  [ -z "$(find "$path" -maxdepth 0 -perm /0022 -print -quit)" ] \
    || fail "required directory is group/world writable: $path"
}

assert_root_file() {
  path="$1"
  [ -f "$path" ] && [ ! -L "$path" ] && [ -s "$path" ] \
    || fail "required file is missing, empty, or symlinked: $path"
  [ "$(stat -c '%U:%G' "$path")" = root:root ] \
    || fail "required file must be root:root: $path"
  [ -z "$(find "$path" -perm /0022 -print -quit)" ] \
    || fail "required file is group/world writable: $path"
}

for directory in "$DEPLOY_ROOT" "$RELEASE_RECORD_ROOT"
do
  assert_root_directory "$directory"
done
for file in \
  "$COMPOSE_PATH" "$RUNTIME_ENV_PATH" "$IMAGE_ENV_PATH" "$CURRENT_SHA_PATH"
do
  assert_root_file "$file"
done

failed_record_dir="$RELEASE_RECORD_ROOT/$failed_release_sha"
manual_recovery="$failed_record_dir/manual-recovery-required.json"
assert_root_directory "$failed_record_dir"
assert_root_file "$manual_recovery"
[ ! -e "$failed_record_dir/approved-rollback-manifest.json" ] \
  || fail "this failed release already has a successful approved rollback"

rollback_target_sha="$(jq -r '.rollbackTargetSha' "$manual_recovery")"
printf '%s\n' "$rollback_target_sha" | grep -Eq '^[0-9a-f]{40}$' \
  || fail "manual recovery rollback target SHA is invalid"
current_sha="$(tr -d '\n' < "$CURRENT_SHA_PATH")"
[ "$current_sha" = "$rollback_target_sha" ] \
  || fail "current.sha changed after failure containment"

active_backend_sha="$(
  sed -n 's/^BACKEND_IMAGE=gilbom-backend:\([0-9a-f]\{40\}\)$/\1/p' \
    "$IMAGE_ENV_PATH"
)"
active_frontend_sha="$(
  sed -n 's/^FRONTEND_IMAGE=gilbom-frontend:\([0-9a-f]\{40\}\)$/\1/p' \
    "$IMAGE_ENV_PATH"
)"
[ "$active_backend_sha" = "$failed_release_sha" ] \
  && [ "$active_frontend_sha" = "$failed_release_sha" ] \
  || fail "image.env no longer points to the contained failed release"

jq -e \
  --arg failed_release_sha "$failed_release_sha" \
  --arg rollback_target_sha "$rollback_target_sha" \
  '
    .schemaVersion == 1
    and .status == "MANUAL_RECOVERY_REQUIRED"
    and .failedReleaseSha == $failed_release_sha
    and .rollbackTargetSha == $rollback_target_sha
    and .flyway.changed == true
    and (.flyway.afterVersion | type == "number")
    and .automaticRollbackAllowed == false
    and .previousBackendCompatibility == "UNCONFIRMED"
    and .services.frontend == "STOPPED"
    and .services.backend == "PRESERVED"
    and .services.database == "PRESERVED"
    and .databaseRecovery.migrationUndoPerformed == false
    and .databaseRecovery.backupRestorePerformed == false
  ' "$manual_recovery" >/dev/null \
  || fail "manual recovery evidence is not eligible for compatibility review"

target_artifact="$ARTIFACT_ROOT/$rollback_target_sha"
assert_root_directory "$target_artifact"
assert_root_file "$target_artifact/build-metadata.json"
jq -e \
  --arg rollback_target_sha "$rollback_target_sha" \
  '
    .releaseSha == $rollback_target_sha
    and .images.backend.name
      == ("gilbom-backend:" + $rollback_target_sha)
    and .images.frontend.name
      == ("gilbom-frontend:" + $rollback_target_sha)
  ' "$target_artifact/build-metadata.json" >/dev/null \
  || fail "rollback target Artifact metadata is invalid"

compose() {
  docker compose \
    --env-file "$RUNTIME_ENV_PATH" \
    --env-file "$IMAGE_ENV_PATH" \
    --file "$COMPOSE_PATH" \
    "$@"
}

db_id="$(compose ps -q db)"
[ -n "$db_id" ] \
  || fail "running production PostgreSQL container is required"
[ "$(docker inspect --format '{{.State.Health.Status}}' "$db_id")" = healthy ] \
  || fail "production PostgreSQL is not healthy"
flyway_current_version="$(
  compose exec -T db sh -ec '
    export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"
    exec psql \
      --host 127.0.0.1 \
      --username "$POSTGRES_USER" \
      --dbname "$POSTGRES_DB" \
      --no-password \
      --tuples-only \
      --no-align \
      --quiet \
      --set ON_ERROR_STOP=1 \
      --command "
        SELECT COALESCE(MAX(version::integer), 0)
        FROM flyway_history.flyway_schema_history
        WHERE success;
      "
  ' | tr -d '[:space:]'
)" || fail "could not query current production Flyway"
printf '%s\n' "$flyway_current_version" \
  | grep -Eq '^(0|[1-9][0-9]*)$' \
  || fail "current Flyway version is invalid"
[ "$flyway_current_version" = "$(
  jq -r '.flyway.afterVersion' "$manual_recovery"
)" ] || fail "Flyway changed after failure containment"

manual_recovery_sha256="$(
  sha256sum "$manual_recovery" | awk '{print $1}'
)"
target_metadata_sha256="$(
  sha256sum "$target_artifact/build-metadata.json" | awk '{print $1}'
)"
generated_at_utc="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
temporary="$(mktemp "$failed_record_dir/.rollback-preflight.XXXXXX")"
comparison_old=""
comparison_new=""
cleanup() {
  rm -f -- \
    "$temporary" "$comparison_old" "$comparison_new" \
    >/dev/null 2>&1 || true
}
trap cleanup EXIT HUP INT TERM

jq -n -S \
  --arg failed_release_sha "$failed_release_sha" \
  --arg rollback_target_sha "$rollback_target_sha" \
  --arg generated_at_utc "$generated_at_utc" \
  --arg manual_recovery_sha256 "$manual_recovery_sha256" \
  --arg target_metadata_sha256 "$target_metadata_sha256" \
  --arg target_backend_id "$(
    jq -r '.images.backend.id' "$target_artifact/build-metadata.json"
  )" \
  --arg target_frontend_id "$(
    jq -r '.images.frontend.id' "$target_artifact/build-metadata.json"
  )" \
  --argjson flyway_before "$(
    jq -r '.flyway.beforeVersion' "$manual_recovery"
  )" \
  --argjson flyway_current "$flyway_current_version" \
  '
    {
      schemaVersion: 1,
      failedReleaseSha: $failed_release_sha,
      rollbackTargetSha: $rollback_target_sha,
      generatedAtUtc: $generated_at_utc,
      manualRecoverySha256: $manual_recovery_sha256,
      rollbackTarget: {
        metadataSha256: $target_metadata_sha256,
        backendImageId: $target_backend_id,
        frontendImageId: $target_frontend_id
      },
      flyway: {
        beforeFailedDeployVersion: $flyway_before,
        currentVersion: $flyway_current,
        changed: true
      },
      compatibility:
        "REQUIRES_EXPLICIT_PREVIOUS_BACKEND_COMPATIBILITY_APPROVAL",
      automaticRollbackAllowed: false,
      databaseMigrationUndoPlanned: false,
      databaseBackupRestorePlanned: false
    }
  ' > "$temporary"

preflight="$failed_record_dir/rollback-preflight.json"
if [ -e "$preflight" ]; then
  assert_root_file "$preflight"
  comparison_old="$(mktemp)"
  comparison_new="$(mktemp)"
  jq -S \
    'del(.generatedAtUtc)' "$preflight" \
    > "$comparison_old"
  jq -S \
    'del(.generatedAtUtc)' "$temporary" \
    > "$comparison_new"
  cmp -s "$comparison_old" "$comparison_new" \
    || fail "rollback preflight state changed after review began"
  cat "$preflight"
  exit 0
fi

chown root:root "$temporary"
chmod 0440 "$temporary"
mv -T -- "$temporary" "$preflight"
temporary=""
preflight_sha256="$(sha256sum "$preflight" | awk '{print $1}')"
printf '%s  rollback-preflight.json\n' "$preflight_sha256" \
  > "$failed_record_dir/rollback-preflight.sha256"
chown root:root "$failed_record_dir/rollback-preflight.sha256"
chmod 0440 "$failed_record_dir/rollback-preflight.sha256"
cat "$preflight"
