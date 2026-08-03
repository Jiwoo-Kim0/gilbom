#!/bin/sh

set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
PROMOTION_SCRIPT="$PROJECT_DIR/infra/scripts/promote-ci-release-artifacts.sh"
TEST_IMAGE="${ARTIFACT_PROMOTION_TEST_IMAGE:-alpine:3.22}"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/gilbom-promotion-cosign.XXXXXX")"
FAKE_COSIGN="$TEST_ROOT/cosign"
PUBLIC_KEY="$TEST_ROOT/cosign-release.pub"
trap 'rm -rf -- "$TEST_ROOT"' EXIT HUP INT TERM

command -v docker >/dev/null 2>&1 \
  || {
    echo "Artifact promotion self-test failed: docker is required" >&2
    exit 1
  }

{
  printf '%s\n' '#!/bin/sh'
  printf '%s\n' 'case "$1" in'
  printf '%s\n' \
    '  version) printf "GitVersion:    v3.1.2\\n"; exit 0 ;;'
  printf '%s\n' '  verify-blob) exit 0 ;;'
  printf '%s\n' 'esac'
  printf '%s\n' 'exit 1'
} > "$FAKE_COSIGN"
chmod 0755 "$FAKE_COSIGN"
printf '%s\n' 'fixture-public-key' > "$PUBLIC_KEY"
chmod 0444 "$PUBLIC_KEY"

docker run --rm \
  --network none \
  --read-only \
  --cap-drop ALL \
  --cap-add CHOWN \
  --cap-add DAC_OVERRIDE \
  --cap-add FOWNER \
  --tmpfs /opt/gilbom/artifacts:rw,nosuid,nodev,mode=0710 \
  --volume "$PROMOTION_SCRIPT:/promote.sh:ro" \
  --volume "$FAKE_COSIGN:/usr/local/bin/cosign:ro" \
  --volume "$PUBLIC_KEY:/etc/gilbom/cosign-release.pub:ro" \
  --entrypoint /bin/sh \
  "$TEST_IMAGE" -ec '
    sha=0123456789abcdef0123456789abcdef01234567
    root=/opt/gilbom/artifacts

    make_fixture() {
      directory="$1"
      marker="$2"
      mkdir -p "$directory/sbom" "$directory/vulnerabilities"
      printf "backend-sbom-%s\n" "$marker" \
        > "$directory/sbom/backend.sbom.json"
      printf "frontend-sbom-%s\n" "$marker" \
        > "$directory/sbom/frontend.sbom.json"
      printf "backend-vulnerabilities-%s\n" "$marker" \
        > "$directory/vulnerabilities/backend.txt"
      printf "frontend-vulnerabilities-%s\n" "$marker" \
        > "$directory/vulnerabilities/frontend.txt"
      printf "{\"status\":\"PASS\",\"marker\":\"%s\"}\n" "$marker" \
        > "$directory/vulnerabilities/policy-summary.json"
      printf "images-%s\n" "$marker" > "$directory/images.tar.zst"
      printf "{\"fixture\":\"bundle\",\"marker\":\"%s\"}\n" "$marker" \
        > "$directory/images.tar.zst.sigstore.json"
      printf "{\"releaseSha\":\"%s\",\"marker\":\"%s\"}\n" \
        "$sha" "$marker" > "$directory/build-metadata.json"
      (
        cd "$directory"
        sha256sum images.tar.zst > images.tar.zst.sha256
        find . -type f ! -name SHA256SUMS -print \
          | LC_ALL=C sort \
          | xargs sha256sum > SHA256SUMS
      )
    }

    mkdir -p "$root/.incoming"
    make_fixture "$root/.incoming/$sha" original
    sh /promote.sh "$sha"

    test -d "$root/$sha"
    test ! -e "$root/.incoming/$sha"
    test "$(stat -c "%U:%G" "$root/$sha")" = root:root
    test -z "$(find "$root/$sha" -perm /0222 -print -quit)"

    make_fixture "$root/.incoming/$sha" original
    sh /promote.sh "$sha" \
      | grep -F "promotion: REUSED"
    test ! -e "$root/.incoming/$sha"

    make_fixture "$root/.incoming/$sha" different
    if sh /promote.sh "$sha" >/dev/null 2>&1; then
      echo "mismatched existing release was incorrectly reused" >&2
      exit 1
    fi
    grep -Fxq "backend-sbom-original" \
      "$root/$sha/sbom/backend.sbom.json"
    test -d "$root/.incoming/$sha"
  '

echo "CI release artifact promotion self-test: PASS"
