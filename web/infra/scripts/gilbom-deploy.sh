#!/bin/sh

set -eu
set +x
umask 077

# 이 root entrypoint의 모든 운영 경로는 고정값이다. deploy 호출자는 인자나
# 환경변수로 Artifact, Compose, 환경 파일, Secret, backup 위치를 바꿀 수 없다.
ARTIFACT_ROOT=/opt/gilbom/artifacts
DEPLOY_PARENT=/opt/gilbom
DEPLOY_ROOT=/opt/gilbom/deploy
COMPOSE_PATH="$DEPLOY_ROOT/compose.prod.yaml"
RUNTIME_ENV_PATH="$DEPLOY_ROOT/runtime.env"
IMAGE_ENV_PATH="$DEPLOY_ROOT/image.env"
SECRET_ROOT=/opt/gilbom/secrets
BACKUP_ROOT=/opt/gilbom/backups
BACKUP_SCRIPT="$DEPLOY_ROOT/infra/scripts/backup-volumes.sh"
MIGRATION_APPROVAL_ROOT=/opt/gilbom/migration-approvals
DEPLOY_APPROVAL_ROOT=/opt/gilbom/deploy-approvals
ROLLBACK_APPROVAL_ROOT=/opt/gilbom/rollback-approvals
RELEASE_RECORD_ROOT=/var/lib/gilbom/releases
CURRENT_SHA_PATH="$RELEASE_RECORD_ROOT/current.sha"
PREVIOUS_SHA_PATH="$RELEASE_RECORD_ROOT/previous.sha"
COSIGN_BIN=/usr/local/bin/cosign
COSIGN_PUBLIC_KEY=/etc/gilbom/cosign-release.pub
COSIGN_VERSION=v3.1.2
LOCK_ROOT=/run/gilbom-deploy
LOCK_PATH="$LOCK_ROOT/deploy.lock"
HEALTH_TIMEOUT_SECONDS=240
SAFE_PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

rollback_armed=false
approved_rollback_armed=false
handling_deploy_failure=false
source_stage_dir=""
source_previous_dir=""
source_tree_swapped=false

fail() {
  reason="$1"
  echo "Production deploy boundary failed: $reason" >&2
  if [ "$approved_rollback_armed" = true ] \
      && [ "$handling_deploy_failure" = false ]; then
    handling_deploy_failure=true
    if handle_approved_rollback_failure "$reason"; then
      :
    else
      echo \
        "Approved rollback failure containment could not complete cleanly" \
        >&2
    fi
  elif [ "$rollback_armed" = true ] \
      && [ "$handling_deploy_failure" = false ]; then
    handling_deploy_failure=true
    if handle_deployment_failure "$reason"; then
      :
    else
      echo \
        "Production deploy failure containment could not complete cleanly" \
        >&2
    fi
  fi
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
  DEPLOY_APPROVAL_ROOT ROLLBACK_APPROVAL_ROOT \
  COSIGN_PRIVATE_KEY COSIGN_PASSWORD \
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
  awk basename bash cat chown chmod cmp cp cut date dirname docker find flock \
  grep install jq mktemp mv printenv rm sed sha256sum sleep stat tail tar tr \
  wc zstd
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

verify_release_signature() {
  directory="$1"
  archive="$directory/images.tar.zst"
  bundle="$directory/images.tar.zst.sigstore.json"
  [ -f "$archive" ] && [ ! -L "$archive" ] && [ -s "$archive" ] \
    && [ -f "$bundle" ] && [ ! -L "$bundle" ] && [ -s "$bundle" ] \
    && "$COSIGN_BIN" verify-blob \
      --insecure-ignore-tlog \
      --key "$COSIGN_PUBLIC_KEY" \
      --bundle "$bundle" \
      "$archive" >/dev/null
}

verify_release_manifest_signature() {
  directory="$1"
  manifest="$directory/SHA256SUMS"
  bundle="$directory/SHA256SUMS.sigstore.json"
  [ -f "$manifest" ] && [ ! -L "$manifest" ] && [ -s "$manifest" ] \
    && [ -f "$bundle" ] && [ ! -L "$bundle" ] && [ -s "$bundle" ] \
    && "$COSIGN_BIN" verify-blob \
      --insecure-ignore-tlog \
      --key "$COSIGN_PUBLIC_KEY" \
      --bundle "$bundle" \
      "$manifest" >/dev/null
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
  if [ "$source_tree_swapped" = true ]; then
    restore_deploy_source_tree >/dev/null 2>&1 || true
  fi
  if [ -n "$source_stage_dir" ] && [ -d "$source_stage_dir" ]; then
    rm -r -- "$source_stage_dir" >/dev/null 2>&1 || true
  fi
  if [ -n "$source_previous_dir" ] && [ -d "$source_previous_dir" ]; then
    rm -r -- "$source_previous_dir" >/dev/null 2>&1 || true
  fi
  if [ -n "$image_env_temp" ] && [ -f "$image_env_temp" ]; then
    rm -f -- "$image_env_temp" >/dev/null 2>&1 || true
  fi
  rm -rf -- "$work_dir" >/dev/null 2>&1 || true
}
trap cleanup EXIT HUP INT TERM

# 4-6. 입력 SHA에서 Artifact 경로를 내부 생성하고 모든 고정 입력 경계를
# 검증한다.
artifact_dir="$ARTIFACT_ROOT/$release_sha"
candidate_compose_path="$artifact_dir/compose.prod.yaml"
source_archive_name="gilbom-web-$release_sha.tar.zst"
source_archive_path="$artifact_dir/$source_archive_name"
assert_root_directory "$DEPLOY_PARENT"
assert_root_directory "$DEPLOY_ROOT"
assert_root_directory "$SECRET_ROOT"
assert_root_directory "$BACKUP_ROOT"
assert_root_directory "$MIGRATION_APPROVAL_ROOT"
assert_root_directory "$DEPLOY_APPROVAL_ROOT"
assert_root_directory "$ROLLBACK_APPROVAL_ROOT"
assert_root_directory "$RELEASE_RECORD_ROOT"
assert_root_secure_tree "$DEPLOY_ROOT"
assert_root_secure_tree "$SECRET_ROOT"
assert_root_file "$COMPOSE_PATH"
assert_root_file "$RUNTIME_ENV_PATH"
assert_root_file "$IMAGE_ENV_PATH"
assert_root_file "$BACKUP_SCRIPT"
assert_root_file "$COSIGN_BIN"
assert_root_file "$COSIGN_PUBLIC_KEY"
"$COSIGN_BIN" version \
  | grep -Fq "GitVersion:    $COSIGN_VERSION" \
  || fail "Cosign verifier version differs from $COSIGN_VERSION"
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
[ "$(find "$artifact_dir" -type f | wc -l | tr -d ' ')" -eq 13 ] \
  || fail "promoted release artifact must contain exactly thirteen files"

for relative_path in \
  SHA256SUMS \
  SHA256SUMS.sigstore.json \
  compose.prod.yaml \
  images.tar.zst \
  images.tar.zst.sigstore.json \
  images.tar.zst.sha256 \
  "$source_archive_name" \
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

# 7. 서명된 전체 manifest를 먼저 검증한 뒤 디렉터리 전체와 image archive
# 전용 checksum을 모두 검사한다.
verify_release_manifest_signature "$artifact_dir" \
  || fail "promoted complete release manifest signature verification failed"
(
  cd "$artifact_dir"
  sha256sum -c SHA256SUMS
  sha256sum -c images.tar.zst.sha256
) || fail "promoted release checksum verification failed"
verify_release_signature "$artifact_dir" \
  || fail "promoted release Cosign signature verification failed"

metadata="$artifact_dir/build-metadata.json"
backend_image="gilbom-backend:$release_sha"
frontend_image="gilbom-frontend:$release_sha"
cosign_public_key_sha256="$(
  sha256sum "$COSIGN_PUBLIC_KEY" | awk '{print $1}'
)"
candidate_compose_sha256="$(
  sha256sum "$candidate_compose_path" | awk '{print $1}'
)"
source_archive_sha256="$(
  sha256sum "$source_archive_path" | awk '{print $1}'
)"

