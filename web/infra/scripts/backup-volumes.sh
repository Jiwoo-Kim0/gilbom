#!/bin/sh

set -eu
set +x

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$PROJECT_DIR/compose.prod.yaml}"
ENV_FILE="${ENV_FILE:-}"
RUNTIME_ENV_FILE="${RUNTIME_ENV_FILE:-}"
IMAGE_ENV_FILE="${IMAGE_ENV_FILE:-}"
BACKUP_ROOT="${BACKUP_ROOT:-}"
QUIESCED=false

if [ -z "$ENV_FILE" ] \
    && [ -z "$RUNTIME_ENV_FILE" ] \
    && [ -z "$IMAGE_ENV_FILE" ]; then
  ENV_FILE="$PROJECT_DIR/.env.prod"
fi

fail() {
  echo "Production volume backup failed: $1" >&2
  exit 1
}

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

# robot_images가 JPEG의 유일한 사본이므로 DB가 참조하는 모든 AVAILABLE
# object_key와 hash를 먼저 고정한다. orphan 파일은 복구 가능성을 위해 함께
# 보존하되, DB가 참조하는 파일이 없거나 변조된 경우에는 backup 성공으로 보지 않는다.
robot_image_database_manifest="$bundle/robot_images.database.sha256"
compose exec -T db sh -ec '
  export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"
  exec psql \
    --host 127.0.0.1 \
    --username "$POSTGRES_USER" \
    --dbname "$POSTGRES_DB" \
    --no-password \
    --tuples-only \
    --no-align \
    --quiet \
    --set ON_ERROR_STOP=1 \
    --command "
      SELECT sha256_hex || '\''  '\'' || object_key
      FROM storage.robot_image
      WHERE bucket_name = '\''local-disk'\''
        AND image_status = '\''AVAILABLE'\''
        AND deleted_at IS NULL
      ORDER BY object_key;
    "
' > "$robot_image_database_manifest"
chmod 0600 "$robot_image_database_manifest"

BACKUP_DIR="$bundle" \
ENV_FILE="$ENV_FILE" \
RUNTIME_ENV_FILE="$RUNTIME_ENV_FILE" \
IMAGE_ENV_FILE="$IMAGE_ENV_FILE" \
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

robot_images_volume="$(
  docker volume ls \
    --filter "label=com.docker.compose.project=$project_name" \
    --filter "label=com.docker.compose.volume=robot_images" \
    --quiet
)"
[ -n "$robot_images_volume" ] \
  || fail "named volume is missing: robot_images"
robot_image_volume_manifest="$bundle/robot_images.volume.sha256"
docker run \
  --rm \
  --read-only \
  --mount "type=volume,src=$robot_images_volume,dst=/source,readonly" \
  --mount "type=bind,src=$bundle,dst=/backup" \
  --tmpfs /tmp:rw,noexec,nosuid,nodev,size=16m \
  alpine:3.22 \
  sh -ec '
    cd /source
    find . -type f -name "*.jpg" -exec sha256sum {} \; \
      | sed "s#  \./#  #" \
      | sort > /backup/robot_images.volume.sha256
    chown "$1:$2" /backup/robot_images.volume.sha256
  ' sh "$(id -u)" "$(id -g)"
chmod 0600 "$robot_image_volume_manifest"

missing_image=false
while IFS= read -r expected_image
do
  [ -n "$expected_image" ] || continue
  if ! grep -Fxq "$expected_image" "$robot_image_volume_manifest"; then
    printf '%s\n' \
      "Missing or altered robot image: ${expected_image#*  }" >&2
    missing_image=true
  fi
done < "$robot_image_database_manifest"
[ "$missing_image" = false ] \
  || fail "robot_images volume does not cover every AVAILABLE database row"

database_image_count="$(wc -l < "$robot_image_database_manifest" | tr -d ' ')"
volume_image_count="$(wc -l < "$robot_image_volume_manifest" | tr -d ' ')"
{
  printf 'database_available_images=%s\n' "$database_image_count"
  printf 'volume_jpeg_files=%s\n' "$volume_image_count"
  printf '%s\n' 'database_image_coverage=PASS'
} > "$bundle/robot_images.summary"
chmod 0600 "$bundle/robot_images.summary"

(
  cd "$bundle"
  sha256sum \
    ./*.dump \
    ./*.dump.sha256 \
    ./*.tar.gz \
    ./robot_images.database.sha256 \
    ./robot_images.volume.sha256 \
    ./robot_images.summary \
    > SHA256SUMS
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
