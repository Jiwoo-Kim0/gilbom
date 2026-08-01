#!/bin/sh

set -eu
set +x

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
PROD_COMPOSE="$PROJECT_DIR/compose.prod.yaml"
SMOKE_COMPOSE="$PROJECT_DIR/compose.smoke.yaml"
RESILIENCE_COMPOSE="$PROJECT_DIR/compose.resilience.yaml"
CLIENT="$SCRIPT_DIR/container-report-smoke-client.py"
PROJECT_NAME="${RESILIENCE_PROJECT_NAME:?RESILIENCE_PROJECT_NAME is required}"
ENV_FILE="${RESILIENCE_ENV_FILE:?RESILIENCE_ENV_FILE is required}"
STATE_FILE="${RESILIENCE_STATE_FILE:?RESILIENCE_STATE_FILE is required}"
SECRET_DIR="${RESILIENCE_SECRET_DIR:?RESILIENCE_SECRET_DIR is required}"
PUBLISHED_HOST="${RESILIENCE_PUBLISHED_HOST:-127.0.0.1}"
WORK_DIR="$(dirname -- "$ENV_FILE")/resilience"

compose() {
  docker compose \
    --project-name "$PROJECT_NAME" \
    --env-file "$ENV_FILE" \
    -f "$PROD_COMPOSE" \
    -f "$SMOKE_COMPOSE" \
    -f "$RESILIENCE_COMPOSE" \
    "$@"
}

compose_with_env() {
  selected_env="$1"
  shift
  docker compose \
    --project-name "$PROJECT_NAME" \
    --env-file "$selected_env" \
    -f "$PROD_COMPOSE" \
    -f "$SMOKE_COMPOSE" \
    -f "$RESILIENCE_COMPOSE" \
    "$@"
}

compose_with_bad_app_secret() {
  bad_secret="$1"
  shift
  env POSTGRES_APP_PASSWORD_SECRET_FILE="$bad_secret" \
    docker compose \
      --project-name "$PROJECT_NAME" \
      --env-file "$ENV_FILE" \
      -f "$PROD_COMPOSE" \
      -f "$SMOKE_COMPOSE" \
      -f "$RESILIENCE_COMPOSE" \
      "$@"
}

fail() {
  echo "Production resilience verification failed: $1" >&2
  compose ps >&2 || true
  exit 1
}

container_id() {
  compose ps -q "$1"
}

container_health() {
  docker inspect \
    --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' \
    "$1" 2>/dev/null || true
}

wait_for_health() {
  service="$1"
  timeout_seconds="$2"
  started_at="$(date +%s)"
  while true; do
    id="$(container_id "$service")"
    if [ -n "$id" ] \
        && [ "$(container_health "$id")" = healthy ]; then
      return 0
    fi
    now="$(date +%s)"
    [ $((now - started_at)) -lt "$timeout_seconds" ] \
      || return 1
    sleep 2
  done
}

wait_for_automatic_restart() {
  service="$1"
  previous_restarts="$2"
  previous_pid="$3"
  timeout_seconds="$4"
  started_at="$(date +%s)"
  while true; do
    id="$(container_id "$service")"
    if [ -n "$id" ]; then
      restarts="$(
        docker inspect --format '{{.RestartCount}}' "$id" 2>/dev/null \
          || printf '0'
      )"
      pid="$(
        docker inspect --format '{{.State.Pid}}' "$id" 2>/dev/null \
          || printf '0'
      )"
      if [ "$restarts" -gt "$previous_restarts" ] \
          && [ "$pid" -gt 0 ] \
          && [ "$pid" != "$previous_pid" ] \
          && [ "$(container_health "$id")" = healthy ]; then
        return 0
      fi
    fi
    now="$(date +%s)"
    [ $((now - started_at)) -lt "$timeout_seconds" ] \
      || return 1
    sleep 2
  done
}

refresh_base_url() {
  binding="$(compose port frontend 8080)"
  [ -n "$binding" ] || fail "Frontend host binding is unavailable"
  published_port="${binding##*:}"
  case "$published_port" in
    ""|*[!0-9]*)
      fail "Frontend host binding has an invalid port"
      ;;
  esac
  BASE_URL="http://$PUBLISHED_HOST:$published_port"
}

