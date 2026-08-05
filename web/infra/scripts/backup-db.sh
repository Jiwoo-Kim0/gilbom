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
BACKUP_NAME=postgres
STARTED_AT="$(date -u +%s)"

# shellcheck source=infra/scripts/backup-common.sh
. "$SCRIPT_DIR/backup-common.sh"

if [ -z "$ENV_FILE" ] \
    && [ -z "$RUNTIME_ENV_FILE" ] \
    && [ -z "$IMAGE_ENV_FILE" ]; then
  ENV_FILE="$PROJECT_DIR/.env.prod"
fi

fail() {
  echo "PostgreSQL backup failed: $1" >&2
  # 실패도 metric으로 남긴다. 남기지 않으면 timer가 조용히 죽은 것과 구분되지 않는다.
  gilbom_backup_record_result \
    "$BACKUP_NAME" 0 "$(( $(date -u +%s) - STARTED_AT ))" 0 \
    2>/dev/null || true
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

# 암호화 수단이 없으면 dump를 뜨기 전에 멈춘다. 평문 dump를 만들어 놓고 실패하면
# 개인정보가 든 파일이 디스크에 남는다.
gilbom_backup_require_gpg
recipient_fingerprints="$(gilbom_backup_recipient_fingerprints)"

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
chmod 0600 "$backup_file"

# 개인정보가 든 dump는 평문으로 디스크에 남기지 않는다. 암호화가 끝나면 원본을 지운다.
encrypted_file="$(gilbom_backup_encrypt_file "$backup_file")"
gilbom_backup_checksum "$encrypted_file"

manifest_file="$encrypted_file.manifest.json"
gilbom_backup_write_manifest \
  "$manifest_file" \
  "$BACKUP_NAME" \
  "$recipient_fingerprints" \
  "$encrypted_file" \
  "$encrypted_file.sha256"

gilbom_backup_prune "$BACKUP_DIR" '*.dump.gpg'
gilbom_backup_prune "$BACKUP_DIR" '*.dump.gpg.sha256'
gilbom_backup_prune "$BACKUP_DIR" '*.dump.gpg.manifest.json'

gilbom_backup_record_result \
  "$BACKUP_NAME" \
  1 \
  "$(( $(date -u +%s) - STARTED_AT ))" \
  "$(gilbom_backup_directory_size "$encrypted_file")"

echo "PostgreSQL backup: PASS"
echo "Backup file: $encrypted_file"
echo "Manifest: $manifest_file"
echo "TOTP keyring은 이 번들에 들어 있지 않다. 별도 경로로 따로 반출한다."
