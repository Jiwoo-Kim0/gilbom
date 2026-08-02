#!/bin/sh

set -eu
umask 077

fail() {
  echo "CI artifact remote readiness failed: $1" >&2
  exit 1
}

[ "$#" -eq 1 ] \
  || fail "usage: $0 USER@HOST"

DESTINATION="$1"
KNOWN_HOSTS_FILE="${KNOWN_HOSTS_FILE:-}"
SSH_IDENTITY_FILE="${SSH_IDENTITY_FILE:-}"
SSH_PORT="${SSH_PORT:-22}"

command -v ssh >/dev/null 2>&1 || fail "ssh is required"
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
  'set -eu
   artifact_root=/opt/gilbom/artifacts
   incoming_root="$artifact_root/.incoming"
   promotion_command=/usr/local/sbin/gilbom-promote-ci-release-artifacts
   test -d "$artifact_root"
   test ! -L "$artifact_root"
   test -d "$incoming_root"
   test ! -L "$incoming_root"
   test -w "$incoming_root"
   test -f "$promotion_command"
   test ! -L "$promotion_command"
   test -x "$promotion_command"
   test "$(stat -c "%U:%G:%a" "$promotion_command")" = root:root:755
   sudo --non-interactive --list "$promotion_command" >/dev/null'

echo "CI artifact remote readiness: PASS"
