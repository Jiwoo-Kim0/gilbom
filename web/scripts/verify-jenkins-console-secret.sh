#!/bin/sh

set -eu
set +x

PROJECT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
OUTPUT_DIR="${JENKINS_LOG_SECURITY_OUTPUT_DIR:-$PROJECT_DIR/.artifacts/jenkins-log-security}"
SECRET_DIR="${SECRET_SCAN_DIR:-$PROJECT_DIR/secrets}"
GITLEAKS_CONFIG="$PROJECT_DIR/.gitleaks.toml"

fail() {
  echo "Jenkins console Secret scan failed: $1" >&2
  exit 1
}

resolve_log_path() {
  if [ -n "${JENKINS_LOG_PATH:-}" ]; then
    printf '%s\n' "$JENKINS_LOG_PATH"
    return
  fi

  [ -n "${JENKINS_HOME:-}" ] \
    || fail "JENKINS_HOME is unavailable"
  [ -n "${JOB_NAME:-}" ] \
    || fail "JOB_NAME is unavailable"
  printf '%s\n' "$BUILD_NUMBER" | grep -Eq '^[1-9][0-9]*$' \
    || fail "BUILD_NUMBER is invalid"
  printf '%s\n' "$JOB_NAME" \
    | grep -Eq '^[A-Za-z0-9._ -]+(/[A-Za-z0-9._ -]+)*$' \
    || fail "JOB_NAME contains an unsafe path component"

  job_root="$JENKINS_HOME"
  old_ifs="$IFS"
  IFS=/
  # JOB_NAME의 각 folder/job component는 Jenkins home에서 jobs/<name>으로
  # 중첩된다. shell glob이나 평가를 사용하지 않는다.
  set -- $JOB_NAME
  IFS="$old_ifs"
  for component in "$@"
  do
    [ "$component" != . ] && [ "$component" != .. ] \
      || fail "JOB_NAME contains a traversal component"
    job_root="$job_root/jobs/$component"
  done
  printf '%s/builds/%s/log\n' "$job_root" "$BUILD_NUMBER"
}

command -v gitleaks >/dev/null 2>&1 \
  || fail "Gitleaks is unavailable"
[ -f "$GITLEAKS_CONFIG" ] && [ ! -L "$GITLEAKS_CONFIG" ] \
  || fail "Gitleaks configuration is missing or symlinked"

log_path="$(resolve_log_path)"
[ -f "$log_path" ] && [ ! -L "$log_path" ] && [ -r "$log_path" ] \
  || fail "current Jenkins console log is unavailable or symlinked"

mkdir -p "$OUTPUT_DIR"
report="$OUTPUT_DIR/gitleaks-jenkins-console.json"
if gitleaks dir \
    --no-banner \
    --config "$GITLEAKS_CONFIG" \
    --redact \
    --report-format json \
    --report-path "$report" \
    "$log_path"
then
  :
else
  status=$?
  case "$status" in
    1) fail "Gitleaks detected a Secret in the Jenkins console" ;;
    *) fail "Gitleaks could not scan the Jenkins console (exit $status)" ;;
  esac
fi

patterns="$OUTPUT_DIR/.exact-ci-secret-patterns"
trap 'rm -f -- "$patterns"' EXIT HUP INT TERM
: > "$patterns"
if [ -d "$SECRET_DIR" ] && [ ! -L "$SECRET_DIR" ]; then
  find "$SECRET_DIR" -type f ! -name .gitignore -size +0c -exec awk '
    length($0) >= 8 &&
    $0 !~ /^-----BEGIN [A-Z0-9 ]+-----$/ &&
    $0 !~ /^-----END [A-Z0-9 ]+-----$/ {
      print
    }
  ' {} + | LC_ALL=C sort -u > "$patterns"
fi
if [ -s "$patterns" ] && grep -aFq -f "$patterns" "$log_path"; then
  fail "exact ephemeral CI Secret material exists in the Jenkins console"
fi
rm -f -- "$patterns"
trap - EXIT HUP INT TERM

printf 'PASS\n' > "$OUTPUT_DIR/status"
echo "Jenkins console Secret scan: PASS"
