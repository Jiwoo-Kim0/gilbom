#!/bin/sh

set -eu

. /opt/gilbom-drill/release.env

[ "$DRILL_FORCE_UNHEALTHY" = false ] || exit 1

export PGPASSWORD="${PGPASSWORD:?PGPASSWORD is required}"

/usr/local/bin/gilbom-drill-compatibility-probe

wget --quiet --output-document=- \
  http://127.0.0.1:8080/actuator/health/readiness \
  | grep -Fq '"status":"UP"'
