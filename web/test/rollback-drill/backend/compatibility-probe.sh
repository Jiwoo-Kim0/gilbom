#!/bin/sh

set -eu

. /opt/gilbom-drill/release.env

export PGPASSWORD="${PGPASSWORD:?PGPASSWORD is required}"

current_version="$(
  psql \
    --host "${PGHOST:?PGHOST is required}" \
    --port "${PGPORT:-5432}" \
    --username "${PGUSER:?PGUSER is required}" \
    --dbname "${PGDATABASE:?PGDATABASE is required}" \
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
[ "$current_version" -le "$DRILL_MAX_SUPPORTED_FLYWAY" ] || exit 1

required_column="$(
  psql \
    --host "$PGHOST" \
    --port "${PGPORT:-5432}" \
    --username "$PGUSER" \
    --dbname "$PGDATABASE" \
    --no-password \
    --tuples-only \
    --no-align \
    --set ON_ERROR_STOP=1 \
    --command "
      SELECT COUNT(*)
      FROM information_schema.columns
      WHERE table_schema = 'drill'
        AND table_name = 'backend_contract'
        AND column_name = 'required_value';
    " | tr -d '[:space:]'
)"
[ "$required_column" = 1 ]
