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
DEPLOY_APPROVAL_ROOT=/opt/gilbom/deploy-approvals
COSIGN_BIN=/usr/local/bin/cosign
COSIGN_PUBLIC_KEY=/etc/gilbom/cosign-release.pub
COSIGN_VERSION=v3.1.2
SAFE_PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

PATH="$SAFE_PATH"
HOME=/root
export PATH HOME
unset CDPATH ENV BASH_ENV GIT_DIR GIT_WORK_TREE

fail() {
  echo "Production deploy preflight failed: $1" >&2
  exit 1
}

[ "$(id -u)" -eq 0 ] \
  || fail "this command must run as root"
[ "$#" -eq 1 ] \
  || fail "usage: $0 RELEASE_SHA"

release_sha="$1"
printf '%s\n' "$release_sha" | grep -Eq '^[0-9a-f]{40}$' \
  || fail "release SHA must contain 40 lowercase hexadecimal characters"

for command in \
  awk cat chown chmod cmp cp cut date dirname docker find grep install jq \
  mktemp mv rm sed sha256sum stat tail tr wc zstd
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

assert_root_owned_directory() {
  path="$1"
  [ -d "$path" ] && [ ! -L "$path" ] \
    || fail "required directory is missing or symlinked: $path"
  [ "$(stat -c '%U' "$path")" = root ] \
    || fail "required directory must be root-owned: $path"
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

atomic_root_file() {
  source="$1"
  destination="$2"
  mode="$3"
  directory="$(dirname -- "$destination")"
  temporary="$(mktemp "$directory/.preflight.XXXXXX")"
  cp -- "$source" "$temporary"
  chown root:root "$temporary"
  chmod "$mode" "$temporary"
  mv -T -- "$temporary" "$destination"
}

compose() {
  docker compose \
    --env-file "$RUNTIME_ENV_PATH" \
    --env-file "$IMAGE_ENV_PATH" \
    --file "$COMPOSE_PATH" \
    "$@"
}

artifact_dir="$ARTIFACT_ROOT/$release_sha"
candidate_compose_path="$artifact_dir/compose.prod.yaml"
source_archive_name="gilbom-web-$release_sha.tar.zst"
source_archive_path="$artifact_dir/$source_archive_name"
metadata="$artifact_dir/build-metadata.json"
policy="$artifact_dir/vulnerabilities/policy-summary.json"
release_record_dir="$RELEASE_RECORD_ROOT/$release_sha"
approval_file="$DEPLOY_APPROVAL_ROOT/$release_sha.json"

assert_root_owned_directory "$ARTIFACT_ROOT"
for directory in \
  "$DEPLOY_ROOT" \
  "$RELEASE_RECORD_ROOT" \
  "$DEPLOY_APPROVAL_ROOT" \
  "$artifact_dir"
do
  assert_root_directory "$directory"
done
for file in \
  "$COMPOSE_PATH" \
  "$RUNTIME_ENV_PATH" \
  "$IMAGE_ENV_PATH" \
  "$COSIGN_BIN" \
  "$COSIGN_PUBLIC_KEY" \
  "$artifact_dir/SHA256SUMS" \
  "$artifact_dir/SHA256SUMS.sigstore.json" \
  "$candidate_compose_path" \
  "$artifact_dir/images.tar.zst" \
  "$artifact_dir/images.tar.zst.sigstore.json" \
  "$artifact_dir/images.tar.zst.sha256" \
  "$source_archive_path" \
  "$metadata" \
  "$policy"
do
  assert_root_file "$file"
done
[ -z "$(find "$artifact_dir" -type l -print -quit)" ] \
  || fail "artifact directory contains a symlink"
[ -z "$(find "$artifact_dir" -perm /0222 -print -quit)" ] \
  || fail "artifact directory is not immutable"
[ "$(find "$artifact_dir" -type f | wc -l | tr -d ' ')" -eq 13 ] \
  || fail "artifact directory must contain exactly thirteen files"
"$COSIGN_BIN" version \
  | grep -Fq "GitVersion:    $COSIGN_VERSION" \
  || fail "Cosign verifier version differs from $COSIGN_VERSION"
"$COSIGN_BIN" verify-blob \
  --insecure-ignore-tlog \
  --key "$COSIGN_PUBLIC_KEY" \
  --bundle "$artifact_dir/SHA256SUMS.sigstore.json" \
  "$artifact_dir/SHA256SUMS" >/dev/null \
  || fail "complete release manifest Cosign signature is invalid"
(
  cd "$artifact_dir"
  sha256sum -c SHA256SUMS >/dev/null
  sha256sum -c images.tar.zst.sha256 >/dev/null
) || fail "artifact checksum verification failed"
"$COSIGN_BIN" verify-blob \
  --insecure-ignore-tlog \
  --key "$COSIGN_PUBLIC_KEY" \
  --bundle "$artifact_dir/images.tar.zst.sigstore.json" \
  "$artifact_dir/images.tar.zst" >/dev/null \
  || fail "release image archive Cosign signature is invalid"
zstd --quiet --test "$source_archive_path" \
  || fail "release source archive failed zstd integrity testing"

jq_cosign_public_key_sha256="$(
  sha256sum "$COSIGN_PUBLIC_KEY" | awk '{print $1}'
)"
candidate_compose_sha256="$(
  sha256sum "$candidate_compose_path" | awk '{print $1}'
)"
source_archive_sha256="$(
  sha256sum "$source_archive_path" | awk '{print $1}'
)"
jq -e \
  --arg release_sha "$release_sha" \
  --arg backend_image "gilbom-backend:$release_sha" \
  --arg frontend_image "gilbom-frontend:$release_sha" \
  --arg cosign_public_key_sha256 "$jq_cosign_public_key_sha256" \
  --arg compose_sha256 "$candidate_compose_sha256" \
  --arg source_archive_name "$source_archive_name" \
  --arg source_archive_sha256 "$source_archive_sha256" \
  '
    .schemaVersion == 1
    and .releaseSha == $release_sha
    and .images.backend.name == $backend_image
    and .images.frontend.name == $frontend_image
    and (.images.backend.id | test("^sha256:[0-9a-f]{64}$"))
    and (.images.frontend.id | test("^sha256:[0-9a-f]{64}$"))
    and .artifacts.images.signature.mode == "offline-key"
    and .artifacts.images.signature.cosignVersion == "v3.1.2"
    and (
      .artifacts.images.signature.publicKeySha256
      == $cosign_public_key_sha256
    )
    and (
      .artifacts.images.signature.bundle.path
      == "images.tar.zst.sigstore.json"
    )
    and .artifacts.source.path == $source_archive_name
    and .artifacts.source.sha256 == $source_archive_sha256
    and .artifacts.compose.path == "compose.prod.yaml"
    and .artifacts.compose.sha256 == $compose_sha256
    and (.flyway.latestVersion | test("^V[1-9][0-9]*$"))
    and (.flyway.migrations | type == "array" and length > 0)
    and all(
      .flyway.migrations[];
      (.version | type == "number")
      and (.file | test("^V[1-9][0-9]*__[A-Za-z0-9_]+[.]sql$"))
      and (.sha256 | test("^[0-9a-f]{64}$"))
    )
  ' "$metadata" >/dev/null \
  || fail "release metadata is invalid"
