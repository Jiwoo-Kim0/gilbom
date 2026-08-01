#!/bin/sh

set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
BACKEND_IMAGE="${BACKEND_IMAGE:-gilbom-backend:local}"
FRONTEND_IMAGE="${FRONTEND_IMAGE:-gilbom-frontend:local}"
IMAGE_VERIFY_MODE="${IMAGE_VERIFY_MODE:-full}"
IMAGE_SCANNER_MODE="${IMAGE_SCANNER_MODE:-auto}"
IMAGE_SECURITY_OUTPUT_DIR="$(
  CDPATH= cd -- "$PROJECT_DIR" \
    && mkdir -p "${IMAGE_SECURITY_OUTPUT_DIR:-.artifacts/image-security}" \
    && CDPATH= cd -- "${IMAGE_SECURITY_OUTPUT_DIR:-.artifacts/image-security}" \
    && pwd
)"
SECRET_SCAN_DIR="${SECRET_SCAN_DIR:-$PROJECT_DIR/secrets}"
CHECK_DIR="$(mktemp -d)"
SECRET_PATTERNS="$CHECK_DIR/secret-patterns"

fail() {
  echo "Production image verification failed: $1" >&2
  exit 1
}

cleanup() {
  rm -r "$CHECK_DIR" >/dev/null 2>&1 || true
}

trap cleanup EXIT HUP INT TERM
umask 077
: > "$SECRET_PATTERNS"

case "$IMAGE_VERIFY_MODE" in
  full|static|runtime|logs)
    ;;
  *)
    fail "IMAGE_VERIFY_MODE must be full, static, runtime, or logs"
    ;;
esac

case "$IMAGE_SCANNER_MODE" in
  auto|required|off)
    ;;
  *)
    fail "IMAGE_SCANNER_MODE must be auto, required, or off"
    ;;
esac

collect_secret_patterns() {
  directory="$1"
  [ -d "$directory" ] || return 0

  # PEM header/footer는 Secret 값이 아니다. 나머지 non-empty line은 모두
  # exact-match pattern으로 사용한다.
  find "$directory" \
    -type f \
    ! -name .gitignore \
    -size +0c \
    -exec awk '
    length($0) > 0 &&
    $0 !~ /^-----BEGIN [A-Z0-9 ]+-----$/ &&
    $0 !~ /^-----END [A-Z0-9 ]+-----$/ {
      print
    }
  ' {} + >> "$SECRET_PATTERNS"
}

collect_secret_patterns "$SECRET_SCAN_DIR"
LC_ALL=C sort -u "$SECRET_PATTERNS" \
  -o "$SECRET_PATTERNS"
if awk '
  length($0) < 8 {
    short_secret = 1
  }
  END {
    exit(short_secret ? 0 : 1)
  }
' "$SECRET_PATTERNS"; then
  fail "non-empty Secret material must be at least 8 characters"
fi

contains_exact_secret() {
  target="$1"
  [ -s "$SECRET_PATTERNS" ] \
    && LC_ALL=C grep -aFq -f "$SECRET_PATTERNS" "$target"
}

assert_non_root_user() {
  image="$1"
  role="$2"
  user="$(docker image inspect \
    --format '{{.Config.User}}' "$image")"
  case "$user" in
    ""|0|0:*|root|root:*)
      fail "$role image USER is root or unset"
      ;;
  esac
}

extract_layer_paths() {
  archive="$1"
  destination="$2"
  manifest="$CHECK_DIR/manifest.json"
  layers="$CHECK_DIR/layers"

  tar -xOf "$archive" manifest.json > "$manifest"
  sed -n 's/.*"Layers":\[\([^]]*\)\].*/\1/p' "$manifest" \
    | tr ',' '\n' \
    | sed \
        -e 's/^[[:space:]]*"//' \
        -e 's/"[[:space:]]*$//' \
    > "$layers"
  [ -s "$layers" ] \
    || fail "could not enumerate docker save layers"

  : > "$destination"
  while IFS= read -r layer
  do
    tar -xOf "$archive" "$layer" \
      | tar -tf - >> "$destination"
  done < "$layers"
}

