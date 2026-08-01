#!/bin/sh

set -eu
set +x

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$PROJECT_DIR/compose.prod.yaml}"
ENV_FILE="${ENV_FILE:-}"
RUNTIME_ENV_FILE="${RUNTIME_ENV_FILE:-}"
IMAGE_ENV_FILE="${IMAGE_ENV_FILE:-}"
RELEASE_DIR="${RELEASE_DIR:-$PROJECT_DIR/.artifacts/releases}"
PREVIOUS_BACKEND_IMAGE="${PREVIOUS_BACKEND_IMAGE:-}"
PREVIOUS_FRONTEND_IMAGE="${PREVIOUS_FRONTEND_IMAGE:-}"
PREVIOUS_GIT_REVISION="${PREVIOUS_GIT_REVISION:-}"
ROLLBACK_APPROVED="${ROLLBACK_APPROVED:-false}"
ROLLBACK_PULL="${ROLLBACK_PULL:-false}"

if [ -z "$ENV_FILE" ] \
    && [ -z "$RUNTIME_ENV_FILE" ] \
    && [ -z "$IMAGE_ENV_FILE" ]; then
  ENV_FILE="$PROJECT_DIR/.env.prod"
fi

fail() {
  echo "Production rollback failed: $1" >&2
  exit 1
}

require_immutable_image() {
  image="$1"
  role="$2"
  if printf '%s\n' "$image" \
      | grep -Eq '@sha256:[0-9a-f]{64}$'; then
    return 0
  fi
  image_revision="$(
    printf '%s\n' "$image" \
      | sed -n 's/^.*:\([0-9a-f]\{40\}\)$/\1/p'
  )"
  [ -n "$image_revision" ] \
    || fail "$role rollback image must be pinned by digest or full Commit SHA"
  printf '%s\n' "$PREVIOUS_GIT_REVISION" \
    | grep -Eq '^[0-9a-f]{40}$' \
    || fail "PREVIOUS_GIT_REVISION is required for offline Commit SHA rollback"
  [ "$image_revision" = "$PREVIOUS_GIT_REVISION" ] \
    || fail "$role rollback image tag does not match PREVIOUS_GIT_REVISION"
}

[ "$ROLLBACK_APPROVED" = true ] \
  || fail "set ROLLBACK_APPROVED=true after migration compatibility review"
if [ -n "$ENV_FILE" ]; then
  [ -z "$RUNTIME_ENV_FILE" ] && [ -z "$IMAGE_ENV_FILE" ] \
    || fail "ENV_FILE cannot be combined with split environment files"
  [ -f "$ENV_FILE" ] || fail "ENV_FILE does not exist"
else
  [ -n "$RUNTIME_ENV_FILE" ] \
    || fail "RUNTIME_ENV_FILE is required when ENV_FILE is not used"
  [ -n "$IMAGE_ENV_FILE" ] \
    || fail "IMAGE_ENV_FILE is required when ENV_FILE is not used"
  [ -f "$RUNTIME_ENV_FILE" ] || fail "RUNTIME_ENV_FILE does not exist"
  [ -f "$IMAGE_ENV_FILE" ] || fail "IMAGE_ENV_FILE does not exist"
fi
[ -n "$PREVIOUS_BACKEND_IMAGE" ] \
  || fail "PREVIOUS_BACKEND_IMAGE is required"
[ -n "$PREVIOUS_FRONTEND_IMAGE" ] \
  || fail "PREVIOUS_FRONTEND_IMAGE is required"
require_immutable_image "$PREVIOUS_BACKEND_IMAGE" Backend
require_immutable_image "$PREVIOUS_FRONTEND_IMAGE" Frontend
umask 077

compose() {
  if [ -n "$ENV_FILE" ]; then
    BACKEND_IMAGE="$PREVIOUS_BACKEND_IMAGE" \
    FRONTEND_IMAGE="$PREVIOUS_FRONTEND_IMAGE" \
      docker compose \
        --env-file "$ENV_FILE" \
        -f "$COMPOSE_FILE" \
        "$@"
  else
    BACKEND_IMAGE="$PREVIOUS_BACKEND_IMAGE" \
    FRONTEND_IMAGE="$PREVIOUS_FRONTEND_IMAGE" \
      docker compose \
        --env-file "$RUNTIME_ENV_FILE" \
        --env-file "$IMAGE_ENV_FILE" \
        -f "$COMPOSE_FILE" \
        "$@"
  fi
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
frontend_probe="$(
  docker exec "$frontend_id" /bin/sh -ec '
  if grep -Fq "listen 8443 ssl;" /tmp/nginx-conf.d/site.conf; then
    exec wget \
      --quiet \
      --no-check-certificate \
      --output-document=- \
      https://127.0.0.1:8443/api/health
  fi
  wget \
    --quiet \
    --output-document=- \
    http://127.0.0.1:8080/healthz
  if wget \
      --quiet \
      --spider \
      http://127.0.0.1:8080/api/health; then
    echo "HTTP application serving is enabled during certificate bootstrap" >&2
    exit 1
  fi
  '
)" || fail "rollback HTTPS/bootstrap policy check failed"
if docker exec "$frontend_id" \
    grep -Fq "listen 8443 ssl;" /tmp/nginx-conf.d/site.conf; then
  printf '%s\n' "$frontend_probe" \
    | grep -Fq '"status":"UP"' \
    || fail "rollback HTTPS end-to-end health check failed"
else
  [ "$frontend_probe" = "bootstrap" ] \
    || fail "rollback certificate bootstrap health check failed"
fi

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
