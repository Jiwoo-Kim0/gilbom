#!/bin/sh

set -eu
set +x
umask 077

# 이 root entrypoint의 모든 운영 경로는 고정값이다. deploy 호출자는 인자나
# 환경변수로 Artifact, Compose, 환경 파일, Secret, backup 위치를 바꿀 수 없다.
ARTIFACT_ROOT=/opt/gilbom/artifacts
DEPLOY_ROOT=/opt/gilbom/deploy
COMPOSE_PATH="$DEPLOY_ROOT/compose.prod.yaml"
RUNTIME_ENV_PATH="$DEPLOY_ROOT/runtime.env"
IMAGE_ENV_PATH="$DEPLOY_ROOT/image.env"
SECRET_ROOT=/opt/gilbom/secrets
BACKUP_ROOT=/opt/gilbom/backups
BACKUP_SCRIPT="$DEPLOY_ROOT/infra/scripts/backup-volumes.sh"
MIGRATION_APPROVAL_ROOT=/opt/gilbom/migration-approvals
DEPLOY_APPROVAL_ROOT=/opt/gilbom/deploy-approvals
RELEASE_RECORD_ROOT=/var/lib/gilbom/releases
CURRENT_SHA_PATH="$RELEASE_RECORD_ROOT/current.sha"
PREVIOUS_SHA_PATH="$RELEASE_RECORD_ROOT/previous.sha"
LOCK_ROOT=/run/gilbom-deploy
LOCK_PATH="$LOCK_ROOT/deploy.lock"
HEALTH_TIMEOUT_SECONDS=240
SAFE_PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

fail() {
  echo "Production deploy boundary failed: $1" >&2
  exit 1
}

[ "$#" -eq 1 ] \
  || fail "usage: $0 RELEASE_SHA"

release_sha="$1"
printf '%s\n' "$release_sha" | grep -Eq '^[0-9a-f]{40}$' \
  || fail "release SHA must contain 40 lowercase hexadecimal characters"

# sudo env_reset·!setenv이 1차 경계지만 root entrypoint도 경로·Docker endpoint
# override를 독립적으로 거부한다.
for variable in \
  COMPOSE_FILE ENV_FILE RUNTIME_ENV_FILE IMAGE_ENV_FILE RELEASE_DIR \
  PROJECT_DIR EXPECTED_GIT_REVISION BACKEND_IMAGE FRONTEND_IMAGE \
  DEPLOY_PULL ALLOW_MUTABLE_IMAGE_TAGS SECRET_SCAN_DIR BACKUP_DIR \
  BACKUP_ROOT MIGRATION_APPROVAL_ROOT RELEASE_RECORD_ROOT \
  DEPLOY_APPROVAL_ROOT \
  COMPOSE_PROJECT_NAME DOCKER_HOST DOCKER_CONTEXT
do
  value="$(printenv "$variable" 2>/dev/null || true)"
  [ -z "$value" ] \
    || fail "deployment path and image environment overrides are forbidden"
done

[ "$(id -u)" -eq 0 ] \
  || fail "this command must run as root"

PATH="$SAFE_PATH"
HOME=/root
export PATH HOME
unset CDPATH ENV BASH_ENV GIT_DIR GIT_WORK_TREE

for command in \
  awk basename bash chown chmod cmp cp cut date dirname docker find flock \
  grep install jq mktemp mv printenv rm sed sha256sum sleep stat tail tr wc zstd
do
  command -v "$command" >/dev/null 2>&1 \
    || fail "required command is missing: $command"
done

assert_root_directory() {
  path="$1"
  [ -d "$path" ] && [ ! -L "$path" ] \
    || fail "required fixed directory is missing or symlinked: $path"
  [ "$(stat -c '%U:%G' "$path")" = root:root ] \
    || fail "fixed directory must be root:root: $path"
  [ -z "$(find "$path" -maxdepth 0 -perm /0022 -print -quit)" ] \
    || fail "fixed directory must not be group/world writable: $path"
}

assert_root_file() {
  path="$1"
  [ -f "$path" ] && [ ! -L "$path" ] \
    || fail "required fixed file is missing or symlinked: $path"
  [ "$(stat -c '%U:%G' "$path")" = root:root ] \
    || fail "fixed file must be root:root: $path"
  [ -z "$(find "$path" -perm /0022 -print -quit)" ] \
    || fail "fixed file must not be group/world writable: $path"
}

