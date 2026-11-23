#!/bin/sh

set -eu
set +x
umask 077

fail() {
  echo "Cosign release manifest signing failed: $1" >&2
  exit 1
}

[ "$#" -eq 0 ] \
  || fail "this signer accepts no arguments"

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
VERSION_FILE="$PROJECT_DIR/infra/cosign/version.env"
SIGNING_CONFIG="$PROJECT_DIR/infra/cosign/offline-signing-config.json"
MANIFEST="$PROJECT_DIR/release/SHA256SUMS"
BUNDLE="$PROJECT_DIR/release/SHA256SUMS.sigstore.json"
EVIDENCE_ROOT="$PROJECT_DIR/.artifacts/cosign-manifest"
WORK_ROOT="$PROJECT_DIR/.artifacts/cosign-manifest-work"
PRIVATE_KEY="${COSIGN_PRIVATE_KEY:-}"

[ -f "$VERSION_FILE" ] && [ ! -L "$VERSION_FILE" ] \
  || fail "pinned Cosign version file is missing or symlinked"
# shellcheck disable=SC1090
. "$VERSION_FILE"

for command_name in awk cat chmod cosign grep jq mkdir mktemp rm sha256sum
do
  command -v "$command_name" >/dev/null 2>&1 \
    || fail "$command_name is required"
done
[ -n "${COSIGN_PASSWORD:-}" ] \
  || fail "COSIGN_PASSWORD must come from a Jenkins Secret Text credential"
case "$PRIVATE_KEY" in
  /*) ;;
  *) fail "COSIGN_PRIVATE_KEY must be an absolute Jenkins Secret File path" ;;
esac
[ -f "$PRIVATE_KEY" ] && [ ! -L "$PRIVATE_KEY" ] && [ -s "$PRIVATE_KEY" ] \
  || fail "Jenkins Cosign private key is missing, empty, or symlinked"
[ -f "$MANIFEST" ] && [ ! -L "$MANIFEST" ] && [ -s "$MANIFEST" ] \
  || fail "release SHA256SUMS is missing, empty, or symlinked"
[ -f "$SIGNING_CONFIG" ] && [ ! -L "$SIGNING_CONFIG" ] \
  || fail "offline signing configuration is missing or symlinked"
[ ! -e "$BUNDLE" ] \
  || fail "release manifest signature Bundle already exists"
[ ! -e "$WORK_ROOT" ] \
  || fail "Cosign manifest signing work directory already exists"

cosign version \
  | grep -Fq "GitVersion:    v$COSIGN_VERSION" \
  || fail "Cosign version differs from v$COSIGN_VERSION"
jq -e \
  '
    .mediaType == "application/vnd.dev.sigstore.signingconfig.v0.2+json"
    and .rekorTlogConfig == {}
    and .tsaConfig == {}
  ' "$SIGNING_CONFIG" >/dev/null \
  || fail "offline signing configuration enables an unexpected service"

mkdir -p "$EVIDENCE_ROOT" "$WORK_ROOT"
work_dir="$(mktemp -d "$WORK_ROOT/run.XXXXXX")"
cleanup() {
  rm -rf -- "$work_dir" "$WORK_ROOT" >/dev/null 2>&1 || true
}
trap cleanup EXIT HUP INT TERM

public_key="$work_dir/release.pub"
wrong_prefix="$work_dir/wrong"

COSIGN_PASSWORD="$COSIGN_PASSWORD" \
  cosign public-key --key "$PRIVATE_KEY" > "$public_key" \
  || fail "could not derive the release public key"

COSIGN_PASSWORD="$COSIGN_PASSWORD" \
  cosign sign-blob \
    --yes \
    --signing-config "$SIGNING_CONFIG" \
    --key "$PRIVATE_KEY" \
    --bundle "$BUNDLE" \
    "$MANIFEST" >/dev/null \
  || fail "could not sign the complete release manifest"
chmod 0600 "$BUNDLE"

sh "$SCRIPT_DIR/verify-cosign-release-signature.sh" \
  "$MANIFEST" "$BUNDLE" "$public_key" >/dev/null \
  || fail "positive release manifest verification did not pass"

if {
  cat "$MANIFEST"
  printf 'tampered'
} | cosign verify-blob \
      --insecure-ignore-tlog \
      --key "$public_key" \
      --bundle "$BUNDLE" \
      - >/dev/null 2>&1
then
  fail "tampered release manifest was incorrectly accepted"
fi

COSIGN_PASSWORD="$COSIGN_PASSWORD" \
  cosign generate-key-pair \
    --output-key-prefix "$wrong_prefix" >/dev/null 2>&1 \
  || fail "could not generate the ephemeral wrong-key fixture"
if cosign verify-blob \
      --insecure-ignore-tlog \
      --key "$wrong_prefix.pub" \
      --bundle "$BUNDLE" \
      "$MANIFEST" >/dev/null 2>&1
then
  fail "wrong public key was incorrectly accepted"
fi

manifest_sha256="$(sha256sum "$MANIFEST" | awk '{print $1}')"
bundle_sha256="$(sha256sum "$BUNDLE" | awk '{print $1}')"
public_key_sha256="$(sha256sum "$public_key" | awk '{print $1}')"
jq -n \
  --arg cosign_version "v$COSIGN_VERSION" \
  --arg manifest_sha256 "$manifest_sha256" \
  --arg bundle_sha256 "$bundle_sha256" \
  --arg public_key_sha256 "$public_key_sha256" \
  '{
    schemaVersion: 1,
    status: "PASS",
    signingMode: "offline-key",
    transparencyLog: "disabled",
    cosignVersion: $cosign_version,
    manifest: {
      path: "SHA256SUMS",
      sha256: $manifest_sha256
    },
    bundle: {
      path: "SHA256SUMS.sigstore.json",
      sha256: $bundle_sha256
    },
    publicKeySha256: $public_key_sha256,
    tests: {
      validSignatureAccepted: true,
      tamperedManifestRejected: true,
      wrongPublicKeyRejected: true
    }
  }' > "$EVIDENCE_ROOT/summary.json"
chmod 0600 "$EVIDENCE_ROOT/summary.json"

echo "Cosign complete release manifest signing and negative tests: PASS"
