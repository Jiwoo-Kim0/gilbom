#!/bin/sh

set -eu
set +x
umask 077

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
PROD_COMPOSE="$PROJECT_DIR/compose.prod.yaml"
SMOKE_COMPOSE="$PROJECT_DIR/compose.smoke.yaml"
RESILIENCE_COMPOSE="$PROJECT_DIR/compose.resilience.yaml"
FIXTURE_SQL="$SCRIPT_DIR/fixtures/container-report-smoke.sql"
CLIENT="$SCRIPT_DIR/container-report-smoke-client.py"
PROJECT_NAME="gilbom-report-smoke-$(date +%s)-$$"
DOCKER_BIND_WORK_ROOT="${DOCKER_BIND_WORK_ROOT:-.artifacts/docker-bind-work}"
case "$DOCKER_BIND_WORK_ROOT" in
  /*)
    ;;
  *)
    DOCKER_BIND_WORK_ROOT="$PROJECT_DIR/$DOCKER_BIND_WORK_ROOT"
    ;;
esac
mkdir -p "$DOCKER_BIND_WORK_ROOT"
chmod 0700 "$DOCKER_BIND_WORK_ROOT"
DOCKER_BIND_WORK_ROOT="$(CDPATH= cd -- "$DOCKER_BIND_WORK_ROOT" && pwd -P)"
CHECK_DIR="$(mktemp -d "$DOCKER_BIND_WORK_ROOT/container-report.XXXXXX")"
SECRET_DIR="$CHECK_DIR/secrets"
CERTBOT_DIR="$CHECK_DIR/certbot"
ENV_FILE="$CHECK_DIR/.env.smoke"
STATE_FILE="$CHECK_DIR/report-state.json"
SMOKE_PUBLISHED_HOST="${REPORT_SMOKE_PUBLISHED_HOST:-127.0.0.1}"
SMOKE_FRONTEND_BIND_ADDRESS="${REPORT_SMOKE_FRONTEND_BIND_ADDRESS:-127.0.0.1}"
STARTED=false

compose() {
  if [ "${REPORT_SMOKE_RESILIENCE:-false}" = true ]; then
    docker compose \
      --project-name "$PROJECT_NAME" \
      --env-file "$ENV_FILE" \
      -f "$PROD_COMPOSE" \
      -f "$SMOKE_COMPOSE" \
      -f "$RESILIENCE_COMPOSE" \
      "$@"
  else
    docker compose \
      --project-name "$PROJECT_NAME" \
      --env-file "$ENV_FILE" \
      -f "$PROD_COMPOSE" \
      -f "$SMOKE_COMPOSE" \
      "$@"
  fi
}

cleanup() {
  if [ "$STARTED" = true ]; then
    compose down --volumes --remove-orphans >/dev/null 2>&1 || true
  fi
  rm -r "$CHECK_DIR" >/dev/null 2>&1 || true
}

fail() {
  echo "Container report smoke failed: $1" >&2
  if [ "$STARTED" = true ]; then
    compose ps >&2 || true
    compose logs --tail 80 db mosquitto backend frontend >&2 || true
  fi
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 \
    || fail "required command is unavailable: $1"
}

db_admin_query() {
  sql="$1"
  compose exec -T db sh -ec '
    export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"
    psql \
      --host 127.0.0.1 \
      --username "$POSTGRES_USER" \
      --dbname "$POSTGRES_DB" \
      --no-password \
      --tuples-only \
      --no-align \
      --set ON_ERROR_STOP=1 \
      --command "$1"
  ' sh "$sql"
}

wait_for_health() {
  service="$1"
  deadline=$(( $(date +%s) + 180 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    container_id="$(compose ps -q "$service")"
    if [ -n "$container_id" ]; then
      status="$(
        docker inspect \
          --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' \
          "$container_id" 2>/dev/null || true
      )"
      [ "$status" = healthy ] && return 0
      [ "$status" != unhealthy ] || return 1
    fi
    sleep 2
  done
  return 1
}

refresh_base_url() {
  frontend_binding="$(compose port frontend 8080)"
  [ -n "$frontend_binding" ] || fail "Frontend host binding is unavailable"
  frontend_port="${frontend_binding##*:}"
  case "$frontend_port" in
    ""|*[!0-9]*)
      fail "Frontend host binding has an invalid port"
      ;;
  esac
  BASE_URL="http://$SMOKE_PUBLISHED_HOST:$frontend_port"
}

write_environment() {
  llm_enabled="$1"
  llm_key_file="$2"
  llm_model="$3"
  llm_budget="$4"
  cat > "$ENV_FILE" <<EOF
COMPOSE_PROJECT_NAME=$PROJECT_NAME
SPRING_PROFILES_ACTIVE=ec2,docker
SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE=20s
GILBOM_HEALTH_READINESS_COMPONENTS=readinessState,db,flywayReadiness,storageReadiness
TZ=UTC

FRONTEND_BIND_ADDRESS=$SMOKE_FRONTEND_BIND_ADDRESS
FRONTEND_HTTP_PORT=0
FRONTEND_HTTPS_PORT=0
GILBOM_NGINX_TLS_MODE=off
MQTT_BIND_ADDRESS=127.0.0.1
MQTT_TLS_PORT=0

BACKEND_IMAGE=${BACKEND_IMAGE:-gilbom-backend:local}
FRONTEND_IMAGE=${FRONTEND_IMAGE:-gilbom-frontend:local}

CERTBOT_DOMAIN=report-smoke.example.invalid
CERTBOT_EMAIL=report-smoke@example.invalid
CERTBOT_CONF_DIR=$CERTBOT_DIR/conf
CERTBOT_WWW_DIR=$CERTBOT_DIR/www
NGINX_CERT_DIR=$CERTBOT_DIR/nginx
NGINX_CERT_GID=101
CERTBOT_CPUS=0.5
CERTBOT_MEMORY_LIMIT=256m
CERTBOT_PIDS_LIMIT=128

POSTGRES_DB=gilbom_report_smoke
POSTGRES_ADMIN_USER=gilbom_db_admin
POSTGRES_ADMIN_PASSWORD_SECRET_FILE=$SECRET_DIR/postgres_admin_password
POSTGRES_FLYWAY_USER=gilbom_flyway
POSTGRES_FLYWAY_PASSWORD_SECRET_FILE=$SECRET_DIR/postgres_flyway_password
POSTGRES_APP_USER=gilbom_app
POSTGRES_APP_PASSWORD_SECRET_FILE=$SECRET_DIR/postgres_app_password

MQTT_CLIENT_ID=gilbom-report-smoke-backend
MQTT_USERNAME=gilbom
MQTT_PASSWORD_SECRET_FILE=$SECRET_DIR/mqtt_backend_password
MQTT_CA_CERTIFICATE_FILE=$SECRET_DIR/mqtt_ca_certificate.pem
MQTT_TLS_SERVER_NAME=mosquitto
MQTT_PUBLIC_BROKER_URI=ssl://mqtt.example.invalid:8883
MQTT_CREDENTIAL_PROVISIONING_ENABLED=false
MQTT_CONNECT_COMPLETION_TIMEOUT=10s
MQTT_DISCONNECT_COMPLETION_TIMEOUT=5s
MOSQUITTO_PASSWORD_FILE_SECRET_FILE=$SECRET_DIR/mosquitto_passwd
MOSQUITTO_TLS_FULLCHAIN_FILE=$SECRET_DIR/mosquitto_tls_fullchain.pem
MOSQUITTO_TLS_PRIVATE_KEY_SECRET_FILE=$SECRET_DIR/mosquitto_tls_private_key.pem

GILBOM_STORAGE_UPLOAD_URL=https://report-smoke.example.invalid/api/v1/images
GILBOM_STORAGE_SIGNING_KEY_SECRET_FILE=$SECRET_DIR/gilbom_storage_signing_key
AUTH_RATE_LIMIT_HMAC_KEY_VERSION=1
AUTH_RATE_LIMIT_HMAC_KEY_SECRET_FILE=$SECRET_DIR/gilbom_auth_rate_limit_hmac_key
MFA_TOTP_SECRET_ENCRYPTION_ENABLED=true
MFA_TOTP_SECRET_ACTIVE_KEY_VERSION=1
MFA_TOTP_KEYRING_SECRET_FILE=$SECRET_DIR/gilbom_mfa_totp_keyring

INITIAL_ADMIN_BOOTSTRAP_ENABLED=true
INITIAL_ADMIN_EMAIL=report-smoke-author@example.test
INITIAL_ADMIN_PASSWORD_SECRET_FILE=$SECRET_DIR/gilbom_initial_admin_password
INITIAL_ADMIN_DISPLAY_NAME=보고서 Smoke 작성자
INITIAL_ADMIN_USER_CODE=REPORT-SMOKE-AUTHOR

GILBOM_LLM_ENABLED=$llm_enabled
GILBOM_LLM_PROVIDER=GMS
GILBOM_LLM_BASE_URL=https://gms.ssafy.io/gmsapi/api.openai.com/v1
GILBOM_LLM_MODEL=$llm_model
GILBOM_LLM_API_KEY_SECRET_FILE=$llm_key_file
GILBOM_LLM_CONNECT_TIMEOUT=3s
GILBOM_LLM_READ_TIMEOUT=${GILBOM_LLM_READ_TIMEOUT:-30s}
GILBOM_LLM_MAX_OUTPUT_TOKENS=${GILBOM_LLM_MAX_OUTPUT_TOKENS:-600}
GILBOM_LLM_DAILY_REQUEST_LIMIT=1
GILBOM_LLM_DAILY_BUDGET=$llm_budget
GILBOM_LLM_MAX_CONCURRENT_REQUESTS=1
GILBOM_LLM_PROMPT_VERSION=report-v1
GILBOM_LLM_POLICY_APPROVED=${GILBOM_LLM_POLICY_APPROVED:-false}
GILBOM_LLM_TRAINING_EXCLUSION_APPROVED=${GILBOM_LLM_TRAINING_EXCLUSION_APPROVED:-false}
GILBOM_LLM_DATA_RETENTION_APPROVED=${GILBOM_LLM_DATA_RETENTION_APPROVED:-false}
GILBOM_LLM_OUTBOUND_ALLOWLIST_APPLIED=${GILBOM_LLM_OUTBOUND_ALLOWLIST_APPLIED:-false}
GILBOM_LLM_IMAGE_INPUT_ENABLED=false
GILBOM_LLM_IMAGE_DEIDENTIFICATION_APPROVED=false

GILBOM_CORS_ENABLED=false
GILBOM_CORS_ALLOWED_ORIGINS=
GILBOM_CORS_ALLOW_CREDENTIALS=true
VITE_NAVER_MAP_CLIENT_ID=report-smoke-public-id

POSTGRES_CPUS=2.0
POSTGRES_MEMORY_LIMIT=2g
POSTGRES_MEMORY_RESERVATION=256m
POSTGRES_PIDS_LIMIT=256
POSTGRES_SHM_SIZE=256m
BACKEND_CPUS=2.0
BACKEND_MEMORY_LIMIT=2g
BACKEND_MEMORY_RESERVATION=256m
BACKEND_PIDS_LIMIT=256
FRONTEND_CPUS=0.5
FRONTEND_MEMORY_LIMIT=128m
FRONTEND_MEMORY_RESERVATION=32m
FRONTEND_PIDS_LIMIT=64
MQTT_CPUS=1.0
MQTT_MEMORY_LIMIT=256m
MQTT_MEMORY_RESERVATION=64m
MQTT_PIDS_LIMIT=128
MQTT_INIT_CPUS=0.25
MQTT_INIT_MEMORY_LIMIT=64m
MQTT_INIT_PIDS_LIMIT=32
LOG_MAX_SIZE=10m
LOG_MAX_FILES=3
EOF
  chmod 0600 "$ENV_FILE"
}

assert_canary_authorized() {
  [ "${REPORT_SMOKE_GMS_CANARY:-false}" = true ] || return 1
  for approval in \
    GILBOM_LLM_POLICY_APPROVED \
    GILBOM_LLM_TRAINING_EXCLUSION_APPROVED \
    GILBOM_LLM_DATA_RETENTION_APPROVED \
    GILBOM_LLM_OUTBOUND_ALLOWLIST_APPLIED
  do
    eval "approval_value=\${$approval:-false}"
    [ "$approval_value" = true ] \
      || fail "GMS canary requires $approval=true"
  done
  [ -n "${GILBOM_LLM_API_KEY_SECRET_FILE:-}" ] \
    || fail "GMS canary requires GILBOM_LLM_API_KEY_SECRET_FILE"
  [ -s "$GILBOM_LLM_API_KEY_SECRET_FILE" ] \
    || fail "GMS canary API key file is missing or empty"
  case "${GILBOM_LLM_MODEL:-}" in
    gpt-5.4-mini|gpt-5.4)
      ;;
    *)
      fail "GMS canary model must be gpt-5.4-mini or gpt-5.4"
      ;;
  esac
  case "${GILBOM_LLM_DAILY_BUDGET:-}" in
    ""|0|0.0|0.00)
      fail "GMS canary requires a positive GILBOM_LLM_DAILY_BUDGET"
      ;;
  esac
  return 0
}

trap cleanup EXIT HUP INT TERM

for command_name in docker openssl python3 sha256sum
do
  require_command "$command_name"
done
case "$SMOKE_PUBLISHED_HOST" in
  ""|*[!A-Za-z0-9._-]*)
    fail "REPORT_SMOKE_PUBLISHED_HOST must be a hostname or IPv4 address"
    ;;
esac
case "$SMOKE_FRONTEND_BIND_ADDRESS" in
  127.0.0.1|0.0.0.0)
    ;;
  *)
    fail "REPORT_SMOKE_FRONTEND_BIND_ADDRESS must be 127.0.0.1 or 0.0.0.0"
    ;;
esac
if [ "$SMOKE_PUBLISHED_HOST" != 127.0.0.1 ] \
    && [ "$SMOKE_FRONTEND_BIND_ADDRESS" != 0.0.0.0 ]; then
  fail "a remote report smoke host requires a non-loopback Frontend binding"
fi
docker compose version >/dev/null 2>&1 \
  || fail "Docker Compose v2 is unavailable"
[ -f "$PROD_COMPOSE" ] || fail "compose.prod.yaml is missing"
[ -f "$SMOKE_COMPOSE" ] || fail "compose.smoke.yaml is missing"
[ "${REPORT_SMOKE_RESILIENCE:-false}" != true ] \
  || [ -f "$RESILIENCE_COMPOSE" ] \
  || fail "compose.resilience.yaml is missing"
[ -f "$FIXTURE_SQL" ] || fail "report fixture SQL is missing"
[ -f "$CLIENT" ] || fail "report smoke HTTP client is missing"
[ "${REPORT_SMOKE_RESILIENCE:-false}" != true ] \
  || [ "${REPORT_SMOKE_GMS_CANARY:-false}" != true ] \
  || fail "resilience outage mode and real GMS canary cannot run together"

install -d -m 0700 "$SECRET_DIR"
install -d -m 0755 \
  "$CERTBOT_DIR/conf" \
  "$CERTBOT_DIR/www" \
  "$CERTBOT_DIR/nginx"
openssl rand -hex -out "$SECRET_DIR/postgres_admin_password" 32
openssl rand -hex -out "$SECRET_DIR/postgres_flyway_password" 32
openssl rand -hex -out "$SECRET_DIR/postgres_app_password" 32
openssl rand -hex -out "$SECRET_DIR/mqtt_backend_password" 32
openssl rand -base64 -out "$SECRET_DIR/gilbom_storage_signing_key" 48
openssl rand -base64 -out "$SECRET_DIR/gilbom_auth_rate_limit_hmac_key" 48
printf '1=' > "$SECRET_DIR/gilbom_mfa_totp_keyring"
openssl rand -base64 32 \
  | tr -d '\n' >> "$SECRET_DIR/gilbom_mfa_totp_keyring"
printf '\n' >> "$SECRET_DIR/gilbom_mfa_totp_keyring"
openssl rand -hex -out "$SECRET_DIR/gilbom_initial_admin_password" 10
openssl rand -hex -out "$SECRET_DIR/author_new_password" 10
install -m 0400 /dev/null "$SECRET_DIR/gilbom_llm_api_key"

openssl req \
  -x509 \
  -newkey rsa:2048 \
  -sha256 \
  -nodes \
  -days 1 \
  -subj /CN=mosquitto \
  -addext subjectAltName=DNS:mosquitto \
  -addext basicConstraints=critical,CA:TRUE \
  -keyout "$SECRET_DIR/mosquitto_tls_private_key.pem" \
  -out "$SECRET_DIR/mosquitto_tls_fullchain.pem" \
  >/dev/null 2>&1
cp "$SECRET_DIR/mosquitto_tls_fullchain.pem" \
  "$SECRET_DIR/mqtt_ca_certificate.pem"

docker run --rm \
  --user "$(id -u):$(id -g)" \
  --entrypoint /bin/sh \
  --mount "type=bind,src=$SECRET_DIR,dst=/work" \
  eclipse-mosquitto:2.1.2-alpine \
  -ec '
    mqtt_password="$(cat /work/mqtt_backend_password)"
    mosquitto_passwd \
      -b -c /work/mosquitto_passwd gilbom "$mqtt_password"
    unset mqtt_password
  ' >/dev/null
chmod 0444 "$SECRET_DIR"/*
chmod 0400 "$SECRET_DIR/author_new_password"

write_environment \
  false \
  "$SECRET_DIR/gilbom_llm_api_key" \
  "" \
  0

cd "$PROJECT_DIR"
compose config --quiet \
  || fail "production plus smoke Compose does not resolve"

if [ "${REPORT_SMOKE_SKIP_BUILD:-false}" != true ]; then
  docker compose \
    --project-name "$PROJECT_NAME-build" \
    --env-file "$ENV_FILE" \
    -f "$PROJECT_DIR/compose.build.yaml" \
    build backend frontend \
    || fail "production image build failed"
fi

STARTED=true
compose up --detach --no-build --wait --wait-timeout 240 \
  || fail "disposable production stack did not become healthy"

db_id="$(compose ps -q db)"
backend_id="$(compose ps -q backend)"
frontend_id="$(compose ps -q frontend)"
[ -n "$db_id" ] && [ -n "$backend_id" ] && [ -n "$frontend_id" ] \
  || fail "a required smoke container is missing"

[ "$(db_admin_query \
  "SELECT (COUNT(*) = 44 AND bool_and(success))::int FROM flyway_history.flyway_schema_history;")" = 1 ] \
  || fail "Flyway did not complete successfully"
[ "$(db_admin_query "SELECT COUNT(*) FROM report.anomaly_report;")" = 0 ] \
  || fail "the disposable PostgreSQL volume was not empty"

compose exec -T db sh -ec '
  export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"
  psql \
    --host 127.0.0.1 \
    --username "$POSTGRES_USER" \
    --dbname "$POSTGRES_DB" \
    --no-password \
    --set ON_ERROR_STOP=1
' < "$FIXTURE_SQL" >/dev/null \
  || fail "fixture patrol and defect data could not be inserted"

refresh_base_url

python3 "$CLIENT" full \
  --base-url "$BASE_URL" \
  --state-file "$STATE_FILE" \
  --author-password-file "$SECRET_DIR/gilbom_initial_admin_password" \
  --author-new-password-file "$SECRET_DIR/author_new_password" \
  || fail "report API and SSE workflow failed"

report_id="$(
  python3 - "$STATE_FILE" <<'PY'
import json
import sys
with open(sys.argv[1], encoding="utf-8") as source:
    print(json.load(source)["reportId"])
PY
)"
approved_version="$(
  python3 - "$STATE_FILE" <<'PY'
import json
import sys
with open(sys.argv[1], encoding="utf-8") as source:
    print(json.load(source)["approvedVersionNo"])
PY
)"
pdf_sha256="$(
  python3 - "$STATE_FILE" <<'PY'
import json
import sys
with open(sys.argv[1], encoding="utf-8") as source:
    print(json.load(source)["pdfSha256"])
PY
)"
pdf_path="/app/data/report-pdf/reports/$report_id/v$approved_version/$pdf_sha256.pdf"
docker cp "$backend_id:$pdf_path" "$CHECK_DIR/approved-before.pdf" \
  >/dev/null \
  || fail "approved PDF is absent from the report volume"
[ "$(dd if="$CHECK_DIR/approved-before.pdf" bs=4 count=1 2>/dev/null)" = "%PDF" ] \
  || fail "approved artifact is not a PDF"
[ "$(sha256sum "$CHECK_DIR/approved-before.pdf" | awk '{print $1}')" = "$pdf_sha256" ] \
  || fail "approved PDF content hash differs from database metadata"

compose restart backend >/dev/null \
  || fail "Backend restart failed"
wait_for_health backend \
  || fail "Backend did not become ready after restart"
python3 "$CLIENT" persistence \
  --base-url "$BASE_URL" \
  --state-file "$STATE_FILE" \
  || fail "report metadata did not survive Backend restart"

backend_id="$(compose ps -q backend)"
docker cp "$backend_id:$pdf_path" "$CHECK_DIR/approved-after.pdf" \
  >/dev/null \
  || fail "approved PDF disappeared after Backend restart"
cmp -s "$CHECK_DIR/approved-before.pdf" "$CHECK_DIR/approved-after.pdf" \
  || fail "approved PDF changed after Backend restart"

BACKEND_CONTAINER="$backend_id" \
FRONTEND_CONTAINER="$frontend_id" \
SECRET_SCAN_DIR="$SECRET_DIR" \
IMAGE_VERIFY_MODE=logs \
  sh "$SCRIPT_DIR/verify-production-images.sh" \
  || fail "runtime logs crossed the Secret/request boundary"

if [ "${REPORT_SMOKE_RESILIENCE:-false}" = true ]; then
  RESILIENCE_PROJECT_NAME="$PROJECT_NAME" \
  RESILIENCE_ENV_FILE="$ENV_FILE" \
  RESILIENCE_STATE_FILE="$STATE_FILE" \
  RESILIENCE_SECRET_DIR="$SECRET_DIR" \
  RESILIENCE_PUBLISHED_HOST="$SMOKE_PUBLISHED_HOST" \
    sh "$SCRIPT_DIR/verify-production-resilience.sh" \
    || fail "production resilience workflow failed"
fi

if assert_canary_authorized; then
  CANARY_SECRET_DIR="$CHECK_DIR/canary-secret-scan"
  install -d -m 0700 "$CANARY_SECRET_DIR"
  cp "$GILBOM_LLM_API_KEY_SECRET_FILE" \
    "$CANARY_SECRET_DIR/gilbom_llm_api_key"
  chmod 0444 "$CANARY_SECRET_DIR/gilbom_llm_api_key"
  write_environment \
    true \
    "$CANARY_SECRET_DIR/gilbom_llm_api_key" \
    "$GILBOM_LLM_MODEL" \
    "$GILBOM_LLM_DAILY_BUDGET"
  compose config --quiet \
    || fail "GMS canary Compose does not resolve"
  compose up --detach --no-deps --force-recreate backend >/dev/null \
    || fail "GMS canary Backend recreation failed"
  wait_for_health backend \
    || fail "GMS canary Backend did not become ready"
  compose up --detach --no-deps --force-recreate frontend >/dev/null \
    || fail "GMS canary Frontend recreation failed"
  wait_for_health frontend \
    || fail "GMS canary Frontend did not become ready"

  refresh_base_url
  python3 "$CLIENT" canary \
    --base-url "$BASE_URL" \
    --state-file "$STATE_FILE" \
    --model "$GILBOM_LLM_MODEL" \
    --prompt-version report-v1 \
    || fail "GMS canary report generation failed"

  ledger_result="$(
    db_admin_query \
      "SELECT COUNT(*) || ':' || COUNT(*) FILTER (WHERE attempt_status = 'SUCCEEDED') FROM request_control.llm_invocation_usage;"
  )"
  [ "$ledger_result" = "1:1" ] \
    || fail "GMS canary must record exactly one successful provider attempt"

  backend_id="$(compose ps -q backend)"
  frontend_id="$(compose ps -q frontend)"
  BACKEND_CONTAINER="$backend_id" \
  FRONTEND_CONTAINER="$frontend_id" \
  SECRET_SCAN_DIR="$CANARY_SECRET_DIR" \
  IMAGE_VERIFY_MODE=logs \
    sh "$SCRIPT_DIR/verify-production-images.sh" \
    || fail "GMS canary Secret/request data appeared in runtime logs"
else
  echo "Container report GMS canary: SKIPPED (explicit opt-in and approvals required)"
fi

echo "Container report smoke: PASS"
echo "Validated fallback lifecycle, SSE/REST convergence, approved PDF hash, and restart persistence."