assert_root_readonly_tree() {
  path="$1"
  [ -z "$(find "$path" -type l -print -quit)" ] \
    || fail "fixed tree contains a symlink: $path"
  [ -z "$(
    find "$path" \( ! -user root -o ! -group root \) -print -quit
  )" ] || fail "fixed tree must be entirely root:root: $path"
  [ -z "$(find "$path" -perm /0222 -print -quit)" ] \
    || fail "fixed tree must be read-only: $path"
}

assert_root_secure_tree() {
  path="$1"
  [ -z "$(find "$path" -type l -print -quit)" ] \
    || fail "fixed tree contains a symlink: $path"
  [ -z "$(
    find "$path" \( ! -user root -o ! -group root \) -print -quit
  )" ] || fail "fixed tree must be entirely root:root: $path"
  [ -z "$(find "$path" -perm /0022 -print -quit)" ] \
    || fail "fixed tree must not be group/world writable: $path"
}

atomic_line() {
  destination="$1"
  value="$2"
  mode="$3"
  directory="$(dirname -- "$destination")"
  basename="$(basename -- "$destination")"
  temporary="$(mktemp "$directory/.$basename.XXXXXX")"
  printf '%s\n' "$value" > "$temporary"
  chown root:root "$temporary"
  chmod "$mode" "$temporary"
  mv -T -- "$temporary" "$destination"
}

assert_root_directory "$LOCK_ROOT"
[ ! -L "$LOCK_PATH" ] \
  || fail "deployment lock path is symlinked"
exec 9>"$LOCK_PATH"
flock --nonblock 9 \
  || fail "another production deployment is already active"
[ "$(stat -c '%U:%G' "$LOCK_PATH")" = root:root ] \
  || fail "deployment lock must be root:root"
chmod 0600 "$LOCK_PATH"

work_dir="$(mktemp -d "/var/tmp/gilbom-deploy-$release_sha.XXXXXX")"
image_env_temp=""
cleanup() {
  if [ -n "$image_env_temp" ] && [ -f "$image_env_temp" ]; then
    rm -f -- "$image_env_temp" >/dev/null 2>&1 || true
  fi
  rm -rf -- "$work_dir" >/dev/null 2>&1 || true
}
trap cleanup EXIT HUP INT TERM

# 4-6. 입력 SHA에서 Artifact 경로를 내부 생성하고 모든 고정 입력 경계를
# 검증한다.
artifact_dir="$ARTIFACT_ROOT/$release_sha"
assert_root_directory "$DEPLOY_ROOT"
assert_root_directory "$SECRET_ROOT"
assert_root_directory "$BACKUP_ROOT"
assert_root_directory "$MIGRATION_APPROVAL_ROOT"
assert_root_directory "$DEPLOY_APPROVAL_ROOT"
assert_root_directory "$RELEASE_RECORD_ROOT"
assert_root_secure_tree "$DEPLOY_ROOT"
assert_root_secure_tree "$SECRET_ROOT"
assert_root_file "$COMPOSE_PATH"
assert_root_file "$RUNTIME_ENV_PATH"
assert_root_file "$IMAGE_ENV_PATH"
assert_root_file "$BACKUP_SCRIPT"
[ "$(wc -l < "$IMAGE_ENV_PATH" | tr -d ' ')" -eq 2 ] \
  || fail "fixed image.env must contain exactly two lines"
[ "$(
  grep -Ec '^BACKEND_IMAGE=gilbom-backend:[0-9a-f]{40}$' \
    "$IMAGE_ENV_PATH"
)" -eq 1 ] \
  || fail "fixed image.env must contain one full-SHA Backend image"
[ "$(
  grep -Ec '^FRONTEND_IMAGE=gilbom-frontend:[0-9a-f]{40}$' \
    "$IMAGE_ENV_PATH"
)" -eq 1 ] \
  || fail "fixed image.env must contain one full-SHA Frontend image"

[ -d "$artifact_dir" ] && [ ! -L "$artifact_dir" ] \
  || fail "promoted release artifact is missing or symlinked"
assert_root_readonly_tree "$artifact_dir"
[ "$(find "$artifact_dir" -type f | wc -l | tr -d ' ')" -eq 9 ] \
  || fail "promoted release artifact must contain exactly nine files"

