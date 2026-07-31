#!/bin/sh

set -eu
set +x

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$PROJECT_DIR/compose.prod.yaml}"
ENV_FILE="${ENV_FILE:-$PROJECT_DIR/.env.prod}"
BACKUP_ROOT="${BACKUP_ROOT:-}"
QUIESCED=false

fail() {
  echo "Production volume backup failed: $1" >&2
  exit 1
}

compose() {
  docker compose \
    --env-file "$ENV_FILE" \
    -f "$COMPOSE_FILE" \
    "$@"
}

resume_services() {
  if [ "$QUIESCED" = true ]; then
    compose up \
      --detach \
      --no-build \
      --wait \
      --wait-timeout 240 >/dev/null 2>&1 || true
  fi
}

trap resume_services EXIT HUP INT TERM
umask 077

[ -f "$ENV_FILE" ] || fail "ENV_FILE does not exist"
[ -n "$BACKUP_ROOT" ] || fail "BACKUP_ROOT is required"
case "$BACKUP_ROOT" in
  /*)
    ;;
  *)
    fail "BACKUP_ROOT must be an absolute path"
    ;;
esac

cd "$PROJECT_DIR"
compose config --quiet
project_name="$(compose config | sed -n 's/^name: //p' | head -n 1)"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
bundle="$BACKUP_ROOT/$timestamp-$project_name"
install -d -m 0700 "$bundle"

for service in db mosquitto backend frontend
do
  id="$(compose ps -q "$service")"
  [ -n "$id" ] || fail "$service is not running"
done

# 파일과 MQTT session snapshot을 일관되게 만들기 위해 외부 진입점과 writer를
# 잠시 중지한다. PostgreSQL은 transaction-consistent pg_dump를 위해 유지한다.
compose stop frontend backend mosquitto
QUIESCED=true

BACKUP_DIR="$bundle" \
ENV_FILE="$ENV_FILE" \
COMPOSE_FILE="$COMPOSE_FILE" \
  sh "$SCRIPT_DIR/backup-db.sh"

for logical_name in \
  report_pdf_data \
  robot_images \
  mosquitto_config \
  mosquitto_credentials \
  mosquitto_data
do
  volume_name="$(docker volume ls \
    --filter "label=com.docker.compose.project=$project_name" \
    --filter "label=com.docker.compose.volume=$logical_name" \
    --quiet)"
  [ -n "$volume_name" ] \
    || fail "named volume is missing: $logical_name"
  archive="$bundle/$logical_name.tar.gz"
  docker run \
    --rm \
    --read-only \
    --mount "type=volume,src=$volume_name,dst=/source,readonly" \
    --mount "type=bind,src=$bundle,dst=/backup" \
    --tmpfs /tmp:rw,noexec,nosuid,nodev,size=16m \
    alpine:3.22 \
    sh -ec '
      tar -C /source -czf "/backup/$1.tar.gz" .
      chown "$2:$3" "/backup/$1.tar.gz"
    ' sh "$logical_name" "$(id -u)" "$(id -g)"
  [ -s "$archive" ] || fail "volume archive is empty: $logical_name"
  chmod 0600 "$archive"
done

(
  cd "$bundle"
  sha256sum ./*.dump ./*.tar.gz > SHA256SUMS
)
chmod 0600 "$bundle/SHA256SUMS"

compose up \
  --detach \
  --no-build \
  --wait \
  --wait-timeout 240 \
  || fail "services did not recover after backup"
QUIESCED=false

echo "Production volume backup: PASS"
echo "Backup bundle: $bundle"
