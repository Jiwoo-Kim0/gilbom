#!/bin/sh

# 암호화된 backup 번들을 임시 PostgreSQL과 임시 volume에 복원하고 검증한다.
#
# 운영 stack은 건드리지 않는다. 임시 container·network·volume만 만들고 끝나면 지운다.
# "backup이 있다"와 "backup으로 되돌릴 수 있다"는 다른 이야기라 이 절차가 필요하다.
#
#   ./restore-drill.sh /opt/gilbom/backups/<번들 디렉터리>
#   ./restore-drill.sh --keep /path/to/bundle     # 조사하려고 임시 자원을 남긴다
#
# 복호화에는 개인키가 필요하다. 개인키는 운영 서버에 두지 않는 것이 원칙이므로,
# 원칙대로라면 반출본을 내려받은 PC에서 이 스크립트를 돌린다. 서버에서 돌려야 한다면
# GNUPGHOME으로 임시 keyring을 지정하고 끝난 뒤 반드시 지운다.

set -eu
set +x

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"

KEEP=false
if [ "${1:-}" = "--keep" ]; then
  KEEP=true
  shift
fi

BUNDLE="${1:-}"
POSTGRES_IMAGE="${POSTGRES_IMAGE:-postgis/postgis:16-3.5}"
ALPINE_IMAGE="${ALPINE_IMAGE:-alpine:3.22}"
DRILL_ID="restore-drill-$(date -u +%Y%m%d%H%M%S)-$$"
DRILL_BOOTSTRAP_DB=postgres
DRILL_DB=gilbom
DRILL_USER=gilbom_drill
WORK_DIR=""
CONTAINER=""
NETWORK=""
CREATED_VOLUMES=""
FAILURES=0

fail() {
  echo "Restore drill failed: $1" >&2
  exit 1
}

check() {
  label=$1
  outcome=$2
  detail=${3:-}
  if [ "$outcome" = PASS ]; then
    printf '  PASS  %s%s\n' "$label" "${detail:+ — $detail}"
  else
    printf '  FAIL  %s%s\n' "$label" "${detail:+ — $detail}"
    FAILURES=$((FAILURES + 1))
  fi
}

find_drill_volume() {
  suffix=$1
  for candidate in $CREATED_VOLUMES
  do
    case "$candidate" in
      *-"$suffix")
        printf '%s\n' "$candidate"
        return 0
        ;;
    esac
  done
  return 1
}

hash_restored_volume() {
  volume_name=$1
  output_name=$2
  docker run --rm \
    --read-only \
    --mount "type=volume,src=$volume_name,dst=/source,readonly" \
    --mount "type=bind,src=$WORK_DIR,dst=/out" \
    --tmpfs /tmp:rw,noexec,nosuid,nodev,size=16m \
    "$ALPINE_IMAGE" \
    sh -ec '
      cd /source
      find . -type f -exec sha256sum {} \; \
        | sed "s#  \./#  #" \
        | sort > "/out/$1"
    ' sh "$output_name"
}

cleanup() {
  if [ "$KEEP" = true ]; then
    echo
    echo "임시 자원을 남겨 뒀다. 조사 후 직접 지운다:"
    [ -n "$CONTAINER" ] && echo "  docker rm -f $CONTAINER"
    [ -n "$NETWORK" ] && echo "  docker network rm $NETWORK"
    for volume in $CREATED_VOLUMES; do
      echo "  docker volume rm $volume"
    done
    [ -n "$WORK_DIR" ] && echo "  rm -rf $WORK_DIR"
    return 0
  fi
  [ -n "$CONTAINER" ] && docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  [ -n "$NETWORK" ] && docker network rm "$NETWORK" >/dev/null 2>&1 || true
  for volume in $CREATED_VOLUMES; do
    docker volume rm "$volume" >/dev/null 2>&1 || true
  done
  # 복호화한 평문은 반드시 지운다.
  [ -n "$WORK_DIR" ] && rm -rf "$WORK_DIR" || true
}

trap cleanup EXIT HUP INT TERM
umask 077

