#!/bin/sh

set -eu

DEPLOY_DIR="${DEPLOY_DIR:-/opt/gilbom/deploy}"
RUNTIME_ENV_FILE="${RUNTIME_ENV_FILE:-$DEPLOY_DIR/runtime.env}"
IMAGE_ENV_FILE="${IMAGE_ENV_FILE:-$DEPLOY_DIR/image.env}"
COMPOSE_FILE="${COMPOSE_FILE:-$DEPLOY_DIR/compose.prod.yaml}"
EVENT_ID="${EVENT_ID:-}"
IMAGE_ID="${IMAGE_ID:-}"
PERSISTENCE_RECREATE_APPROVED="${PERSISTENCE_RECREATE_APPROVED:-false}"

fail() {
  echo "Day 8 persistence verification failed: $1" >&2
  exit 1
}

compose() {
  docker compose \
    --env-file "$RUNTIME_ENV_FILE" \
    --env-file "$IMAGE_ENV_FILE" \
    -f "$COMPOSE_FILE" \
    "$@"
}

db_query() {
  sql=$1
  compose exec -T db /bin/sh -ec '
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
      --command "$1"
  ' sh "$sql"
}

volume_manifest() {
  root=$1
  compose exec -T backend /bin/sh -ec '
    root=$1
    test -d "$root"
    find "$root" -type f -exec sha256sum {} \; | sort
  ' sh "$root"
}

stored_file_hash() {
  root=$1
  object_key=$2
  compose exec -T \
    -e STORAGE_ROOT="$root" \
    -e OBJECT_KEY="$object_key" \
    backend /bin/sh -ec '
      case "$OBJECT_KEY" in
        ""|/*|../*|*/../*|*/..)
          exit 2
          ;;
      esac
      test -s "$STORAGE_ROOT/$OBJECT_KEY"
      sha256sum "$STORAGE_ROOT/$OBJECT_KEY" | awk "{print \$1}"
    '
}

printf '%s\n' "$EVENT_ID" \
  | grep -Eq '^[0-9a-fA-F-]{36}$' \
  || fail "EVENT_ID must be the UUID printed by publish-dev-incident.py"
printf '%s\n' "$IMAGE_ID" \
  | grep -Eq '^[0-9a-fA-F-]{36}$' \
  || fail "IMAGE_ID must be the UUID printed by publish-dev-incident.py"
[ "$PERSISTENCE_RECREATE_APPROVED" = true ] \
  || fail "set PERSISTENCE_RECREATE_APPROVED=true to authorize Backend recreation"

image_row="$(
  db_query "
    SELECT image_id
           || '|' || event_id
           || '|' || bucket_name
           || '|' || object_key
           || '|' || content_type
           || '|' || byte_size
           || '|' || sha256_hex
    FROM storage.robot_image
    WHERE image_id = '$IMAGE_ID'::uuid
      AND event_id = '$EVENT_ID'::uuid
      AND image_status = 'AVAILABLE';
  "
)"
[ -n "$image_row" ] || fail "uploaded image metadata is missing"

old_ifs=$IFS
IFS='|'
set -- $image_row
IFS=$old_ifs
[ "$#" -eq 7 ] || fail "uploaded image metadata is malformed"
[ "$1" = "$IMAGE_ID" ] || fail "database image_id differs"
[ "$2" = "$EVENT_ID" ] || fail "database event_id differs"
[ "$3" = local-disk ] || fail "bucket_name is not local-disk"
object_key=$4
[ "$5" = image/jpeg ] || fail "content_type is not image/jpeg"
[ "$6" -gt 0 ] && [ "$6" -le 512000 ] \
  || fail "stored JPEG byte size is outside 1..512000"
database_image_hash=$7

actual_image_hash="$(
  stored_file_hash /app/data/robot-images "$object_key"
)"
[ "$actual_image_hash" = "$database_image_hash" ] \
  || fail "database sha256 and robot_images file differ"