verify_static_image() {
  image="$1"
  role="$2"
  archive="$CHECK_DIR/$role-image.tar"
  history="$CHECK_DIR/$role-history.txt"
  inspect="$CHECK_DIR/$role-inspect.json"
  layer_paths="$CHECK_DIR/$role-layer-paths.txt"

  docker image inspect "$image" > "$inspect" \
    || fail "$role image is unavailable: $image"
  assert_non_root_user "$image" "$role"

  docker history \
    --no-trunc \
    --format '{{.CreatedBy}}' \
    "$image" > "$history"
  if grep -Eiq \
    '(^|[[:space:]])(ARG|ENV)[[:space:]].*(PASSWORD|SECRET|TOKEN|API[_-]?KEY|SIGNING[_-]?KEY|PRIVATE[_-]?KEY)' \
    "$history"; then
    fail "Secret-like ARG/ENV exists in $role image history"
  fi
  if grep -Eiq \
    '(^|[/[:space:]])\.env($|[./[:space:]])|(^|[/[:space:]])secrets?/' \
    "$history"; then
    fail ".env or secrets path exists in $role image history"
  fi
  if contains_exact_secret "$history" \
      || contains_exact_secret "$inspect"; then
    fail "exact Secret material exists in $role image metadata"
  fi

  docker save --output "$archive" "$image"
  if contains_exact_secret "$archive"; then
    fail "exact Secret material exists in docker save for $role"
  fi

  extract_layer_paths "$archive" "$layer_paths"
  if grep -Eq '(^|/)\.env($|\.)' "$layer_paths"; then
    fail ".env file exists in a $role runtime layer"
  fi
  if grep -Eq '(^|/)secrets?(/|$)' "$layer_paths"; then
    fail "secrets directory exists in a $role runtime layer"
  fi
}

run_runtime_checks() {
  sh "$SCRIPT_DIR/verify-backend-runtime-image.sh" \
    "$BACKEND_IMAGE"
  sh "$SCRIPT_DIR/verify-frontend-runtime-image.sh" \
    "$FRONTEND_IMAGE"
}

probe_and_scan_logs() {
  backend_container="${BACKEND_CONTAINER:-}"
  frontend_container="${FRONTEND_CONTAINER:-}"
  [ -n "$backend_container" ] \
    || fail "BACKEND_CONTAINER is required for log verification"
  [ -n "$frontend_container" ] \
    || fail "FRONTEND_CONTAINER is required for log verification"

  for container in "$backend_container" "$frontend_container"
  do
    [ "$(docker inspect \
      --format '{{.State.Running}}' "$container")" = true ] \
      || fail "log target container is not running"
  done

  authorization_canary="day7-auth-$(openssl rand -hex 16)"
  prompt_canary="day7-prompt-$(openssl rand -hex 16)"
  response_canary="day7-response-$(openssl rand -hex 16)"
  {
    printf '%s\n' "$authorization_canary"
    printf '%s\n' "$prompt_canary"
    printf '%s\n' "$response_canary"
  } >> "$SECRET_PATTERNS"

  frontend_probe_url=http://127.0.0.1:8080/healthz
  frontend_probe_tls=false
  if docker exec "$frontend_container" \
      grep -Fq "listen 8443 ssl;" /tmp/nginx-conf.d/site.conf; then
    frontend_probe_url=https://127.0.0.1:8443/api/health
    frontend_probe_tls=true
  fi

  # 공개 health에 민감 header와 body canary를 보내 request logging leakage를
  # 탐지한다. 4xx 응답은 목적상 정상이며 canary 값은 출력하지 않는다.
  set -- wget --quiet --spider
  [ "$frontend_probe_tls" != true ] \
    || set -- "$@" --no-check-certificate
  set -- \
    "$@" \
    --header "Authorization: Bearer $authorization_canary" \
    "$frontend_probe_url"
  docker exec "$frontend_container" "$@" \
      >/dev/null 2>&1 \
    || fail "could not execute Authorization log canary request"

  set -- wget --quiet --output-document=/dev/null
  [ "$frontend_probe_tls" != true ] \
    || set -- "$@" --no-check-certificate
  set -- \
    "$@" \
    --header 'Content-Type: application/json' \
    --post-data \
    "{\"prompt\":\"$prompt_canary\",\"llmResponse\":\"$response_canary\"}" \
    "$frontend_probe_url"
  docker exec "$frontend_container" "$@" \
      >/dev/null 2>&1 || true

  for entry in \
    "backend:$backend_container" \
    "frontend:$frontend_container"
  do
    role="${entry%%:*}"
    container="${entry#*:}"
    log_file="$CHECK_DIR/$role-container.log"
    docker logs "$container" > "$log_file" 2>&1

    if contains_exact_secret "$log_file"; then
      fail "Secret or request canary exists in $role container logs"
    fi
    if grep -Eiq \
      'authorization([_ -]*header)?[=:][[:space:]]*(bearer|basic)|bearer[[:space:]][A-Za-z0-9._~+/-]{12,}|(system|user)[_ -]*prompt[=:]|prompt(Input|Body|Raw)[=:]|llm[_ -]*(request|response)[_ -]*(body|content|raw)?[=:]|(x-api-key|api[_ -]*key)[=:]' \
      "$log_file"; then
      fail "sensitive header, prompt, response, or API key structure exists in $role logs"
    fi
  done
}