jq -e \
  '
    .status == "PASS"
    and (.criticalCount | type == "number")
    and (.highCount | type == "number")
    and (.exceptedHighCount | type == "number")
    and .criticalCount == 0
    and .highCount == .exceptedHighCount
  ' "$policy" >/dev/null \
  || fail "release vulnerability policy is not deployable"

BACKEND_IMAGE="gilbom-backend:$release_sha" \
FRONTEND_IMAGE="gilbom-frontend:$release_sha" \
docker compose \
  --env-file "$RUNTIME_ENV_PATH" \
  --env-file "$IMAGE_ENV_PATH" \
  --file "$candidate_compose_path" \
  config --quiet \
  || fail "signed release Compose configuration is invalid or runtime.env is incomplete"

backend_image_id="$(jq -r '.images.backend.id' "$metadata")"
frontend_image_id="$(jq -r '.images.frontend.id' "$metadata")"
archive_sha256="$(
  awk '
    NF == 2 && $2 == "images.tar.zst" {print $1}
  ' "$artifact_dir/images.tar.zst.sha256"
)"
printf '%s\n' "$archive_sha256" | grep -Eq '^[0-9a-f]{64}$' \
  || fail "archive checksum is invalid"
sha256_sums_sha256="$(sha256sum "$artifact_dir/SHA256SUMS" | awk '{print $1}')"
signature_bundle_sha256="$(
  sha256sum "$artifact_dir/images.tar.zst.sigstore.json" | awk '{print $1}'
)"
manifest_signature_bundle_sha256="$(
  sha256sum "$artifact_dir/SHA256SUMS.sigstore.json" | awk '{print $1}'
)"
critical_count="$(jq -r '.criticalCount' "$policy")"
high_count="$(jq -r '.highCount' "$policy")"
excepted_high_count="$(jq -r '.exceptedHighCount' "$policy")"
unapproved_high_count="$((high_count - excepted_high_count))"

