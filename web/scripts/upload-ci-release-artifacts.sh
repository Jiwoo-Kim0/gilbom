#!/bin/sh

set -eu
umask 077

fail() {
  echo "CI release artifact upload failed: $1" >&2
  exit 1
}

[ "$#" -eq 2 ] \
  || fail "usage: $0 RELEASE_DIR USER@HOST"

RELEASE_DIR="$(CDPATH= cd -- "$1" && pwd)"
DESTINATION="$2"
RELEASE_SHA="${RELEASE_SHA:-}"
KNOWN_HOSTS_FILE="${KNOWN_HOSTS_FILE:-}"
SSH_IDENTITY_FILE="${SSH_IDENTITY_FILE:-}"
SSH_PORT="${SSH_PORT:-22}"
REMOTE_ARTIFACT_ROOT="${REMOTE_ARTIFACT_ROOT:-/opt/gilbom/artifacts}"
REMOTE_INCOMING_ROOT="$REMOTE_ARTIFACT_ROOT/.incoming"
REMOTE_INCOMING_DIR="$REMOTE_INCOMING_ROOT/$RELEASE_SHA"
SOURCE_ARCHIVE="gilbom-web-$RELEASE_SHA.tar.zst"

for command_name in ssh scp sha256sum jq
do
  command -v "$command_name" >/dev/null 2>&1 \
    || fail "$command_name is required"
done

printf '%s\n' "$RELEASE_SHA" | grep -Eq '^[0-9a-f]{40}$' \
  || fail "RELEASE_SHA must contain 40 lowercase hexadecimal characters"
printf '%s\n' "$DESTINATION" \
  | grep -Eq '^[A-Za-z0-9._-]+@[A-Za-z0-9._:-]+$' \
  || fail "destination must use USER@HOST without shell metacharacters"
printf '%s\n' "$SSH_PORT" | grep -Eq '^[0-9]{1,5}$' \
  || fail "SSH_PORT must be numeric"
[ "$REMOTE_ARTIFACT_ROOT" = /opt/gilbom/artifacts ] \
  || fail "REMOTE_ARTIFACT_ROOT must be /opt/gilbom/artifacts"
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

expected_files='
sbom/backend.sbom.json
sbom/frontend.sbom.json
vulnerabilities/backend.txt
vulnerabilities/frontend.txt
vulnerabilities/policy-summary.json
images.tar.zst
images.tar.zst.sigstore.json
images.tar.zst.sha256
compose.prod.yaml
build-metadata.json
SHA256SUMS
SHA256SUMS.sigstore.json
'
expected_files="$expected_files
$SOURCE_ARCHIVE"

for relative_path in $expected_files
do
  artifact="$RELEASE_DIR/$relative_path"
  [ -f "$artifact" ] && [ ! -L "$artifact" ] && [ -s "$artifact" ] \
    || fail "required release artifact is missing, empty, or unsafe: $relative_path"
done
[ "$(find "$RELEASE_DIR" -type f | wc -l)" -eq 13 ] \
  || fail "release directory must contain exactly thirteen regular files"
[ -z "$(find "$RELEASE_DIR" -type l -print -quit)" ] \
  || fail "release directory must not contain symlinks"
[ -z "$(
  find "$RELEASE_DIR" -mindepth 1 \
    ! -type f ! -type d -print -quit
)" ] || fail "release directory contains an unsupported entry type"

(
  cd "$RELEASE_DIR"
  sha256sum -c SHA256SUMS
  sha256sum -c images.tar.zst.sha256
) || fail "local SHA-256 verification failed"
jq -e --arg sha "$RELEASE_SHA" \
  '.releaseSha == $sha' "$RELEASE_DIR/build-metadata.json" >/dev/null \
  || fail "build-metadata.json does not match RELEASE_SHA"

ssh_options="
  -o BatchMode=yes
  -o IdentitiesOnly=yes
  -o StrictHostKeyChecking=yes
  -o UserKnownHostsFile=$KNOWN_HOSTS_FILE
  -i $SSH_IDENTITY_FILE
  -p $SSH_PORT
