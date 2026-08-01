#!/bin/sh

set -eu
umask 027

RELEASE_ROOT="${RELEASE_ROOT:-/opt/gilbom/artifacts/releases}"
STAGING_ROOT="${STAGING_ROOT:-/var/tmp/gilbom-artifacts}"

fail() {
  echo "Artifact promotion failed: $1" >&2
  exit 1
}

[ "$(id -u)" -eq 0 ] \
  || fail "this command must run as root"
[ "$#" -eq 2 ] \
  || fail "usage: $0 RELEASE_SHA STAGING_DIRECTORY"

release_sha="$1"
source_directory="$2"
printf '%s\n' "$release_sha" | grep -Eq '^[0-9a-f]{40}$' \
  || fail "release SHA must contain 40 lowercase hexadecimal characters"
for root_path in "$RELEASE_ROOT" "$STAGING_ROOT"
do
  printf '%s\n' "$root_path" | grep -Eq '^/[A-Za-z0-9._/-]+$' \
    || fail "release and staging roots must be absolute safe paths"
done

mkdir -p "$RELEASE_ROOT" "$STAGING_ROOT"
[ ! -L "$RELEASE_ROOT" ] || fail "release root must not be a symlink"
[ ! -L "$STAGING_ROOT" ] || fail "staging root must not be a symlink"

expected_source="$STAGING_ROOT/$release_sha"
[ "$source_directory" = "$expected_source" ] \
  || fail "staging directory is outside the release-specific path"
[ -d "$source_directory" ] \
  || fail "staging directory does not exist"
[ ! -L "$source_directory" ] \
  || fail "staging directory must not be a symlink"
[ -f "$source_directory/SHA256SUMS" ] \
  || fail "SHA256SUMS is missing"
[ ! -L "$source_directory/SHA256SUMS" ] \
  || fail "SHA256SUMS must not be a symlink"

for name in \
  "gilbom-web-$release_sha.tar.zst" \
  "gilbom-backend-$release_sha.tar.zst" \
  "gilbom-frontend-$release_sha.tar.zst" \
  "gilbom-jenkins-$release_sha.tar.zst" \
  release.properties \
  SHA256SUMS
do
  source_file="$source_directory/$name"
  [ -f "$source_file" ] && [ ! -L "$source_file" ] \
    || fail "required staging file is missing or unsafe: $name"
done
entry_count="$(find "$source_directory" -mindepth 1 -maxdepth 1 | wc -l)"
[ "$entry_count" -eq 6 ] \
  || fail "staging directory must contain exactly six expected files"

manifest_count=0
while IFS= read -r line
do
  printf '%s\n' "$line" \
    | grep -Eq '^[0-9a-f]{64}  (\./)?[A-Za-z0-9._-]+(\.tar\.zst|\.properties)$' \
    || fail "SHA256SUMS contains an unsafe entry"
  manifest_count=$((manifest_count + 1))
done < "$source_directory/SHA256SUMS"
[ "$manifest_count" -eq 5 ] \
  || fail "SHA256SUMS does not cover all required artifacts"
for name in \
  "gilbom-web-$release_sha.tar.zst" \
  "gilbom-backend-$release_sha.tar.zst" \
  "gilbom-frontend-$release_sha.tar.zst" \
  "gilbom-jenkins-$release_sha.tar.zst" \
  release.properties
do
  awk -v expected="$name" '
    length($1) == 64 &&
    ($2 == expected || $2 == "./" expected) {
      found = 1
    }
    END {
      exit(found ? 0 : 1)
    }
  ' "$source_directory/SHA256SUMS" \
    || fail "SHA256SUMS is missing required entry: $name"
done

(
  cd "$source_directory"
  sha256sum --check SHA256SUMS
) || fail "staging checksum verification failed"

destination="$RELEASE_ROOT/$release_sha"
[ ! -e "$destination" ] \
  || fail "release already exists and is immutable: $destination"
incoming="$RELEASE_ROOT/.incoming-$release_sha-$$"
mkdir "$incoming"
trap 'rm -r "$incoming" >/dev/null 2>&1 || true' EXIT HUP INT TERM

for source_file in "$source_directory"/*
do
  [ -f "$source_file" ] && [ ! -L "$source_file" ] \
    || fail "all promoted entries must be regular non-symlink files"
  cp -- "$source_file" "$incoming/"
done
chown -R root:root "$incoming"
find "$incoming" -type d -exec chmod 0750 {} +
find "$incoming" -type f -exec chmod 0640 {} +

(
  cd "$incoming"
  sha256sum --check SHA256SUMS
) || fail "post-copy checksum verification failed"

{
  printf 'release_sha=%s\n' "$release_sha"
  printf 'promoted_at=%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  printf 'owner=root:root\n'
  printf 'integrity=sha256-verified-before-and-after-copy\n'
} > "$incoming/PROMOTION_RECEIPT"
chown root:root "$incoming/PROMOTION_RECEIPT"
chmod 0640 "$incoming/PROMOTION_RECEIPT"

mv "$incoming" "$destination"
trap - EXIT HUP INT TERM
[ "$(stat -c '%U:%G' "$destination")" = "root:root" ] \
  || fail "promoted release is not root-owned"

echo "Artifact promotion: PASS ($destination)"
