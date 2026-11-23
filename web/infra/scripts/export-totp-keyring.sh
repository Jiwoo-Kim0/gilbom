#!/bin/sh

# TOTP keyring을 수신자 공개키로 암호화해 반출 준비 상태로 만든다.
#
# 왜 별도 스크립트인가: keyring은 DB backup에 들어가지 않는다. 들어가면 암호문과
# 그것을 푸는 키가 한 번들에 담겨 암호화의 의미가 없어진다. 그래서 runbook은 처음부터
# "따로 반출해 따로 보관하라"고 적어 두었는데, **그 절차만 스크립트가 없었다.**
# 결과는 예상대로였다 — 손으로 만든 반출본이 8/3자로 멈춰 있었고, 그마저 개인키가
# 존재하지 않는 고아 키로 잠겨 있었다.
#
#   sudo sh export-totp-keyring.sh
#
# keyring을 잃으면 auth.mfa_factor의 암호문은 복구 불가다. DB를 되살려도 사용자
# 전원이 MFA를 다시 등록해야 한다. 그래서 backup 성공 여부와 별개로 metric을 남긴다.

set -eu
set +x

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
BACKUP_ROOT="${BACKUP_ROOT:-/opt/gilbom/backups}"
TOTP_KEYRING_FILE="${TOTP_KEYRING_FILE:-/opt/gilbom/secrets/gilbom_mfa_totp_keyring}"
BACKUP_NAME=totp
STARTED_AT="$(date -u +%s)"
PARTIAL_FILE=""

# shellcheck source=infra/scripts/backup-common.sh
. "$SCRIPT_DIR/backup-common.sh"

fail() {
  echo "TOTP keyring export failed: $1" >&2
  gilbom_backup_record_result \
    "$BACKUP_NAME" 0 "$(( $(date -u +%s) - STARTED_AT ))" 0 \
    2>/dev/null || true
  exit 1
}

# 평문 사본을 남기지 않는다. 암호화 전에 죽어도 지워져야 한다.
cleanup() {
  if [ -n "$PARTIAL_FILE" ]; then
    rm -f "$PARTIAL_FILE" >/dev/null 2>&1 || true
  fi
}

trap cleanup EXIT HUP INT TERM
umask 077

[ -f "$TOTP_KEYRING_FILE" ] || fail "keyring is missing: $TOTP_KEYRING_FILE"
[ -s "$TOTP_KEYRING_FILE" ] || fail "keyring is empty: $TOTP_KEYRING_FILE"

gilbom_backup_require_gpg

fingerprints="$(gilbom_backup_recipient_fingerprints)" \
  || fail "recipient fingerprints could not be read"
recipient_count="$(printf '%s\n' "$fingerprints" | grep -c .)"
[ "$recipient_count" -ge 1 ] || fail "no recipient is registered"

target_dir="$BACKUP_ROOT/$BACKUP_NAME"
mkdir -p "$target_dir"
chmod 0700 "$target_dir"

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
plain="$target_dir/$stamp-gilbom_mfa_totp_keyring"
PARTIAL_FILE="$plain"

cp -- "$TOTP_KEYRING_FILE" "$plain" || fail "keyring could not be staged"
chmod 0600 "$plain"

encrypted="$(gilbom_backup_encrypt_file "$plain")" \
  || fail "keyring could not be encrypted"
PARTIAL_FILE=""

gilbom_backup_checksum "$encrypted"
manifest="$target_dir/$stamp-bundle.manifest.json"
gilbom_backup_write_manifest \
  "$manifest" "$BACKUP_NAME" "$fingerprints" "$encrypted" "$encrypted.sha256"

gilbom_backup_prune "$target_dir" '*-gilbom_mfa_totp_keyring.gpg'
gilbom_backup_prune "$target_dir" '*-gilbom_mfa_totp_keyring.gpg.sha256'
gilbom_backup_prune "$target_dir" '*-bundle.manifest.json'

size="$(stat -c %s "$encrypted")"
gilbom_backup_record_result \
  "$BACKUP_NAME" 1 "$(( $(date -u +%s) - STARTED_AT ))" "$size"

echo "TOTP keyring export: PASS"
echo "Recipients: $recipient_count"
printf '%s\n' "$fingerprints" | sed 's/^/  /'
echo "Bundle:   $encrypted"
echo "Checksum: $encrypted.sha256"
echo "Manifest: $manifest"
echo
echo "아직 반출된 것이 아니다. 이 파일들을 host 밖으로 내려받은 뒤"
echo "record-offhost-export.sh $BACKUP_NAME $encrypted 를 실행한다."

if [ "$recipient_count" -lt 2 ]; then
  echo
  echo "경고: 수신자가 한 명뿐이다. 그 사람이 빠지면 keyring을 열 수 없다."
fi
