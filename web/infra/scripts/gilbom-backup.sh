#!/bin/sh

# Backup 진입점이다. systemd timer와 사람이 같은 명령을 쓰도록 한 곳으로 모은다.
# auditd가 이 경로를 감시하므로, 백업 실행 흔적이 host 감사 로그에 남는다.
#
#   gilbom-backup database
#   gilbom-backup volumes

set -eu
set +x
umask 077

PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
HOME=/root
export PATH HOME
unset CDPATH ENV BASH_ENV

DEPLOY_DIR="${DEPLOY_DIR:-/opt/gilbom/deploy}"
BACKUP_ROOT="${BACKUP_ROOT:-/opt/gilbom/backups}"

fail() {
  echo "Gilbom backup failed: $1" >&2
  exit 1
}

[ "$(id -u)" -eq 0 ] || fail "this command must run as root"
[ "$#" -eq 1 ] || fail "usage: $0 database|volumes"

[ -d "$DEPLOY_DIR" ] || fail "deploy directory is missing: $DEPLOY_DIR"
[ -f "$DEPLOY_DIR/runtime.env" ] || fail "runtime.env is missing"
[ -f "$DEPLOY_DIR/image.env" ] || fail "image.env is missing"
[ -f "$DEPLOY_DIR/compose.prod.yaml" ] || fail "compose.prod.yaml is missing"

cd "$DEPLOY_DIR"

case "$1" in
  database)
    install -d -m 0700 "$BACKUP_ROOT/postgres"
    env \
      RUNTIME_ENV_FILE="$DEPLOY_DIR/runtime.env" \
      IMAGE_ENV_FILE="$DEPLOY_DIR/image.env" \
      BACKUP_DIR="$BACKUP_ROOT/postgres" \
      sh infra/scripts/backup-db.sh
    ;;
  volumes)
    install -d -m 0700 "$BACKUP_ROOT"
    env \
      RUNTIME_ENV_FILE="$DEPLOY_DIR/runtime.env" \
      IMAGE_ENV_FILE="$DEPLOY_DIR/image.env" \
      BACKUP_ROOT="$BACKUP_ROOT" \
      sh infra/scripts/backup-volumes.sh
    ;;
  *)
    fail "unknown backup target: $1"
    ;;
esac