if [ -e "$RELEASE_RECORD_ROOT/current.sha" ]; then
  assert_root_file "$RELEASE_RECORD_ROOT/current.sha"
  current_sha="$(tr -d '\n' < "$RELEASE_RECORD_ROOT/current.sha")"
else
  current_backend_sha="$(
    sed -n 's/^BACKEND_IMAGE=gilbom-backend:\([0-9a-f]\{40\}\)$/\1/p' \
      "$IMAGE_ENV_PATH"
  )"
  current_frontend_sha="$(
    sed -n 's/^FRONTEND_IMAGE=gilbom-frontend:\([0-9a-f]\{40\}\)$/\1/p' \
      "$IMAGE_ENV_PATH"
  )"
  [ -n "$current_backend_sha" ] \
    && [ "$current_backend_sha" = "$current_frontend_sha" ] \
    || fail "current production SHA cannot be resolved"
  current_sha="$current_backend_sha"
fi
printf '%s\n' "$current_sha" | grep -Eq '^[0-9a-f]{40}$' \
  || fail "current production SHA is invalid"

compose config --quiet \
  || fail "fixed production Compose configuration is invalid"
db_id="$(compose ps -q db)"
[ -n "$db_id" ] \
  || fail "running production PostgreSQL container is required"
[ "$(docker inspect --format '{{.State.Health.Status}}' "$db_id")" = healthy ] \
  || fail "production PostgreSQL is not healthy"

current_version="$(
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
)" || fail "could not query current Flyway version"
printf '%s\n' "$current_version" | grep -Eq '^(0|[1-9][0-9]*)$' \
  || fail "current Flyway version is invalid"
target_version="$(
  jq -r '.flyway.latestVersion | ltrimstr("V")' "$metadata"
)"

work_dir="$(mktemp -d "/var/tmp/gilbom-preflight-$release_sha.XXXXXX")"
cleanup() {
  rm -rf -- "$work_dir" >/dev/null 2>&1 || true
}
trap cleanup EXIT HUP INT TERM

database_migrations="$work_dir/database-migrations.txt"
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
    --field-separator="|" \
    --set ON_ERROR_STOP=1 \
    --command "
      SELECT version::integer, script
      FROM flyway_history.flyway_schema_history
      WHERE success
      ORDER BY version::integer;
    "
' > "$database_migrations" \
  || fail "could not query applied Flyway migrations"

expected_current="$work_dir/expected-current.txt"
jq -r --argjson current_version "$current_version" '
  .flyway.migrations[]
  | select(.version <= $current_version)
  | "\(.version)|\(.file)"
' "$metadata" > "$expected_current"

failed_migration_count="$(
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
        SELECT COUNT(*)
        FROM flyway_history.flyway_schema_history
        WHERE NOT success;
      "
  ' | tr -d '[:space:]'
)" || fail "could not query failed Flyway migrations"

compatibility=COMPATIBLE_NO_SCHEMA_CHANGE
compatibility_reason="Target schema equals the current production schema."
if [ "$current_sha" = "$release_sha" ]; then
  compatibility=BLOCKED_ALREADY_CURRENT
  compatibility_reason="Requested release is already the current production release."
elif [ "$failed_migration_count" -ne 0 ]; then
  compatibility=BLOCKED_FAILED_MIGRATION
  compatibility_reason="Production Flyway history contains a failed migration."
