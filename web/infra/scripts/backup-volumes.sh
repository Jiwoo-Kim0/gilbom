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
BACKUP_NAME=volumes
STARTED_AT="$(date -u +%s)"

# shellcheck source=infra/scripts/backup-common.sh
. "$SCRIPT_DIR/backup-common.sh"

if [ -z "$ENV_FILE" ] \
    && [ -z "$RUNTIME_ENV_FILE" ] \
    && [ -z "$IMAGE_ENV_FILE" ]; then
  ENV_FILE="$PROJECT_DIR/.env.prod"
fi

fail() {
  echo "Production volume backup failed: $1" >&2
  # 실패도 metric으로 남긴다. 남기지 않으면 timer가 죽은 것과 구분되지 않는다.
  gilbom_backup_record_result \
    "$BACKUP_NAME" 0 "$(( $(date -u +%s) - STARTED_AT ))" 0 \
    2>/dev/null || true
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

resolve_volume() {
  logical_name=$1
  resolved="$(
    docker volume ls \
      --filter "label=com.docker.compose.project=$project_name" \
      --filter "label=com.docker.compose.volume=$logical_name" \
      --quiet
  )"
  [ -n "$resolved" ] \
    || fail "named volume is missing: $logical_name"
  [ "$(printf '%s\n' "$resolved" | wc -l | tr -d ' ')" -eq 1 ] \
    || fail "multiple named volumes matched: $logical_name"
  printf '%s\n' "$resolved"
}

write_volume_content_manifest() {
  logical_name=$1
  output_name=$2
  volume_name="$(resolve_volume "$logical_name")"
  docker run \
    --rm \
    --read-only \
    --mount "type=volume,src=$volume_name,dst=/source,readonly" \
    --mount "type=bind,src=$bundle,dst=/backup" \
    --tmpfs /tmp:rw,noexec,nosuid,nodev,size=16m \
    alpine:3.22 \
    sh -ec '
      cd /source
      find . -type f -exec sha256sum {} \; \
        | sed "s#  \./#  #" \
        | sort > "/backup/$1"
      chown "$2:$3" "/backup/$1"
    ' sh "$output_name" "$(id -u)" "$(id -g)"
  chmod 0600 "$bundle/$output_name"
}