# 8. metadata의 release SHA, image tag/ID, Flyway 증거를 검증한다.
jq -e \
  --arg release_sha "$release_sha" \
  --arg backend_image "$backend_image" \
  --arg frontend_image "$frontend_image" \
  --arg cosign_public_key_sha256 "$cosign_public_key_sha256" \
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
    and (.flyway.latestVersion | test("^V[1-9][0-9]*$"))
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

# 후보 Compose는 현재 운영 파일을 건드리기 전에 서버 전용 runtime.env와
# target full-SHA image 값을 사용해 완전히 렌더링되어야 한다. 필수 runtime
# 변수가 빠졌거나 Compose가 손상되었으면 image load 전 여기서 차단한다.
BACKEND_IMAGE="$backend_image" \
FRONTEND_IMAGE="$frontend_image" \
docker compose \
  --env-file "$RUNTIME_ENV_PATH" \
  --env-file "$IMAGE_ENV_PATH" \
  --file "$candidate_compose_path" \
  config --quiet \
  || fail "signed release Compose configuration is invalid or runtime.env is incomplete"

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

# 9. Cosign 검증을 통과한 archive의 압축 stream 전체를 독립 검사한다.
zstd --quiet --test "$artifact_dir/images.tar.zst" \
  || fail "release image archive failed zstd integrity testing"
zstd --quiet --test "$source_archive_path" \
  || fail "release source archive failed zstd integrity testing"

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
backend_engine_id="$(
  docker image inspect --format '{{.Id}}' "$backend_image"
)" || fail "loaded Backend image is unavailable"
frontend_engine_id="$(
  docker image inspect --format '{{.Id}}' "$frontend_image"
)" || fail "loaded Frontend image is unavailable"

# Docker의 containerd image store와 classic image store는 같은 image config를
# 로드해도 `docker image inspect .Id`에 서로 다른 종류의 digest를 노출할 수
# 있다. 저장소 모드에 독립적인 image identity는 docker-save manifest의 Config
# content digest이므로, 로드된 두 tag를 다시 스트리밍 save하여 이를 검증한다.
loaded_manifest="$work_dir/loaded-images-manifest.json"
if bash -o pipefail -c \
    'docker image save "$1" "$2" | tar -xOf - manifest.json' \
    gilbom-loaded-images "$backend_image" "$frontend_image" \
    > "$loaded_manifest"; then
  :
else
  fail "could not inspect loaded image config identities"
fi
jq -e 'type == "array" and length == 2' "$loaded_manifest" >/dev/null \
  || fail "loaded image save manifest is invalid"

portable_image_id() {
  image="$1"
  config_path="$(
    jq -er \
      --arg image "$image" \
      '
        [
          .[]
          | select(
              (.RepoTags | type == "array")
              and (.RepoTags | index($image) != null)
            )
          | .Config
        ]
        | if length == 1 then .[0] else error("ambiguous image tag") end
      ' "$loaded_manifest"
  )" || fail "loaded image tag is missing from save manifest: $image"
  printf '%s\n' "$config_path" \
    | grep -Eq \
        '^(blobs/sha256/[0-9a-f]{64}|[0-9a-f]{64}[.]json)$' \
    || fail "loaded image config path is unsafe: $image"
  config_name="$(basename -- "$config_path")"
  config_digest="${config_name%.json}"
  printf 'sha256:%s\n' "$config_digest"
}

actual_backend_id="$(portable_image_id "$backend_image")"
actual_frontend_id="$(portable_image_id "$frontend_image")"
[ "$actual_backend_id" = "$expected_backend_id" ] \
  || fail \
    "loaded Backend config ID differs from release metadata (expected=$expected_backend_id actual=$actual_backend_id engine=$backend_engine_id)"
[ "$actual_frontend_id" = "$expected_frontend_id" ] \
  || fail \
    "loaded Frontend config ID differs from release metadata (expected=$expected_frontend_id actual=$actual_frontend_id engine=$frontend_engine_id)"

compose() {
  docker compose \
    --env-file "$RUNTIME_ENV_PATH" \
    --env-file "$IMAGE_ENV_PATH" \
    --file "$COMPOSE_PATH" \
    "$@"
}

image_env_sha() {
  backend_sha="$(
    sed -n 's/^BACKEND_IMAGE=gilbom-backend:\([0-9a-f]\{40\}\)$/\1/p' \
      "$IMAGE_ENV_PATH"
  )"
  frontend_sha="$(
    sed -n 's/^FRONTEND_IMAGE=gilbom-frontend:\([0-9a-f]\{40\}\)$/\1/p' \
      "$IMAGE_ENV_PATH"
  )"
  [ -n "$backend_sha" ] && [ "$backend_sha" = "$frontend_sha" ] \
    || return 1
  printf '%s\n' "$backend_sha"
}

query_flyway_version() {
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
}

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

