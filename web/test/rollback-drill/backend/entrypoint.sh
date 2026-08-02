#!/bin/sh

set -eu

. /opt/gilbom-drill/release.env

export PGPASSWORD="${PGPASSWORD:?PGPASSWORD is required}"

until pg_isready \
    --host "${PGHOST:?PGHOST is required}" \
    --port "${PGPORT:-5432}" \
    --username "${PGUSER:?PGUSER is required}" \
    --dbname "${PGDATABASE:?PGDATABASE is required}" >/dev/null 2>&1
do
  sleep 1
done

psql \
  --host "$PGHOST" \
  --port "${PGPORT:-5432}" \
  --username "$PGUSER" \
  --dbname "$PGDATABASE" \
  --no-password \
  --set ON_ERROR_STOP=1 <<'SQL'
CREATE SCHEMA IF NOT EXISTS flyway_history;
CREATE TABLE IF NOT EXISTS flyway_history.flyway_schema_history (
    version INTEGER PRIMARY KEY,
    script TEXT NOT NULL,
    success BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE SCHEMA IF NOT EXISTS drill;
CREATE TABLE IF NOT EXISTS drill.backend_contract (
    id INTEGER PRIMARY KEY,
    required_value TEXT
);
CREATE TABLE IF NOT EXISTS drill.persistent_state (
    id INTEGER PRIMARY KEY,
    payload TEXT NOT NULL
);
SQL

current_version="$(
  psql \
    --host "$PGHOST" \
    --port "${PGPORT:-5432}" \
    --username "$PGUSER" \
    --dbname "$PGDATABASE" \
    --no-password \
    --tuples-only \
    --no-align \
    --set ON_ERROR_STOP=1 \
    --command '
      SELECT COALESCE(MAX(version), 0)
      FROM flyway_history.flyway_schema_history
      WHERE success;
    ' | tr -d '[:space:]'
)"

while [ "$current_version" -lt "$DRILL_TARGET_FLYWAY_VERSION" ]
do
  next_version="$((current_version + 1))"
  case "$next_version" in
    36)
      psql \
        --host "$PGHOST" \
        --port "${PGPORT:-5432}" \
        --username "$PGUSER" \
        --dbname "$PGDATABASE" \
        --no-password \
        --set ON_ERROR_STOP=1 \
        --command '
          CREATE TABLE IF NOT EXISTS drill.additive_v36 (
              id INTEGER PRIMARY KEY,
              note TEXT NOT NULL
          );
        '
      ;;
    37)
      psql \
        --host "$PGHOST" \
        --port "${PGPORT:-5432}" \
        --username "$PGUSER" \
        --dbname "$PGDATABASE" \
        --no-password \
        --set ON_ERROR_STOP=1 \
        --command '
          ALTER TABLE drill.backend_contract
          DROP COLUMN IF EXISTS required_value;
        '
      ;;
  esac
  psql \
    --host "$PGHOST" \
    --port "${PGPORT:-5432}" \
    --username "$PGUSER" \
    --dbname "$PGDATABASE" \
    --no-password \
    --set ON_ERROR_STOP=1 \
    --set "version=$next_version" \
    --command "
      INSERT INTO flyway_history.flyway_schema_history(version, script, success)
      VALUES (
        $next_version,
        'V${next_version}__rollback_drill.sql',
        TRUE
      );
    "
  current_version="$next_version"
done

mkdir -p /tmp/gilbom-drill-www/actuator/health
mkdir -p /tmp/gilbom-drill-www/api
chmod 0755 \
  /tmp/gilbom-drill-www \
  /tmp/gilbom-drill-www/actuator \
  /tmp/gilbom-drill-www/actuator/health \
  /tmp/gilbom-drill-www/api
printf '%s\n' '{"status":"UP"}' \
  > /tmp/gilbom-drill-www/actuator/health/readiness
printf '%s\n' \
  '{"status":"UP","components":{"mqttConnection":{"status":"UP"}}}' \
  > /tmp/gilbom-drill-www/actuator/health/index.html
printf '%s\n' '{"status":"UP","service":"gilbom-rollback-drill"}' \
  > /tmp/gilbom-drill-www/api/health

exec busybox-extras httpd -f -p 8080 -h /tmp/gilbom-drill-www
