#!/bin/sh

set -eu
umask 077

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
. "$PROJECT_DIR/infra/security/tool-versions.env"

BACKEND_IMAGE="${BACKEND_IMAGE:-gilbom-backend:local}"
FRONTEND_IMAGE="${FRONTEND_IMAGE:-gilbom-frontend:local}"
OUTPUT_DIR="$(
  CDPATH= cd -- "$PROJECT_DIR" \
    && mkdir -p "${IMAGE_SECURITY_OUTPUT_DIR:-.artifacts/image-security}" \
    && CDPATH= cd -- "${IMAGE_SECURITY_OUTPUT_DIR:-.artifacts/image-security}" \
    && pwd
)"
WORK_ROOT="$(
  CDPATH= cd -- "$PROJECT_DIR" \
    && mkdir -p "${IMAGE_SECURITY_WORK_ROOT:-.artifacts/image-security-work}" \
    && CDPATH= cd -- "${IMAGE_SECURITY_WORK_ROOT:-.artifacts/image-security-work}" \
    && pwd
)"
RUN_DIR="$(mktemp -d "$WORK_ROOT/run.XXXXXX")"
chmod 0700 "$OUTPUT_DIR" "$WORK_ROOT" "$RUN_DIR"
TRIVY_CACHE_DIR="$(
  CDPATH= cd -- "$PROJECT_DIR" \
    && mkdir -p "${TRIVY_CACHE_DIR:-$WORK_ROOT/trivy-cache}" \
    && CDPATH= cd -- "${TRIVY_CACHE_DIR:-$WORK_ROOT/trivy-cache}" \
    && pwd
)"

fail() {
  echo "Image security scan failed: $1" >&2
  exit 1
}

cleanup() {
  rm -r "$RUN_DIR" >/dev/null 2>&1 || true
}
trap cleanup EXIT HUP INT TERM

run_trivy_image() {
  input_file="$1"
  format="$2"
  output_file="$3"

  if command -v trivy >/dev/null 2>&1; then
    trivy image \
      --quiet \
      --cache-dir "$TRIVY_CACHE_DIR" \
      --scanners vuln \
      --format "$format" \
      --output "$output_file" \
      --input "$input_file"
    return
  fi

  command -v docker >/dev/null 2>&1 \
    || fail "neither trivy nor docker is available"
  docker run --rm \
    --user "$(id -u):$(id -g)" \
    --volume "$WORK_ROOT:$WORK_ROOT" \
    --volume "$TRIVY_CACHE_DIR:$TRIVY_CACHE_DIR" \
    --volume "$RUN_DIR:$RUN_DIR:ro" \
    --volume "$OUTPUT_DIR:$OUTPUT_DIR" \
    "$TRIVY_IMAGE" \
    image \
    --quiet \
    --cache-dir "$TRIVY_CACHE_DIR" \
    --scanners vuln \
    --format "$format" \
    --output "$output_file" \
    --input "$input_file"
}

rm -f \
  "$OUTPUT_DIR/backend.trivy.json" \
  "$OUTPUT_DIR/backend.sbom.cdx.json" \
  "$OUTPUT_DIR/frontend.trivy.json" \
  "$OUTPUT_DIR/frontend.sbom.cdx.json" \
  "$OUTPUT_DIR/policy-summary.json" \
  "$OUTPUT_DIR/scanner-status.txt"

for entry in "backend:$BACKEND_IMAGE" "frontend:$FRONTEND_IMAGE"
do
  role="${entry%%:*}"
  image="${entry#*:}"
  archive="$RUN_DIR/$role-image.tar"

  docker image inspect "$image" >/dev/null \
    || fail "$role image is unavailable: $image"
  docker save --output "$archive" "$image" \
    || fail "could not save $role image"

  run_trivy_image \
    "$archive" \
    json \
    "$OUTPUT_DIR/$role.trivy.json" \
    || fail "Trivy vulnerability scan failed for $role"
  run_trivy_image \
    "$archive" \
    cyclonedx \
    "$OUTPUT_DIR/$role.sbom.cdx.json" \
    || fail "CycloneDX SBOM generation failed for $role"
  test -s "$OUTPUT_DIR/$role.trivy.json" \
    || fail "empty vulnerability report for $role"
  test -s "$OUTPUT_DIR/$role.sbom.cdx.json" \
    || fail "empty SBOM for $role"
done

python3 "$SCRIPT_DIR/security_policy.py" \
  --exceptions "$PROJECT_DIR/infra/security/vulnerability-exceptions.yaml" \
  --report "backend=$OUTPUT_DIR/backend.trivy.json" \
  --report "frontend=$OUTPUT_DIR/frontend.trivy.json" \
  --output "$OUTPUT_DIR/policy-summary.json"

printf 'scanner=trivy\nversion=%s\n' "$TRIVY_VERSION" \
  > "$OUTPUT_DIR/scanner-status.txt"
chmod 0600 "$OUTPUT_DIR"/*
echo "Image security scan: PASS"
