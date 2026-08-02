#!/bin/sh

set -eu
umask 077

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
RELEASE_DIR="$PROJECT_DIR/release"
SOURCE_DIR="$PROJECT_DIR/.artifacts/image-security"

fail() {
  echo "Release security artifact preparation failed: $1" >&2
  exit 1
}

printf '%s\n' "${RELEASE_SHA:-}" | grep -Eq '^[0-9a-f]{40}$' \
  || fail "RELEASE_SHA must be a full lowercase 40-character SHA"
[ "${BACKEND_IMAGE:-}" = "gilbom-backend:$RELEASE_SHA" ] \
  || fail "BACKEND_IMAGE must use the full RELEASE_SHA"
[ "${FRONTEND_IMAGE:-}" = "gilbom-frontend:$RELEASE_SHA" ] \
  || fail "FRONTEND_IMAGE must use the full RELEASE_SHA"
[ "${IMAGE_SCANNER_MODE:-}" = required ] \
  || fail "IMAGE_SCANNER_MODE=required is mandatory for a release"
[ ! -e "$RELEASE_DIR" ] \
  || fail "release directory already exists; refusing to overwrite it"

for file in \
  backend.trivy.json \
  backend.sbom.cdx.json \
  frontend.trivy.json \
  frontend.sbom.cdx.json \
  scanner-status.txt
do
  test -s "$SOURCE_DIR/$file" \
    || fail "required Trivy output is missing or empty: $file"
done
grep -Fxq 'scanner=trivy' "$SOURCE_DIR/scanner-status.txt" \
  || fail "the required scanner was not Trivy"

mkdir -p "$RELEASE_DIR/sbom" "$RELEASE_DIR/vulnerabilities"
cp -- "$SOURCE_DIR/backend.sbom.cdx.json" \
  "$RELEASE_DIR/sbom/backend.sbom.json"
cp -- "$SOURCE_DIR/frontend.sbom.cdx.json" \
  "$RELEASE_DIR/sbom/frontend.sbom.json"

python3 "$SCRIPT_DIR/security_policy.py" \
  --exceptions "$PROJECT_DIR/infra/security/vulnerability-exceptions.yaml" \
  --report "backend=$SOURCE_DIR/backend.trivy.json" \
  --report "frontend=$SOURCE_DIR/frontend.trivy.json" \
  --output "$RELEASE_DIR/vulnerabilities/policy-summary.json"

python3 "$SCRIPT_DIR/render-vulnerability-report.py" \
  --role backend \
  --image "$BACKEND_IMAGE" \
  --input "$SOURCE_DIR/backend.trivy.json" \
  --output "$RELEASE_DIR/vulnerabilities/backend.txt"
python3 "$SCRIPT_DIR/render-vulnerability-report.py" \
  --role frontend \
  --image "$FRONTEND_IMAGE" \
  --input "$SOURCE_DIR/frontend.trivy.json" \
  --output "$RELEASE_DIR/vulnerabilities/frontend.txt"

for file in \
  "$RELEASE_DIR/sbom/backend.sbom.json" \
  "$RELEASE_DIR/sbom/frontend.sbom.json" \
  "$RELEASE_DIR/vulnerabilities/backend.txt" \
  "$RELEASE_DIR/vulnerabilities/frontend.txt" \
  "$RELEASE_DIR/vulnerabilities/policy-summary.json"
do
  test -s "$file" || fail "release output is empty: $file"
done

python3 - "$RELEASE_DIR/vulnerabilities/policy-summary.json" <<'PY'
import json
import sys

summary = json.load(open(sys.argv[1], encoding="utf-8"))
if summary.get("status") != "PASS":
    raise SystemExit("release vulnerability policy is not PASS")
if summary.get("criticalCount") != 0:
    raise SystemExit("release contains a Critical vulnerability")
if summary.get("highCount", 0) != summary.get("exceptedHighCount", 0):
    raise SystemExit("release contains an unapproved High vulnerability")
PY

chmod 0600 "$RELEASE_DIR"/sbom/* "$RELEASE_DIR"/vulnerabilities/*
echo "Release SBOM and vulnerability artifacts: PASS"