verify_report_persistence() {
  python3 "$CLIENT" persistence \
    --base-url "$BASE_URL" \
    --state-file "$STATE_FILE"
}

state_field() {
  field="$1"
  python3 - "$STATE_FILE" "$field" <<'PY'
import json
import sys
with open(sys.argv[1], encoding="utf-8") as source:
    print(json.load(source)[sys.argv[2]])
PY
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

set_env_value() {
  key="$1"
  value="$2"
  temporary="$WORK_DIR/environment.tmp"
  awk -v key="$key" -v value="$value" '
    BEGIN {
      found = 0
    }
    index($0, key "=") == 1 {
      print key "=" value
      found = 1
      next
    }
    {
      print
    }
    END {
      if (!found) {
        print key "=" value
      }
    }
  ' "$ENV_FILE" > "$temporary"
  mv "$temporary" "$ENV_FILE"
  chmod 0600 "$ENV_FILE"
}

recreate_backend_and_frontend() {
  compose up \
    --detach \
    --no-deps \
    --force-recreate \
    backend >/dev/null \
    || fail "Backend recreation failed"
  wait_for_health backend 180 \
    || fail "Backend readiness did not recover after recreation"
  compose up \
    --detach \
    --no-deps \
    --force-recreate \
    frontend >/dev/null \
    || fail "Frontend recreation failed"
  wait_for_health frontend 90 \
    || fail "Frontend health did not recover after recreation"
  refresh_base_url
}

assert_restart_policy() {
  for service in db mosquitto backend frontend
  do
    id="$(container_id "$service")"
    [ -n "$id" ] || fail "$service container is missing"
    policy="$(
      docker inspect \
        --format '{{.HostConfig.RestartPolicy.Name}}' \
        "$id"
    )"
    [ "$policy" = unless-stopped ] \
      || fail "$service restart policy is not unless-stopped"
  done
}

unset \
  POSTGRES_APP_PASSWORD_SECRET_FILE \
  GILBOM_LLM_ENABLED \
  GILBOM_LLM_MODEL \
  GILBOM_LLM_DAILY_REQUEST_LIMIT \
  GILBOM_LLM_DAILY_BUDGET \
  GILBOM_LLM_POLICY_APPROVED \
  GILBOM_LLM_TRAINING_EXCLUSION_APPROVED \
  GILBOM_LLM_DATA_RETENTION_APPROVED \
  GILBOM_LLM_OUTBOUND_ALLOWLIST_APPLIED \
  GILBOM_LLM_API_KEY_SECRET_FILE || true

umask 077
case "$PUBLISHED_HOST" in
  ""|*[!A-Za-z0-9._-]*)
    fail "RESILIENCE_PUBLISHED_HOST must be a hostname or IPv4 address"
    ;;
esac
install -d -m 0700 "$WORK_DIR"
compose config --quiet \
  || fail "resilience Compose does not resolve"
assert_restart_policy
refresh_base_url
verify_report_persistence \
  || fail "baseline approved report is unavailable"

report_id="$(state_field reportId)"
approved_version="$(state_field approvedVersionNo)"
pdf_sha256="$(state_field pdfSha256)"
pdf_path="/app/data/report-pdf/reports/$report_id/v$approved_version/$pdf_sha256.pdf"
image_canary="resilience-image-volume-$PROJECT_NAME"
image_path="/app/data/robot-images/resilience/volume-canary.txt"
backend_id="$(container_id backend)"
docker exec "$backend_id" /bin/sh -ec '
  umask 077
  mkdir -p /app/data/robot-images/resilience
  printf "%s\n" "$1" \
    > /app/data/robot-images/resilience/volume-canary.txt
' sh "$image_canary" \
  || fail "non-root Backend could not write the image volume canary"
image_sha256="$(
  docker exec "$backend_id" \
    sha256sum "$image_path" \
    | awk '{print $1}'
)"

# Docker API의 kill은 수동 stop으로 분류돼 unless-stopped를 억제한다.
# PID namespace의 init(PID 1)은 내부 SIGKILL을 무시하므로, 컨테이너 안에서
# PID 1에 SIGTERM을 직접 보내 프로세스 종료와 restart manager 복구를 만든다.
backend_restarts="$(
  docker inspect --format '{{.RestartCount}}' "$backend_id"
)"
backend_pid="$(
  docker inspect --format '{{.State.Pid}}' "$backend_id"
)"
docker exec "$backend_id" /bin/sh -ec 'kill -TERM 1' \
  >/dev/null 2>&1 || true
