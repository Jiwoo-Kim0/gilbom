#!/bin/sh

set -eu
set +x

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$PROJECT_DIR/compose.prod.yaml}"
ENV_FILE="${ENV_FILE:-$PROJECT_DIR/.env.prod}"
RELEASE_DIR="${RELEASE_DIR:-$PROJECT_DIR/.artifacts/releases}"
IMAGE_SCANNER_MODE="${IMAGE_SCANNER_MODE:-required}"
ALLOW_MUTABLE_IMAGE_TAGS="${ALLOW_MUTABLE_IMAGE_TAGS:-false}"
DEPLOY_PULL="${DEPLOY_PULL:-false}"
CHECK_DIR="$(mktemp -d)"

cleanup() {
  rm -r "$CHECK_DIR" >/dev/null 2>&1 || true
}

fail() {
  echo "Production deployment failed: $1" >&2
  exit 1
}

trap cleanup EXIT HUP INT TERM
umask 077

[ -f "$ENV_FILE" ] || fail "ENV_FILE does not exist"
[ -f "$COMPOSE_FILE" ] || fail "COMPOSE_FILE does not exist"
command -v docker >/dev/null 2>&1 || fail "Docker CLI is required"
command -v sha256sum >/dev/null 2>&1 || fail "sha256sum is required"

compose() {
  docker compose \
    --env-file "$ENV_FILE" \
    -f "$COMPOSE_FILE" \
    "$@"
}

service_image() {
  service="$1"
  compose config | awk -v service="$service" '
    $0 == "  " service ":" {
      in_service = 1
      next
    }
    in_service && /^  [a-zA-Z0-9_.-]+:$/ {
      exit
    }
    in_service && /^    image: / {
      sub(/^    image: /, "")
      print
      exit
    }
  '
}

assert_immutable_application_image() {
  image="$1"
  role="$2"
  if printf '%s\n' "$image" \
      | grep -Eq '@sha256:[0-9a-f]{64}$'; then
    return 0
  fi
  [ "$ALLOW_MUTABLE_IMAGE_TAGS" = true ] \
    || fail "$role image must be pinned by digest"
  echo "WARNING: $role uses a mutable image reference" >&2
}

record_release() {
  state="$1"
  timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
  output="$RELEASE_DIR/$timestamp-$state.txt"
  install -d -m 0700 "$RELEASE_DIR"
  {
    printf 'recorded_at_utc=%s\n' "$timestamp"
    printf 'state=%s\n' "$state"
    printf 'git_revision=%s\n' "$(
      git -C "$PROJECT_DIR" rev-parse HEAD 2>/dev/null \
        || printf 'unavailable'
    )"
    printf 'compose_sha256=%s\n' "$(
      sha256sum "$CHECK_DIR/compose.yaml" | awk '{print $1}'
    )"
    for service in \
      db mosquitto mosquitto-config-init backend frontend
    do
      container_id="$(compose ps --all -q "$service")"
      [ -n "$container_id" ] || continue
      image_id="$(docker inspect \
        --format '{{.Image}}' "$container_id")"
      image_ref="$(service_image "$service")"
      repo_digests="$(docker image inspect \
        --format '{{join .RepoDigests ","}}' \
        "$image_id" 2>/dev/null || true)"
      printf '%s_image_ref=%s\n' "$service" "$image_ref"
      printf '%s_image_id=%s\n' "$service" "$image_id"
      printf '%s_repo_digests=%s\n' "$service" "$repo_digests"
    done
  } > "$output"
  chmod 0600 "$output"
  echo "Release record: $output"
}

cd "$PROJECT_DIR"
compose config --quiet
compose config > "$CHECK_DIR/compose.yaml"

backend_image="$(service_image backend)"
frontend_image="$(service_image frontend)"
[ -n "$backend_image" ] || fail "Backend image could not be resolved"
[ -n "$frontend_image" ] || fail "Frontend image could not be resolved"
assert_immutable_application_image "$backend_image" Backend
assert_immutable_application_image "$frontend_image" Frontend

ENV_FILE="$ENV_FILE" \
COMPOSE_FILE="$COMPOSE_FILE" \
  sh "$PROJECT_DIR/scripts/verify-production-compose.sh"

if [ "$DEPLOY_PULL" = true ]; then
  compose pull \
    db mosquitto mosquitto-config-init backend frontend
fi

BACKEND_IMAGE="$backend_image" \
FRONTEND_IMAGE="$frontend_image" \
IMAGE_SCANNER_MODE="$IMAGE_SCANNER_MODE" \
IMAGE_VERIFY_MODE=static \
  sh "$PROJECT_DIR/scripts/verify-production-images.sh"

# Flyway가 실행된 뒤의 자동 image rollback은 migration 호환성을 보장할 수 없다.
# 실패 시 현재 상태와 log를 보존하고 운영자가 Runbook의 호환성 판단을 수행한다.
compose up --detach --no-build --wait --wait-timeout 240 \
  || fail "Compose did not become healthy; inspect logs before rollback"

frontend_id="$(compose ps -q frontend)"
backend_id="$(compose ps -q backend)"
[ -n "$frontend_id" ] && [ -n "$backend_id" ] \
  || fail "application containers are missing"

docker exec "$frontend_id" \
  wget --quiet --output-document=- \
  http://127.0.0.1:8080/healthz \
  | grep -Fxq ok \
  || fail "Frontend health check failed"
docker exec "$frontend_id" \
  wget --quiet --output-document=- \
  http://127.0.0.1:8080/api/health \
  | grep -Fq '"status":"UP"' \
  || fail "Frontend-to-Backend health check failed"

record_release deployed
echo "Production deployment: PASS"