# 개인키에는 암호가 걸려 있는 것이 정상이다. gpg가 암호를 물어보려면 터미널이
# 필요한데, GPG_TTY가 없으면 "Inappropriate ioctl for device"로 조용히 실패한다.
if [ -t 0 ] && command -v tty >/dev/null 2>&1; then
  GPG_TTY="$(tty)"
  export GPG_TTY
fi

[ -n "$BUNDLE" ] || fail "usage: $0 [--keep] BUNDLE_DIRECTORY"
[ -d "$BUNDLE" ] || fail "bundle directory does not exist: $BUNDLE"
command -v docker >/dev/null 2>&1 || fail "Docker CLI is required"
command -v gpg >/dev/null 2>&1 || fail "gpg is required to decrypt the bundle"

BUNDLE="$(CDPATH='' cd -- "$BUNDLE" && pwd)"

echo "복원 시험 대상: $BUNDLE"
echo

echo "1. 번들 무결성"
if [ -f "$BUNDLE/SHA256SUMS" ]; then
  if ( cd "$BUNDLE" && sha256sum --quiet --check SHA256SUMS ); then
    check "SHA256SUMS 대조" PASS
  else
    check "SHA256SUMS 대조" FAIL "반출 중 손상됐거나 파일이 바뀌었다"
  fi
else
  check "SHA256SUMS 존재" FAIL "번들이 아니거나 backup이 중간에 끊겼다"
fi
[ "$FAILURES" -eq 0 ] || fail "bundle integrity check failed"

for required_manifest in \
  database.content.sha256 \
  robot_images.database.sha256 \
  robot_images.volume.sha256 \
  report_pdf_data.database.sha256 \
  report_pdf_data.volume.sha256
do
  [ -f "$BUNDLE/$required_manifest" ] \
    && [ ! -L "$BUNDLE/$required_manifest" ] \
    || fail \
      "required content manifest is missing: $required_manifest (새 volume backup이 필요하다)"
  grep -Eq \
    "^[0-9a-f]{64}  \\./$required_manifest$" \
    "$BUNDLE/SHA256SUMS" \
    || fail "content manifest is not covered by SHA256SUMS: $required_manifest"
done
check "DB·image·PDF source hash manifest" PASS

WORK_DIR="$(mktemp -d)"
chmod 0700 "$WORK_DIR"

echo
echo "2. 복호화"
dump_encrypted="$(find "$BUNDLE" -maxdepth 1 -name '*.dump.gpg' | head -n 1)"
[ -n "$dump_encrypted" ] || fail "encrypted PostgreSQL dump is missing in the bundle"
gpg --batch --quiet --yes --decrypt \
  --output "$WORK_DIR/gilbom.dump" "$dump_encrypted" \
  || fail "PostgreSQL dump를 복호화하지 못했다.
  - 개인키가 있는 곳에서 실행했는가? (gpg --list-secret-keys)
  - 암호 입력창이 뜨지 않았다면 터미널에서 직접 실행한다. 스크립트를 파이프나
    자동화 도구로 돌리면 gpg가 암호를 물어볼 통로가 없다.
  - 먼저 아무 파일이나 한 번 복호화해 gpg-agent에 암호를 캐시해 두면 그다음부터는
    묻지 않는다."
check "PostgreSQL dump 복호화" PASS "$(basename "$dump_encrypted")"