write_image_env() {
  target_sha="$1"
  target_temp="$(mktemp "$DEPLOY_ROOT/.image.env.rollback.XXXXXX")" \
    || return 1
  {
    printf 'BACKEND_IMAGE=gilbom-backend:%s\n' "$target_sha"
    printf 'FRONTEND_IMAGE=gilbom-frontend:%s\n' "$target_sha"
  } > "$target_temp" || {
    rm -f -- "$target_temp"
    return 1
  }
  chown root:root "$target_temp" \
    && chmod 0600 "$target_temp" \
    && mv -T -- "$target_temp" "$IMAGE_ENV_PATH"
}

install_compose_file() {
  source="$1"
  [ -f "$source" ] && [ ! -L "$source" ] && [ -s "$source" ] \
    || return 1
  target_temp="$(mktemp "$DEPLOY_ROOT/.compose.prod.yaml.XXXXXX")" \
    || return 1
  cp -- "$source" "$target_temp" \
    && chown root:root "$target_temp" \
    && chmod 0644 "$target_temp" \
    && mv -T -- "$target_temp" "$COMPOSE_PATH"
}

snapshot_compose_file() {
  destination="$1"
  if [ -e "$destination" ]; then
    assert_root_file "$destination"
    cmp -s "$COMPOSE_PATH" "$destination"
    return
  fi
  snapshot_temp="$(mktemp "$(dirname -- "$destination")/.compose-snapshot.XXXXXX")" \
    || return 1
  cp -- "$COMPOSE_PATH" "$snapshot_temp" \
    && chown root:root "$snapshot_temp" \
    && chmod 0440 "$snapshot_temp" \
    && mv -T -- "$snapshot_temp" "$destination"
}

prepare_release_source_tree() {
  [ -z "$source_stage_dir" ] || return 1
  source_stage_dir="$(
    mktemp -d "$DEPLOY_PARENT/.deploy-source-$release_sha.XXXXXX"
  )" || return 1
  chmod 0700 "$source_stage_dir" || return 1

  # The complete manifest signature and SHA256SUMS were verified before this
  # extraction. Extract into a sibling staging directory so an incomplete tree
  # can never become /opt/gilbom/deploy. git archive records directories as
  # 0775; normalize them through umask instead of preserving group-write bits.
  if ! bash -o pipefail -c \
    'umask 022
     zstd --quiet -dc "$1" \
      | tar --extract --file - --directory "$2" \
          --no-same-owner --no-same-permissions' \
      gilbom-source-extract "$source_archive_path" "$source_stage_dir"; then
    echo "Release source staging rejected: archive extraction failed" >&2
    return 1
  fi

  if [ ! -f "$source_stage_dir/compose.monitoring.yaml" ] \
      || [ ! -f "$source_stage_dir/infra/scripts/install-day10-host.sh" ] \
      || [ ! -f "$source_stage_dir/compose.prod.yaml" ]; then
    echo "Release source staging rejected: required deployment file is missing" >&2
    return 1
  fi
  if ! cmp -s \
      "$source_stage_dir/compose.prod.yaml" "$candidate_compose_path"; then
    echo "Release source staging rejected: Compose artifacts differ" >&2
    return 1
  fi
  if [ -e "$source_stage_dir/runtime.env" ] \
      || [ -e "$source_stage_dir/image.env" ]; then
    echo "Release source staging rejected: runtime state is present" >&2
    return 1
  fi
  unsafe_source_entry="$(find "$source_stage_dir" -type l -print -quit)"
  if [ -n "$unsafe_source_entry" ]; then
    echo \
      "Release source staging rejected: symlink is present: $unsafe_source_entry" \
      >&2
    return 1
  fi
  unsafe_source_entry="$(
    find "$source_stage_dir" -mindepth 1 \
      ! -type f ! -type d -print -quit
  )"
  if [ -n "$unsafe_source_entry" ]; then
    echo \
      "Release source staging rejected: special entry is present: $unsafe_source_entry" \
      >&2
    return 1
  fi
  unsafe_source_entry="$(
    find "$source_stage_dir" -perm /7022 -print -quit
  )"
  if [ -n "$unsafe_source_entry" ]; then
    echo \
      "Release source staging rejected: unsafe mode: $unsafe_source_entry" \
      >&2
    return 1
  fi
  if [ -d "$source_stage_dir/secrets" ] \
      && [ -n "$(
        find "$source_stage_dir/secrets" -type f \
          ! -name .gitignore -print -quit
      )" ]; then
    echo "Release source staging rejected: Secret payload is present" >&2
    return 1
  fi

  chown -R root:root "$source_stage_dir" \
    && find "$source_stage_dir" -type d -exec chmod go-w {} + \
    && find "$source_stage_dir" -type f -exec chmod go-w {} + \
    || {
      echo "Release source staging rejected: ownership normalization failed" >&2
      return 1
    }
}

activate_release_source_tree() {
  [ -n "$source_stage_dir" ] && [ -d "$source_stage_dir" ] \
    || return 1
  [ "$source_tree_swapped" = false ] || return 1

  # runtime.env and image.env are host state, not release content. Copy their
  # current values into the staged tree immediately before the atomic switch.
  install -o root -g root -m 0600 \
    "$RUNTIME_ENV_PATH" "$source_stage_dir/runtime.env" \
    && install -o root -g root -m 0600 \
      "$IMAGE_ENV_PATH" "$source_stage_dir/image.env" \
    && install -o root -g root -m 0644 \
      "$candidate_compose_path" "$source_stage_dir/compose.prod.yaml" \
    || return 1
  printf '%s\n' "$release_sha" > "$source_stage_dir/.release-source.sha" \
    && chown root:root "$source_stage_dir/.release-source.sha" \
    && chmod 0440 "$source_stage_dir/.release-source.sha" \
    || return 1
  assert_root_secure_tree "$source_stage_dir"

  source_previous_dir="$DEPLOY_PARENT/.deploy-previous-$release_sha.$$"
  [ ! -e "$source_previous_dir" ] || return 1
  mv -T -- "$DEPLOY_ROOT" "$source_previous_dir" || return 1
  if mv -T -- "$source_stage_dir" "$DEPLOY_ROOT"; then
    source_stage_dir=""
    source_tree_swapped=true
    return 0
  fi
  mv -T -- "$source_previous_dir" "$DEPLOY_ROOT" >/dev/null 2>&1 || true
  source_previous_dir=""
  return 1
}

