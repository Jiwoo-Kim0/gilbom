#!/bin/sh

set -u
umask 077

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
. "$PROJECT_DIR/infra/security/tool-versions.env"

OUTPUT_DIR="$(
  CDPATH= cd -- "$PROJECT_DIR" \
    && mkdir -p "${SOURCE_SECURITY_OUTPUT_DIR:-.artifacts/source-security}" \
    && CDPATH= cd -- "${SOURCE_SECURITY_OUTPUT_DIR:-.artifacts/source-security}" \
    && pwd
)"
WORK_ROOT="$(
  CDPATH= cd -- "$PROJECT_DIR" \
    && mkdir -p "${SECURITY_WORK_ROOT:-.artifacts/security-work}" \
    && CDPATH= cd -- "${SECURITY_WORK_ROOT:-.artifacts/security-work}" \
    && pwd
)"
REPOSITORY_ROOT="$(git -C "$PROJECT_DIR" rev-parse --show-toplevel)"
PROJECT_RELATIVE="$(git -C "$PROJECT_DIR" rev-parse --show-prefix | sed 's#/$##')"
[ -n "$PROJECT_RELATIVE" ] || PROJECT_RELATIVE=.
chmod 0700 "$OUTPUT_DIR" "$WORK_ROOT"
mkdir -p "$WORK_ROOT/trivy-cache"

failures=0

record_failure() {
  echo "Source security verification failed: $1" >&2
  failures=$((failures + 1))
}

run_gitleaks_git() {
  report="$OUTPUT_DIR/gitleaks-history.json"
  log_options=--all
  [ "$PROJECT_RELATIVE" = . ] \
    || log_options="--all -- $PROJECT_RELATIVE"

  if command -v gitleaks >/dev/null 2>&1; then
    (
      cd "$REPOSITORY_ROOT" \
        && gitleaks git \
          --config "$PROJECT_DIR/.gitleaks.toml" \
          --redact \
          --report-format json \
          --report-path "$report" \
          --log-opts "$log_options" \
          "$REPOSITORY_ROOT"
    )
    return
  fi

  docker run --rm \
    --user "$(id -u):$(id -g)" \
    --volume "$REPOSITORY_ROOT:/repo:ro" \
    --volume "$OUTPUT_DIR:/out" \
    --workdir /repo \
    "$GITLEAKS_IMAGE" \
    git \
    --config "/repo/$PROJECT_RELATIVE/.gitleaks.toml" \
    --redact \
    --report-format json \
    --report-path /out/gitleaks-history.json \
    --log-opts "$log_options" \
    /repo
}

run_gitleaks_directory() {
  report="$OUTPUT_DIR/gitleaks-workspace.json"
  if command -v gitleaks >/dev/null 2>&1; then
    gitleaks dir \
      --config "$PROJECT_DIR/.gitleaks.toml" \
      --redact \
      --report-format json \
      --report-path "$report" \
      "$PROJECT_DIR"
    return
  fi

  docker run --rm \
    --user "$(id -u):$(id -g)" \
    --volume "$PROJECT_DIR:/project:ro" \
    --volume "$OUTPUT_DIR:/out" \
    "$GITLEAKS_IMAGE" \
    dir \
    --config /project/.gitleaks.toml \
    --redact \
    --report-format json \
    --report-path /out/gitleaks-workspace.json \
    /project
}

run_redocly() {
  if command -v redocly >/dev/null 2>&1; then
    redocly lint \
      --config "$PROJECT_DIR/redocly.yaml" \
      "$PROJECT_DIR/docs/openapi-v1.yaml"
    return
  fi

  docker run --rm \
    --user "$(id -u):$(id -g)" \
    --volume "$PROJECT_DIR:/project:ro" \
    --workdir /project \
    "$REDOCLY_IMAGE" \
    lint \
    --config redocly.yaml \
    docs/openapi-v1.yaml
}

run_trivy_fs() {
  format="$1"
  output="$2"
  if command -v trivy >/dev/null 2>&1; then
    trivy fs \
      --quiet \
      --cache-dir "$WORK_ROOT/trivy-cache" \
      --scanners vuln \
      --skip-dirs "$PROJECT_DIR/.artifacts" \
      --skip-dirs "$PROJECT_DIR/backend/build" \
      --skip-dirs "$PROJECT_DIR/frontend/gilbom/node_modules" \
      --skip-dirs "$PROJECT_DIR/frontend/gilbom/dist" \
      --format "$format" \
      --output "$output" \
      "$PROJECT_DIR"
    return
  fi

  docker run --rm \
    --user "$(id -u):$(id -g)" \
    --volume "$PROJECT_DIR:/project:ro" \
    --volume "$WORK_ROOT:$WORK_ROOT" \
    --volume "$OUTPUT_DIR:$OUTPUT_DIR" \
    "$TRIVY_IMAGE" \
    fs \
    --quiet \
    --cache-dir "$WORK_ROOT/trivy-cache" \
    --scanners vuln \
    --skip-dirs /project/.artifacts \
    --skip-dirs /project/backend/build \
    --skip-dirs /project/frontend/gilbom/node_modules \
    --skip-dirs /project/frontend/gilbom/dist \
    --format "$format" \
    --output "$output" \
    /project
}

rm -f "$OUTPUT_DIR"/*

run_gitleaks_git
gitleaks_status=$?
case "$gitleaks_status" in
  0) ;;
  1) record_failure "Gitleaks detected a Secret in Git history" ;;
  *) record_failure "Gitleaks Git history scan failed (exit $gitleaks_status)" ;;
esac

run_gitleaks_directory
gitleaks_status=$?
case "$gitleaks_status" in
  0) ;;
  1) record_failure "Gitleaks detected a Secret in the source workspace" ;;
  *) record_failure "Gitleaks workspace scan failed (exit $gitleaks_status)" ;;
esac

if ! run_redocly > "$OUTPUT_DIR/redocly-lint.txt" 2>&1; then
  sed -n '1,160p' "$OUTPUT_DIR/redocly-lint.txt" >&2
  record_failure "Redocly reported an OpenAPI error"
fi

run_trivy_fs json "$OUTPUT_DIR/dependencies.trivy.json" \
  || record_failure "Trivy dependency scan failed"
run_trivy_fs cyclonedx "$OUTPUT_DIR/dependencies.sbom.cdx.json" \
  || record_failure "Trivy dependency SBOM generation failed"

if [ -s "$OUTPUT_DIR/dependencies.trivy.json" ]; then
  python3 "$SCRIPT_DIR/security_policy.py" \
    --exceptions "$PROJECT_DIR/infra/security/vulnerability-exceptions.yaml" \
    --report "dependency=$OUTPUT_DIR/dependencies.trivy.json" \
    --output "$OUTPUT_DIR/dependency-policy-summary.json" \
    || record_failure "dependency vulnerability policy rejected the report"
else
  record_failure "Trivy dependency report is empty"
fi

{
  printf 'gitleaks=%s\n' "$GITLEAKS_VERSION"
  printf 'trivy=%s\n' "$TRIVY_VERSION"
  printf 'redocly=%s\n' "$REDOCLY_VERSION"
  printf 'failures=%s\n' "$failures"
} > "$OUTPUT_DIR/tool-status.txt"
chmod 0600 "$OUTPUT_DIR"/*

if [ "$failures" -ne 0 ]; then
  echo "Source security verification: FAIL ($failures gate(s))" >&2
  exit 1
fi

echo "Source security verification: PASS"