for relative_path in \
  SHA256SUMS \
  images.tar.zst \
  images.tar.zst.sha256 \
  build-metadata.json \
  sbom/backend.sbom.json \
  sbom/frontend.sbom.json \
  vulnerabilities/backend.txt \
  vulnerabilities/frontend.txt \
  vulnerabilities/policy-summary.json
do
  file="$artifact_dir/$relative_path"
  [ -f "$file" ] && [ ! -L "$file" ] && [ -s "$file" ] \
    || fail "required promoted artifact is missing, empty, or unsafe: $relative_path"
done

# 7. 승격 디렉터리 전체와 image archive 전용 checksum을 모두 검사한다.
(
  cd "$artifact_dir"
  sha256sum -c SHA256SUMS
  sha256sum -c images.tar.zst.sha256
) || fail "promoted release checksum verification failed"

metadata="$artifact_dir/build-metadata.json"
backend_image="gilbom-backend:$release_sha"
frontend_image="gilbom-frontend:$release_sha"

# 8. metadata의 release SHA, image tag/ID, Flyway 증거를 검증한다.
jq -e \
  --arg release_sha "$release_sha" \
  --arg backend_image "$backend_image" \
  --arg frontend_image "$frontend_image" \
  '
    .schemaVersion == 1
    and .releaseSha == $release_sha
    and .images.backend.name == $backend_image
    and .images.frontend.name == $frontend_image
    and (.images.backend.id | test("^sha256:[0-9a-f]{64}$"))
    and (.images.frontend.id | test("^sha256:[0-9a-f]{64}$"))
    and (.flyway.latestVersion | test("^V[1-9][0-9]*$"))
    and (.flyway.migrations | type == "array" and length > 0)
    and all(
      .flyway.migrations[];
      (.version | type == "number")
      and (.file | test("^V[1-9][0-9]*__[A-Za-z0-9_]+[.]sql$"))
      and (.sha256 | test("^[0-9a-f]{64}$"))
    )
  ' "$metadata" >/dev/null \
  || fail "release metadata does not match the requested SHA"
jq -e '.status == "PASS"' \
  "$artifact_dir/vulnerabilities/policy-summary.json" >/dev/null \
  || fail "release vulnerability policy is not PASS"

metadata_migrations="$work_dir/metadata-migrations.tsv"
jq -r \
  '.flyway.migrations[] | [.version, .file, .sha256] | @tsv' \
  "$metadata" > "$metadata_migrations"
awk -F '\t' '
  BEGIN { expected = 1 }
  NF != 3 || $1 != expected || $2 == "" || $3 !~ /^[0-9a-f]{64}$/ {
    exit 1
  }
  { expected++ }
  END { if (NR == 0) exit 1 }
' "$metadata_migrations" \
  || fail "release migration metadata is not contiguous or valid"
latest_version="$(
  jq -r '.flyway.latestVersion | ltrimstr("V")' "$metadata"
)"
last_metadata_version="$(tail -n 1 "$metadata_migrations" | cut -f1)"
[ "$latest_version" = "$last_metadata_version" ] \
  || fail "Flyway latestVersion differs from the migration list"

# 9. docker load 전에 압축 stream 전체를 독립 검사한다.
zstd --quiet --test "$artifact_dir/images.tar.zst" \
  || fail "release image archive failed zstd integrity testing"

# 10. pipefail이 적용된 정확한 zstd -dc | docker load 경로로 image를 적재한다.
load_output="$work_dir/docker-load.txt"
if bash -o pipefail -c \
    'zstd --quiet -dc "$1" | docker load' \
    gilbom-load "$artifact_dir/images.tar.zst" > "$load_output"; then
  :
else
  fail "release image archive load failed"
fi

# 11-12. 두 full-SHA tag가 실제로 존재하고 metadata의 immutable image ID와
# 정확히 일치해야 한다.
for image in "$backend_image" "$frontend_image"
do
  docker image inspect "$image" >/dev/null 2>&1 \
    || fail "required loaded image tag is unavailable: $image"
  docker image inspect --format '{{json .RepoTags}}' "$image" \
    | jq -e --arg image "$image" 'index($image) != null' >/dev/null \
    || fail "loaded image does not contain the required tag: $image"
