#!/bin/sh

set -eu

BACKEND_CONTAINER="${BACKEND_CONTAINER:-$(docker compose ps -q backend)}"
DB_CONTAINER="${DB_CONTAINER:-$(docker compose ps -q db)}"
MQTT_CONTAINER="${MQTT_CONTAINER:-$(docker compose ps -q mosquitto)}"
DB_STOPPED=false
MQTT_STOPPED=false
BACKEND_STARTED_AT=

fail() {
  echo "Backend health policy verification failed: $1" >&2
  for container in \
    "$BACKEND_CONTAINER" \
    "$DB_CONTAINER" \
    "$MQTT_CONTAINER"
  do
    if [ -n "$container" ] \
        && docker inspect "$container" >/dev/null 2>&1; then
      docker inspect \
        --format 'name={{.Name}} state={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' \
        "$container" >&2 || true
    fi
  done
  exit 1
}

cleanup() {
  if [ "$DB_STOPPED" = true ]; then
    docker start "$DB_CONTAINER" >/dev/null 2>&1 || true
  fi
  if [ "$MQTT_STOPPED" = true ]; then
    docker start "$MQTT_CONTAINER" >/dev/null 2>&1 || true
  fi
}

trap cleanup EXIT HUP INT TERM

for container in \
  "$BACKEND_CONTAINER" \
  "$DB_CONTAINER" \
  "$MQTT_CONTAINER"
do
  [ -n "$container" ] \
    || fail "a required container is missing"
  [ "$(docker inspect --format '{{.State.Running}}' "$container")" = true ] \
    || fail "a required container is not running"
done
BACKEND_STARTED_AT="$(docker inspect \
  --format '{{.State.StartedAt}}' "$BACKEND_CONTAINER")"

backend_request_succeeds() {
  docker exec "$BACKEND_CONTAINER" \
    wget \
      --quiet \
      --spider \
      --timeout=5 \
      "http://127.0.0.1:8080/actuator/health/$1" \
      >/dev/null 2>&1
}

wait_for_request() {
  expected="$1"
  path="$2"
  timeout_seconds="$3"
  started_at="$(date +%s)"
  while true; do
    succeeded=false
    if backend_request_succeeds "$path"; then
      succeeded=true
    fi
    if [ "$succeeded" = "$expected" ]; then
      return 0
    fi
    now="$(date +%s)"
    if [ $((now - started_at)) -ge "$timeout_seconds" ]; then
      return 1
    fi
    sleep 1
  done
}

wait_for_container_health() {
  container="$1"
  timeout_seconds="$2"
  started_at="$(date +%s)"
  while true; do
    state="$(docker inspect \
      --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' \
      "$container")"
    [ "$state" = healthy ] && return 0
    now="$(date +%s)"
    if [ $((now - started_at)) -ge "$timeout_seconds" ]; then
      return 1
    fi
    sleep 1
  done
}

wait_for_request true liveness 30 \
  || fail "initial liveness did not become UP"
wait_for_request true readiness 30 \
  || fail "initial readiness did not become UP"
wait_for_request true mqtt 60 \
  || fail "initial MQTT detail did not become UP"

readiness="$(
  docker exec "$BACKEND_CONTAINER" \
    wget --quiet --output-document=- \
    http://127.0.0.1:8080/actuator/health/readiness
)"
for contributor in \
  readinessState \
  db \
  flywayReadiness \
  storageReadiness
do
  printf '%s\n' "$readiness" \
    | grep -Fq "\"$contributor\"" \
    || fail "readiness is missing $contributor"
done
printf '%s\n' "$readiness" | grep -Fq '"mqttConnection"' \
  && fail "MQTT unexpectedly participates in default readiness"
printf '%s\n' "$readiness" | grep -Eiq '"[^"]*llm[^"]*"' \
  && fail "LLM unexpectedly participates in readiness"

mqtt_detail="$(
  docker exec "$BACKEND_CONTAINER" \
    wget --quiet --output-document=- \
    http://127.0.0.1:8080/actuator/health/mqtt
)"
printf '%s\n' "$mqtt_detail" | grep -Fq '"connected":true' \
  || fail "MQTT detail does not expose a connected state"

# Broker는 공식 image의 SIGTERM으로 종료한다. 기본 정책에서는 로봇 기능만
# unavailable이 되고 DB 기반 Backend readiness는 유지돼야 한다.
docker stop --time 30 "$MQTT_CONTAINER" >/dev/null
MQTT_STOPPED=true
wait_for_request false mqtt 60 \
  || fail "MQTT detail stayed UP after Broker shutdown"
backend_request_succeeds readiness \
  || fail "MQTT outage incorrectly changed default readiness"

docker start "$MQTT_CONTAINER" >/dev/null
MQTT_STOPPED=false
wait_for_container_health "$MQTT_CONTAINER" 120 \
  || fail "Broker health did not recover"
wait_for_request true mqtt 90 \
  || fail "MQTT detail did not recover"

# DB는 필수 의존성이므로 DB가 없을 때 liveness는 유지하되 readiness는 반드시
# 성공하지 않아야 한다.
docker stop --time 60 "$DB_CONTAINER" >/dev/null
DB_STOPPED=true
backend_request_succeeds liveness \
  || fail "DB outage incorrectly changed liveness"
wait_for_request false readiness 60 \
  || fail "readiness stayed UP after DB shutdown"
backend_request_succeeds liveness \
  || fail "Backend JVM stopped during DB outage"

docker start "$DB_CONTAINER" >/dev/null
DB_STOPPED=false
wait_for_container_health "$DB_CONTAINER" 120 \
  || fail "database health did not recover"
wait_for_request true readiness 120 \
  || fail "readiness did not recover with the database"

[ "$(docker inspect \
  --format '{{.State.Running}}' "$BACKEND_CONTAINER")" = true ] \
  || fail "Backend stopped during dependency checks"
[ "$(docker inspect \
  --format '{{.State.StartedAt}}' "$BACKEND_CONTAINER")" = "$BACKEND_STARTED_AT" ] \
  || fail "Backend restarted during dependency checks"

echo "Backend health policy verification: PASS"
