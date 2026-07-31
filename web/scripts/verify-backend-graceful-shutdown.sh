#!/bin/sh

set -eu

BACKEND_CONTAINER="${BACKEND_CONTAINER:-$(docker compose ps -q backend)}"
DB_CONTAINER="${DB_CONTAINER:-$(docker compose ps -q db)}"
DB_PAUSED=false

fail() {
  echo "Backend graceful shutdown verification failed: $1" >&2
  if [ -n "${BACKEND_CONTAINER:-}" ] \
      && docker inspect "$BACKEND_CONTAINER" >/dev/null 2>&1; then
    docker inspect \
      --format 'state={{.State.Status}} exit={{.State.ExitCode}} oom={{.State.OOMKilled}} finished={{.State.FinishedAt}}' \
      "$BACKEND_CONTAINER" >&2 || true
    echo "Backend shutdown log tail:" >&2
    docker logs --tail 160 "$BACKEND_CONTAINER" >&2 2>&1 || true
  fi
  exit 1
}

cleanup() {
  if [ "$DB_PAUSED" = "true" ]; then
    docker unpause "$DB_CONTAINER" >/dev/null 2>&1 || true
  fi
}

trap cleanup EXIT HUP INT TERM

[ -n "$BACKEND_CONTAINER" ] \
  || fail "backend container is not running"
[ -n "$DB_CONTAINER" ] \
  || fail "database container is not running"

docker pause "$DB_CONTAINER" >/dev/null
DB_PAUSED=true

# DB health contributor가 응답을 기다리게 해 실제 in-flight HTTP 요청을 만든다.
docker exec "$BACKEND_CONTAINER" \
  wget --quiet --output-document=- \
  http://127.0.0.1:8080/actuator/health >/dev/null 2>&1 &
REQUEST_PID=$!

sleep 2
kill -0 "$REQUEST_PID" >/dev/null 2>&1 \
  || fail "could not hold an in-flight request"

STARTED_AT="$(date +%s)"
docker stop --time 30 "$BACKEND_CONTAINER" >/dev/null &
STOP_PID=$!

# Graceful shutdown은 새 요청 수락을 중단하지만 기존 요청이 끝날 때까지 기다려야 한다.
sleep 2
container_running="$(docker inspect \
  --format '{{.State.Running}}' "$BACKEND_CONTAINER")"
[ "$container_running" = "true" ] \
  || fail "backend exited before the in-flight request was released"

docker unpause "$DB_CONTAINER" >/dev/null
DB_PAUSED=false

wait "$REQUEST_PID" || true
wait "$STOP_PID" \
  || fail "docker stop did not finish within its 30 second limit"

FINISHED_AT="$(date +%s)"
ELAPSED_SECONDS=$((FINISHED_AT - STARTED_AT))
[ "$ELAPSED_SECONDS" -lt 30 ] \
  || fail "backend did not leave margin before the 30 second stop limit"

EXIT_CODE="$(docker inspect \
  --format '{{.State.ExitCode}}' "$BACKEND_CONTAINER")"
[ "$EXIT_CODE" -ne 137 ] \
  || fail "backend required SIGKILL after the graceful stop limit"

docker logs "$BACKEND_CONTAINER" 2>&1 \
  | grep -Fq 'Commencing graceful shutdown' \
  || fail "graceful shutdown start was not logged"
docker logs "$BACKEND_CONTAINER" 2>&1 \
  | grep -Fq 'Graceful shutdown complete' \
  || fail "graceful shutdown completion was not logged"

echo "Backend graceful shutdown verification: PASS (${ELAPSED_SECONDS}s)"