done

expected_backend_id="$(jq -r '.images.backend.id' "$metadata")"
expected_frontend_id="$(jq -r '.images.frontend.id' "$metadata")"
actual_backend_id="$(
  docker image inspect --format '{{.Id}}' "$backend_image"
)" || fail "loaded Backend image is unavailable"
actual_frontend_id="$(
  docker image inspect --format '{{.Id}}' "$frontend_image"
)" || fail "loaded Frontend image is unavailable"
[ "$actual_backend_id" = "$expected_backend_id" ] \
  || fail "loaded Backend image ID differs from release metadata"
[ "$actual_frontend_id" = "$expected_frontend_id" ] \
  || fail "loaded Frontend image ID differs from release metadata"

compose() {
  docker compose \
    --env-file "$RUNTIME_ENV_PATH" \
    --env-file "$IMAGE_ENV_PATH" \
    --file "$COMPOSE_PATH" \
    "$@"
}

compose config --quiet \
  || fail "fixed production Compose configuration is invalid"

# 승인 화면에 표시된 현재 운영 SHA와 실제 배포 직전 상태를 같은 값으로
# 고정한다. current.sha가 없는 기존 설치는 image.env를 읽되 성공 처리 전에는
# 상태 파일을 쓰지 않는다.
current_sha_bootstrap=false
if [ -e "$CURRENT_SHA_PATH" ]; then
  assert_root_file "$CURRENT_SHA_PATH"
  current_sha="$(tr -d '\n' < "$CURRENT_SHA_PATH")"
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
    || fail "current.sha is absent and image.env cannot establish the current release"
  current_sha="$current_backend_sha"
  current_sha_bootstrap=true
fi
printf '%s\n' "$current_sha" | grep -Eq '^[0-9a-f]{40}$' \
  || fail "current production SHA is invalid"
[ "$current_sha" != "$release_sha" ] \
  || fail "requested release is already current"

db_id="$(compose ps -q db)"
[ -n "$db_id" ] \
  || fail "running production PostgreSQL container is required"
[ "$(docker inspect --format '{{.State.Health.Status}}' "$db_id")" = healthy ] \
  || fail "production PostgreSQL is not healthy before deployment"

# 13. 운영 Flyway history에서 현재 version과 적용 script 목록을 조회한다.
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
)" || fail "could not query the current Flyway version"
printf '%s\n' "$current_version" | grep -Eq '^(0|[1-9][0-9]*)$' \
  || fail "current Flyway version is not a non-negative integer"
[ "$current_version" -le "$latest_version" ] \
  || fail "database Flyway version is newer than the requested release"

failed_migrations="$(
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
[ "$failed_migrations" = 0 ] \
  || fail "Flyway history contains a failed migration"

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

expected_current_migrations="$work_dir/expected-current-migrations.txt"
jq -r --argjson current_version "$current_version" '
  .flyway.migrations[]
  | select(.version <= $current_version)
  | "\(.version)|\(.file)"
' "$metadata" > "$expected_current_migrations"
cmp -s "$database_migrations" "$expected_current_migrations" \
  || fail "database Flyway history differs from release metadata"

# 14. 현재 DB보다 높은 migration만 deterministic JSON plan으로 기록한다.
release_record_dir="$RELEASE_RECORD_ROOT/$release_sha"
if [ -e "$release_record_dir" ]; then
  assert_root_directory "$release_record_dir"
else
  install -d -o root -g root -m 0700 "$release_record_dir"
fi
migration_plan="$release_record_dir/migration-plan.json"
migration_plan_temp="$(mktemp "$release_record_dir/.migration-plan.XXXXXX")"
jq -cS \
  --arg release_sha "$release_sha" \
  --argjson current_version "$current_version" \
  --argjson target_version "$latest_version" \
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
  ' "$metadata" > "$migration_plan_temp"
chown root:root "$migration_plan_temp"
chmod 0440 "$migration_plan_temp"
mv -T -- "$migration_plan_temp" "$migration_plan"
migration_plan_sha256="$(sha256sum "$migration_plan" | awk '{print $1}')"
atomic_line \
  "$release_record_dir/migration-plan.sha256" \
  "$migration_plan_sha256  migration-plan.json" \
  0440
pending_migration_count="$(
  jq -r '.migrations | length' "$migration_plan"
)"

