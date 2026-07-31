#!/bin/sh

set -eu
set +x

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$PROJECT_DIR/compose.prod.yaml}"
ENV_FILE="${ENV_FILE:-$PROJECT_DIR/.env.prod}"
RELEASE_DIR="${RELEASE_DIR:-$PROJECT_DIR/.artifacts/releases}"
PREVIOUS_BACKEND_IMAGE="${PREVIOUS_BACKEND_IMAGE:-}"
PREVIOUS_FRONTEND_IMAGE="${PREVIOUS_FRONTEND_IMAGE:-}"
ROLLBACK_APPROVED="${ROLLBACK_APPROVED:-false}"
ROLLBACK_PULL="${ROLLBACK_PULL:-false}"

fail() {
  echo "Production rollback failed: $1" >&2
  exit 1
}

require_digest() {
  image="$1"
  role="$2"
  printf '%s\n' "$image" \
    | grep -Eq '@sha256:[0-9a-f]{64}$' \
    || fail "$role rollback image must be pinned by digest"
}

[ "$ROLLBACK_APPROVED" = true ] \
  || fail "set ROLLBACK_APPROVED=true after migration compatibility review"
[ -f "$ENV_FILE" ] || fail "ENV_FILE does not exist"
[ -n "$PREVIOUS_BACKEND_IMAGE" ] \
  || fail "PREVIOUS_BACKEND_IMAGE is required"
[ -n "$PREVIOUS_FRONTEND_IMAGE" ] \
  || fail "PREVIOUS_FRONTEND_IMAGE is required"
require_digest "$PREVIOUS_BACKEND_IMAGE" Backend
require_digest "$PREVIOUS_FRONTEND_IMAGE" Frontend
umask 077

compose() {
  BACKEND_IMAGE="$PREVIOUS_BACKEND_IMAGE" \
  FRONTEND_IMAGE="$PREVIOUS_FRONTEND_IMAGE" \
    docker compose \
      --env-file "$ENV_FILE" \
      -f "$COMPOSE_FILE" \
      "$@"
}

cd "$PROJECT_DIR"
compose config --quiet

if [ "$ROLLBACK_PULL" = true ]; then
  compose pull backend frontend
fi

# DB와 named volume은 건드리지 않는다. 먼저 외부 진입점을 내리고 Backend를
# 교체한 뒤 readiness가 통과한 경우에만 Frontend를 다시 올린다.
compose stop frontend
compose up \
  --detach \
  --no-build \
  --no-deps \
  --wait \
  --wait-timeout 180 \
  backend \
  || fail "previous Backend did not become ready"
compose up \
  --detach \
  --no-build \
  --no-deps \
  --wait \
  --wait-timeout 120 \
  frontend \
  || fail "previous Frontend did not become healthy"

frontend_id="$(compose ps -q frontend)"
docker exec "$frontend_id" \
  wget --quiet --output-document=- \
  http://127.0.0.1:8080/api/health \
  | grep -Fq '"status":"UP"' \
  || fail "rollback end-to-end health check failed"

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
install -d -m 0700 "$RELEASE_DIR"
record="$RELEASE_DIR/$timestamp-rollback.txt"
{
  printf 'recorded_at_utc=%s\n' "$timestamp"
  printf 'git_revision=%s\n' "$(
    git -C "$PROJECT_DIR" rev-parse HEAD 2>/dev/null \
      || printf 'unavailable'
  )"
  printf 'backend_image=%s\n' "$PREVIOUS_BACKEND_IMAGE"
  printf 'frontend_image=%s\n' "$PREVIOUS_FRONTEND_IMAGE"
  for service in backend frontend
  do
    container_id="$(compose ps -q "$service")"
    printf '%s_image_id=%s\n' "$service" "$(
      docker inspect --format '{{.Image}}' "$container_id"
    )"
  done
} > "$record"
chmod 0600 "$record"

echo "Production rollback: PASS"
echo "Rollback record: $record"
echo "Database migrations were not reverted."
