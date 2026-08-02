#!/bin/sh

set -eu
set +x

fail() {
  echo "Remote production deploy approval failed: $1" >&2
  exit 1
}

[ "$#" -eq 4 ] \
  || fail "usage: $0 RELEASE_SHA PREFLIGHT_SHA256 APPROVAL_JSON USER@HOST"

release_sha="$1"
preflight_sha256="$2"
approval_json="$3"
destination="$4"
known_hosts_file="${KNOWN_HOSTS_FILE:-}"
ssh_identity_file="${SSH_IDENTITY_FILE:-}"
ssh_port="${SSH_PORT:-22}"

printf '%s\n' "$release_sha" | grep -Eq '^[0-9a-f]{40}$' \
  || fail "release SHA must contain 40 lowercase hexadecimal characters"
printf '%s\n' "$preflight_sha256" | grep -Eq '^[0-9a-f]{64}$' \
  || fail "preflight SHA-256 is invalid"
printf '%s\n' "$destination" \
  | grep -Eq '^[A-Za-z0-9._-]+@[A-Za-z0-9._:-]+$' \
  || fail "destination must use USER@HOST without shell metacharacters"
printf '%s\n' "$ssh_port" | grep -Eq '^[0-9]{1,5}$' \
  || fail "SSH_PORT must be numeric"
[ -f "$approval_json" ] && [ ! -L "$approval_json" ] \
  || fail "approval JSON is missing or symlinked"
jq -e \
  'type == "object" and (.approvedBy | type == "string")' \
  "$approval_json" >/dev/null \
  || fail "approval JSON is invalid"
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
  "sudo --non-interactive /usr/local/sbin/gilbom-record-deploy-approval $release_sha $preflight_sha256" \
  < "$approval_json"
