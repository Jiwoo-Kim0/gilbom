#!/bin/sh

set -eu
umask 077

fail() {
  echo "Remote CI incoming quarantine failed: $1" >&2
  exit 1
}

[ "$#" -eq 3 ] \
  || fail "usage: $0 RELEASE_SHA BUILD_NUMBER USER@HOST"

RELEASE_SHA="$1"
BUILD_NUMBER="$2"
DESTINATION="$3"
KNOWN_HOSTS_FILE="${KNOWN_HOSTS_FILE:-}"
SSH_IDENTITY_FILE="${SSH_IDENTITY_FILE:-}"
SSH_PORT="${SSH_PORT:-22}"

printf '%s\n' "$RELEASE_SHA" | grep -Eq '^[0-9a-f]{40}$' \
  || fail "release SHA must contain 40 lowercase hexadecimal characters"
printf '%s\n' "$BUILD_NUMBER" | grep -Eq '^[1-9][0-9]{0,9}$' \
  || fail "build number must be a positive decimal integer"
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

ssh \
  -o BatchMode=yes \
  -o IdentitiesOnly=yes \
  -o StrictHostKeyChecking=yes \
  -o "UserKnownHostsFile=$KNOWN_HOSTS_FILE" \
  -i "$SSH_IDENTITY_FILE" \
  -p "$SSH_PORT" \
  "$DESTINATION" \
  "set -eu
   incoming_root='/opt/gilbom/artifacts/.incoming'
   source=\"\$incoming_root/$RELEASE_SHA\"
   quarantine=\"\$incoming_root/.aborted-$RELEASE_SHA-build-$BUILD_NUMBER\"
   test -d \"\$incoming_root\"
   test ! -L \"\$incoming_root\"
   test ! -L \"\$source\"
   if test ! -e \"\$source\"
   then
     echo 'CI incoming quarantine: SKIPPED (no incoming directory)'
     exit 0
   fi
   test -d \"\$source\"
   test ! -L \"\$source\"
   test ! -e \"\$quarantine\"
   mv -T -- \"\$source\" \"\$quarantine\"
   echo 'CI incoming quarantine: PASS ($RELEASE_SHA, build $BUILD_NUMBER)'"
