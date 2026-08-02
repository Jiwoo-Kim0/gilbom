#!/bin/sh

set -u
umask 077

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
. "$PROJECT_DIR/infra/security/tool-versions.env"

ACTION="${1:-all}"
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
mkdir -p "$WORK_ROOT/trivy-cache"
chmod 0700 "$OUTPUT_DIR" "$WORK_ROOT"

fail() {
  echo "Source security verification failed: $1" >&2
  exit 1
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
  role="$1"
  format="$2"
  output="$3"
  case "$role" in
    backend) target="$PROJECT_DIR/backend"; container_target=/project/backend ;;
    frontend) target="$PROJECT_DIR/frontend"; container_target=/project/frontend ;;
    *) fail "unsupported dependency role: $role" ;;
  esac

  if command -v trivy >/dev/null 2>&1; then
    trivy fs \
      --quiet \
      --cache-dir "$WORK_ROOT/trivy-cache" \
      --scanners vuln \
      --skip-dirs "$PROJECT_DIR/backend/build" \
      --skip-dirs "$PROJECT_DIR/frontend/gilbom/node_modules" \
      --skip-dirs "$PROJECT_DIR/frontend/gilbom/dist" \
      --format "$format" \
      --output "$output" \
      "$target"
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
    --skip-dirs /project/backend/build \
    --skip-dirs /project/frontend/gilbom/node_modules \
    --skip-dirs /project/frontend/gilbom/dist \
    --format "$format" \
    --output "$output" \
    "$container_target"
}

reset_reports() {
  find "$OUTPUT_DIR" -mindepth 1 -maxdepth 1 -type f -delete
  {
    printf 'gitleaks=%s\n' "$GITLEAKS_VERSION"
    printf 'trivy=%s\n' "$TRIVY_VERSION"
    printf 'redocly=%s\n' "$REDOCLY_VERSION"
  } > "$OUTPUT_DIR/tool-versions.txt"
}

scan_history() {
  run_gitleaks_git
  status=$?
  case "$status" in
    0) ;;
    1) fail "Gitleaks detected a Secret in Git history" ;;
    *) fail "Gitleaks Git history scan failed (exit $status)" ;;
  esac
  echo "Git history Secret scan: PASS"
}

scan_workspace() {
  run_gitleaks_directory
  status=$?
  case "$status" in
    0) ;;
    1) fail "Gitleaks detected a Secret in the checkout workspace" ;;
    *) fail "Gitleaks workspace scan failed (exit $status)" ;;
  esac
  echo "Checkout workspace Secret scan: PASS"
}

lint_openapi() {
  if ! run_redocly > "$OUTPUT_DIR/redocly-lint.txt" 2>&1; then
    sed -n '1,160p' "$OUTPUT_DIR/redocly-lint.txt" >&2
    fail "Redocly reported an OpenAPI error"
  fi
  echo "OpenAPI lint: PASS"
}

scan_dependencies() {
  role="$1"
  run_trivy_fs "$role" json "$OUTPUT_DIR/$role-dependencies.trivy.json" \
    || fail "Trivy $role dependency scan failed"
  run_trivy_fs "$role" cyclonedx "$OUTPUT_DIR/$role-dependencies.sbom.cdx.json" \
    || fail "Trivy $role dependency SBOM generation failed"
  test -s "$OUTPUT_DIR/$role-dependencies.trivy.json" \
    || fail "Trivy $role dependency report is empty"
  test -s "$OUTPUT_DIR/$role-dependencies.sbom.cdx.json" \
    || fail "Trivy $role dependency SBOM is empty"
  echo "$role dependency scan: PASS"
}

enforce_dependency_policy() {
  for role in backend frontend
  do
    test -s "$OUTPUT_DIR/$role-dependencies.trivy.json" \
      || fail "missing $role dependency report"
  done
  python3 "$SCRIPT_DIR/security_policy.py" \
    --exceptions "$PROJECT_DIR/infra/security/vulnerability-exceptions.yaml" \
    --report "dependency=$OUTPUT_DIR/backend-dependencies.trivy.json" \
    --report "dependency=$OUTPUT_DIR/frontend-dependencies.trivy.json" \
    --output "$OUTPUT_DIR/dependency-policy-summary.json" \
    || fail "dependency vulnerability policy rejected a report"
  echo "Dependency vulnerability policy: PASS"
}

case "$ACTION" in
  reset)
    reset_reports
    ;;
  history)
    scan_history
    ;;
  workspace)
    scan_workspace
    ;;
  openapi)
    lint_openapi
    ;;
  backend-dependencies)
    scan_dependencies backend
    ;;
  frontend-dependencies)
    scan_dependencies frontend
    ;;
  dependency-policy)
    enforce_dependency_policy
    ;;
  all)
    reset_reports
    scan_history
    scan_workspace
    lint_openapi
    scan_dependencies backend
    scan_dependencies frontend
    enforce_dependency_policy
    echo "Source security verification: PASS"
    ;;
  *)
    fail "action must be reset, history, workspace, openapi, backend-dependencies, frontend-dependencies, dependency-policy, or all"
    ;;
esac

find "$OUTPUT_DIR" -mindepth 1 -maxdepth 1 -type f -exec chmod 0600 {} +