verify_reference_coverage() {
  label=$1
  database_manifest=$2
  volume_manifest=$3
  missing=false
  while IFS= read -r expected_file
  do
    [ -n "$expected_file" ] || continue
    if ! grep -Fxq "$expected_file" "$volume_manifest"; then
      printf '%s\n' \
        "Missing or altered $label file: ${expected_file#*  }" >&2
      missing=true
    fi
  done < "$database_manifest"
  [ "$missing" = false ] \
    || fail "$label volume does not cover every database reference"
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

# 서비스를 멈추기 전에 암호화 수단을 확인한다. 멈춘 뒤에 실패하면 정지 시간만 늘어난다.
gilbom_backup_require_gpg
recipient_fingerprint="$(gilbom_backup_recipient_fingerprint)"

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

# 애플리케이션 전체 DB를 table별 정규화된 SHA-256으로 고정한다. 복원 후 같은
# 스크립트로 다시 생성한 manifest와 byte-for-byte로 같아야 restore PASS다.
database_content_manifest="$bundle/database.content.sha256"
compose exec -T db sh -ec '
  export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"
  export PGHOST=127.0.0.1
  export PGUSER="$POSTGRES_USER"
  export PGDATABASE="$POSTGRES_DB"
  exec sh -s
' < "$SCRIPT_DIR/generate-database-content-manifest.sh" \
  > "$database_content_manifest"
chmod 0600 "$database_content_manifest"

# DB가 참조하는 image/PDF의 storage key와 hash를 함께 고정한다. orphan 파일도
# 복구 가능성을 위해 archive에는 보존하지만, DB 참조 파일이 없거나 변조됐다면
# backup 성공으로 보지 않는다.
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

report_pdf_database_manifest="$bundle/report_pdf_data.database.sha256"
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
      SELECT encode(approved_pdf_sha256, '\''hex'\'')
          || '\''  '\'' || approved_pdf_storage_key
      FROM report.anomaly_report
      WHERE approved_pdf_storage_key IS NOT NULL
        AND approved_pdf_sha256 IS NOT NULL
      ORDER BY approved_pdf_storage_key;
    "
' > "$report_pdf_database_manifest"
chmod 0600 "$report_pdf_database_manifest"

robot_image_volume_manifest="$bundle/robot_images.volume.sha256"
report_pdf_volume_manifest="$bundle/report_pdf_data.volume.sha256"
write_volume_content_manifest robot_images robot_images.volume.sha256
write_volume_content_manifest report_pdf_data report_pdf_data.volume.sha256

verify_reference_coverage \
  "robot image" \
  "$robot_image_database_manifest" \
  "$robot_image_volume_manifest"
verify_reference_coverage \
  "report PDF" \
  "$report_pdf_database_manifest" \
  "$report_pdf_volume_manifest"

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
  volume_name="$(resolve_volume "$logical_name")"
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
  # mosquitto credential과 보고서 PDF가 들어 있다. 평문으로 두지 않는다.
  gilbom_backup_encrypt_file "$archive" >/dev/null
done

database_image_count="$(wc -l < "$robot_image_database_manifest" | tr -d ' ')"
volume_image_count="$(wc -l < "$robot_image_volume_manifest" | tr -d ' ')"
{
  printf 'database_available_images=%s\n' "$database_image_count"
  printf 'volume_files=%s\n' "$volume_image_count"
  printf '%s\n' 'database_image_coverage=PASS'
} > "$bundle/robot_images.summary"
chmod 0600 "$bundle/robot_images.summary"

database_pdf_count="$(wc -l < "$report_pdf_database_manifest" | tr -d ' ')"
volume_pdf_count="$(wc -l < "$report_pdf_volume_manifest" | tr -d ' ')"
{
  printf 'database_approved_pdfs=%s\n' "$database_pdf_count"
  printf 'volume_files=%s\n' "$volume_pdf_count"
  printf '%s\n' 'database_pdf_coverage=PASS'
} > "$bundle/report_pdf_data.summary"
chmod 0600 "$bundle/report_pdf_data.summary"

# dump와 volume archive는 이미 암호화됐다. 아래 manifest는 hash와 논리 경로만
# 포함하므로 복원 대조용으로 둔다. SHA256SUMS가 manifest 자체의 변조도 막는다.
(
  cd "$bundle"
  sha256sum \
    ./*.dump.gpg \
    ./*.dump.gpg.sha256 \
    ./*.tar.gz.gpg \
    ./database.content.sha256 \
    ./report_pdf_data.database.sha256 \
    ./report_pdf_data.volume.sha256 \
    ./report_pdf_data.summary \
    ./robot_images.database.sha256 \
    ./robot_images.volume.sha256 \
    ./robot_images.summary \
    > SHA256SUMS
)
chmod 0600 "$bundle/SHA256SUMS"

gilbom_backup_write_manifest \
  "$bundle/bundle.manifest.json" \
  "$BACKUP_NAME" \
  "$recipient_fingerprint" \
  "$bundle/SHA256SUMS" \
  "$bundle/database.content.sha256" \
  "$bundle/robot_images.summary" \
  "$bundle/report_pdf_data.summary"

compose up \
  --detach \
  --no-build \
  --wait \
  --wait-timeout 240 \
  || fail "services did not recover after backup"
QUIESCED=false

gilbom_backup_prune_directories "$BACKUP_ROOT" "*-$project_name"

gilbom_backup_record_result \
  "$BACKUP_NAME" \
  1 \
  "$(( $(date -u +%s) - STARTED_AT ))" \
  "$(gilbom_backup_directory_size "$bundle")"

echo "Production volume backup: PASS"
echo "Backup bundle: $bundle"
echo "Manifest: $bundle/bundle.manifest.json"
