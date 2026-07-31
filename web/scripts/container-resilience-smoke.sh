#!/bin/sh

set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"

export REPORT_SMOKE_RESILIENCE=true
exec sh "$SCRIPT_DIR/container-report-smoke.sh"
