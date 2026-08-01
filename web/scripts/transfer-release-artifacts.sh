#!/bin/sh

set -eu
umask 077

fail() {
  echo "Release artifact transfer failed: $1" >&2
  exit 1
}

[ "$#" -eq 2 ] \
  || fail "usage: $0 RELEASE_DIR USER@HOST"

RELEASE_DIR="$(CDPATH= cd -- "$1" && pwd)"
DESTINATION="$2"
RELEASE_SHA="$(basename "$RELEASE_DIR")"
KNOWN_HOSTS_FILE="${KNOWN_HOSTS_FILE:-}"
SSH_PORT="${SSH_PORT:-22}"
REMOTE_STAGING_ROOT="${REMOTE_STAGING_ROOT:-/var/tmp/gilbom-artifacts}"
REMOTE_PROMOTION_COMMAND="${REMOTE_PROMOTION_COMMAND:-/usr/local/sbin/gilbom-promote-release-artifacts}"
REMOTE_STAGING_DIR="$REMOTE_STAGING_ROOT/$RELEASE_SHA"

printf '%s\n' "$RELEASE_SHA" | grep -Eq '^[0-9a-f]{40}$' \
  || fail "release directory name must be a full Commit SHA"
printf '%s\n' "$DESTINATION" \
  | grep -Eq '^[A-Za-z0-9._-]+@[A-Za-z0-9._:-]+$' \
  || fail "destination must use USER@HOST without shell metacharacters"
printf '%s\n' "$SSH_PORT" | grep -Eq '^[0-9]{1,5}$' \
  || fail "SSH_PORT must be numeric"
printf '%s\n' "$REMOTE_STAGING_ROOT" \
  | grep -Eq '^/[A-Za-z0-9._/-]+$' \
  || fail "REMOTE_STAGING_ROOT must be an absolute safe path"
printf '%s\n' "$REMOTE_PROMOTION_COMMAND" \
  | grep -Eq '^/[A-Za-z0-9._/-]+$' \
  || fail "REMOTE_PROMOTION_COMMAND must be an absolute safe path"
[ -n "$KNOWN_HOSTS_FILE" ] \
  || fail "KNOWN_HOSTS_FILE is required"
[ -f "$KNOWN_HOSTS_FILE" ] \
  || fail "KNOWN_HOSTS_FILE is not a regular file"
[ -f "$RELEASE_DIR/SHA256SUMS" ] \
  || fail "SHA256SUMS is missing"

files=
for name in \
  "gilbom-web-$RELEASE_SHA.tar.zst" \
  "gilbom-backend-$RELEASE_SHA.tar.zst" \
  "gilbom-frontend-$RELEASE_SHA.tar.zst" \
  "gilbom-jenkins-$RELEASE_SHA.tar.zst" \
  release.properties \
  SHA256SUMS
do
  file="$RELEASE_DIR/$name"
  [ -f "$file" ] && [ ! -L "$file" ] \
    || fail "required release file is missing or unsafe: $name"
  files="${files}${files:+
}$file"
done
entry_count="$(find "$RELEASE_DIR" -mindepth 1 -maxdepth 1 | wc -l)"
[ "$entry_count" -eq 6 ] \
  || fail "release directory must contain exactly six expected files"

manifest_count=0
while IFS= read -r line
do
  printf '%s\n' "$line" \
    | grep -Eq '^[0-9a-f]{64}  (\./)?[A-Za-z0-9._-]+(\.tar\.zst|\.properties)$' \
    || fail "SHA256SUMS contains an unsafe entry"
  manifest_count=$((manifest_count + 1))
done < "$RELEASE_DIR/SHA256SUMS"
[ "$manifest_count" -eq 5 ] \
  || fail "SHA256SUMS does not cover exactly five release artifacts"
for name in \
  "gilbom-web-$RELEASE_SHA.tar.zst" \
  "gilbom-backend-$RELEASE_SHA.tar.zst" \
  "gilbom-frontend-$RELEASE_SHA.tar.zst" \
  "gilbom-jenkins-$RELEASE_SHA.tar.zst" \
  release.properties
do
  awk -v expected="$name" '
    length($1) == 64 &&
    ($2 == expected || $2 == "./" expected) {
      found++
    }
    END {
      exit(found == 1 ? 0 : 1)
    }
  ' "$RELEASE_DIR/SHA256SUMS" \
    || fail "SHA256SUMS entry is missing or duplicated: $name"
done

(
  cd "$RELEASE_DIR"
  sha256sum --check SHA256SUMS
) || fail "local checksum verification failed"

set -- \
  -o BatchMode=yes \
  -o IdentitiesOnly=yes \
  -o StrictHostKeyChecking=yes \
  -o "UserKnownHostsFile=$KNOWN_HOSTS_FILE" \
  -p "$SSH_PORT"

ssh "$@" "$DESTINATION" \
  "umask 077 && mkdir -p '$REMOTE_STAGING_ROOT' && mkdir '$REMOTE_STAGING_DIR'"

# The local list is produced only from regular files in the validated release
# directory. OpenSSH scp uses SFTP by default and host-key verification is
# mandatory above.
old_ifs=$IFS
IFS='
'
for file in $files
do
  scp \
    -o BatchMode=yes \
    -o IdentitiesOnly=yes \
    -o StrictHostKeyChecking=yes \
    -o "UserKnownHostsFile=$KNOWN_HOSTS_FILE" \
    -P "$SSH_PORT" \
    -- "$file" "$DESTINATION:$REMOTE_STAGING_DIR/"
done
IFS=$old_ifs

ssh "$@" "$DESTINATION" \
  "cd '$REMOTE_STAGING_DIR' && sha256sum --check SHA256SUMS"
ssh "$@" "$DESTINATION" \
  "sudo --non-interactive '$REMOTE_PROMOTION_COMMAND' '$RELEASE_SHA' '$REMOTE_STAGING_DIR'"

echo "Release artifact transfer and root promotion: PASS ($RELEASE_SHA)"