# 15. Jenkins 인증 사용자의 승인 기록이 exact preflight, Artifact, 현재 운영
# SHA와 migration plan을 모두 가리키는지 확인한다. 승인 뒤 운영 상태가
# 달라졌으면 배포를 진행하지 않는다.
deploy_preflight="$release_record_dir/deploy-preflight.json"
deploy_preflight_checksum="$release_record_dir/deploy-preflight.sha256"
deploy_approval_file="$DEPLOY_APPROVAL_ROOT/$release_sha.json"
for file in \
  "$deploy_preflight" \
  "$deploy_preflight_checksum" \
  "$deploy_approval_file"
do
  assert_root_file "$file"
done
(
  cd "$release_record_dir"
  sha256sum -c deploy-preflight.sha256
) || fail "deployment preflight checksum verification failed"
deploy_preflight_sha256="$(sha256sum "$deploy_preflight" | awk '{print $1}')"
artifact_manifest_sha256="$(
  sha256sum "$artifact_dir/SHA256SUMS" | awk '{print $1}'
)"
jq -e \
  --arg release_sha "$release_sha" \
  --arg current_sha "$current_sha" \
  --arg artifact_manifest_sha256 "$artifact_manifest_sha256" \
  --arg migration_plan_sha256 "$migration_plan_sha256" \
  --argjson current_version "$current_version" \
  --argjson target_version "$latest_version" \
  --slurpfile migration_plan "$migration_plan" \
  '
    .schemaVersion == 1
    and .releaseSha == $release_sha
    and .production.currentSha == $current_sha
    and .production.rollbackTargetSha == $current_sha
    and .artifact.sha256SumsSha256 == $artifact_manifest_sha256
    and .flyway.currentVersion == $current_version
    and .flyway.targetVersion == $target_version
    and .flyway.migrationPlanSha256 == $migration_plan_sha256
    and .flyway.pendingMigrations == $migration_plan[0].migrations
    and (.flyway.compatibility | startswith("COMPATIBLE_"))
    and .vulnerabilities.critical == 0
    and .vulnerabilities.unapprovedHigh == 0
  ' "$deploy_preflight" >/dev/null \
  || fail "approved preflight differs from the deployment state"
jq -e \
  --arg release_sha "$release_sha" \
  --arg preflight_sha256 "$deploy_preflight_sha256" \
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
    and (.approvedBy | test("^[^\\u0000-\\u001f\\u007f]+$"))
    and (.approvedAtUtc | test(
      "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$"
    ))
  ' "$deploy_approval_file" >/dev/null \
  || fail "Jenkins deployment approval is invalid or stale"

# 신규 migration이 있으면 같은 Jenkins 승인이 생성한 exact plan 승인도
# 독립적으로 검사한다.
approval_required=false
approval_file="$MIGRATION_APPROVAL_ROOT/$release_sha.json"
approval_snapshot="$work_dir/migration-approval.json"
printf '%s\n' null > "$approval_snapshot"
if [ "$pending_migration_count" -gt 0 ]; then
  approval_required=true
  [ -f "$approval_file" ] && [ ! -L "$approval_file" ] \
    || fail "migration approval is required for plan $migration_plan"
  assert_root_file "$approval_file"
  jq -e \
    --arg release_sha "$release_sha" \
    --arg plan_sha256 "$migration_plan_sha256" \
    '
      .schemaVersion == 1
      and .releaseSha == $release_sha
      and .migrationPlanSha256 == $plan_sha256
      and (.approvedBy | type == "string")
      and (.approvedBy | length >= 1 and length <= 64)
      and (.approvedBy | test("^[^\\u0000-\\u001f\\u007f]+$"))
      and (.approvedAtUtc | test(
        "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$"
      ))
    ' "$approval_file" >/dev/null \
    || fail "migration approval does not match the exact pending plan"
  cp -- "$approval_file" "$approval_snapshot"
fi

# 16. 기존 production stack을 기준으로 DB와 모든 영속 Volume을 backup한다.
if backup_output="$(
  ENV_FILE= \
  RUNTIME_ENV_FILE="$RUNTIME_ENV_PATH" \
  IMAGE_ENV_FILE="$IMAGE_ENV_PATH" \
  COMPOSE_FILE="$COMPOSE_PATH" \
  BACKUP_ROOT="$BACKUP_ROOT" \
    sh "$BACKUP_SCRIPT"
)"; then
  printf '%s\n' "$backup_output"
