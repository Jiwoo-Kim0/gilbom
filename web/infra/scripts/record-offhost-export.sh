#!/bin/sh

# Off-host 반출을 기록한다. 반출 자체는 사람이 MobaXterm SFTP로 하지만, 언제 무엇을
# 내렸는지 남지 않으면 "backup은 있는데 서버에만 있는" 상태를 알아채지 못한다.
#
#   ./record-offhost-export.sh postgres /opt/gilbom/backups/postgres/<파일>.gpg
#   ./record-offhost-export.sh volumes  /opt/gilbom/backups/volumes/<번들 디렉터리>
#
# 실행 전에 반출본의 checksum을 대조하므로, 내려받다 잘린 파일은 기록되지 않는다.

set -eu
set +x

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"

# shellcheck source=infra/scripts/backup-common.sh
. "$SCRIPT_DIR/backup-common.sh"

fail() {
  echo "Off-host export record failed: $1" >&2
  exit 1
}

[ "$#" -eq 2 ] || fail "usage: $0 BACKUP_NAME EXPORTED_PATH"
backup_name=$1
exported_path=$2

case "$backup_name" in
  ""|*[!a-z0-9-]*)
    fail "backup name must contain only lowercase letters, digits, and hyphens"
    ;;
esac
[ -e "$exported_path" ] || fail "exported path does not exist: $exported_path"

if [ -d "$exported_path" ]; then
  [ -f "$exported_path/SHA256SUMS" ] \
    || fail "bundle has no SHA256SUMS: $exported_path"
  ( cd "$exported_path" && sha256sum --quiet --check SHA256SUMS ) \
    || fail "bundle checksum mismatch"
else
  [ -f "$exported_path.sha256" ] \
    || fail "checksum file is missing: $exported_path.sha256"
  ( cd "$(dirname "$exported_path")" \
      && sha256sum --quiet --check "$(basename "$exported_path").sha256" ) \
    || fail "file checksum mismatch"
  case "$exported_path" in
    *.gpg)
      ;;
    *)
      fail "only encrypted (.gpg) artifacts may be exported off-host"
      ;;
  esac
fi

[ -d "$BACKUP_TEXTFILE_DIR" ] \
  || fail "textfile directory does not exist: $BACKUP_TEXTFILE_DIR"

metric_file="$BACKUP_TEXTFILE_DIR/gilbom-backup-offhost-$backup_name.prom"
temporary="$(mktemp "$BACKUP_TEXTFILE_DIR/.gilbom-offhost.XXXXXX")"
{
  echo '# HELP gilbom_backup_offhost_exported_seconds Last off-host export time.'
  echo '# TYPE gilbom_backup_offhost_exported_seconds gauge'
  printf 'gilbom_backup_offhost_exported_seconds{backup="%s"} %s\n' \
    "$backup_name" "$(date -u +%s)"
} > "$temporary"
chmod 0644 "$temporary"
mv "$temporary" "$metric_file"

echo "Off-host export record: PASS"
echo "Backup: $backup_name"
echo "Verified: $exported_path"
