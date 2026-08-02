#!/bin/sh

set -eu
umask 077

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
. "$PROJECT_DIR/infra/security/tool-versions.env"

ACTION="${1:-scan}"
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
TRIVY_TEMP_DIR="$RUN_DIR/trivy-tmp"
mkdir -p "$TRIVY_TEMP_DIR"
chmod 0700 "$OUTPUT_DIR" "$WORK_ROOT" "$RUN_DIR" "$TRIVY_TEMP_DIR"
TRIVY_CACHE_DIR="$(
  CDPATH= cd -- "$PROJECT_DIR" \
    && mkdir -p "${TRIVY_CACHE_DIR:-$WORK_ROOT/trivy-cache}" \
    && CDPATH= cd -- "${TRIVY_CACHE_DIR:-$WORK_ROOT/trivy-cache}" \
    && pwd
)"
chmod 0700 "$TRIVY_CACHE_DIR"

fail() {
  echo "Image security scan failed: $1" >&2
  exit 1
}

cleanup() {
  rm -r "$RUN_DIR" >/dev/null 2>&1 || true
}
trap cleanup EXIT HUP INT TERM

run_trivy() {
  if command -v trivy >/dev/null 2>&1; then
    TMPDIR="$TRIVY_TEMP_DIR" trivy "$@"
    return
  fi

  command -v docker >/dev/null 2>&1 \
    || fail "neither trivy nor docker is available"
  docker run --rm \
    --user "$(id -u):$(id -g)" \
    --env TMPDIR="$TRIVY_TEMP_DIR" \
    --volume "$WORK_ROOT:$WORK_ROOT" \
    --volume "$TRIVY_CACHE_DIR:$TRIVY_CACHE_DIR" \
    --volume "$OUTPUT_DIR:$OUTPUT_DIR" \
    "$TRIVY_IMAGE" \
    "$@"
}

prepare_scanner() {
  minimum_free_kb="${TRIVY_MIN_FREE_KB:-5242880}"
  printf '%s\n' "$minimum_free_kb" | grep -Eq '^[0-9]+$' \
    || fail "TRIVY_MIN_FREE_KB must be a non-negative integer"
  available_kb="$(
    df -Pk "$WORK_ROOT" \
      | awk 'NR == 2 {print $4}'
  )"
  printf '%s\n' "$available_kb" | grep -Eq '^[0-9]+$' \
    || fail "could not determine workspace free space"
  [ "$available_kb" -ge "$minimum_free_kb" ] \
    || fail "workspace needs at least ${minimum_free_kb} KiB free for Trivy databases (available=${available_kb} KiB)"
  echo "Image security scanner disk preflight: PASS (available=${available_kb} KiB, required=${minimum_free_kb} KiB)"

  rm -f "$OUTPUT_DIR/scanner-readiness.txt"
  run_trivy image \
    --quiet \
    --cache-dir "$TRIVY_CACHE_DIR" \
    --download-db-only \
    || fail "Trivy vulnerability database prefetch failed"
  run_trivy image \
    --quiet \
    --cache-dir "$TRIVY_CACHE_DIR" \
    --download-java-db-only \
    || fail "Trivy Java database prefetch failed"
  printf 'scanner=trivy\nversion=%s\ndatabases=ready\navailableBeforePrefetchKilobytes=%s\nminimumFreeKilobytes=%s\n' \
    "$TRIVY_VERSION" "$available_kb" "$minimum_free_kb" \
    > "$OUTPUT_DIR/scanner-readiness.txt"
  chmod 0600 "$OUTPUT_DIR/scanner-readiness.txt"
  echo "Image security scanner readiness: PASS"
}

run_trivy_image() {
  input_file="$1"
  format="$2"
  output_file="$3"

  run_trivy image \
    --quiet \
    --cache-dir "$TRIVY_CACHE_DIR" \
    --scanners vuln \
    --format "$format" \
    --output "$output_file" \
    --input "$input_file"
}

case "$ACTION" in
  prepare)
    prepare_scanner
    exit 0
    ;;
  scan) ;;
  *)
    fail "action must be prepare or scan"
    ;;
esac

[ "${TRIVY_REQUIRE_PREPARED:-false}" != true ] \
  || grep -Fxq 'databases=ready' "$OUTPUT_DIR/scanner-readiness.txt" \
  || fail "Trivy databases were not prepared by the fail-fast readiness stage"

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