wait_for_automatic_restart \
  backend "$backend_restarts" "$backend_pid" 180 \
  || fail "Backend did not automatically restart and become ready"
refresh_base_url
verify_report_persistence \
  || fail "report lookup failed after Backend PID recovery"
echo "Backend forced-PID restart and readiness recovery: PASS"

# Frontend도 같은 방식으로 PID와 restart count 변화를 검증한다.
frontend_id="$(container_id frontend)"
frontend_restarts="$(
  docker inspect --format '{{.RestartCount}}' "$frontend_id"
)"
frontend_pid="$(
  docker inspect --format '{{.State.Pid}}' "$frontend_id"
)"
docker exec "$frontend_id" /bin/sh -ec 'kill -TERM 1' \
  >/dev/null 2>&1 || true
wait_for_automatic_restart \
  frontend "$frontend_restarts" "$frontend_pid" 120 \
  || fail "Frontend did not automatically restart"
frontend_id="$(container_id frontend)"
docker exec "$frontend_id" \
  wget --quiet --output-document=- \
  http://127.0.0.1:8080/healthz \
  | grep -Fxq ok \
  || fail "Nginx /healthz did not recover"
docker exec "$frontend_id" \
  wget --quiet --output-document=- \
  http://127.0.0.1:8080/ \
  | grep -Fq '<div id="root"></div>' \
  || fail "Frontend static page did not recover"
echo "Frontend forced-PID restart and static recovery: PASS"

# Task 6의 검증기를 같은 운영 container에 적용해 DB/MQTT 단절과 복구를
# 재검증한다. 이 검증기는 liveness/readiness 분리, pool 회복, MQTT 재연결과
# Backend PID 불변을 함께 확인한다.
backend_id="$(container_id backend)"
db_id="$(container_id db)"
mqtt_id="$(container_id mosquitto)"
BACKEND_CONTAINER="$backend_id" \
DB_CONTAINER="$db_id" \
MQTT_CONTAINER="$mqtt_id" \
  sh "$SCRIPT_DIR/verify-backend-health-policy.sh" \
  || fail "PostgreSQL/MQTT dependency recovery failed"
refresh_base_url
verify_report_persistence \
  || fail "report lookup failed after DB/MQTT recovery"
echo "PostgreSQL pool/readiness and MQTT reconnect recovery: PASS"

# 활성 정책과 fake key로 Backend를 재생성한 뒤 유일한 egress network를 끊는다.
# app/data network는 internal이므로 외부 provider로 우회할 경로가 없다.
llm_key="$SECRET_DIR/gilbom_llm_api_key"
fake_llm_scan="$SECRET_DIR/resilience_fake_llm_key"
chmod 0600 "$llm_key"
openssl rand -hex -out "$llm_key" 32
cp "$llm_key" "$fake_llm_scan"
chmod 0444 "$llm_key" "$fake_llm_scan"
set_env_value GILBOM_LLM_ENABLED true
set_env_value GILBOM_LLM_PROVIDER GMS
set_env_value GILBOM_LLM_MODEL gpt-5.4-mini
set_env_value GILBOM_LLM_PROMPT_VERSION report-v1
set_env_value GILBOM_LLM_DAILY_REQUEST_LIMIT 2
# gpt-5.4-mini의 attempt당 최대 예약액 221.25를 두 번 허용한다.
set_env_value GILBOM_LLM_DAILY_BUDGET 500
set_env_value GILBOM_LLM_MAX_CONCURRENT_REQUESTS 1
set_env_value GILBOM_LLM_POLICY_APPROVED true
set_env_value GILBOM_LLM_TRAINING_EXCLUSION_APPROVED true
set_env_value GILBOM_LLM_DATA_RETENTION_APPROVED true
set_env_value GILBOM_LLM_OUTBOUND_ALLOWLIST_APPLIED true
recreate_backend_and_frontend

backend_id="$(container_id backend)"
llm_network="$(
  docker inspect \
    --format '{{range $name, $_ := .NetworkSettings.Networks}}{{println $name}}{{end}}' \
    "$backend_id" \
    | grep -- '-egress$' \
    | head -n 1
)"
[ -n "$llm_network" ] || fail "Backend llm-egress network is missing"
docker network disconnect "$llm_network" "$backend_id" \
  || fail "could not block Backend LLM egress"