else
  fail "production DB and volume backup failed"
fi
backup_bundle="$(
  printf '%s\n' "$backup_output" \
    | sed -n 's/^Backup bundle: //p' \
    | tail -n 1
)"
case "$backup_bundle" in
  "$BACKUP_ROOT"/*) ;;
  *) fail "backup script did not report a fixed-root backup bundle" ;;
esac
[ -d "$backup_bundle" ] && [ ! -L "$backup_bundle" ] \
  || fail "reported backup bundle is missing or symlinked"
[ -s "$backup_bundle/SHA256SUMS" ] \
  || fail "backup bundle checksum manifest is missing"
(
  cd "$backup_bundle"
  sha256sum -c SHA256SUMS
) || fail "backup bundle checksum verification failed"

# 17. 승인 때 고정한 current.sha를 previous.sha로 원자 기록한다. 기존 설치의
# bootstrap state도 이 시점에만 생성한다.
if [ "$current_sha_bootstrap" = true ]; then
  # 기존 수동 배포를 Stage 11 state model로 편입하는 초기화이며, 요청한 새
  # release로의 성공 처리는 아니다. 이후 실패해도 기존 SHA가 current로 남는다.
  atomic_line "$CURRENT_SHA_PATH" "$current_sha" 0440
fi
atomic_line "$PREVIOUS_SHA_PATH" "$current_sha" 0440

# 18. 새 full-SHA image.env를 같은 filesystem에서 원자 교체한다.
image_env_temp="$(mktemp "$DEPLOY_ROOT/.image.env.XXXXXX")"
{
  printf 'BACKEND_IMAGE=%s\n' "$backend_image"
  printf 'FRONTEND_IMAGE=%s\n' "$frontend_image"
} > "$image_env_temp"
chown root:root "$image_env_temp"
chmod 0600 "$image_env_temp"
mv -T -- "$image_env_temp" "$IMAGE_ENV_PATH"
image_env_temp=""

# 19. pull/build 없이 이미 검증·load한 image만 사용해 stack을 갱신한다.
compose config --quiet \
  || fail "updated fixed production Compose configuration is invalid"
compose up --detach --no-build \
  || fail "production Compose update failed"

wait_healthy() {
  service="$1"
  deadline="$(( $(date +%s) + HEALTH_TIMEOUT_SECONDS ))"
  while [ "$(date +%s)" -lt "$deadline" ]
  do
    container_id="$(compose ps -q "$service")"
    if [ -n "$container_id" ]; then
      state="$(docker inspect --format '{{.State.Status}}' "$container_id")"
      health="$(docker inspect \
        --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' \
        "$container_id")"
      [ "$state" = running ] && [ "$health" = healthy ] \
        && return 0
      case "$state:$health" in
        exited:*|dead:*|*:unhealthy) return 1 ;;
      esac
    fi
    sleep 5
  done
  return 1
}

# 20. Backend container health와 readiness endpoint를 각각 확인한다.
wait_healthy backend \
  || fail "Backend did not become healthy"
backend_id="$(compose ps -q backend)"
backend_readiness="$(
  docker exec "$backend_id" \
    wget --quiet --output-document=- \
      http://127.0.0.1:8080/actuator/health/readiness
)" || fail "Backend readiness endpoint failed"
printf '%s\n' "$backend_readiness" \
  | jq -e '.status == "UP"' >/dev/null \
  || fail "Backend readiness is not UP"

# 21. Frontend image 자체 healthcheck를 명시적으로 재실행한다.
wait_healthy frontend \
  || fail "Frontend did not become healthy"
frontend_id="$(compose ps -q frontend)"
docker exec "$frontend_id" /usr/local/bin/gilbom-nginx-healthcheck \
  || fail "Frontend health check failed"

# 22. Frontend container의 app network에서 Backend API를 호출한다.
frontend_backend_smoke="$(
  docker exec "$frontend_id" \
    wget --quiet --output-document=- http://backend:8080/api/health
)" || fail "Frontend-to-Backend smoke request failed"
printf '%s\n' "$frontend_backend_smoke" \
  | jq -e '.status == "UP"' >/dev/null \
  || fail "Frontend-to-Backend smoke response is not UP"

# 23. Broker health와 Backend MQTT health component를 함께 확인한다.
wait_healthy mosquitto \
  || fail "MQTT Broker did not become healthy"
mqtt_health="$(
  docker exec "$frontend_id" \
    wget --quiet --output-document=- http://backend:8080/actuator/health
)" || fail "Backend MQTT health query failed"
printf '%s\n' "$mqtt_health" \
  | jq -e \
      '.status == "UP" and .components.mqttConnection.status == "UP"' \
      >/dev/null \
  || fail "Backend MQTT health is not UP"

# 24. 적용 뒤 Flyway version과 전체 script 목록을 다시 metadata와 대조한다.
applied_version="$(
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
)" || fail "could not query the applied Flyway version"
[ "$applied_version" = "$latest_version" ] \
  || fail "applied Flyway version differs from release metadata"

applied_migrations="$work_dir/applied-migrations.txt"
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
' > "$applied_migrations" \
  || fail "could not query applied Flyway scripts"
cut -f1,2 "$metadata_migrations" | tr '\t' '|' \
  > "$work_dir/expected-applied-migrations.txt"
cmp -s \
  "$applied_migrations" \
  "$work_dir/expected-applied-migrations.txt" \
  || fail "applied Flyway scripts differ from release metadata"

# 25. Secret 원문 없이 artifact, image, migration, backup, health 결과를
# immutable release manifest로 기록한다.
manifest_path="$release_record_dir/release-manifest.json"
[ ! -e "$manifest_path" ] \
  || fail "immutable release manifest already exists"
deployed_at_utc="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
manifest_temp="$(mktemp "$release_record_dir/.release-manifest.XXXXXX")"
jq -n \
  --arg release_sha "$release_sha" \
  --arg previous_sha "$current_sha" \
  --arg deployed_at_utc "$deployed_at_utc" \
  --arg artifact_directory "$artifact_dir" \
  --arg artifact_manifest_sha256 "$(
    sha256sum "$artifact_dir/SHA256SUMS" | awk '{print $1}'
  )" \
  --arg compose_sha256 "$(sha256sum "$COMPOSE_PATH" | awk '{print $1}')" \
  --arg backend_image "$backend_image" \
  --arg backend_image_id "$actual_backend_id" \
  --arg frontend_image "$frontend_image" \
  --arg frontend_image_id "$actual_frontend_id" \
  --argjson flyway_before "$current_version" \
  --argjson flyway_after "$applied_version" \
  --arg migration_plan_sha256 "$migration_plan_sha256" \
  --argjson migration_count "$pending_migration_count" \
  --argjson approval_required "$approval_required" \
  --arg backup_bundle "$backup_bundle" \
  --slurpfile deploy_approval "$deploy_approval_file" \
  --slurpfile migration_plan "$migration_plan" \
  --slurpfile migration_approval "$approval_snapshot" \
  '
    {
      schemaVersion: 1,
      releaseSha: $release_sha,
      previousSha: $previous_sha,
      deployedAtUtc: $deployed_at_utc,
      artifact: {
        directory: $artifact_directory,
        sha256SumsSha256: $artifact_manifest_sha256
      },
      composeSha256: $compose_sha256,
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
      flyway: {
        beforeVersion: $flyway_before,
        afterVersion: $flyway_after,
        migrationCount: $migration_count,
        planSha256: $migration_plan_sha256,
        plan: $migration_plan[0],
        approvalRequired: $approval_required,
        approval: $migration_approval[0]
      },
      approval: $deploy_approval[0],
      backupBundle: $backup_bundle,
      health: {
        backendReadiness: "PASS",
        frontend: "PASS",
        frontendToBackend: "PASS",
        mqtt: "PASS"
      }
    }
  ' > "$manifest_temp"
chown root:root "$manifest_temp"
chmod 0440 "$manifest_temp"
mv -T -- "$manifest_temp" "$manifest_path"

# 26. 모든 검증과 manifest 기록이 끝난 성공 경로에서만 current.sha를 바꾼다.
atomic_line "$CURRENT_SHA_PATH" "$release_sha" 0440

echo "Production release manifest: $manifest_path"
echo "Production deploy boundary: PASS ($release_sha)"
