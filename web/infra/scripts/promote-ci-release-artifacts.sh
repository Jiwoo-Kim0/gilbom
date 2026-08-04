#!/bin/sh

set -eu
umask 077

ARTIFACT_ROOT=/opt/gilbom/artifacts
INCOMING_ROOT="$ARTIFACT_ROOT/.incoming"
COSIGN_BIN=/usr/local/bin/cosign
COSIGN_PUBLIC_KEY=/etc/gilbom/cosign-release.pub
COSIGN_VERSION=v3.1.2

fail() {
  echo "CI release artifact promotion failed: $1" >&2
  exit 1
}

[ "$(id -u)" -eq 0 ] \
  || fail "this command must run as root"
[ "$#" -eq 1 ] \
  || fail "usage: $0 RELEASE_SHA"

release_sha="$1"
printf '%s\n' "$release_sha" | grep -Eq '^[0-9a-f]{40}$' \
  || fail "release SHA must contain 40 lowercase hexadecimal characters"
[ -d "$ARTIFACT_ROOT" ] && [ ! -L "$ARTIFACT_ROOT" ] \
  || fail "artifact root is missing or symlinked"
[ -d "$INCOMING_ROOT" ] && [ ! -L "$INCOMING_ROOT" ] \
  || fail "incoming root is missing or symlinked"

incoming="$INCOMING_ROOT/$release_sha"
destination="$ARTIFACT_ROOT/$release_sha"
lock="$ARTIFACT_ROOT/.promote-$release_sha.lock"

expected_files='
sbom/backend.sbom.json
sbom/frontend.sbom.json
vulnerabilities/backend.txt
vulnerabilities/frontend.txt
vulnerabilities/policy-summary.json
images.tar.zst
images.tar.zst.sigstore.json
images.tar.zst.sha256
build-metadata.json
compose.prod.yaml
SHA256SUMS
SHA256SUMS.sigstore.json
'
manifest_files='
./sbom/backend.sbom.json
./sbom/frontend.sbom.json
./vulnerabilities/backend.txt
./vulnerabilities/frontend.txt
./vulnerabilities/policy-summary.json
./images.tar.zst
./images.tar.zst.sigstore.json
./images.tar.zst.sha256
./build-metadata.json
./compose.prod.yaml
'