wait_for_health backend 30 \
  || fail "LLM egress outage changed Backend readiness"
python3 "$CLIENT" outbound-fallback \
  --base-url "$BASE_URL" \
  --state-file "$STATE_FILE" \
  || fail "LLM outage fallback or general API failed"
llm_usage="$(
  db_admin_query \
    "SELECT COUNT(*) || ':' || COUNT(*) FILTER (WHERE attempt_status = 'FAILED') FROM request_control.llm_invocation_usage;"
)"
[ "$llm_usage" = "2:2" ] \
  || fail "blocked LLM calls were not bounded to two failed attempts"
[ "$(docker inspect --format '{{.State.Running}}' "$backend_id")" = true ] \
  || fail "Backend process stopped during LLM outage"
echo "LLM egress isolation, health, API, and Template fallback: PASS"

# 실제 LLM key path 변수를 제거한 별도 env로 /dev/null Secret fallback을
# 검증한다. fake key 원문은 별도 scan file에 남겨 이후 log leakage도 검사한다.
chmod 0600 "$llm_key"
: > "$llm_key"
chmod 0444 "$llm_key"
set_env_value GILBOM_LLM_ENABLED false
set_env_value GILBOM_LLM_MODEL ""
set_env_value GILBOM_LLM_DAILY_REQUEST_LIMIT 0
set_env_value GILBOM_LLM_DAILY_BUDGET 0
set_env_value GILBOM_LLM_POLICY_APPROVED false
set_env_value GILBOM_LLM_TRAINING_EXCLUSION_APPROVED false
set_env_value GILBOM_LLM_DATA_RETENTION_APPROVED false
set_env_value GILBOM_LLM_OUTBOUND_ALLOWLIST_APPLIED false
NO_LLM_ENV="$WORK_DIR/no-llm-secret.env"
awk '
  $0 !~ /^GILBOM_LLM_API_KEY_SECRET_FILE=/
' "$ENV_FILE" > "$NO_LLM_ENV"
chmod 0600 "$NO_LLM_ENV"
compose_with_env "$NO_LLM_ENV" up \
  --detach \
  --no-deps \
  --force-recreate \
  backend >/dev/null \
  || fail "Backend without an LLM Secret did not start"
started_at="$(date +%s)"
while true; do
  no_llm_backend_id="$(
    compose_with_env "$NO_LLM_ENV" ps -q backend
  )"
  if [ -n "$no_llm_backend_id" ] \
      && [ "$(container_health "$no_llm_backend_id")" = healthy ]; then
    break
  fi
  now="$(date +%s)"
  [ $((now - started_at)) -lt 180 ] \
    || fail "Backend without an LLM Secret did not become ready"
  sleep 2
done
compose_with_env "$NO_LLM_ENV" up \
  --detach \
  --no-deps \
  --force-recreate \
  frontend >/dev/null \
  || fail "Frontend recreation for missing LLM Secret failed"
started_at="$(date +%s)"
while true; do
  no_llm_frontend_id="$(
    compose_with_env "$NO_LLM_ENV" ps -q frontend
  )"
  if [ -n "$no_llm_frontend_id" ] \
      && [ "$(container_health "$no_llm_frontend_id")" = healthy ]; then
    break
  fi
  now="$(date +%s)"
  [ $((now - started_at)) -lt 90 ] \
    || fail "Frontend did not recover for missing LLM Secret"
  sleep 2
done
llm_mount_source="$(
  docker inspect \
    --format '{{range .Mounts}}{{if eq .Destination "/run/secrets/gilbom_llm_api_key"}}{{.Source}}{{end}}{{end}}' \
    "$no_llm_backend_id"
)"
[ "$llm_mount_source" = /dev/null ] \
  || fail "missing LLM key did not resolve to the empty /dev/null Secret"
binding="$(
  compose_with_env "$NO_LLM_ENV" port frontend 8080
)"
published_port="${binding##*:}"
case "$published_port" in
  ""|*[!0-9]*)
    fail "Frontend host binding has an invalid port"
    ;;