attempt=0
while :
do
  linked="$(
    db_query "
      SELECT (
        EXISTS (
          SELECT 1
          FROM telemetry.robot_event
          WHERE event_id = '$EVENT_ID'::uuid
            AND event_type = 'BRAILLE_BLOCK_INCIDENT_DETECTED'
        )
        AND EXISTS (
          SELECT 1
          FROM defect.defect_observation
          WHERE event_id = '$EVENT_ID'::uuid
            AND image_id = '$IMAGE_ID'::uuid
        )
      )::int;
    "
  )"
  [ "$linked" = 1 ] && break
  attempt=$((attempt + 1))
  [ "$attempt" -lt 30 ] \
    || fail "MQTT Incident was not linked to the uploaded image"
  sleep 1
done

pdf_rows="$(
  db_query "
    SELECT approved_pdf_storage_key
           || '|' || ENCODE(approved_pdf_sha256, 'hex')
    FROM report.anomaly_report
    WHERE approved_pdf_storage_key IS NOT NULL
    ORDER BY report_id;
  "
)"
[ -n "$pdf_rows" ] \
  || fail "no approved report PDF exists; approve one report before this gate"

printf '%s\n' "$pdf_rows" \
  | while IFS='|' read -r pdf_key pdf_hash
    do
      [ -n "$pdf_key" ] && [ -n "$pdf_hash" ] \
        || fail "approved PDF metadata is incomplete"
      actual_pdf_hash="$(
        stored_file_hash /app/data/report-pdf "$pdf_key"
      )"
      [ "$actual_pdf_hash" = "$pdf_hash" ] \
        || fail "approved PDF DB hash and file differ: $pdf_key"
    done

image_manifest_before="$(volume_manifest /app/data/robot-images)"
pdf_manifest_before="$(volume_manifest /app/data/report-pdf)"
[ -n "$image_manifest_before" ] || fail "robot_images volume is empty"
[ -n "$pdf_manifest_before" ] || fail "report_pdf_data volume is empty"
signing_key_hash_before="$(
  compose exec -T backend \
    sha256sum /run/secrets/gilbom_storage_signing_key \
    | awk '{print $1}'
)"

compose up \
  --detach \
  --no-build \
  --no-deps \
  --force-recreate \
  --wait \
  --wait-timeout 180 \
  backend

backend_id="$(compose ps -q backend)"
[ -n "$backend_id" ] || fail "Backend was not recreated"
[ "$(docker inspect --format '{{.State.Health.Status}}' "$backend_id")" = healthy ] \
  || fail "recreated Backend is not healthy"

image_manifest_after="$(volume_manifest /app/data/robot-images)"
pdf_manifest_after="$(volume_manifest /app/data/report-pdf)"
signing_key_hash_after="$(
  compose exec -T backend \
    sha256sum /run/secrets/gilbom_storage_signing_key \
    | awk '{print $1}'
)"

[ "$image_manifest_before" = "$image_manifest_after" ] \
  || fail "robot image files changed across Backend recreation"
[ "$pdf_manifest_before" = "$pdf_manifest_after" ] \
  || fail "approved PDF files changed across Backend recreation"
[ "$signing_key_hash_before" = "$signing_key_hash_after" ] \
  || fail "storage signing Secret changed across Backend recreation"
[ "$(stored_file_hash /app/data/robot-images "$object_key")" = "$database_image_hash" ] \
  || fail "uploaded image is not readable after Backend recreation"

defect_id="$(
  db_query "
    SELECT defect_id
    FROM defect.defect_observation
    WHERE event_id = '$EVENT_ID'::uuid
      AND image_id = '$IMAGE_ID'::uuid;
  "
)"
[ -n "$defect_id" ] || fail "linked defect_id is missing after recreation"

echo "Day 8 image, Incident link, PDF, volume and signing Secret persistence: PASS"
echo "defect-id=$defect_id"
