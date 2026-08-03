#!/bin/sh

set -eu
umask 077

fail() {
  echo "Remote immutable CI Artifact lookup failed: $1" >&2
  exit 1
}

[ "$#" -eq 2 ] \
  || fail "usage: $0 RELEASE_SHA USER@HOST"

RELEASE_SHA="$1"
DESTINATION="$2"
KNOWN_HOSTS_FILE="${KNOWN_HOSTS_FILE:-}"
SSH_IDENTITY_FILE="${SSH_IDENTITY_FILE:-}"
SSH_PORT="${SSH_PORT:-22}"

printf '%s\n' "$RELEASE_SHA" | grep -Eq '^[0-9a-f]{40}$' \
  || fail "release SHA must contain 40 lowercase hexadecimal characters"
printf '%s\n' "$DESTINATION" \
  | grep -Eq '^[A-Za-z0-9._-]+@[A-Za-z0-9._:-]+$' \
  || fail "destination must use USER@HOST without shell metacharacters"
printf '%s\n' "$SSH_PORT" | grep -Eq '^[0-9]{1,5}$' \
  || fail "SSH_PORT must be numeric"
printf '%s\n' "$KNOWN_HOSTS_FILE" \
  | grep -Eq '^/[A-Za-z0-9._/@-]+$' \
  || fail "KNOWN_HOSTS_FILE must use an absolute safe path"
printf '%s\n' "$SSH_IDENTITY_FILE" \
  | grep -Eq '^/[A-Za-z0-9._/@-]+$' \
  || fail "SSH_IDENTITY_FILE must use an absolute safe path"
[ -f "$KNOWN_HOSTS_FILE" ] && [ ! -L "$KNOWN_HOSTS_FILE" ] \
  || fail "KNOWN_HOSTS_FILE must be a regular non-symlink file"
[ -f "$SSH_IDENTITY_FILE" ] && [ ! -L "$SSH_IDENTITY_FILE" ] \
  || fail "SSH_IDENTITY_FILE must be a regular non-symlink file"

set +e
ssh \
  -o BatchMode=yes \
  -o IdentitiesOnly=yes \
  -o StrictHostKeyChecking=yes \
  -o "UserKnownHostsFile=$KNOWN_HOSTS_FILE" \
  -i "$SSH_IDENTITY_FILE" \
  -p "$SSH_PORT" \
  "$DESTINATION" \
  "set -eu
   artifact_root='/opt/gilbom/artifacts'
   release=\"\$artifact_root/$RELEASE_SHA\"
   test -d \"\$artifact_root\"
   test ! -L \"\$artifact_root\"
   test ! -L \"\$release\"
   if test ! -e \"\$release\"
   then
     echo 'CI immutable release lookup: MISSING ($RELEASE_SHA)'
     exit 10
   fi
   test -d \"\$release\"
   test ! -L \"\$release\"
   test \"\$(stat -c '%U:%G:%a' \"\$release\")\" = 'root:root:550'
   echo 'CI immutable release lookup: PRESENT ($RELEASE_SHA)'"
lookup_status="$?"
set -e

case "$lookup_status" in
  0|10) exit "$lookup_status" ;;
  *) fail "remote immutable directory validation failed" ;;
esac