elif [ "$current_version" -gt "$target_version" ]; then
  compatibility=BLOCKED_SCHEMA_DOWNGRADE
  compatibility_reason="Target schema is older than the production schema."
elif ! cmp -s "$database_migrations" "$expected_current"; then
  compatibility=BLOCKED_HISTORY_MISMATCH
  compatibility_reason="Production Flyway history is not a prefix of release metadata."
elif [ "$current_version" -lt "$target_version" ]; then
  compatibility=COMPATIBLE_FORWARD_MIGRATION_REQUIRES_APPROVAL
  compatibility_reason="Forward migrations require approval of the exact migration plan."
fi

install -d -o root -g root -m 0700 "$release_record_dir"
[ ! -e "$release_record_dir/release-manifest.json" ] \
  || fail "release already has an immutable deployment manifest"
existing_approval=false
if [ -e "$approval_file" ]; then
  assert_root_file "$approval_file"
  existing_approval=true
fi

migration_plan="$release_record_dir/migration-plan.json"
jq -cS \
  --arg release_sha "$release_sha" \
  --argjson current_version "$current_version" \
  --argjson target_version "$target_version" \
  '
    {
      schemaVersion: 1,
      releaseSha: $release_sha,
      currentVersion: $current_version,
      targetVersion: $target_version,
      migrations: [
        .flyway.migrations[]
        | select(.version > $current_version)
        | {
            version: .version,
            file: .file,
            sha256: .sha256
          }
      ]
    }
  ' "$metadata" > "$work_dir/migration-plan.json"
migration_plan_sha256="$(
  sha256sum "$work_dir/migration-plan.json" | awk '{print $1}'
)"
if [ "$existing_approval" = true ]; then
  assert_root_file "$migration_plan"
  assert_root_file "$release_record_dir/migration-plan.sha256"
  (
    cd "$release_record_dir"
    sha256sum -c migration-plan.sha256 >/dev/null
  ) || fail "approved migration plan checksum verification failed"
  cmp -s "$work_dir/migration-plan.json" "$migration_plan" \
    || fail "production migration state changed after immutable approval"
  [ "$(
    sha256sum "$migration_plan" | awk '{print $1}'
  )" = "$migration_plan_sha256" ] \
    || fail "approved migration plan differs from current production state"
else
  atomic_root_file "$work_dir/migration-plan.json" "$migration_plan" 0440
fi
printf '%s  migration-plan.json\n' "$migration_plan_sha256" \
  > "$work_dir/migration-plan.sha256"
if [ "$existing_approval" != true ]; then
  atomic_root_file \
    "$work_dir/migration-plan.sha256" \
    "$release_record_dir/migration-plan.sha256" \
    0440
fi