restore_deploy_source_tree() {
  [ "$source_tree_swapped" = true ] || return 0
  [ -n "$source_previous_dir" ] && [ -d "$source_previous_dir" ] \
    || return 1
  failed_source_dir="$DEPLOY_PARENT/.deploy-failed-$release_sha.$$"
  [ ! -e "$failed_source_dir" ] || return 1
  mv -T -- "$DEPLOY_ROOT" "$failed_source_dir" || return 1
  if mv -T -- "$source_previous_dir" "$DEPLOY_ROOT"; then
    rm -r -- "$failed_source_dir" >/dev/null 2>&1 || true
    source_previous_dir=""
    source_tree_swapped=false
    return 0
  fi
  mv -T -- "$failed_source_dir" "$DEPLOY_ROOT" >/dev/null 2>&1 || true
  return 1
}

capture_failure_logs() {
  destination="$release_record_dir/deploy-failure-logs.txt"
  [ ! -e "$destination" ] || return 0
  temporary="$(mktemp "$release_record_dir/.deploy-failure-logs.XXXXXX")" \
    || return 1
  if compose logs --no-color --timestamps db mosquitto backend frontend \
      > "$temporary" 2>&1; then
    :
  else
    printf '%s\n' \
      'Container logs could not be collected completely.' >> "$temporary"
  fi
  chown root:root "$temporary" \
    && chmod 0400 "$temporary" \
    && mv -T -- "$temporary" "$destination"
}

ensure_rollback_images() {
  target_sha="$1"
  target_backend="gilbom-backend:$target_sha"
  target_frontend="gilbom-frontend:$target_sha"
  target_artifact="$ARTIFACT_ROOT/$target_sha"
  [ -d "$target_artifact" ] && [ ! -L "$target_artifact" ] \
    || return 1
  [ -z "$(find "$target_artifact" -type l -print -quit)" ] \
    || return 1
  [ -z "$(
    find "$target_artifact" \( ! -user root -o ! -group root \) \
      -print -quit
  )" ] || return 1
  [ -z "$(find "$target_artifact" -perm /0222 -print -quit)" ] \
    || return 1
  for relative_path in \
    SHA256SUMS images.tar.zst images.tar.zst.sigstore.json \
    images.tar.zst.sha256 build-metadata.json
  do
    [ -f "$target_artifact/$relative_path" ] \
      && [ ! -L "$target_artifact/$relative_path" ] \
      && [ -s "$target_artifact/$relative_path" ] \
      || return 1
  done
  if [ -e "$target_artifact/SHA256SUMS.sigstore.json" ]; then
    verify_release_manifest_signature "$target_artifact" \
      || return 1
  fi
  (
    cd "$target_artifact"
    sha256sum -c SHA256SUMS >/dev/null
    sha256sum -c images.tar.zst.sha256 >/dev/null
  ) || return 1
  verify_release_signature "$target_artifact" \
    || return 1
  jq -e \
    --arg target_sha "$target_sha" \
    --arg target_backend "$target_backend" \
    --arg target_frontend "$target_frontend" \
    '
      .releaseSha == $target_sha
      and .images.backend.name == $target_backend
      and .images.frontend.name == $target_frontend
      and (.images.backend.id | test("^sha256:[0-9a-f]{64}$"))
      and (.images.frontend.id | test("^sha256:[0-9a-f]{64}$"))
    ' "$target_artifact/build-metadata.json" >/dev/null \
    || return 1
  if ! docker image inspect "$target_backend" >/dev/null 2>&1 \
      || ! docker image inspect "$target_frontend" >/dev/null 2>&1; then
    zstd --quiet --test "$target_artifact/images.tar.zst" \
      || return 1
    bash -o pipefail -c \
      'zstd --quiet -dc "$1" | docker load' \
      gilbom-rollback-load "$target_artifact/images.tar.zst" \
      >/dev/null \
      || return 1
  fi
  docker image inspect "$target_backend" >/dev/null 2>&1 \
    && docker image inspect "$target_frontend" >/dev/null 2>&1 \
    || return 1

  rollback_loaded_manifest="$work_dir/rollback-loaded-images.json"
  bash -o pipefail -c \
    'docker image save "$1" "$2" | tar -xOf - manifest.json' \
    gilbom-rollback-images "$target_backend" "$target_frontend" \
    > "$rollback_loaded_manifest" \
    || return 1
  for role in backend frontend
  do
    case "$role" in
      backend) target_image="$target_backend" ;;
      frontend) target_image="$target_frontend" ;;
    esac
    config_path="$(
      jq -er \
        --arg image "$target_image" \
        '
          [
            .[]
            | select(
                (.RepoTags | type == "array")
                and (.RepoTags | index($image) != null)
              )
            | .Config
          ]
          | if length == 1 then .[0] else error("ambiguous image tag") end
        ' "$rollback_loaded_manifest"
    )" || return 1
    printf '%s\n' "$config_path" \
      | grep -Eq \
          '^(blobs/sha256/[0-9a-f]{64}|[0-9a-f]{64}[.]json)$' \
      || return 1
    config_name="$(basename -- "$config_path")"
    loaded_id="sha256:${config_name%.json}"
    expected_id="$(
      jq -r ".images.$role.id" "$target_artifact/build-metadata.json"
    )"
    [ "$loaded_id" = "$expected_id" ] || return 1
  done
}

verify_rollback_https_smoke() {
  frontend_id="$1"
  response="$(
    docker exec "$frontend_id" /bin/sh -ec '
      grep -Fq "listen 8443 ssl;" /tmp/nginx-conf.d/site.conf
      exec wget \
        --quiet \
        --no-check-certificate \
        --output-document=- \
        https://127.0.0.1:8443/api/health
    '
  )" || return 1
  printf '%s\n' "$response" \
    | jq -e '.status == "UP"' >/dev/null
}

