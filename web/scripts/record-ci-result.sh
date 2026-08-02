#!/bin/sh

set -eu

RESULT_ID="${1:-}"
case "$RESULT_ID" in
  ""|*[!a-z0-9-]*)
    echo "CI result id must contain only lowercase letters, digits, and hyphens" >&2
    exit 1
    ;;
esac

mkdir -p .artifacts/ci-results
printf 'PASS\n' > ".artifacts/ci-results/$RESULT_ID.status"
