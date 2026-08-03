#!/bin/sh

# Backup 암호화 수신자 공개키를 등록한다.
#
# 개인키는 이 서버에 두지 않는다. EC2가 통째로 털려도 반출본을 열 수 없어야 하고,
# 복호화는 키를 가진 사람의 PC에서만 일어나야 한다.
#
#   ./prepare-backup-encryption.sh /path/to/backup-recipient-public.asc

set -eu
set +x

SECRET_DIR="${SECRET_DIR:-/opt/gilbom/secrets}"
TARGET_FILE="$SECRET_DIR/backup-recipient.asc"

fail() {
  echo "Backup encryption setup failed: $1" >&2
  exit 1
}

[ "$#" -eq 1 ] || fail "usage: $0 PUBLIC_KEY_FILE"
source_key=$1

command -v gpg >/dev/null 2>&1 || fail "gpg is required"
[ -f "$source_key" ] || fail "public key file does not exist: $source_key"
[ -s "$source_key" ] || fail "public key file is empty"

umask 077
home="$(mktemp -d)"
chmod 0700 "$home"
cleanup() {
  rm -rf "$home"
}
trap cleanup EXIT HUP INT TERM

gpg --batch --quiet --no-options --homedir "$home" \
  --import "$source_key" >/dev/null 2>&1 \
  || fail "file is not an importable OpenPGP key"

# 개인키가 섞여 들어오면 서버에 복호화 수단이 생긴다. 그 자체가 이 설계의 반대다.
if gpg --batch --quiet --no-options --homedir "$home" \
    --list-secret-keys --with-colons 2>/dev/null \
    | grep -q '^sec:'; then
  fail "file contains a private key; export only the public key"
fi

fingerprint="$(
  gpg --batch --quiet --no-options --homedir "$home" \
    --list-keys --with-colons 2>/dev/null \
    | awk -F: '$1 == "fpr" { print $10; exit }'
)"
[ -n "$fingerprint" ] || fail "key fingerprint could not be read"

install -d -m 0700 "$SECRET_DIR"
install -m 0400 "$source_key" "$TARGET_FILE"

echo "Backup encryption setup: PASS"
echo "Recipient key: $TARGET_FILE"
echo "Fingerprint: $fingerprint"
echo "이 지문을 개인키 보관자와 대조한다. 지문이 다르면 아무도 못 여는 backup이 쌓인다."
