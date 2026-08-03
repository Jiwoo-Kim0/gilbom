#!/bin/sh

# 실행 중인 PostgreSQL의 애플리케이션 테이블을 정규화해 SHA-256 manifest로 만든다.
# backup 시점과 격리 restore 시점에 같은 스크립트를 실행해야 "행 수가 비슷하다"가
# 아니라 실제 DB 내용이 동일하다는 증거가 된다.
#
# 필수 환경:
#   PGHOST, PGUSER, PGDATABASE, PGPASSWORD
#
# 출력 형식:
#   <sha256>  <row-count>  <schema.table>

set -eu
set +x

export LC_ALL=C
export PGTZ=UTC
# 같은 timestamptz, bytea, interval과 float가 host 설정과 무관하게 같은 text가
# 되도록 client session 출력을 고정한다.
PGOPTIONS="-c timezone=UTC -c datestyle=ISO,YMD -c intervalstyle=iso_8601 -c bytea_output=hex -c extra_float_digits=3"
export PGOPTIONS

PSQL_BIN="${PSQL_BIN:-psql}"
DATABASE_CONTENT_SCHEMAS="${DATABASE_CONTENT_SCHEMAS:-auth,robot,telemetry,defect,storage,report,audit,session_store,request_control,flyway_history}"

fail() {
  echo "Database content manifest failed: $1" >&2
  exit 1
}

command -v "$PSQL_BIN" >/dev/null 2>&1 || fail "psql is required"
command -v sha256sum >/dev/null 2>&1 || fail "sha256sum is required"

[ -n "${PGHOST:-}" ] || fail "PGHOST is required"
[ -n "${PGUSER:-}" ] || fail "PGUSER is required"
[ -n "${PGDATABASE:-}" ] || fail "PGDATABASE is required"
[ -n "${PGPASSWORD:-}" ] || fail "PGPASSWORD is required"

schema_literals=""
old_ifs=$IFS
IFS=,
for schema in $DATABASE_CONTENT_SCHEMAS
do
  case "$schema" in
    ""|[!a-z_]*|*[!a-z0-9_]*)
      fail "invalid schema name: $schema"
      ;;
  esac
  if [ -n "$schema_literals" ]; then
    schema_literals="$schema_literals,"
  fi
  schema_literals="${schema_literals}'${schema}'"
done
IFS=$old_ifs
[ -n "$schema_literals" ] || fail "at least one schema is required"

psql_command() {
  "$PSQL_BIN" \
    --host "$PGHOST" \
    --username "$PGUSER" \
    --dbname "$PGDATABASE" \
    --no-password \
    --tuples-only \
    --no-align \
    --quiet \
    --set ON_ERROR_STOP=1 \
    "$@"
}

tables="$(
  psql_command --command "
    SELECT format('%I.%I', table_schema, table_name)
    FROM information_schema.tables
    WHERE table_type = 'BASE TABLE'
      AND table_schema IN ($schema_literals)
    ORDER BY table_schema COLLATE \"C\", table_name COLLATE \"C\";
  "
)"
[ -n "$tables" ] || fail "no application tables were found"

printf '%s\n' '# gilbom-database-content-manifest-v1'
printf '%s\n' '# sha256  row-count  relation-type:schema.relation'

printf '%s\n' "$tables" |
while IFS= read -r qualified_table
do
  [ -n "$qualified_table" ] || continue
  row_count="$(
    psql_command --command "SELECT count(*) FROM $qualified_table;"
  )"
  case "$row_count" in
    ""|*[!0-9]*)
      fail "invalid row count for $qualified_table"
      ;;
  esac

  content_sha256="$(
    psql_command --command "
      COPY (
        SELECT row_to_json(row_value)::text
        FROM $qualified_table AS row_value
        ORDER BY row_to_json(row_value)::text COLLATE \"C\"
      ) TO STDOUT;
    " |
      sha256sum |
      awk '{ print $1 }'
  )"
  case "$content_sha256" in
    ""|*[!0-9a-f]*)
      fail "invalid SHA-256 for $qualified_table"
      ;;
  esac
  [ "${#content_sha256}" -eq 64 ] \
    || fail "invalid SHA-256 length for $qualified_table"

  printf '%s  %s  table:%s\n' \
    "$content_sha256" \
    "$row_count" \
    "$qualified_table"
done

# pg_dump가 보존하는 sequence의 last_value/is_called도 DB 상태다. 이것이 빠지면
# 복원 후 신규 INSERT가 기존 PK와 충돌할 수 있으므로 별도 relation으로 고정한다.
sequences="$(
  psql_command --command "
    SELECT format('%I.%I', sequence_schema, sequence_name)
    FROM information_schema.sequences
    WHERE sequence_schema IN ($schema_literals)
    ORDER BY sequence_schema COLLATE \"C\", sequence_name COLLATE \"C\";
  "
)"

printf '%s\n' "$sequences" |
while IFS= read -r qualified_sequence
do
  [ -n "$qualified_sequence" ] || continue
  content_sha256="$(
    psql_command --command "
      COPY (
        SELECT row_to_json(sequence_value)::text
        FROM (
          SELECT last_value, is_called
          FROM $qualified_sequence
        ) AS sequence_value
      ) TO STDOUT;
    " |
      sha256sum |
      awk '{ print $1 }'
  )"
  case "$content_sha256" in
    ""|*[!0-9a-f]*)
      fail "invalid SHA-256 for $qualified_sequence"
      ;;
  esac
  [ "${#content_sha256}" -eq 64 ] \
    || fail "invalid SHA-256 length for $qualified_sequence"
  printf '%s  1  sequence:%s\n' \
    "$content_sha256" \
    "$qualified_sequence"
done
