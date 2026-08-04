#!/bin/sh

set -eu
umask 077

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
REPOSITORY_ROOT="$(git -C "$PROJECT_DIR" rev-parse --show-toplevel)"
PROJECT_PREFIX="$(git -C "$PROJECT_DIR" rev-parse --show-prefix | sed 's#/$##')"
RELEASE_SHA="$(git -C "$PROJECT_DIR" rev-parse HEAD)"
OUTPUT_ROOT="${RELEASE_OUTPUT_ROOT:-$PROJECT_DIR/.artifacts/release}"
BACKEND_IMAGE="${BACKEND_IMAGE:-gilbom-backend:$RELEASE_SHA}"
FRONTEND_IMAGE="${FRONTEND_IMAGE:-gilbom-frontend:$RELEASE_SHA}"
JENKINS_IMAGE="${JENKINS_IMAGE:-gilbom-jenkins:2.555.3-docker-29.6.2}"

fail() {
  echo "Release artifact build failed: $1" >&2
  exit 1
}

for command_name in docker git sha256sum zstd
do
  command -v "$command_name" >/dev/null 2>&1 \
    || fail "$command_name is required"
done

printf '%s\n' "$RELEASE_SHA" | grep -Eq '^[0-9a-f]{40}$' \
  || fail "HEAD must resolve to a full 40-character Commit SHA"
test -z "$(git -C "$REPOSITORY_ROOT" status --porcelain)" \
  || fail "the Git worktree must be clean"

RELEASE_DIR="$OUTPUT_ROOT/$RELEASE_SHA"
[ ! -e "$RELEASE_DIR" ] \
  || fail "release directory already exists: $RELEASE_DIR"
mkdir -p "$OUTPUT_ROOT"
[ ! -L "$OUTPUT_ROOT" ] \
  || fail "release output root must not be a symlink"
mkdir "$RELEASE_DIR"
chmod 0700 "$RELEASE_DIR"

release_complete=false
WORK_DIR=
cleanup() {
  if [ -n "$WORK_DIR" ] && [ -d "$WORK_DIR" ]; then
    rm -r "$WORK_DIR" >/dev/null 2>&1 || true
  fi
  if [ "$release_complete" != true ] && [ -d "$RELEASE_DIR" ]; then
    rm -r "$RELEASE_DIR" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT HUP INT TERM

WORK_DIR="$(mktemp -d "$OUTPUT_ROOT/.build-$RELEASE_SHA.XXXXXX")"

source_tar="$WORK_DIR/gilbom-web-$RELEASE_SHA.tar"
if [ -n "$PROJECT_PREFIX" ]; then
  git -C "$REPOSITORY_ROOT" archive \
    --format=tar \
    --output "$source_tar" \
    "HEAD:$PROJECT_PREFIX"
else
  git -C "$REPOSITORY_ROOT" archive \
    --format=tar \
    --output "$source_tar" \
    HEAD
fi
zstd --quiet --threads=0 -19 \
  -o "$RELEASE_DIR/gilbom-web-$RELEASE_SHA.tar.zst" \
  "$source_tar"

for entry in \
  "backend:$BACKEND_IMAGE" \
  "frontend:$FRONTEND_IMAGE" \
  "jenkins:$JENKINS_IMAGE"
do
  role="${entry%%:*}"
  image="${entry#*:}"
  image_tar="$WORK_DIR/gilbom-$role-$RELEASE_SHA.tar"

  docker image inspect "$image" >/dev/null \
    || fail "$role image is unavailable: $image"
  docker image save --output "$image_tar" "$image"
  zstd --quiet --threads=0 -19 \
    -o "$RELEASE_DIR/gilbom-$role-$RELEASE_SHA.tar.zst" \
    "$image_tar"
  rm -f "$image_tar"
done

{
  printf 'release_sha=%s\n' "$RELEASE_SHA"
  printf 'backend_image=%s\n' "$BACKEND_IMAGE"
  printf 'frontend_image=%s\n' "$FRONTEND_IMAGE"
  printf 'jenkins_image=%s\n' "$JENKINS_IMAGE"
  printf 'archive_compression=zstd\n'
  printf 'integrity=sha256\n'
} > "$RELEASE_DIR/release.properties"

(
  cd "$RELEASE_DIR"
  sha256sum ./*.tar.zst ./release.properties > SHA256SUMS
  sha256sum --check SHA256SUMS
)
chmod 0600 "$RELEASE_DIR"/*

release_complete=true
echo "Release artifacts: PASS ($RELEASE_DIR)"
