#!/bin/sh

set -eu
set +x

fail() {
  echo "Cosign release signature verification failed: $1" >&2
  exit 1
}

[ "$#" -eq 3 ] \
  || fail "usage: $0 ARCHIVE BUNDLE PUBLIC_KEY"

ARCHIVE="$1"
BUNDLE="$2"
PUBLIC_KEY="$3"
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
VERSION_FILE="$PROJECT_DIR/infra/cosign/version.env"

[ -f "$VERSION_FILE" ] && [ ! -L "$VERSION_FILE" ] \
  || fail "pinned Cosign version file is missing or symlinked"
# shellcheck disable=SC1090
. "$VERSION_FILE"

for command_name in cosign grep jq
do
  command -v "$command_name" >/dev/null 2>&1 \
    || fail "$command_name is required"
done
for file in "$ARCHIVE" "$BUNDLE" "$PUBLIC_KEY"
do
  [ -f "$file" ] && [ ! -L "$file" ] && [ -s "$file" ] \
    || fail "input is missing, empty, or symlinked: $file"
done

cosign version \
  | grep -Fq "GitVersion:    v$COSIGN_VERSION" \
  || fail "Cosign version differs from v$COSIGN_VERSION"
jq -e \
  '
    .mediaType == "application/vnd.dev.sigstore.bundle.v0.3+json"
    and (.messageSignature.signature | type == "string" and length > 0)
    and (
      .messageSignature.messageDigest.algorithm
      == "SHA2_256"
    )
    and (
      .messageSignature.messageDigest.digest
      | type == "string" and length > 0
    )
  ' "$BUNDLE" >/dev/null \
  || fail "Bundle is not a valid Sigstore v0.3 message-signature document"

# This project intentionally uses an offline, Jenkins-held private key. The
# pinned on-host public key is the trust root, so there is no public Rekor
# entry to verify.
cosign verify-blob \
  --insecure-ignore-tlog \
  --key "$PUBLIC_KEY" \
  --bundle "$BUNDLE" \
  "$ARCHIVE" >/dev/null \
  || fail "archive signature is invalid"

echo "Cosign release signature verification: PASS"
