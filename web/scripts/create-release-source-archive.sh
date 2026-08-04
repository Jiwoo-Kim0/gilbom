#!/bin/sh

set -eu
umask 077

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
REPOSITORY_ROOT="$(git -C "$PROJECT_DIR" rev-parse --show-toplevel)"
PROJECT_PREFIX="$(git -C "$PROJECT_DIR" rev-parse --show-prefix | sed 's#/$##')"
RELEASE_DIR="$PROJECT_DIR/release"
RELEASE_SHA="${RELEASE_SHA:-}"
ARCHIVE_NAME="gilbom-web-$RELEASE_SHA.tar.zst"
ARCHIVE="$RELEASE_DIR/$ARCHIVE_NAME"

fail() {
  echo "Release source archive creation failed: $1" >&2
  exit 1
}

for command_name in chmod git grep mktemp rm sed tar zstd
do
  command -v "$command_name" >/dev/null 2>&1 \
    || fail "$command_name is required"
done

printf '%s\n' "$RELEASE_SHA" | grep -Eq '^[0-9a-f]{40}$' \
  || fail "RELEASE_SHA must contain 40 lowercase hexadecimal characters"
[ "$(git -C "$PROJECT_DIR" rev-parse HEAD)" = "$RELEASE_SHA" ] \
  || fail "RELEASE_SHA must match the checked-out HEAD"
[ -d "$RELEASE_DIR" ] && [ ! -L "$RELEASE_DIR" ] \
  || fail "release directory is missing or symlinked"
[ ! -e "$ARCHIVE" ] \
  || fail "release source archive already exists"

WORK_DIR="$(mktemp -d "$RELEASE_DIR/.source-work.XXXXXX")"
cleanup() {
  rm -r "$WORK_DIR" >/dev/null 2>&1 || true
}
trap cleanup EXIT HUP INT TERM

source_tar="$WORK_DIR/gilbom-web-$RELEASE_SHA.tar"
if [ -n "$PROJECT_PREFIX" ]; then
  git -C "$REPOSITORY_ROOT" archive \
    --format=tar \
    --output "$source_tar" \
    "$RELEASE_SHA:$PROJECT_PREFIX"
else
  git -C "$REPOSITORY_ROOT" archive \
    --format=tar \
    --output "$source_tar" \
    "$RELEASE_SHA"
fi

# The host extracts this archive into /opt/gilbom/deploy. Git archive excludes
# untracked runtime state by construction, and this explicit gate prevents a
# future tracked runtime file or Secret payload from crossing that boundary.
archive_listing="$WORK_DIR/archive-listing.txt"
tar -tf "$source_tar" > "$archive_listing"
grep -Fxq 'compose.monitoring.yaml' "$archive_listing" \
  || fail "compose.monitoring.yaml is missing from the source archive"
grep -Fxq 'infra/scripts/install-day10-host.sh' "$archive_listing" \
  || fail "the Day 10 host installer is missing from the source archive"
unsafe_entries="$(
  grep -E \
    '(^|/)(runtime[.]env|image[.]env)$|(^|/)secrets/[^/]+$' \
    "$archive_listing" \
    | grep -Ev '(^|/)secrets/[.]gitignore$' \
    || true
)"
[ -z "$unsafe_entries" ] \
  || fail "runtime state or a Secret payload is tracked in the source archive"

zstd --quiet --threads=0 -19 \
  --output "$ARCHIVE" \
  "$source_tar"
zstd --quiet --test "$ARCHIVE" \
  || fail "release source archive failed zstd integrity testing"
chmod 0600 "$ARCHIVE"

echo "Release source archive: PASS ($ARCHIVE_NAME)"
