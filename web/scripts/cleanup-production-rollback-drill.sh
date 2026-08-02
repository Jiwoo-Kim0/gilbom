#!/bin/sh

set -eu
set +x

fail() {
  echo "Production rollback drill cleanup failed: $1" >&2
  exit 1
}

[ "$#" -eq 1 ] \
  || fail "usage: $0 ROLLBACK_DRILL_RUN_ID"
run_id="$1"
printf '%s\n' "$run_id" | grep -Eq '^[a-z0-9][a-z0-9-]{0,30}$' \
  || fail "unsafe rollback drill run identifier"
[ "${DOCKER_TLS_VERIFY:-}" = 1 ] \
  || fail "cleanup requires Jenkins DinD TLS"
case "${DOCKER_HOST:-}" in
  tcp://*) ;;
  *) fail "cleanup refuses a local Docker Socket" ;;
esac
[ ! -S /var/run/docker.sock ] \
  || fail "the Jenkins executor must not mount the Docker Socket"

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$PROJECT_DIR/compose.rollback-drill.yaml"
base_project="gilbom-rollback-drill-$run_id"
dummy_backend="gilbom-rollback-drill-cleanup/backend:local"
dummy_frontend="gilbom-rollback-drill-cleanup/frontend:local"

for scenario in a b c
do
  project="$base_project-$scenario"
  COMPOSE_PROJECT_NAME="$project" \
  BACKEND_IMAGE="$dummy_backend" \
  FRONTEND_IMAGE="$dummy_frontend" \
    docker compose --file "$COMPOSE_FILE" \
      down --volumes --remove-orphans >/dev/null 2>&1 || true
done

image_prefix="gilbom-rollback-drill-$run_id"
image_ids="$(
  docker image ls \
    --filter "reference=$image_prefix-*" \
    --quiet | LC_ALL=C sort -u
)"
if [ -n "$image_ids" ]; then
  # shellcheck disable=SC2086
  docker image rm $image_ids >/dev/null 2>&1 || true
fi

echo "Production rollback drill cleanup: PASS"
