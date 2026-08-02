#!/bin/sh

set -eu
set +x

fail() {
  echo "Stage 15 host evidence fetch failed: $1" >&2
  exit 1
}

[ "$#" -eq 1 ] \
  || fail "usage: $0 USER@HOST"
destination="$1"
known_hosts_file="${KNOWN_HOSTS_FILE:-}"
ssh_identity_file="${SSH_IDENTITY_FILE:-}"
ssh_port="${SSH_PORT:-22}"

printf '%s\n' "$destination" \
  | grep -Eq '^[A-Za-z0-9._-]+@[A-Za-z0-9._:-]+$' \
  || fail "destination must use USER@HOST without shell metacharacters"
printf '%s\n' "$ssh_port" | grep -Eq '^[0-9]{1,5}$' \
  || fail "SSH_PORT must be numeric"
for file in "$known_hosts_file" "$ssh_identity_file"
do
  printf '%s\n' "$file" | grep -Eq '^/[A-Za-z0-9._/@+-]+$' \
    || fail "SSH credential files must use absolute safe paths"
  [ -f "$file" ] && [ ! -L "$file" ] \
    || fail "SSH credential file is missing or symlinked"
done

ssh \
  -o BatchMode=yes \
  -o IdentitiesOnly=yes \
  -o StrictHostKeyChecking=yes \
  -o "UserKnownHostsFile=$known_hosts_file" \
  -i "$ssh_identity_file" \
  -p "$ssh_port" \
  "$destination" \
  'sudo --non-interactive /usr/local/sbin/gilbom-stage15-host-evidence'
