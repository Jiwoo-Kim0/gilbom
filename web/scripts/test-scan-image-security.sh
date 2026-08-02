#!/bin/sh

set -eu

if [ "$(basename -- "$0")" = trivy ]; then
  case "${TMPDIR:-}" in
    "$EXPECTED_WORK_ROOT"/run.*/trivy-tmp) ;;
    *)
      echo "Trivy TMPDIR escaped the workspace work root: ${TMPDIR:-unset}" >&2
      exit 1
      ;;
  esac
  [ -d "$TMPDIR" ]

  arguments=" $* "
  case "$arguments" in
    *" --cache-dir $EXPECTED_WORK_ROOT/trivy-cache "*) ;;
    *)
      echo "Trivy cache did not use the workspace work root" >&2
      exit 1
      ;;
  esac
  printf '%s\n' "$*" >> "$TRIVY_CALL_LOG"
  exit 0
fi

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/gilbom-image-security.XXXXXX")"
trap 'rm -rf -- "$TEST_ROOT"' EXIT HUP INT TERM

PROJECT_DIR="$TEST_ROOT/project"
FAKE_BIN="$TEST_ROOT/bin"
EXPECTED_WORK_ROOT="$PROJECT_DIR/.artifacts/image-security-work"
EXPECTED_OUTPUT_DIR="$PROJECT_DIR/.artifacts/image-security"
TRIVY_CALL_LOG="$TEST_ROOT/trivy-calls.txt"
export EXPECTED_WORK_ROOT EXPECTED_OUTPUT_DIR TRIVY_CALL_LOG

mkdir -p \
  "$PROJECT_DIR/scripts" \
  "$PROJECT_DIR/infra/security" \
  "$FAKE_BIN"
cp "$SCRIPT_DIR/scan-image-security.sh" "$PROJECT_DIR/scripts/"
cp "$SCRIPT_DIR/../infra/security/tool-versions.env" \
  "$PROJECT_DIR/infra/security/"
cp "$SCRIPT_DIR/test-scan-image-security.sh" "$FAKE_BIN/trivy"
chmod 0700 "$FAKE_BIN/trivy"

RESOLVED_TRIVY="$(PATH="$FAKE_BIN:$PATH" command -v trivy)"
[ "$RESOLVED_TRIVY" = "$FAKE_BIN/trivy" ] || {
  echo "Image security regression test did not resolve the fake trivy command" >&2
  exit 1
}

PATH="$FAKE_BIN:$PATH" \
IMAGE_SECURITY_OUTPUT_DIR="$EXPECTED_OUTPUT_DIR" \
IMAGE_SECURITY_WORK_ROOT="$EXPECTED_WORK_ROOT" \
TRIVY_MIN_FREE_KB=0 \
  sh "$PROJECT_DIR/scripts/scan-image-security.sh" prepare

[ "$(wc -l < "$TRIVY_CALL_LOG")" -eq 2 ]
grep -Fq -- '--download-db-only' "$TRIVY_CALL_LOG"
grep -Fq -- '--download-java-db-only' "$TRIVY_CALL_LOG"
grep -Fxq 'scanner=trivy' "$EXPECTED_OUTPUT_DIR/scanner-readiness.txt"
grep -Fxq 'databases=ready' "$EXPECTED_OUTPUT_DIR/scanner-readiness.txt"
grep -Fxq 'minimumFreeKilobytes=0' \
  "$EXPECTED_OUTPUT_DIR/scanner-readiness.txt"

MISSING_READINESS_OUTPUT="$PROJECT_DIR/.artifacts/missing-readiness"
if PATH="$FAKE_BIN:$PATH" \
    IMAGE_SECURITY_OUTPUT_DIR="$MISSING_READINESS_OUTPUT" \
    IMAGE_SECURITY_WORK_ROOT="$EXPECTED_WORK_ROOT" \
    TRIVY_REQUIRE_PREPARED=true \
      sh "$PROJECT_DIR/scripts/scan-image-security.sh" scan \
        >"$TEST_ROOT/missing-readiness.stdout" \
        2>"$TEST_ROOT/missing-readiness.stderr"; then
  echo "Image scan continued without fail-fast readiness evidence" >&2
  exit 1
fi
grep -Fq \
  'Trivy databases were not prepared by the fail-fast readiness stage' \
  "$TEST_ROOT/missing-readiness.stderr"

echo "Image security workspace TMPDIR and database readiness test: PASS"
