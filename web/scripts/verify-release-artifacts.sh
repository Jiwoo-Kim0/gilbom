#!/bin/sh

set -eu
umask 077

PROJECT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
RELEASE_DIR="$PROJECT_DIR/release"
SECRET_DIR="$PROJECT_DIR/secrets"
PATTERNS="$PROJECT_DIR/.artifacts/release-secret-patterns"

fail() {
  echo "Release artifact verification failed: $1" >&2
  exit 1
}

for path in \
  sbom/backend.sbom.json \
  sbom/frontend.sbom.json \
  vulnerabilities/backend.txt \
  vulnerabilities/frontend.txt \
  vulnerabilities/policy-summary.json \
  images.tar.zst \
  images.tar.zst.sigstore.json \
  images.tar.zst.sha256 \
  build-metadata.json \
  SHA256SUMS
do
  test -s "$RELEASE_DIR/$path" || fail "missing or empty file: $path"
done

find "$RELEASE_DIR" -type l -print -quit | grep -q . \
  && fail "symlinks are forbidden in release artifacts"
find "$RELEASE_DIR" -type f \
  \( -name '.env' -o -path '*/secrets/*' -o -name '*.pem' -o -name '*.key' \) \
  -print -quit | grep -q . \
  && fail "Secret-shaped file is present in release artifacts"

(
  cd "$RELEASE_DIR"
  sha256sum -c SHA256SUMS
  sha256sum -c images.tar.zst.sha256
)
jq empty "$RELEASE_DIR/build-metadata.json"
jq empty "$RELEASE_DIR/images.tar.zst.sigstore.json"
jq -e \
  --arg sha "$RELEASE_SHA" \
  --arg backend "$BACKEND_IMAGE" \
  --arg frontend "$FRONTEND_IMAGE" \
  '
    .releaseSha == $sha
    and .images.backend.name == $backend
    and .images.frontend.name == $frontend
    and (.tests | to_entries | all(.value == "PASS"))
    and .flyway.latestVersion == "V40"
    and (.flyway.migrations | length == 40)
    and all(
      .flyway.migrations[];
      (.version | type == "number")
      and (.file | test("^V[1-9][0-9]*__[A-Za-z0-9_]+[.]sql$"))
      and (.sha256 | test("^[0-9a-f]{64}$"))
    )
    and .artifacts.images.path == "images.tar.zst"
    and .artifacts.images.signature.mode == "offline-key"
    and .artifacts.images.signature.cosignVersion == "v3.1.2"
    and .artifacts.images.signature.transparencyLog == "disabled"
    and (
      .artifacts.images.signature.publicKeySha256
      | test("^[0-9a-f]{64}$")
    )
    and (
      .artifacts.images.signature.bundle.path
      == "images.tar.zst.sigstore.json"
    )
    and .artifacts.images.signature.negativeTests.tamperedArchiveRejected == true
    and .artifacts.images.signature.negativeTests.wrongPublicKeyRejected == true
  ' "$RELEASE_DIR/build-metadata.json" >/dev/null \
  || fail "build metadata contract is invalid"

: > "$PATTERNS"
if [ -d "$SECRET_DIR" ]; then
  find "$SECRET_DIR" -type f ! -name .gitignore -size +0c -exec awk '
    length($0) >= 8 &&
    $0 !~ /^-----BEGIN [A-Z0-9 ]+-----$/ &&
    $0 !~ /^-----END [A-Z0-9 ]+-----$/ {
      print
    }
  ' {} + | LC_ALL=C sort -u > "$PATTERNS"
fi
if [ -s "$PATTERNS" ] \
    && find "$RELEASE_DIR" -type f -exec grep -aFq -f "$PATTERNS" {} +; then
  fail "exact CI Secret material exists in a release artifact"
fi

if find "$RELEASE_DIR" -type f -exec grep -aEIl \
    'AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|glpat-[A-Za-z0-9_-]{20,}|github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9_-]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|-----BEGIN( [A-Z0-9]+)? PRIVATE KEY-----' \
    {} + | grep -q .; then
  fail "Secret-like content exists in a release artifact"
fi

echo "Release artifact verification: PASS"