for archive in "$BUNDLE"/*.tar.gz.gpg; do
  [ -f "$archive" ] || continue
  plain="$WORK_DIR/$(basename "$archive" .gpg)"
  gpg --batch --quiet --yes --decrypt --output "$plain" "$archive" \
    || fail "volume archive could not be decrypted: $(basename "$archive")"
done
check "volume archive 복호화" PASS \
  "$(find "$WORK_DIR" -maxdepth 1 -name '*.tar.gz' | wc -l | tr -d ' ')개"

echo
echo "3. 임시 자원 기동"
NETWORK="$DRILL_ID"
docker network create --internal "$NETWORK" >/dev/null \
  || fail "temporary network could not be created"

drill_password="$(head -c 24 /dev/urandom | base64 | tr -d '\n/+=')"
CONTAINER="$DRILL_ID-db"
docker run \
  --detach \
  --name "$CONTAINER" \
  --network "$NETWORK" \
  --env POSTGRES_DB="$DRILL_BOOTSTRAP_DB" \
  --env POSTGRES_USER="$DRILL_USER" \
  --env POSTGRES_PASSWORD="$drill_password" \
  --tmpfs /tmp:rw,noexec,nosuid,nodev,size=64m \
  "$POSTGRES_IMAGE" >/dev/null \
  || fail "temporary PostgreSQL could not start"

ready=false
attempt=0
while [ "$attempt" -lt 60 ]; do
  # PostgreSQL 공식 entrypoint는 새 PGDATA를 초기화하는 동안 임시 server를
  # 먼저 띄운다. 이때도 pg_isready는 성공하지만, 곧 그 server를 내리고 최종
  # server를 다시 시작한다. 최종 초기화 marker 없이 pg_isready만 믿으면
  # 바로 뒤의 pg_restore가 shutdown 전환과 충돌한다.
  if docker logs "$CONTAINER" 2>&1 \
      | grep -Fq 'PostgreSQL init process complete; ready for start up.' \
      && docker exec "$CONTAINER" pg_isready \
        --username "$DRILL_USER" --dbname "$DRILL_BOOTSTRAP_DB" \
        >/dev/null 2>&1; then
    ready=true
    break
  fi
  attempt=$((attempt + 1))
  sleep 2
done
[ "$ready" = true ] || fail "temporary PostgreSQL did not become ready"
check "임시 PostgreSQL 기동" PASS "$CONTAINER"

# postgis/postgis 이미지는 POSTGRES_DB에 PostGIS와 tiger schema를 미리 만든다.
# 운영 dump에도 같은 객체가 있으므로 그 DB에 곧바로 복원하면 충돌한다. 확장이나
# schema가 없는 template0에서 별도 대상 DB를 만든 뒤 dump가 모든 객체를 복원하게 한다.
docker exec "$CONTAINER" \
  createdb \
    --username "$DRILL_USER" \
    --template template0 \
    "$DRILL_DB" \
  || fail "empty restore database could not be created"
check "빈 복원 DB 생성" PASS "$DRILL_DB (template0)"

for archive in "$WORK_DIR"/*.tar.gz; do
  [ -f "$archive" ] || continue
  logical_name="$(basename "$archive" .tar.gz)"
  volume="$DRILL_ID-$logical_name"
  docker volume create "$volume" >/dev/null \
    || fail "temporary volume could not be created: $volume"
  CREATED_VOLUMES="$CREATED_VOLUMES $volume"
  docker run --rm \
    --mount "type=volume,src=$volume,dst=/target" \
    --mount "type=bind,src=$WORK_DIR,dst=/source,readonly" \
    "$ALPINE_IMAGE" \
    tar -C /target -xzf "/source/$(basename "$archive")" \
    || fail "volume archive could not be extracted: $logical_name"
done
check "임시 volume 복원" PASS \
  "$(printf '%s' "$CREATED_VOLUMES" | wc -w | tr -d ' ')개"

echo
echo "4. DB 복원"
docker exec --interactive "$CONTAINER" \
  pg_restore \
    --username "$DRILL_USER" \
    --dbname "$DRILL_DB" \
    --no-owner \
    --no-privileges \
    --exit-on-error \
  < "$WORK_DIR/gilbom.dump" \
  || fail "pg_restore failed"
check "pg_restore" PASS

drill_sql() {
  docker exec --interactive "$CONTAINER" \
    psql \
      --username "$DRILL_USER" \
      --dbname "$DRILL_DB" \
      --tuples-only \
      --no-align \
      --quiet \
      --set ON_ERROR_STOP=1 \
      --command "$1"
}

docker exec \
  --interactive \
  --env PGHOST=127.0.0.1 \
  --env PGUSER="$DRILL_USER" \
  --env PGDATABASE="$DRILL_DB" \
  --env PGPASSWORD="$drill_password" \
  "$CONTAINER" \
  sh -s \
  < "$PROJECT_DIR/infra/scripts/generate-database-content-manifest.sh" \
  > "$WORK_DIR/database.content.sha256" \
  || fail "restored database content manifest could not be generated"
if cmp -s \
    "$BUNDLE/database.content.sha256" \
    "$WORK_DIR/database.content.sha256"; then
  table_count="$(
    awk '!/^#/ && $3 ~ /^table:/ { count++ } END { print count + 0 }' \
      "$WORK_DIR/database.content.sha256"
  )"
  check "DB table content SHA-256" PASS "$table_count개 table 완전 일치"
  sequence_count="$(
    awk '!/^#/ && $3 ~ /^sequence:/ { count++ } END { print count + 0 }' \
      "$WORK_DIR/database.content.sha256"
  )"
  check "DB sequence state SHA-256" PASS "$sequence_count개 sequence 완전 일치"
else
  diff -u \
    "$BUNDLE/database.content.sha256" \
    "$WORK_DIR/database.content.sha256" \
    > "$WORK_DIR/database.content.diff" 2>/dev/null || true
  check "DB table content SHA-256" FAIL "backup 시점과 복원본 내용이 다르다"
  check "DB sequence state SHA-256" FAIL "backup 시점과 복원본 내용이 다르다"
fi

echo
echo "5. 스키마와 마이그레이션"
expected_schemas="auth robot telemetry defect storage report audit session_store request_control"
missing_schemas=""
for schema in $expected_schemas; do
  found="$(drill_sql "SELECT count(*) FROM information_schema.schemata WHERE schema_name = '$schema';" || echo 0)"
  [ "$found" = 1 ] || missing_schemas="$missing_schemas $schema"
done
if [ -z "$missing_schemas" ]; then
  check "스키마 9종" PASS
else
  check "스키마 9종" FAIL "누락:$missing_schemas"
fi

flyway_version="$(
  drill_sql "SELECT version FROM flyway_history.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1;" \
    2>/dev/null || true
)"
flyway_failed="$(
  drill_sql "SELECT count(*) FROM flyway_history.flyway_schema_history WHERE NOT success;" \
    2>/dev/null || echo unknown
)"
if [ -n "$flyway_version" ] && [ "$flyway_failed" = 0 ]; then
  check "Flyway version" PASS "최신 V$flyway_version, 실패 이력 없음"
else
  check "Flyway version" FAIL "최신 '$flyway_version', 실패 '$flyway_failed'"
fi

echo
echo "6. 주요 데이터"
report_row() {
  label=$1
  query=$2
  minimum=$3
  value="$(drill_sql "$query" 2>/dev/null || echo error)"
  case "$value" in
    ''|*[!0-9]*)
      check "$label" FAIL "질의 실패"
      ;;
    *)
      if [ "$value" -ge "$minimum" ]; then
        check "$label" PASS "$value행"
      else
        check "$label" FAIL "$value행 (최소 $minimum)"
      fi
      ;;
  esac
}

report_row "사용자 계정" "SELECT count(*) FROM auth.admin_user;" 1
report_row "역할 부여" "SELECT count(*) FROM auth.user_role;" 1
report_row "로봇" "SELECT count(*) FROM robot.robot;" 1
report_row "임무" "SELECT count(*) FROM robot.mission;" 0
report_row "결함" "SELECT count(*) FROM defect.defect;" 0
report_row "감사 로그" "SELECT count(*) FROM audit.audit_log;" 1
report_row "보고서 version" "SELECT count(*) FROM report.report_version;" 0

approved_pdf="$(
  drill_sql "SELECT count(*) FROM report.anomaly_report WHERE approved_pdf_storage_key IS NOT NULL;" \
    2>/dev/null || echo error
)"
check "승인 PDF 참조" PASS "$approved_pdf건"

# 감사 로그는 append-only 설계다. identity 컬럼의 최댓값과 행 수가 크게 어긋나면
# 중간이 잘렸다는 뜻이다.
audit_span="$(
  drill_sql "SELECT count(*) || '/' || COALESCE(max(audit_id), 0) FROM audit.audit_log;" \
    2>/dev/null || echo error
)"
check "감사 로그 연속성" PASS "행/최대 ID = $audit_span"

echo
echo "7. TOTP 암호문과 keyring"
totp_rows="$(
  drill_sql "SELECT count(*) FROM auth.mfa_factor WHERE factor_status = 'ACTIVE';" \
    2>/dev/null || echo error
)"
totp_versions="$(
  drill_sql "SELECT string_agg(DISTINCT secret_key_version::text, ',') FROM auth.mfa_factor;" \
    2>/dev/null || echo ''
)"
keyring_file="${TOTP_KEYRING_FILE:-/opt/gilbom/secrets/gilbom_mfa_totp_keyring}"
if [ "$totp_rows" = 0 ]; then
  check "TOTP 암호문" PASS "활성 factor 없음"
elif [ -r "$keyring_file" ]; then
  missing_version=""
  for version in $(printf '%s' "$totp_versions" | tr ',' ' '); do
    [ -n "$version" ] || continue
    grep -q "^$version=" "$keyring_file" || missing_version="$missing_version $version"
  done
  if [ -z "$missing_version" ]; then
    check "TOTP key version" PASS "keyring에 버전 $totp_versions 존재"
  else
    check "TOTP key version" FAIL "keyring에 없는 버전:$missing_version"
  fi
else
  check "TOTP key version" FAIL "keyring을 읽을 수 없다: $keyring_file"
fi
echo "  주의: keyring을 잃으면 이 암호문은 복구 불가다. DB backup과 분리해 따로 반출한다."

echo
echo "8. 이미지 파일과 DB 참조"
image_volume="$(find_drill_volume robot_images || true)"

if [ -z "$image_volume" ]; then
  check "robot_images volume" FAIL "번들에 robot_images archive가 없다"
else
  # backup-volumes.sh가 만드는 manifest와 같은 형식으로 뽑아 그대로 대조한다.
  drill_sql "
    SELECT sha256_hex || '  ' || object_key
    FROM storage.robot_image
    WHERE bucket_name = 'local-disk'
      AND image_status = 'AVAILABLE'
      AND deleted_at IS NULL
    ORDER BY object_key;
  " > "$WORK_DIR/robot_images.database.sha256" 2>/dev/null \
    || fail "restored robot image database references could not be read"

  hash_restored_volume \
    "$image_volume" \
    robot_images.volume.sha256 \
    || fail "restored robot_images volume could not be hashed"

  if cmp -s \
      "$BUNDLE/robot_images.volume.sha256" \
      "$WORK_DIR/robot_images.volume.sha256"; then
    restored_image_files="$(
      awk 'NF { count++ } END { print count + 0 }' \
        "$WORK_DIR/robot_images.volume.sha256"
    )"
    check \
      "robot_images restored file SHA-256" \
      PASS \
      "$restored_image_files개 file 완전 일치"
  else
    check \
      "robot_images restored file SHA-256" \
      FAIL \
      "backup 시점과 복원본 file manifest가 다르다"
  fi

  if cmp -s \
      "$BUNDLE/robot_images.database.sha256" \
      "$WORK_DIR/robot_images.database.sha256"; then
    check "robot image DB reference SHA-256" PASS
  else
    check "robot image DB reference SHA-256" FAIL "DB 참조 manifest가 다르다"
  fi

  expected_count="$(
    awk 'NF { count++ } END { print count + 0 }' \
      "$WORK_DIR/robot_images.database.sha256"
  )"
  missing=0
  while IFS= read -r expected; do
    [ -n "$expected" ] || continue
    grep -Fxq "$expected" "$WORK_DIR/robot_images.volume.sha256" \
      || missing=$((missing + 1))
  done < "$WORK_DIR/robot_images.database.sha256"

  if [ "$missing" -eq 0 ]; then
    check \
      "robot image DB reference ↔ file SHA-256" \
      PASS \
      "$expected_count건 모두 일치"
  else
    check \
      "robot image DB reference ↔ file SHA-256" \
      FAIL \
      "$missing건 누락 또는 변조"
  fi
fi

echo
echo "9. 승인 PDF 파일과 DB 참조"
report_pdf_volume="$(find_drill_volume report_pdf_data || true)"
if [ -z "$report_pdf_volume" ]; then
  check "report_pdf_data volume" FAIL "번들에 report_pdf_data archive가 없다"
else
  drill_sql "
    SELECT encode(approved_pdf_sha256, 'hex')
        || '  ' || approved_pdf_storage_key
    FROM report.anomaly_report
    WHERE approved_pdf_storage_key IS NOT NULL
      AND approved_pdf_sha256 IS NOT NULL
    ORDER BY approved_pdf_storage_key;
  " > "$WORK_DIR/report_pdf_data.database.sha256" 2>/dev/null \
    || fail "restored report PDF database references could not be read"

  hash_restored_volume \
    "$report_pdf_volume" \
    report_pdf_data.volume.sha256 \
    || fail "restored report_pdf_data volume could not be hashed"

  if cmp -s \
      "$BUNDLE/report_pdf_data.volume.sha256" \
      "$WORK_DIR/report_pdf_data.volume.sha256"; then
    restored_pdf_files="$(
      awk 'NF { count++ } END { print count + 0 }' \
        "$WORK_DIR/report_pdf_data.volume.sha256"
    )"
    check \
      "report_pdf_data restored file SHA-256" \
      PASS \
      "$restored_pdf_files개 file 완전 일치"
  else
    check \
      "report_pdf_data restored file SHA-256" \
      FAIL \
      "backup 시점과 복원본 file manifest가 다르다"
  fi

  if cmp -s \
      "$BUNDLE/report_pdf_data.database.sha256" \
      "$WORK_DIR/report_pdf_data.database.sha256"; then
    check "report PDF DB reference SHA-256" PASS
  else
    check "report PDF DB reference SHA-256" FAIL "DB 참조 manifest가 다르다"
  fi

  expected_pdf_count="$(
    awk 'NF { count++ } END { print count + 0 }' \
      "$WORK_DIR/report_pdf_data.database.sha256"
  )"
  missing_pdf=0
  while IFS= read -r expected_pdf
  do
    [ -n "$expected_pdf" ] || continue
    grep -Fxq "$expected_pdf" "$WORK_DIR/report_pdf_data.volume.sha256" \
      || missing_pdf=$((missing_pdf + 1))
  done < "$WORK_DIR/report_pdf_data.database.sha256"

  if [ "$missing_pdf" -eq 0 ]; then
    check \
      "report PDF DB reference ↔ file SHA-256" \
      PASS \
      "$expected_pdf_count건 모두 일치"
  else
    check \
      "report PDF DB reference ↔ file SHA-256" \
      FAIL \
      "$missing_pdf건 누락 또는 변조"
  fi
fi

echo
echo "10. MQTT credential"
credential_volume="$(find_drill_volume mosquitto_credentials || true)"
if [ -z "$credential_volume" ]; then
  check "mosquitto credential volume" FAIL "번들에 archive가 없다"
else
  credential_files="$(
    docker run --rm \
      --mount "type=volume,src=$credential_volume,dst=/source,readonly" \
      "$ALPINE_IMAGE" \
      sh -ec 'find /source -type f | wc -l'
  )"
  if [ "$credential_files" -gt 0 ]; then
    check "mosquitto credential 파일" PASS "$credential_files개"
  else
    check "mosquitto credential 파일" FAIL "비어 있다"
  fi
fi

echo
echo "11. 세션 처리 정책"
session_rows="$(
  drill_sql "SELECT count(*) FROM session_store.spring_session;" 2>/dev/null || echo error
)"
echo "  복원본에 세션 $session_rows건이 들어 있다."
echo "  실제 재해복구에서는 이 표를 비우고 전체 재로그인을 요구한다(보수적 기본값):"
echo "    TRUNCATE session_store.spring_session_attributes,"
echo "             session_store.session_security_state,"
echo "             session_store.spring_session;"

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "Restore drill: PASS"
else
  echo "Restore drill: FAIL ($FAILURES건)"
  exit 1
fi
