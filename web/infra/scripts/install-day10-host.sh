#!/bin/sh

# Day 10 host 구성요소를 설치한다 — metric textfile 경로, 권한 있는 진입점,
# systemd timer, auditd 규칙.
#
# 이미 있는 값은 덮어쓰지 않는다. Secret과 backup 산출물은 건드리지 않는다.

set -eu
set +x
umask 077

PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
HOME=/root
export PATH HOME
unset CDPATH ENV BASH_ENV

fail() {
  echo "Day 10 host installation failed: $1" >&2
  exit 1
}

[ "$(id -u)" -eq 0 ] || fail "this command must run as root"
[ "$#" -eq 0 ] || fail "this installer accepts no arguments"

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH='' cd -- "$SCRIPT_DIR/../.." && pwd)"
SYSTEMD_DIR="$PROJECT_DIR/infra/systemd"
AUDIT_RULES="$PROJECT_DIR/infra/audit/gilbom.rules"

for source in \
  "$SCRIPT_DIR/collect-host-metrics.sh" \
  "$SCRIPT_DIR/gilbom-backup.sh" \
  "$SCRIPT_DIR/record-offhost-export.sh" \
  "$SCRIPT_DIR/backup-common.sh" \
  "$AUDIT_RULES"
do
  [ -f "$source" ] && [ ! -L "$source" ] && [ -s "$source" ] \
    || fail "required source is missing, empty, or symlinked: $source"
done

for unit in \
  gilbom-db-backup.service \
  gilbom-db-backup.timer \
  gilbom-volume-backup.service \
  gilbom-volume-backup.timer \
  gilbom-host-metrics.service \
  gilbom-host-metrics.timer
do
  [ -f "$SYSTEMD_DIR/$unit" ] || fail "systemd unit is missing: $unit"
done

# Alloy가 read-only bind로 읽는 경로다. container 안의 비-root 사용자가 읽어야 해서
# 0755로 둔다. 값 자체는 metric이라 비밀이 아니다.
install -d -o root -g root -m 0755 \
  /opt/gilbom/monitoring \
  /opt/gilbom/monitoring/textfile
install -d -o root -g root -m 0700 \
  /opt/gilbom/backups \
  /opt/gilbom/backups/postgres

install -o root -g root -m 0755 \
  "$SCRIPT_DIR/collect-host-metrics.sh" \
  /usr/local/sbin/gilbom-host-metrics
install -o root -g root -m 0755 \
  "$SCRIPT_DIR/gilbom-backup.sh" \
  /usr/local/sbin/gilbom-backup
install -o root -g root -m 0750 \
  "$SCRIPT_DIR/record-offhost-export.sh" \
  /usr/local/sbin/gilbom-record-offhost-export

for unit in \
  gilbom-db-backup.service \
  gilbom-db-backup.timer \
  gilbom-volume-backup.service \
  gilbom-volume-backup.timer \
  gilbom-host-metrics.service \
  gilbom-host-metrics.timer
do
  install -o root -g root -m 0644 "$SYSTEMD_DIR/$unit" "/etc/systemd/system/$unit"
done

systemctl daemon-reload
systemctl enable --now gilbom-host-metrics.timer
systemctl enable --now gilbom-db-backup.timer
systemctl enable --now gilbom-volume-backup.timer

if command -v auditctl >/dev/null 2>&1; then
  install -d -o root -g root -m 0750 /etc/audit/rules.d
  install -o root -g root -m 0640 "$AUDIT_RULES" /etc/audit/rules.d/gilbom.rules
  if command -v augenrules >/dev/null 2>&1; then
    augenrules --load >/dev/null \
      || fail "audit rules could not be loaded"
  else
    auditctl -R /etc/audit/rules.d/gilbom.rules >/dev/null \
      || fail "audit rules could not be loaded"
  fi
  echo "Audit rules: loaded"
else
  echo "Audit rules: SKIPPED (auditd is not installed)"
  echo "  apt-get install auditd 후 이 스크립트를 다시 실행한다."
fi

echo "Day 10 host installation: PASS"

if [ ! -f /opt/gilbom/secrets/backup-recipient.asc ]; then
  echo
  echo "다음 단계: backup 암호화 공개키가 아직 없다."
  echo "  sh infra/scripts/prepare-backup-encryption.sh <공개키 파일>"
  echo "키가 없으면 backup timer는 조건 불충족으로 조용히 건너뛴다."
fi
