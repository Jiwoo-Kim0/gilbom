#!/bin/sh

set -eu
set +x

RELEASE_RECORD_ROOT=/var/lib/gilbom/releases

fail() {
  echo "Production release manifest retrieval failed: $1" >&2
  exit 1
}

[ "$(id -u)" -eq 0 ] \
  || fail "this command must run as root"
[ "$#" -eq 1 ] \
  || fail "usage: $0 RELEASE_SHA"

release_sha="$1"
printf '%s\n' "$release_sha" | grep -Eq '^[0-9a-f]{40}$' \
  || fail "release SHA must contain 40 lowercase hexadecimal characters"

manifest="$RELEASE_RECORD_ROOT/$release_sha/release-manifest.json"
[ -f "$manifest" ] && [ ! -L "$manifest" ] && [ -s "$manifest" ] \
  || fail "release manifest is missing or unsafe"
[ "$(stat -c '%U:%G' "$manifest")" = root:root ] \
  || fail "release manifest must be root:root"
[ -z "$(find "$manifest" -perm /0022 -print -quit)" ] \
  || fail "release manifest is group/world writable"
jq -e --arg release_sha "$release_sha" \
  '.releaseSha == $release_sha' "$manifest" >/dev/null \
  || fail "release manifest SHA does not match"

cat "$manifest"
