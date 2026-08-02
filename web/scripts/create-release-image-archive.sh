#!/bin/sh

set -eu
umask 077

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
RELEASE_DIR="$PROJECT_DIR/release"
ARCHIVE="$RELEASE_DIR/images.tar.zst"
CHECKSUM="$RELEASE_DIR/images.tar.zst.sha256"

fail() {
  echo "Release image archive creation failed: $1" >&2
  exit 1
}

printf '%s\n' "${RELEASE_SHA:-}" | grep -Eq '^[0-9a-f]{40}$' \
  || fail "RELEASE_SHA must be a full lowercase 40-character SHA"
[ "${BACKEND_IMAGE:-}" = "gilbom-backend:$RELEASE_SHA" ] \
  || fail "BACKEND_IMAGE must use the full RELEASE_SHA"
[ "${FRONTEND_IMAGE:-}" = "gilbom-frontend:$RELEASE_SHA" ] \
  || fail "FRONTEND_IMAGE must use the full RELEASE_SHA"
test -s "$RELEASE_DIR/vulnerabilities/policy-summary.json" \
  || fail "release security artifacts must be prepared first"
command -v zstd >/dev/null 2>&1 || fail "zstd is required"
command -v sha256sum >/dev/null 2>&1 || fail "sha256sum is required"
docker image inspect "$BACKEND_IMAGE" >/dev/null \
  || fail "Backend SHA image is unavailable"
docker image inspect "$FRONTEND_IMAGE" >/dev/null \
  || fail "Frontend SHA image is unavailable"
[ ! -e "$ARCHIVE" ] || fail "image archive already exists"
[ ! -e "$CHECKSUM" ] || fail "image archive checksum already exists"

WORK_DIR="$(mktemp -d "$RELEASE_DIR/.archive-work.XXXXXX")"
FIFO="$WORK_DIR/docker-save.pipe"
TEMP_ARCHIVE="$WORK_DIR/images.tar.zst"
cleanup() {
  rm -r "$WORK_DIR" >/dev/null 2>&1 || true
}
trap cleanup EXIT HUP INT TERM
mkfifo "$FIFO"

zstd --quiet --threads=0 -10 < "$FIFO" > "$TEMP_ARCHIVE" &
ZSTD_PID=$!
if docker save "$BACKEND_IMAGE" "$FRONTEND_IMAGE" > "$FIFO"; then
  SAVE_STATUS=0
else
  SAVE_STATUS=$?
fi
if wait "$ZSTD_PID"; then
  ZSTD_STATUS=0
else
  ZSTD_STATUS=$?
fi
[ "$SAVE_STATUS" -eq 0 ] || fail "docker save failed (exit $SAVE_STATUS)"
[ "$ZSTD_STATUS" -eq 0 ] || fail "zstd failed (exit $ZSTD_STATUS)"
test -s "$TEMP_ARCHIVE" || fail "image archive is empty"
mv -- "$TEMP_ARCHIVE" "$ARCHIVE"

(
  cd "$RELEASE_DIR"
  sha256sum images.tar.zst > images.tar.zst.sha256
  sha256sum -c images.tar.zst.sha256
)
chmod 0600 "$ARCHIVE" "$CHECKSUM"
echo "Release image archive: PASS"