record_manual_recovery() {
  failure_reason="$1"
  flyway_after="$2"
  containment="$3"
  manifest="$release_record_dir/manual-recovery-required.json"
  [ ! -e "$manifest" ] || return 0
  case "$flyway_after" in
    ''|*[!0-9]*) flyway_after_json=null ;;
    *) flyway_after_json="$flyway_after" ;;
  esac
  recorded_at_utc="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  temporary="$(mktemp "$release_record_dir/.manual-recovery.XXXXXX")" \
    || return 1
  jq -n -S \
    --arg failed_release_sha "$release_sha" \
    --arg rollback_target_sha "$current_sha" \
    --arg recorded_at_utc "$recorded_at_utc" \
    --arg failure_reason "$failure_reason" \
    --argjson flyway_before "$current_version" \
    --argjson flyway_after "$flyway_after_json" \
    --arg containment "$containment" \
    --arg artifact_directory "$artifact_dir" \
    --arg backup_bundle "$backup_bundle" \
    --arg logs "$release_record_dir/deploy-failure-logs.txt" \
    '
      {
        schemaVersion: 1,
        status: "MANUAL_RECOVERY_REQUIRED",
        failedReleaseSha: $failed_release_sha,
        rollbackTargetSha: $rollback_target_sha,
        recordedAtUtc: $recorded_at_utc,
        failureReason: $failure_reason,
        flyway: {
          beforeVersion: $flyway_before,
          afterVersion: $flyway_after,
          changed: (
            $flyway_after == null or $flyway_after != $flyway_before
          )
        },
        automaticRollbackAllowed: false,
        previousBackendCompatibility: "UNCONFIRMED",
        containment: $containment,
        services: {
          frontend: "STOPPED",
          backend: "PRESERVED",
          database: "PRESERVED"
        },
        preserved: {
          artifactDirectory: $artifact_directory,
          backupBundle: $backup_bundle,
          containerLogs: $logs
        },
        databaseRecovery: {
          migrationUndoPerformed: false,
          backupRestorePerformed: false
        },
        operatorAction:
          "Review logs and schema compatibility, then use the gilbom-rollback Jenkins Job."
      }
    ' > "$temporary" \
    || return 1
  chown root:root "$temporary" \
    && chmod 0440 "$temporary" \
    && mv -T -- "$temporary" "$manifest" \
    || return 1
  echo "Manual recovery evidence: $manifest" >&2
  echo \
    "Operator action required: approve compatibility in gilbom-rollback" \
    >&2
}

record_automatic_rollback() {
  failure_reason="$1"
  flyway_after="$2"
  manifest="$release_record_dir/rollback-manifest.json"
  [ ! -e "$manifest" ] || return 1
  rolled_back_at_utc="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  temporary="$(mktemp "$release_record_dir/.rollback-manifest.XXXXXX")" \
    || return 1
  jq -n -S \
    --arg failed_release_sha "$release_sha" \
    --arg rollback_target_sha "$current_sha" \
    --arg rolled_back_at_utc "$rolled_back_at_utc" \
    --arg failure_reason "$failure_reason" \
    --argjson flyway_before "$current_version" \
    --argjson flyway_after "$flyway_after" \
    --arg artifact_directory "$artifact_dir" \
    --arg backup_bundle "$backup_bundle" \
    '
      {
        schemaVersion: 1,
        status: "AUTOMATIC_ROLLBACK_SUCCEEDED",
        failedReleaseSha: $failed_release_sha,
        rollbackTargetSha: $rollback_target_sha,
        rolledBackAtUtc: $rolled_back_at_utc,
        trigger: {
          failureReason: $failure_reason,
          flywayBeforeVersion: $flyway_before,
          flywayAfterFailureVersion: $flyway_after
        },
        decision: "AUTO_ALLOWED_NO_FLYWAY_CHANGE",
        artifactDirectory: $artifact_directory,
        backupBundle: $backup_bundle,
        health: {
          backendReadiness: "PASS",
          frontend: "PASS",
          internalHttps: "PASS"
        },
        databaseRecovery: {
          migrationUndoPerformed: false,
          backupRestorePerformed: false
        }
      }
    ' > "$temporary" \
    || return 1
  chown root:root "$temporary" \
    && chmod 0440 "$temporary" \
    && mv -T -- "$temporary" "$manifest" \
    || return 1
  echo "Automatic production rollback: PASS ($current_sha)" >&2
  echo "Rollback manifest: $manifest" >&2
}

automatic_rollback_without_migration() {
  failure_reason="$1"
  flyway_after="$2"
  compose stop frontend >/dev/null 2>&1 || true
  ensure_rollback_images "$current_sha" || return 1
  install_compose_file "$release_record_dir/previous-compose.prod.yaml" \
    || return 1
  write_image_env "$current_sha" || return 1
  compose config --quiet || return 1
  compose up --detach --no-build --no-deps backend || return 1
  wait_healthy backend || return 1
  backend_id="$(compose ps -q backend)"
  backend_readiness="$(
    docker exec "$backend_id" \
      wget --quiet --output-document=- \
        http://127.0.0.1:8080/actuator/health/readiness
  )" || return 1
  printf '%s\n' "$backend_readiness" \
    | jq -e '.status == "UP"' >/dev/null \
    || return 1
  compose up --detach --no-build --no-deps frontend || return 1
  wait_healthy frontend || return 1
  frontend_id="$(compose ps -q frontend)"
  docker exec "$frontend_id" /usr/local/bin/gilbom-nginx-healthcheck \
    || return 1
  verify_rollback_https_smoke "$frontend_id" || return 1
  atomic_line "$CURRENT_SHA_PATH" "$current_sha" 0440
  atomic_line "$PREVIOUS_SHA_PATH" "$current_sha" 0440
  record_automatic_rollback "$failure_reason" "$flyway_after"
}

handle_deployment_failure() {
  failure_reason="$1"
  rollback_armed=false
  restore_deploy_source_tree \
    || {
      echo "Signed source tree rollback failed" >&2
      return 1
    }
  flyway_after_failure=""
  if queried_version="$(query_flyway_version 2>/dev/null)" \
      && printf '%s\n' "$queried_version" \
        | grep -Eq '^(0|[1-9][0-9]*)$'; then
    flyway_after_failure="$queried_version"
  fi
  capture_failure_logs || true

  if [ -n "$flyway_after_failure" ] \
      && [ "$flyway_after_failure" = "$current_version" ]; then
    if automatic_rollback_without_migration \
        "$failure_reason" "$flyway_after_failure"; then
      return 0
    fi
    compose stop frontend >/dev/null 2>&1 || true
    record_manual_recovery \
      "$failure_reason; automatic rollback failed" \
      "$flyway_after_failure" \
      "AUTOMATIC_ROLLBACK_FAILED"
    return 1
  fi

  compose stop frontend >/dev/null 2>&1 || true
  record_manual_recovery \
    "$failure_reason" \
    "$flyway_after_failure" \
    "FLYWAY_CHANGED_OR_UNAVAILABLE"
}

