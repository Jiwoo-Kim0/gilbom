#!/bin/sh

set -eu
umask 077

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
RELEASE_DIR="$PROJECT_DIR/release"
COMPOSE_SOURCE="$PROJECT_DIR/compose.prod.yaml"
COMPOSE_ARTIFACT="$RELEASE_DIR/compose.prod.yaml"

command -v jq >/dev/null 2>&1 \
  || {
    echo "jq is required to validate build-metadata.json" >&2
    exit 1
  }
[ -f "$COMPOSE_SOURCE" ] \
  && [ ! -L "$COMPOSE_SOURCE" ] \
  && [ -s "$COMPOSE_SOURCE" ] \
  || {
    echo "compose.prod.yaml is missing, empty, or symlinked" >&2
    exit 1
  }
[ ! -e "$COMPOSE_ARTIFACT" ] \
  || {
    echo "release compose.prod.yaml already exists; refusing overwrite" >&2
    exit 1
  }

cp -- "$COMPOSE_SOURCE" "$COMPOSE_ARTIFACT"
chmod 0600 "$COMPOSE_ARTIFACT"

export BACKEND_IMAGE_ID="$(
  docker image inspect "$BACKEND_IMAGE" --format '{{.Id}}'
)"
export FRONTEND_IMAGE_ID="$(
  docker image inspect "$FRONTEND_IMAGE" --format '{{.Id}}'
)"
export BUILD_FINISHED_AT_UTC="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"

python3 "$SCRIPT_DIR/generate-build-metadata.py"
jq empty "$RELEASE_DIR/build-metadata.json"

(
  cd "$RELEASE_DIR"
  find . -type f \
    ! -name SHA256SUMS \
    ! -name SHA256SUMS.sigstore.json \
    ! -name '.build-metadata.json.tmp' \
    -print0 \
    | LC_ALL=C sort -z \
    | xargs -0 sha256sum > SHA256SUMS
  test -s SHA256SUMS
  sha256sum -c SHA256SUMS
)
chmod 0600 "$RELEASE_DIR/SHA256SUMS"
echo "Build metadata and release SHA256SUMS: PASS"
