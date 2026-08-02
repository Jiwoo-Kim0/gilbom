#!/bin/sh

set -eu
set +x
umask 077

ARTIFACT_ROOT=/opt/gilbom/artifacts
DEPLOY_ROOT=/opt/gilbom/deploy
COMPOSE_PATH="$DEPLOY_ROOT/compose.prod.yaml"
RUNTIME_ENV_PATH="$DEPLOY_ROOT/runtime.env"
IMAGE_ENV_PATH="$DEPLOY_ROOT/image.env"
DEPLOY_SCRIPT="$DEPLOY_ROOT/infra/scripts/deploy.sh"
RELEASE_RECORD_ROOT=/var/lib/gilbom/releases
LOCK_ROOT=/run

fail() {
  echo "Production deploy boundary failed: $1" >&2
  exit 1
}

[ "$#" -eq 1 ] \
  || fail "usage: $0 RELEASE_SHA"

release_sha="$1"
printf '%s\n' "$release_sha" | grep -Eq '^[0-9a-f]{40}$' \
  || fail "release SHA must contain 40 lowercase hexadecimal characters"

# sudo 호출자가 환경변수로 검증 대상이나 Compose 경로를 바꾸지 못하게 한다.
if [ -n "${COMPOSE_FILE:-}" ] \
    || [ -n "${ENV_FILE:-}" ] \
    || [ -n "${RUNTIME_ENV_FILE:-}" ] \
    || [ -n "${IMAGE_ENV_FILE:-}" ] \
    || [ -n "${RELEASE_DIR:-}" ] \
    || [ -n "${PROJECT_DIR:-}" ] \
    || [ -n "${EXPECTED_GIT_REVISION:-}" ] \
    || [ -n "${BACKEND_IMAGE:-}" ] \
    || [ -n "${FRONTEND_IMAGE:-}" ] \
    || [ -n "${DEPLOY_PULL:-}" ] \
    || [ -n "${ALLOW_MUTABLE_IMAGE_TAGS:-}" ]; then
  fail "deployment path and image environment overrides are forbidden"
fi

[ "$(id -u)" -eq 0 ] \
  || fail "this command must run as root"

for command in docker zstd jq sha256sum stat find mkfifo
do
  command -v "$command" >/dev/null 2>&1 \
    || fail "required command is missing: $command"
done

assert_root_file() {
  path="$1"
  [ -f "$path" ] && [ ! -L "$path" ] \
    || fail "required fixed deployment file is missing or symlinked: $path"
  [ "$(stat -c '%U:%G' "$path")" = root:root ] \
    || fail "fixed deployment file must be root:root: $path"
  [ -z "$(find "$path" -perm /0022 -print -quit)" ] \
    || fail "fixed deployment file must not be group/world writable: $path"
}

[ -d "$DEPLOY_ROOT" ] && [ ! -L "$DEPLOY_ROOT" ] \
  || fail "fixed deployment root is missing or symlinked"
[ "$(stat -c '%U:%G' "$DEPLOY_ROOT")" = root:root ] \
  || fail "fixed deployment root must be root:root"
[ -z "$(find "$DEPLOY_ROOT" -maxdepth 0 -perm /0022 -print -quit)" ] \
  || fail "fixed deployment root must not be group/world writable"

assert_root_file "$COMPOSE_PATH"
assert_root_file "$RUNTIME_ENV_PATH"
assert_root_file "$DEPLOY_SCRIPT"

artifact_dir="$ARTIFACT_ROOT/$release_sha"
[ -d "$artifact_dir" ] && [ ! -L "$artifact_dir" ] \
  || fail "promoted release artifact is missing or symlinked"
[ -z "$(find "$artifact_dir" -type l -print -quit)" ] \
  || fail "promoted release artifact contains a symlink"
[ -z "$(
  find "$artifact_dir" \( ! -user root -o ! -group root \) -print -quit
)" ] || fail "promoted release artifact must be entirely root:root"
[ -z "$(find "$artifact_dir" -perm /0222 -print -quit)" ] \
  || fail "promoted release artifact must be immutable"
[ "$(find "$artifact_dir" -type f | wc -l)" -eq 9 ] \
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

(
  cd "$artifact_dir"
  sha256sum -c SHA256SUMS
  sha256sum -c images.tar.zst.sha256
) || fail "promoted release checksum verification failed"

backend_image="gilbom-backend:$release_sha"
frontend_image="gilbom-frontend:$release_sha"
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
  ' "$artifact_dir/build-metadata.json" >/dev/null \
  || fail "release metadata does not match the requested SHA and image names"
jq -e '.status == "PASS"' \
  "$artifact_dir/vulnerabilities/policy-summary.json" >/dev/null \
  || fail "release vulnerability policy is not PASS"

[ -d "$LOCK_ROOT" ] && [ ! -L "$LOCK_ROOT" ] \
  || fail "runtime lock root is missing or symlinked"
lock="$LOCK_ROOT/gilbom-deploy.lock"
mkdir "$lock" 2>/dev/null \
  || fail "another production deployment is already active"

work_dir=""
image_env_temp=""
cleanup() {
  if [ -n "$work_dir" ] && [ -d "$work_dir" ]; then
    rm -r -- "$work_dir" >/dev/null 2>&1 || true
  fi
  if [ -n "$image_env_temp" ] && [ -f "$image_env_temp" ]; then
    rm -f -- "$image_env_temp" >/dev/null 2>&1 || true
  fi
  rmdir "$lock" >/dev/null 2>&1 || true
}
trap cleanup EXIT HUP INT TERM

work_dir="$(mktemp -d "/var/tmp/gilbom-deploy-$release_sha.XXXXXX")"
archive_pipe="$work_dir/images.tar"
mkfifo "$archive_pipe"

zstd --quiet --decompress --stdout \
  "$artifact_dir/images.tar.zst" > "$archive_pipe" &
zstd_pid=$!
if docker load --input "$archive_pipe"; then
  docker_status=0
else
  docker_status=$?
fi
if wait "$zstd_pid"; then
  zstd_status=0
else
  zstd_status=$?
fi
[ "$zstd_status" -eq 0 ] \
  || fail "release image archive decompression failed"
[ "$docker_status" -eq 0 ] \
  || fail "release image archive load failed"

expected_backend_id="$(
  jq -r '.images.backend.id' "$artifact_dir/build-metadata.json"
)"
expected_frontend_id="$(
  jq -r '.images.frontend.id' "$artifact_dir/build-metadata.json"
)"
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

image_env_temp="$(mktemp "$DEPLOY_ROOT/.image.env.XXXXXX")"
{
  printf 'BACKEND_IMAGE=%s\n' "$backend_image"
  printf 'FRONTEND_IMAGE=%s\n' "$frontend_image"
} > "$image_env_temp"
chown root:root "$image_env_temp"
chmod 0600 "$image_env_temp"
mv -T -- "$image_env_temp" "$IMAGE_ENV_PATH"
image_env_temp=""

ENV_FILE= \
RUNTIME_ENV_FILE="$RUNTIME_ENV_PATH" \
IMAGE_ENV_FILE="$IMAGE_ENV_PATH" \
COMPOSE_FILE="$COMPOSE_PATH" \
RELEASE_DIR="$RELEASE_RECORD_ROOT" \
EXPECTED_GIT_REVISION="$release_sha" \
DEPLOY_PULL=false \
ALLOW_MUTABLE_IMAGE_TAGS=false \
IMAGE_SCANNER_MODE=off \
SECRET_SCAN_DIR=/opt/gilbom/secrets \
  sh "$DEPLOY_SCRIPT"

echo "Production deploy boundary: PASS ($release_sha)"