"

# shellcheck disable=SC2086
if ssh $ssh_options "$DESTINATION" \
  "test -d '$REMOTE_INCOMING_DIR'"
then
  # A retry may reuse a completely uploaded incoming directory, but it never
  # overwrites it. The root promotion command performs the full structural
  # validation again after manual approval.
  # shellcheck disable=SC2086
  ssh $ssh_options "$DESTINATION" \
    "cd '$REMOTE_INCOMING_DIR' \
      && sha256sum -c SHA256SUMS \
      && sha256sum -c images.tar.zst.sha256" \
    || fail "existing incoming directory is incomplete or corrupt"
  # shellcheck disable=SC2086
  ssh $ssh_options "$DESTINATION" \
    "cmp - '$REMOTE_INCOMING_DIR/SHA256SUMS'" \
    < "$RELEASE_DIR/SHA256SUMS" \
    || fail "existing incoming directory belongs to different content"
  echo "CI release artifact upload: REUSED ($REMOTE_INCOMING_DIR)"
  exit 0
fi

# The upload account can write only below the pre-created .incoming boundary.
# mkdir is intentionally non-idempotent so two different builds cannot race
# while populating the same full-SHA directory.
# shellcheck disable=SC2086
ssh $ssh_options "$DESTINATION" \
  "set -eu
   umask 077
   test -d '$REMOTE_INCOMING_ROOT'
   test ! -L '$REMOTE_INCOMING_ROOT'
   mkdir '$REMOTE_INCOMING_DIR'
   mkdir '$REMOTE_INCOMING_DIR/sbom'
   mkdir '$REMOTE_INCOMING_DIR/vulnerabilities'"

scp_options="
  -o BatchMode=yes
  -o IdentitiesOnly=yes
  -o StrictHostKeyChecking=yes
  -o UserKnownHostsFile=$KNOWN_HOSTS_FILE
  -i $SSH_IDENTITY_FILE
  -P $SSH_PORT
"

# OpenSSH scp uses SFTP by default. Only the thirteen prevalidated regular files
# are copied, and the remote directory was created without replacement above.
# shellcheck disable=SC2086
scp $scp_options -- \
  "$RELEASE_DIR/images.tar.zst" \
  "$RELEASE_DIR/images.tar.zst.sigstore.json" \
  "$RELEASE_DIR/images.tar.zst.sha256" \
  "$RELEASE_DIR/$SOURCE_ARCHIVE" \
  "$RELEASE_DIR/compose.prod.yaml" \
  "$RELEASE_DIR/build-metadata.json" \
  "$RELEASE_DIR/SHA256SUMS" \
  "$RELEASE_DIR/SHA256SUMS.sigstore.json" \
  "$DESTINATION:$REMOTE_INCOMING_DIR/"
# shellcheck disable=SC2086
scp $scp_options -- \
  "$RELEASE_DIR/sbom/backend.sbom.json" \
  "$RELEASE_DIR/sbom/frontend.sbom.json" \
  "$DESTINATION:$REMOTE_INCOMING_DIR/sbom/"
# shellcheck disable=SC2086
scp $scp_options -- \
  "$RELEASE_DIR/vulnerabilities/backend.txt" \
  "$RELEASE_DIR/vulnerabilities/frontend.txt" \
  "$RELEASE_DIR/vulnerabilities/policy-summary.json" \
  "$DESTINATION:$REMOTE_INCOMING_DIR/vulnerabilities/"

# shellcheck disable=SC2086
ssh $ssh_options "$DESTINATION" \
  "set -eu
   find '$REMOTE_INCOMING_DIR' -type d -exec chmod 0700 {} +
   find '$REMOTE_INCOMING_DIR' -type f -exec chmod 0600 {} +
   cd '$REMOTE_INCOMING_DIR'
   sha256sum -c SHA256SUMS
   sha256sum -c images.tar.zst.sha256"

echo "CI release artifact upload: PASS ($REMOTE_INCOMING_DIR)"