generated_at_utc="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
preflight="$work_dir/deploy-preflight.json"
jq -n -S \
  --arg release_sha "$release_sha" \
  --arg generated_at_utc "$generated_at_utc" \
  --arg artifact_directory "$artifact_dir" \
  --arg sha256_sums_sha256 "$sha256_sums_sha256" \
  --arg archive_sha256 "$archive_sha256" \
  --arg cosign_version "$COSIGN_VERSION" \
  --arg cosign_public_key_sha256 "$jq_cosign_public_key_sha256" \
  --arg signature_bundle_sha256 "$signature_bundle_sha256" \
  --arg manifest_signature_bundle_sha256 \
    "$manifest_signature_bundle_sha256" \
  --arg source_archive_name "$source_archive_name" \
  --arg source_archive_sha256 "$source_archive_sha256" \
  --arg compose_sha256 "$candidate_compose_sha256" \
  --arg backend_image "gilbom-backend:$release_sha" \
  --arg backend_image_id "$backend_image_id" \
  --arg frontend_image "gilbom-frontend:$release_sha" \
  --arg frontend_image_id "$frontend_image_id" \
  --argjson critical_count "$critical_count" \
  --argjson high_count "$high_count" \
  --argjson excepted_high_count "$excepted_high_count" \
  --argjson unapproved_high_count "$unapproved_high_count" \
  --arg current_sha "$current_sha" \
  --arg rollback_target_sha "$current_sha" \
  --argjson current_version "$current_version" \
  --argjson target_version "$target_version" \
  --arg migration_plan_sha256 "$migration_plan_sha256" \
  --arg compatibility "$compatibility" \
  --arg compatibility_reason "$compatibility_reason" \
  --slurpfile migration_plan "$migration_plan" \
  '
    {
      schemaVersion: 1,
      releaseSha: $release_sha,
      generatedAtUtc: $generated_at_utc,
      artifact: {
        directory: $artifact_directory,
        sha256SumsSha256: $sha256_sums_sha256,
        archiveSha256: $archive_sha256,
        signature: {
          status: "PASS",
          cosignVersion: $cosign_version,
          publicKeySha256: $cosign_public_key_sha256,
          bundleSha256: $signature_bundle_sha256,
          manifestBundleSha256: $manifest_signature_bundle_sha256
        }
      },
      source: {
        archive: $source_archive_name,
        sha256: $source_archive_sha256
      },
      compose: {
        path: "compose.prod.yaml",
        sha256: $compose_sha256
      },
      images: {
        backend: {
          name: $backend_image,
          id: $backend_image_id
        },
        frontend: {
          name: $frontend_image,
          id: $frontend_image_id
        }
      },
      vulnerabilities: {
        critical: $critical_count,
        high: $high_count,
        exceptedHigh: $excepted_high_count,
        unapprovedHigh: $unapproved_high_count
      },
      production: {
        currentSha: $current_sha,
        rollbackTargetSha: $rollback_target_sha
      },
      flyway: {
        currentVersion: $current_version,
        targetVersion: $target_version,
        migrationPlanSha256: $migration_plan_sha256,
        pendingMigrations: $migration_plan[0].migrations,
        compatibility: $compatibility,
        compatibilityReason: $compatibility_reason
      }
    }
  ' > "$preflight"

if [ "$existing_approval" = true ]; then
  stored_preflight="$release_record_dir/deploy-preflight.json"
  stored_preflight_checksum="$release_record_dir/deploy-preflight.sha256"
  assert_root_file "$stored_preflight"
  assert_root_file "$stored_preflight_checksum"
  (
    cd "$release_record_dir"
    sha256sum -c deploy-preflight.sha256 >/dev/null
  ) || fail "immutable approved preflight checksum verification failed"
  jq -S 'del(.generatedAtUtc)' "$preflight" \
    > "$work_dir/current-preflight-state.json"
  jq -S 'del(.generatedAtUtc)' "$stored_preflight" \
    > "$work_dir/approved-preflight-state.json"
  cmp -s \
    "$work_dir/current-preflight-state.json" \
    "$work_dir/approved-preflight-state.json" \
    || fail "production state changed after immutable deployment approval"
  stored_preflight_sha256="$(
    sha256sum "$stored_preflight" | awk '{print $1}'
  )"
  jq -e \
    --arg release_sha "$release_sha" \
    --arg preflight_sha256 "$stored_preflight_sha256" \
    --arg artifact_manifest_sha256 "$sha256_sums_sha256" \
    --arg current_sha "$current_sha" \
    --arg migration_plan_sha256 "$migration_plan_sha256" \
    '
      .schemaVersion == 1
      and .releaseSha == $release_sha
      and .preflightSha256 == $preflight_sha256
      and .artifactManifestSha256 == $artifact_manifest_sha256
      and .currentSha == $current_sha
      and .migrationPlanSha256 == $migration_plan_sha256
    ' "$approval_file" >/dev/null \
    || fail "immutable approval does not match the current preflight state"
  cat "$stored_preflight"
  exit 0
fi

atomic_root_file \
  "$preflight" \
  "$release_record_dir/deploy-preflight.json" \
  0440
preflight_sha256="$(
  sha256sum "$release_record_dir/deploy-preflight.json" | awk '{print $1}'
)"
printf '%s  deploy-preflight.json\n' "$preflight_sha256" \
  > "$work_dir/deploy-preflight.sha256"
atomic_root_file \
  "$work_dir/deploy-preflight.sha256" \
  "$release_record_dir/deploy-preflight.sha256" \
  0440

cat "$release_record_dir/deploy-preflight.json"