run_security_scanner() {
  [ "$IMAGE_SCANNER_MODE" != off ] || return 0
  status_file="$IMAGE_SECURITY_OUTPUT_DIR/scanner-status.txt"
  rm -f \
    "$status_file" \
    "$IMAGE_SECURITY_OUTPUT_DIR/backend.sbom.json" \
    "$IMAGE_SECURITY_OUTPUT_DIR/backend.vulnerabilities.txt" \
    "$IMAGE_SECURITY_OUTPUT_DIR/backend.critical-fixable.txt" \
    "$IMAGE_SECURITY_OUTPUT_DIR/frontend.sbom.json" \
    "$IMAGE_SECURITY_OUTPUT_DIR/frontend.vulnerabilities.txt" \
    "$IMAGE_SECURITY_OUTPUT_DIR/frontend.critical-fixable.txt"
  if ! docker scout version >/dev/null 2>&1; then
    printf 'scanner=unavailable\n' > "$status_file"
    [ "$IMAGE_SCANNER_MODE" != required ] \
      || fail "Docker Scout is required but unavailable"
    echo "Production image verification: Docker Scout unavailable (recorded, not enforced)"
    return 0
  fi

  printf 'scanner=docker-scout\n' > "$status_file"
  for entry in \
    "backend:$BACKEND_IMAGE" \
    "frontend:$FRONTEND_IMAGE"
  do
    role="${entry%%:*}"
    image="${entry#*:}"
    sbom="$IMAGE_SECURITY_OUTPUT_DIR/$role.sbom.json"
    vulnerabilities="$IMAGE_SECURITY_OUTPUT_DIR/$role.vulnerabilities.txt"
    critical_fixable="$IMAGE_SECURITY_OUTPUT_DIR/$role.critical-fixable.txt"

    docker scout cves \
      --format sbom \
      --output "$sbom" \
      "local://$image" >/dev/null \
      || fail "SBOM generation failed for $role"
    [ -s "$sbom" ] \
      || fail "SBOM output is empty for $role"

    docker scout cves \
      --output "$vulnerabilities" \
      "local://$image" >/dev/null \
      || fail "vulnerability report generation failed for $role"
    [ -s "$vulnerabilities" ] \
      || fail "vulnerability report is empty for $role"

    if ! docker scout cves \
      --only-severity critical \
      --only-fixed \
      --exit-code \
      --output "$critical_fixable" \
      "local://$image" >/dev/null; then
      fail "fixable Critical vulnerability detected in $role image"
    fi
    [ -s "$critical_fixable" ] \
      || fail "Critical vulnerability gate report is empty for $role"
  done
}

case "$IMAGE_VERIFY_MODE" in
  full)
    verify_static_image "$BACKEND_IMAGE" backend
    verify_static_image "$FRONTEND_IMAGE" frontend
    run_runtime_checks
    run_security_scanner
    if [ -n "${BACKEND_CONTAINER:-}" ] \
        || [ -n "${FRONTEND_CONTAINER:-}" ]; then
      probe_and_scan_logs
    fi
    ;;
  static)
    verify_static_image "$BACKEND_IMAGE" backend
    verify_static_image "$FRONTEND_IMAGE" frontend
    run_security_scanner
    ;;
  runtime)
    run_runtime_checks
    ;;
  logs)
    probe_and_scan_logs
    ;;
esac

echo "Production image verification: PASS (mode=$IMAGE_VERIFY_MODE)"