esac
BASE_URL="http://$PUBLISHED_HOST:$published_port"
python3 "$CLIENT" disabled-fallback \
  --base-url "$BASE_URL" \
  --state-file "$STATE_FILE" \
  || fail "missing LLM Secret affected general service"
echo "Missing optional LLM Secret isolation: PASS"

# 모든 container를 새로 만들되 named volume은 그대로 붙인다.
compose up \
  --detach \
  --no-build \
  --force-recreate \
  --wait \
  --wait-timeout 240 \
  || fail "full Compose recreation failed"
refresh_base_url
verify_report_persistence \
  || fail "database report data disappeared after full recreation"
backend_id="$(container_id backend)"
docker cp "$backend_id:$pdf_path" "$WORK_DIR/approved-after-recreate.pdf" \
  >/dev/null \
  || fail "approved PDF disappeared after full recreation"
[ "$(
  sha256sum "$WORK_DIR/approved-after-recreate.pdf" \
    | awk '{print $1}'
)" = "$pdf_sha256" ] \
  || fail "approved PDF changed after full recreation"
[ "$(
  docker exec "$backend_id" \
    sha256sum "$image_path" \
    | awk '{print $1}'
)" = "$image_sha256" ] \
  || fail "image volume canary changed after full recreation"
echo "Full Compose recreation with DB/PDF/image persistence: PASS"

# 잘못된 필수 application DB password로 Backend만 교체한다. readiness가
# 성공하지 않고 오류 log에 원문 password가 없는지 확인한 뒤 정상 Secret으로
# 복구해 최종 stack을 healthy 상태로 돌려놓는다.
bad_db_secret="$SECRET_DIR/resilience_bad_postgres_app_password"
openssl rand -hex -out "$bad_db_secret" 32
chmod 0444 "$bad_db_secret"
compose_with_bad_app_secret "$bad_db_secret" up \
  --detach \
  --no-deps \
  --force-recreate \
  backend >/dev/null \
  || true
bad_backend_id="$(
  compose_with_bad_app_secret "$bad_db_secret" ps --all -q backend
)"
[ -n "$bad_backend_id" ] \
  || fail "invalid DB Secret did not create a Backend container"
bad_started_at="$(date +%s)"
bad_secret_rejected=false
while true; do
  status="$(
    docker inspect --format '{{.State.Status}}' "$bad_backend_id" \
      2>/dev/null || printf 'missing'
  )"
  health="$(container_health "$bad_backend_id")"
  restarts="$(
    docker inspect --format '{{.RestartCount}}' "$bad_backend_id" \
      2>/dev/null || printf '0'
  )"
  if [ "$status" = exited ] \
      || [ "$status" = restarting ] \
      || [ "$health" = unhealthy ] \
      || [ "$restarts" -gt 0 ]; then
    bad_secret_rejected=true
    break
  fi
  now="$(date +%s)"
  [ $((now - bad_started_at)) -lt 120 ] || break
  sleep 2
done
[ "$bad_secret_rejected" = true ] \
  || fail "invalid required DB Secret did not block readiness"
bad_log="$WORK_DIR/bad-db-secret.log"
docker logs "$bad_backend_id" > "$bad_log" 2>&1 || true
bad_value="$(tr -d '\r\n' < "$bad_db_secret")"
if grep -aFq -- "$bad_value" "$bad_log"; then
  fail "invalid DB Secret value appeared in Backend logs"
fi
grep -Eiq \
  'APPLICATION FAILED TO START|HikariPool|DataSource|database|JDBC' \
  "$bad_log" \
  || fail "invalid DB Secret did not produce a clear safe startup error"
if grep -Eiq 'password[=:][^[:space:]]+' "$bad_log"; then
  fail "Backend startup error exposed password material"
fi
echo "Invalid required DB Secret fail-closed behavior: PASS"

recreate_backend_and_frontend
verify_report_persistence \
  || fail "report lookup failed after valid DB Secret recovery"

backend_id="$(container_id backend)"
frontend_id="$(container_id frontend)"
BACKEND_CONTAINER="$backend_id" \
FRONTEND_CONTAINER="$frontend_id" \
SECRET_SCAN_DIR="$SECRET_DIR" \
IMAGE_VERIFY_MODE=logs \
  sh "$SCRIPT_DIR/verify-production-images.sh" \
  || fail "Secret/request material appeared in resilience logs"

echo "Production resilience verification: PASS"