perform_approved_manual_rollback() {
  failed_record_dir="$RELEASE_RECORD_ROOT/$failed_release_sha"
  rollback_manifest="$failed_record_dir/approved-rollback-manifest.json"
  [ ! -e "$rollback_manifest" ] \
    || fail "immutable approved rollback manifest already exists"

  if recovery_backup_output="$(
    ENV_FILE='' \
    RUNTIME_ENV_FILE="$RUNTIME_ENV_PATH" \
    IMAGE_ENV_FILE="$IMAGE_ENV_PATH" \
    COMPOSE_FILE="$COMPOSE_PATH" \
    BACKUP_ROOT="$BACKUP_ROOT" \
      sh "$BACKUP_SCRIPT"
  )"; then
    printf '%s\n' "$recovery_backup_output"
  else
    fail "pre-rollback production DB and volume backup failed"
  fi
  recovery_backup_bundle="$(
    printf '%s\n' "$recovery_backup_output" \
      | sed -n 's/^Backup bundle: //p' \
      | tail -n 1
  )"
  case "$recovery_backup_bundle" in
    "$BACKUP_ROOT"/*) ;;
    *) fail "rollback backup did not report a fixed-root bundle" ;;
  esac
  [ -d "$recovery_backup_bundle" ] \
    && [ ! -L "$recovery_backup_bundle" ] \
    && [ -s "$recovery_backup_bundle/SHA256SUMS" ] \
    || fail "rollback backup bundle is missing or unsafe"
  (
    cd "$recovery_backup_bundle"
    sha256sum -c SHA256SUMS
  ) || fail "rollback backup bundle checksum verification failed"

  approved_rollback_armed=true
  compose stop frontend \
    || fail "could not stop Frontend before approved rollback"
  rollback_compose_snapshot="$failed_record_dir/previous-compose.prod.yaml"
  [ -f "$rollback_compose_snapshot" ] \
    && [ ! -L "$rollback_compose_snapshot" ] \
    && [ -s "$rollback_compose_snapshot" ] \
    || fail "approved rollback Compose snapshot is missing or unsafe"
  install_compose_file "$rollback_compose_snapshot" \
    || fail "could not restore approved rollback Compose"
  write_image_env "$release_sha" \
    || fail "could not restore approved rollback image.env"
  compose config --quiet \
    || fail "approved rollback Compose configuration is invalid"
  compose up --detach --no-build --no-deps backend \
    || fail "approved previous Backend start failed"
  wait_healthy backend \
    || fail "approved previous Backend did not become healthy"
  rollback_backend_id="$(compose ps -q backend)"
  rollback_backend_readiness="$(
    docker exec "$rollback_backend_id" \
      wget --quiet --output-document=- \
        http://127.0.0.1:8080/actuator/health/readiness
  )" || fail "approved previous Backend readiness endpoint failed"
  printf '%s\n' "$rollback_backend_readiness" \
    | jq -e '.status == "UP"' >/dev/null \
    || fail "approved previous Backend readiness is not UP"

  compose up --detach --no-build --no-deps frontend \
    || fail "approved previous Frontend start failed"
  wait_healthy frontend \
    || fail "approved previous Frontend did not become healthy"
  rollback_frontend_id="$(compose ps -q frontend)"
  docker exec \
    "$rollback_frontend_id" /usr/local/bin/gilbom-nginx-healthcheck \
    || fail "approved previous Frontend health check failed"
  verify_rollback_https_smoke "$rollback_frontend_id" \
    || fail "approved rollback internal HTTPS smoke failed"

  rollback_flyway_after="$(query_flyway_version)" \
    || fail "could not query Flyway after approved rollback"
  [ "$rollback_flyway_after" = "$current_version" ] \
    || fail "approved image rollback unexpectedly changed Flyway"

  rolled_back_at_utc="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  temporary="$(mktemp "$failed_record_dir/.approved-rollback.XXXXXX")"
  jq -n -S \
    --arg failed_release_sha "$failed_release_sha" \
    --arg rollback_target_sha "$release_sha" \
    --arg rolled_back_at_utc "$rolled_back_at_utc" \
    --argjson flyway_version "$current_version" \
    --arg backup_bundle "$recovery_backup_bundle" \
    --slurpfile approval "$rollback_approval_file" \
    '
      {
        schemaVersion: 1,
        status: "MANUALLY_APPROVED_ROLLBACK_SUCCEEDED",
        failedReleaseSha: $failed_release_sha,
        rollbackTargetSha: $rollback_target_sha,
        rolledBackAtUtc: $rolled_back_at_utc,
        decision: "APPROVED_PREVIOUS_BACKEND_COMPATIBLE",
        approval: $approval[0],
        flyway: {
          beforeRollbackVersion: $flyway_version,
          afterRollbackVersion: $flyway_version
        },
        backupBundle: $backup_bundle,
        health: {
          backendReadiness: "PASS",
          frontend: "PASS",
          internalHttps: "PASS"
        },
        databaseRecovery: {
          migrationUndoPerformed: false,
          backupRestorePerformed: false
        }
      }
    ' > "$temporary"
  chown root:root "$temporary"
  chmod 0440 "$temporary"
  mv -T -- "$temporary" "$rollback_manifest"
  atomic_line "$CURRENT_SHA_PATH" "$release_sha" 0440
  approved_rollback_armed=false

  echo "Approved rollback manifest: $rollback_manifest"
  echo "Production approved rollback: PASS ($release_sha)"
  echo "Database migrations were not reverted."
}

handle_approved_rollback_failure() {
  failure_reason="$1"
  approved_rollback_armed=false
  failed_record_dir="$RELEASE_RECORD_ROOT/$failed_release_sha"
  compose stop frontend >/dev/null 2>&1 || true
  logs="$failed_record_dir/approved-rollback-failure-logs.txt"
  if [ ! -e "$logs" ]; then
    temporary="$(mktemp "$failed_record_dir/.approved-rollback-logs.XXXXXX")" \
      || return 1
    compose logs --no-color --timestamps db mosquitto backend frontend \
      > "$temporary" 2>&1 || true
    chown root:root "$temporary" \
      && chmod 0400 "$temporary" \
      && mv -T -- "$temporary" "$logs" \
      || return 1
  fi
  flyway_after_rollback_failure=""
  if queried_version="$(query_flyway_version 2>/dev/null)" \
      && printf '%s\n' "$queried_version" \
        | grep -Eq '^(0|[1-9][0-9]*)$'; then
    flyway_after_rollback_failure="$queried_version"
  fi
  case "$flyway_after_rollback_failure" in
    ''|*[!0-9]*) flyway_after_json=null ;;
    *) flyway_after_json="$flyway_after_rollback_failure" ;;
  esac
  manifest="$failed_record_dir/approved-rollback-failure.json"
  [ ! -e "$manifest" ] || return 0
  recorded_at_utc="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  temporary="$(mktemp "$failed_record_dir/.approved-rollback-failure.XXXXXX")" \
    || return 1
  jq -n -S \
    --arg failed_release_sha "$failed_release_sha" \
    --arg rollback_target_sha "$release_sha" \
    --arg recorded_at_utc "$recorded_at_utc" \
    --arg failure_reason "$failure_reason" \
    --argjson flyway_before "$current_version" \
    --argjson flyway_after "$flyway_after_json" \
    --arg logs "$logs" \
    '
      {
        schemaVersion: 1,
        status: "APPROVED_ROLLBACK_FAILED_MANUAL_RECOVERY_REQUIRED",
        failedReleaseSha: $failed_release_sha,
        rollbackTargetSha: $rollback_target_sha,
        recordedAtUtc: $recorded_at_utc,
        failureReason: $failure_reason,
        flyway: {
          beforeRollbackVersion: $flyway_before,
          afterRollbackFailureVersion: $flyway_after
        },
        services: {
          frontend: "STOPPED",
          backend: "PRESERVED",
          database: "PRESERVED"
        },
        containerLogs: $logs,
        databaseRecovery: {
          migrationUndoPerformed: false,
          backupRestorePerformed: false
        }
      }
    ' > "$temporary" \
    || return 1
  chown root:root "$temporary" \
    && chmod 0440 "$temporary" \
    && mv -T -- "$temporary" "$manifest" \
    || return 1
  echo "Approved rollback failed; Frontend remains stopped: $manifest" >&2
}

prepare_release_source_tree \
  || fail "release source archive could not be safely staged"

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
active_image_sha="$(image_env_sha)" \
  || fail "fixed image.env does not identify one active full-SHA release"
recovery_mode=false
failed_release_sha=""
manual_recovery_file=""
rollback_approval_file=""
if [ "$current_sha" = "$release_sha" ]; then
  if [ "$active_image_sha" != "$release_sha" ]; then
    failed_release_sha="$active_image_sha"
    manual_recovery_file="$(
      printf '%s/%s/manual-recovery-required.json' \
        "$RELEASE_RECORD_ROOT" "$failed_release_sha"
    )"
    rollback_approval_file="$ROLLBACK_APPROVAL_ROOT/$failed_release_sha.json"
    for file in "$manual_recovery_file" "$rollback_approval_file"
    do
      assert_root_file "$file"
    done
    recovery_mode=true
  else
    fail "requested release is already current"
  fi
fi

db_id="$(compose ps -q db)"
[ -n "$db_id" ] \
  || fail "running production PostgreSQL container is required"
[ "$(docker inspect --format '{{.State.Health.Status}}' "$db_id")" = healthy ] \
  || fail "production PostgreSQL is not healthy before deployment"

# 13. 운영 Flyway history에서 현재 version과 적용 script 목록을 조회한다.
current_version="$(query_flyway_version)" \
  || fail "could not query the current Flyway version"
printf '%s\n' "$current_version" | grep -Eq '^(0|[1-9][0-9]*)$' \
  || fail "current Flyway version is not a non-negative integer"
if [ "$recovery_mode" = false ]; then
  [ "$current_version" -le "$latest_version" ] \
    || fail "database Flyway version is newer than the requested release"
else
  manual_recovery_sha256="$(
    sha256sum "$manual_recovery_file" | awk '{print $1}'
  )"
  jq -e \
    --arg failed_release_sha "$failed_release_sha" \
    --arg rollback_target_sha "$release_sha" \
    --argjson current_version "$current_version" \
    '
      .schemaVersion == 1
      and .status == "MANUAL_RECOVERY_REQUIRED"
      and .failedReleaseSha == $failed_release_sha
      and .rollbackTargetSha == $rollback_target_sha
      and .flyway.changed == true
      and .flyway.afterVersion == $current_version
      and .automaticRollbackAllowed == false
      and .previousBackendCompatibility == "UNCONFIRMED"
      and .databaseRecovery.migrationUndoPerformed == false
      and .databaseRecovery.backupRestorePerformed == false
    ' "$manual_recovery_file" >/dev/null \
    || fail "manual recovery evidence is invalid or stale"
  rollback_preflight="$(
    printf '%s/%s/rollback-preflight.json' \
      "$RELEASE_RECORD_ROOT" "$failed_release_sha"
  )"
  rollback_preflight_sha256="$(
    sha256sum "$rollback_preflight" | awk '{print $1}'
  )" || fail "rollback preflight is missing"
  assert_root_file "$rollback_preflight"
  jq -e \
    --arg failed_release_sha "$failed_release_sha" \
    --arg rollback_target_sha "$release_sha" \
    --arg manual_recovery_sha256 "$manual_recovery_sha256" \
    --arg rollback_preflight_sha256 "$rollback_preflight_sha256" \
    --argjson current_version "$current_version" \
    '
      .schemaVersion == 1
      and .failedReleaseSha == $failed_release_sha
      and .rollbackTargetSha == $rollback_target_sha
      and .manualRecoverySha256 == $manual_recovery_sha256
      and .rollbackPreflightSha256 == $rollback_preflight_sha256
      and .flywayCurrentVersion == $current_version
      and .compatibility == "APPROVED_PREVIOUS_BACKEND_COMPATIBLE"
      and (.approvedBy | type == "string")
      and (.approvedBy | length >= 1 and length <= 64)
      and (.approvedBy | explode | all(. >= 32 and . != 127))
      and (.approvedAtUtc | test(
        "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$"
      ))
    ' "$rollback_approval_file" >/dev/null \
    || fail "manual rollback approval is invalid or stale"
fi

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
if [ "$recovery_mode" = false ]; then
  jq -r --argjson current_version "$current_version" '
    .flyway.migrations[]
    | select(.version <= $current_version)
    | "\(.version)|\(.file)"
  ' "$metadata" > "$expected_current_migrations"
  cmp -s "$database_migrations" "$expected_current_migrations" \
    || fail "database Flyway history differs from release metadata"
else
  jq -r '
    .flyway.migrations[]
    | "\(.version)|\(.file)"
  ' "$metadata" > "$expected_current_migrations"
  awk -F '|' -v maximum="$latest_version" \
    '$1 <= maximum { print }' \
    "$database_migrations" > "$work_dir/rollback-database-prefix.txt"
  cmp -s \
    "$work_dir/rollback-database-prefix.txt" \
    "$expected_current_migrations" \
    || fail "current database is not a compatible extension of rollback metadata"
  perform_approved_manual_rollback
  exit 0
fi

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
signature_bundle_sha256="$(
  sha256sum "$artifact_dir/images.tar.zst.sigstore.json" | awk '{print $1}'
)"
manifest_signature_bundle_sha256="$(
  sha256sum "$artifact_dir/SHA256SUMS.sigstore.json" | awk '{print $1}'
)"
jq -e \
  --arg release_sha "$release_sha" \
  --arg current_sha "$current_sha" \
  --arg artifact_manifest_sha256 "$artifact_manifest_sha256" \
  --arg cosign_public_key_sha256 "$cosign_public_key_sha256" \
  --arg signature_bundle_sha256 "$signature_bundle_sha256" \
  --arg manifest_signature_bundle_sha256 \
    "$manifest_signature_bundle_sha256" \
  --arg compose_sha256 "$candidate_compose_sha256" \
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
    and .artifact.signature.status == "PASS"
    and .artifact.signature.cosignVersion == "v3.1.2"
    and (
      .artifact.signature.publicKeySha256
      == $cosign_public_key_sha256
    )
    and (
      .artifact.signature.bundleSha256
      == $signature_bundle_sha256
    )
    and (
      .artifact.signature.manifestBundleSha256
      == $manifest_signature_bundle_sha256
    )
    and .compose.path == "compose.prod.yaml"
    and .compose.sha256 == $compose_sha256
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
    and (.approvedBy | explode | all(. >= 32 and . != 127))
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
      and (.approvedBy | explode | all(. >= 32 and . != 127))
      and (.approvedAtUtc | test(
        "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$"
      ))
    ' "$approval_file" >/dev/null \
    || fail "migration approval does not match the exact pending plan"
  cp -- "$approval_file" "$approval_snapshot"
fi

# 16. 기존 production stack을 기준으로 DB와 모든 영속 Volume을 backup한다.
if backup_output="$(
  ENV_FILE='' \
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

# 승인·backup 동안 운영 DB 상태가 바뀌지 않았는지 전환 직전에 다시 고정한다.
# 실패 handler는 이 값과 실패 직후 조회값을 비교한다.
flyway_before_transition="$(query_flyway_version)" \
  || fail "could not query Flyway immediately before image transition"
[ "$flyway_before_transition" = "$current_version" ] \
  || fail "Flyway changed before the approved image transition"
current_version="$flyway_before_transition"

# 서명된 후보 Compose로 전환하기 직전에 현재 운영 Compose를 immutable
# release record에 보관한다. 자동/수동 rollback은 image.env와 이 snapshot을
# 같은 경계에서 함께 복원한다.
previous_compose_snapshot="$release_record_dir/previous-compose.prod.yaml"
snapshot_compose_file "$previous_compose_snapshot" \
  || fail "could not create immutable previous production Compose snapshot"

# 17. 승인 때 고정한 current.sha를 previous.sha로 원자 기록한다. 기존 설치의
# bootstrap state도 이 시점에만 생성한다.
if [ "$current_sha_bootstrap" = true ]; then
  # 기존 수동 배포를 Stage 11 state model로 편입하는 초기화이며, 요청한 새
  # release로의 성공 처리는 아니다. 이후 실패해도 기존 SHA가 current로 남는다.
  atomic_line "$CURRENT_SHA_PATH" "$current_sha" 0440
fi
atomic_line "$PREVIOUS_SHA_PATH" "$current_sha" 0440

# 18. 서명된 후보 Compose와 새 full-SHA image.env를 각각 같은 filesystem에서
# 원자 교체한다.
# 이 지점부터 실패하면 배포 직전/실패 직후 Flyway를 비교하여 A 또는 C
# 정책을 반드시 실행한다.
rollback_armed=true
install_compose_file "$candidate_compose_path" \
  || fail "could not atomically install signed production Compose"
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
applied_version="$(query_flyway_version)" \
  || fail "could not query the applied Flyway version"
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

# 25. 모든 runtime 검증이 끝난 뒤 서명된 source tree를 교체한다. 서버 상태인
# runtime.env와 image.env는 보존되며, 이후 단계가 실패하면 기존 tree도 rollback된다.
activate_release_source_tree \
  || fail "signed release source tree could not be activated"
[ "$(cat "$DEPLOY_ROOT/.release-source.sha")" = "$release_sha" ] \
  || fail "active source tree release marker differs from the release SHA"

# 26. Secret 원문 없이 artifact, source, image, migration, backup, health 결과를
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
  --arg cosign_version "$COSIGN_VERSION" \
  --arg cosign_public_key_sha256 "$cosign_public_key_sha256" \
  --arg signature_bundle_sha256 "$signature_bundle_sha256" \
  --arg manifest_signature_bundle_sha256 \
    "$manifest_signature_bundle_sha256" \
  --arg source_archive_name "$source_archive_name" \
  --arg source_archive_sha256 "$source_archive_sha256" \
  --arg source_tree_marker "$DEPLOY_ROOT/.release-source.sha" \
  --arg compose_sha256 "$(sha256sum "$COMPOSE_PATH" | awk '{print $1}')" \
  --arg backend_image "$backend_image" \
  --arg backend_image_id "$actual_backend_id" \
  --arg backend_engine_id "$backend_engine_id" \
  --arg frontend_image "$frontend_image" \
  --arg frontend_image_id "$actual_frontend_id" \
  --arg frontend_engine_id "$frontend_engine_id" \
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
        sha256SumsSha256: $artifact_manifest_sha256,
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
        sha256: $source_archive_sha256,
        activeTreeMarker: $source_tree_marker
      },
      composeSha256: $compose_sha256,
      compose: {
        path: "compose.prod.yaml",
        sha256: $compose_sha256,
        previousSnapshot: "previous-compose.prod.yaml"
      },
      images: {
        backend: {
          name: $backend_image,
          id: $backend_image_id,
          engineId: $backend_engine_id
        },
        frontend: {
          name: $frontend_image,
          id: $frontend_image_id,
          engineId: $frontend_engine_id
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

# 27. 모든 검증과 manifest 기록이 끝난 성공 경로에서만 current.sha를 바꾼다.
atomic_line "$CURRENT_SHA_PATH" "$release_sha" 0440
rollback_armed=false
source_tree_swapped=false

echo "Production release manifest: $manifest_path"
echo "Production deploy boundary: PASS ($release_sha)"
