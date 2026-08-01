#!/bin/sh

set -eu
set +x

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$PROJECT_DIR/compose.prod.yaml}"
ENV_FILE="${ENV_FILE:-}"
RUNTIME_ENV_FILE="${RUNTIME_ENV_FILE:-}"
IMAGE_ENV_FILE="${IMAGE_ENV_FILE:-}"
BACKUP_DIR="${BACKUP_DIR:-}"
PARTIAL_FILE=""

if [ -z "$ENV_FILE" ] \
    && [ -z "$RUNTIME_ENV_FILE" ] \
    && [ -z "$IMAGE_ENV_FILE" ]; then
  ENV_FILE="$PROJECT_DIR/.env.prod"
fi

fail() {
  echo "PostgreSQL backup failed: $1" >&2
  exit 1
}

cleanup() {
  if [ -n "$PARTIAL_FILE" ]; then
    rm -f "$PARTIAL_FILE" >/dev/null 2>&1 || true
  fi
}

trap cleanup EXIT HUP INT TERM
umask 077

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
[ -n "$BACKUP_DIR" ] || fail "BACKUP_DIR is required"
case "$BACKUP_DIR" in
  /*)
    ;;
  *)
    fail "BACKUP_DIR must be an absolute path"
    ;;
esac
install -d -m 0700 "$BACKUP_DIR"

compose() {
  if [ -n "$ENV_FILE" ]; then
    docker compose \
      --env-file "$ENV_FILE" \
      -f "$COMPOSE_FILE" \
      "$@"
  else
    docker compose \
      --env-file "$RUNTIME_ENV_FILE" \
      --env-file "$IMAGE_ENV_FILE" \
      -f "$COMPOSE_FILE" \
      "$@"
  fi
}

cd "$PROJECT_DIR"
compose config --quiet
db_id="$(compose ps -q db)"
[ -n "$db_id" ] || fail "PostgreSQL container is not running"
[ "$(docker inspect \
  --format '{{.State.Health.Status}}' "$db_id")" = healthy ] \
  || fail "PostgreSQL is not healthy"

project_name="$(compose config | sed -n 's/^name: //p' | head -n 1)"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup_file="$BACKUP_DIR/$timestamp-$project_name.dump"
PARTIAL_FILE="$backup_file.partial"

compose exec -T db sh -ec '
  export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"
  exec pg_dump \
    --host 127.0.0.1 \
    --username "$POSTGRES_USER" \
    --dbname "$POSTGRES_DB" \
    --no-password \
    --format=custom \
    --compress=6
' > "$PARTIAL_FILE"

[ -s "$PARTIAL_FILE" ] || fail "pg_dump output is empty"
docker run \
  --rm \
  --interactive \
  --entrypoint pg_restore \
  postgis/postgis:16-3.5 \
  --list < "$PARTIAL_FILE" >/dev/null \
  || fail "pg_restore could not read the backup"

mv "$PARTIAL_FILE" "$backup_file"
PARTIAL_FILE=""
sha256sum "$backup_file" > "$backup_file.sha256"
chmod 0600 "$backup_file" "$backup_file.sha256"

echo "PostgreSQL backup: PASS"
echo "Backup file: $backup_file"