validate_release_directory() {
  directory="$1"
  [ -d "$directory" ] && [ ! -L "$directory" ] \
    || fail "release directory is missing or symlinked: $directory"
  [ -d "$directory/sbom" ] && [ ! -L "$directory/sbom" ] \
    || fail "SBOM directory is missing or symlinked"
  [ -d "$directory/vulnerabilities" ] \
    && [ ! -L "$directory/vulnerabilities" ] \
    || fail "vulnerability directory is missing or symlinked"
  [ -z "$(find "$directory" -type l -print -quit)" ] \
    || fail "release directory contains a symlink"
  [ -z "$(
    find "$directory" -mindepth 1 \
      ! -type f ! -type d -print -quit
  )" ] || fail "release directory contains an unsupported entry type"
  [ "$(find "$directory" -type f | wc -l)" -eq 12 ] \
    || fail "release directory must contain exactly twelve files"
  [ "$(find "$directory" -mindepth 1 -type d | wc -l)" -eq 2 ] \
    || fail "release directory must contain exactly two subdirectories"

  for relative_path in $expected_files
  do
    file="$directory/$relative_path"
    [ -f "$file" ] && [ ! -L "$file" ] && [ -s "$file" ] \
      || fail "required artifact is missing, empty, or unsafe: $relative_path"
  done

  manifest_count=0
  while IFS= read -r line
  do
    set -- $line
    [ "$#" -eq 2 ] \
      || fail "SHA256SUMS contains a malformed entry"
    digest="$1"
    relative_path="$2"
    [ "${#digest}" -eq 64 ] \
      && printf '%s\n' "$digest" | grep -Eq '^[0-9a-f]+$' \
      || fail "SHA256SUMS contains an invalid digest"
    case "$relative_path" in
      ./sbom/backend.sbom.json|\
      ./sbom/frontend.sbom.json|\
      ./vulnerabilities/backend.txt|\
      ./vulnerabilities/frontend.txt|\
      ./vulnerabilities/policy-summary.json|\
      ./images.tar.zst|\
      ./images.tar.zst.sigstore.json|\
      ./images.tar.zst.sha256|\
      ./build-metadata.json|\
      ./compose.prod.yaml) ;;
      *) fail "SHA256SUMS contains an unsafe or unexpected path" ;;
    esac
    manifest_count=$((manifest_count + 1))
  done < "$directory/SHA256SUMS"
  [ "$manifest_count" -eq 10 ] \
    || fail "SHA256SUMS must contain exactly ten entries"
  for relative_path in $manifest_files
  do
    [ "$(
      awk -v expected="$relative_path" '
        length($1) == 64 && $2 == expected {count++}
        END {print count + 0}
      ' "$directory/SHA256SUMS"
    )" -eq 1 ] || fail "SHA256SUMS entry is missing or duplicated: $relative_path"
  done

  awk '
    NF == 2 &&
    length($1) == 64 &&
    $1 ~ /^[0-9a-f]+$/ &&
    $2 == "images.tar.zst" {
      valid++
    }
    END {
      exit(valid == 1 ? 0 : 1)
    }
  ' "$directory/images.tar.zst.sha256" \
    || fail "images.tar.zst.sha256 is malformed or unsafe"

  [ -f "$COSIGN_BIN" ] && [ ! -L "$COSIGN_BIN" ] \
    || fail "pinned Cosign verifier is missing or symlinked"
  [ -f "$COSIGN_PUBLIC_KEY" ] \
    && [ ! -L "$COSIGN_PUBLIC_KEY" ] \
    && [ -s "$COSIGN_PUBLIC_KEY" ] \
    || fail "release public key is missing, empty, or symlinked"
  "$COSIGN_BIN" version \
    | grep -Fq "GitVersion:    $COSIGN_VERSION" \
    || fail "Cosign verifier version differs from $COSIGN_VERSION"
  "$COSIGN_BIN" verify-blob \
    --insecure-ignore-tlog \
    --key "$COSIGN_PUBLIC_KEY" \
    --bundle "$directory/SHA256SUMS.sigstore.json" \
    "$directory/SHA256SUMS" >/dev/null \
    || fail "complete release manifest signature is invalid"
  (
    cd "$directory"
    sha256sum -c SHA256SUMS
    sha256sum -c images.tar.zst.sha256
  ) || fail "artifact SHA-256 verification failed"
  "$COSIGN_BIN" verify-blob \
    --insecure-ignore-tlog \
    --key "$COSIGN_PUBLIC_KEY" \
    --bundle "$directory/images.tar.zst.sigstore.json" \
    "$directory/images.tar.zst" >/dev/null \
    || fail "release image archive signature is invalid"
}

validate_immutable_destination() {
  directory="$1"
  [ -z "$(
    find "$directory" \( ! -user root -o ! -group root \) -print -quit
  )" ] || fail "final artifact entries must all be root:root"
  [ -z "$(
    find "$directory" -perm /0222 -print -quit
  )" ] || fail "final artifact directory must be read-only"
}

mkdir "$lock" 2>/dev/null \
  || fail "another promotion is already active for this SHA"
trap 'rmdir "$lock" >/dev/null 2>&1 || true' EXIT HUP INT TERM

validate_release_directory "$incoming"

if [ -e "$destination" ]; then
  [ -d "$destination" ] && [ ! -L "$destination" ] \
    || fail "existing destination is not a safe directory"
  validate_release_directory "$destination"
  validate_immutable_destination "$destination"
  cmp -s "$incoming/SHA256SUMS" "$destination/SHA256SUMS" \
    || fail "existing release checksum differs; refusing replacement"
  rm -r -- "$incoming"
  echo "CI release artifact promotion: REUSED ($destination)"
  exit 0
fi

chown -R root:root "$incoming"
find "$incoming" -type d -exec chmod 0550 {} +
find "$incoming" -type f -exec chmod 0440 {} +
validate_release_directory "$incoming"
validate_immutable_destination "$incoming"

# Both directories share ARTIFACT_ROOT, so this rename is an atomic filesystem
# operation. -T prevents treating a concurrently-created destination as a
# directory into which the incoming tree could be nested.
mv -T -- "$incoming" "$destination"
validate_immutable_destination "$destination"

echo "CI release artifact promotion: PASS ($destination)"
