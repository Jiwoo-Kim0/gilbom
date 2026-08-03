#!/bin/sh

# 실제 암호화 backup을 격리 DB/Volume에 복원하고 고정 JSON 증거를 남긴다.
#
#   RESTORE_DRILL_OUTPUT_DIR=.artifacts/day10/restore \
#   TOTP_KEYRING_FILE=/secure/path/gilbom_mfa_totp_keyring \
#     sh scripts/run-isolated-backup-restore-drill.sh /path/to/backup-bundle

set -eu
set +x
umask 077

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
BUNDLE="${1:-}"
OUTPUT_DIR="${RESTORE_DRILL_OUTPUT_DIR:-$PROJECT_DIR/.artifacts/day10/restore}"

fail() {
  echo "Isolated backup restore runner failed: $1" >&2
  exit 1
}

[ "$#" -eq 1 ] || fail "usage: $0 BUNDLE_DIRECTORY"
[ -d "$BUNDLE" ] || fail "bundle directory does not exist: $BUNDLE"
command -v python3 >/dev/null 2>&1 || fail "python3 is required"

case "$OUTPUT_DIR" in
  /*)
    ;;
  *)
    OUTPUT_DIR="$PROJECT_DIR/$OUTPUT_DIR"
    ;;
esac
mkdir -p "$OUTPUT_DIR"
chmod 0700 "$OUTPUT_DIR"

log_file="$OUTPUT_DIR/restore.log"
temporary_log="$(mktemp "$OUTPUT_DIR/.restore.XXXXXX")"
cleanup() {
  rm -f "$temporary_log" >/dev/null 2>&1 || true
}
trap cleanup EXIT HUP INT TERM

set +e
sh "$SCRIPT_DIR/restore-drill.sh" "$BUNDLE" > "$temporary_log" 2>&1
restore_exit=$?
set -e

mv "$temporary_log" "$log_file"
chmod 0600 "$log_file"
cat "$log_file"

set +e
python3 "$SCRIPT_DIR/generate-restore-drill-evidence.py" \
  --bundle "$BUNDLE" \
  --log "$log_file" \
  --exit-code "$restore_exit" \
  --output "$OUTPUT_DIR/summary.json"
evidence_exit=$?
set -e
chmod 0600 "$OUTPUT_DIR/summary.json"

[ "$restore_exit" -eq 0 ] && [ "$evidence_exit" -eq 0 ] \
  || fail "restore drill did not pass; inspect $log_file and $OUTPUT_DIR/summary.json"

echo "Restore drill evidence: $OUTPUT_DIR/summary.json"
