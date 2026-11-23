#!/bin/sh

# Backup 암호화 수신자 공개키를 등록한다.
#
# 개인키는 이 서버에 두지 않는다. EC2가 통째로 털려도 반출본을 열 수 없어야 하고,
# 복호화는 키를 가진 사람의 PC에서만 일어나야 한다.
#
# 공개키를 여러 개 주면 backup이 그 전부를 수신자로 잠근다. 담당자 한 명이 빠져도
# 다른 사람이 열 수 있어야 하므로 **최소 두 명**을 권한다. 개인키는 서로 오가지 않는다.
#
#   ./prepare-backup-encryption.sh 지우.asc A.asc

set -eu
set +x

SECRET_DIR="${SECRET_DIR:-/opt/gilbom/secrets}"
TARGET_FILE="$SECRET_DIR/backup-recipient.asc"

fail() {
  echo "Backup encryption setup failed: $1" >&2
  exit 1
}

[ "$#" -ge 1 ] || fail "usage: $0 PUBLIC_KEY_FILE [PUBLIC_KEY_FILE ...]"

command -v gpg >/dev/null 2>&1 || fail "gpg is required"
for source_key in "$@"
do
  [ -f "$source_key" ] || fail "public key file does not exist: $source_key"
  [ -s "$source_key" ] || fail "public key file is empty: $source_key"
done

umask 077
home="$(mktemp -d)"
chmod 0700 "$home"
cleanup() {
  rm -rf "$home"
}
trap cleanup EXIT HUP INT TERM

for source_key in "$@"
do
  gpg --batch --quiet --no-options --homedir "$home" \
    --import "$source_key" >/dev/null 2>&1 \
    || fail "file is not an importable OpenPGP key: $source_key"
done

# 개인키가 섞여 들어오면 서버에 복호화 수단이 생긴다. 그 자체가 이 설계의 반대다.
if gpg --batch --quiet --no-options --homedir "$home" \
    --list-secret-keys --with-colons 2>/dev/null \
    | grep -q '^sec:'; then
  fail "file contains a private key; export only the public key"
fi

fingerprints="$(
  gpg --batch --quiet --no-options --homedir "$home" \
    --list-keys --with-colons 2>/dev/null \
    | awk -F: '
        $1 == "pub" { want = 1; next }
        $1 == "fpr" && want { print $10; want = 0 }
      '
)"
[ -n "$fingerprints" ] || fail "key fingerprint could not be read"

recipient_count="$(printf '%s\n' "$fingerprints" | grep -c .)"

# 여러 파일을 하나로 합쳐 둔다. 암호화할 때 이 파일의 공개키 전부를 수신자로 쓴다.
staged="$home/recipients.asc"
: > "$staged"
for source_key in "$@"
do
  cat "$source_key" >> "$staged"
  printf '\n' >> "$staged"
done

install -d -m 0700 "$SECRET_DIR"
install -m 0400 "$staged" "$TARGET_FILE"

echo "Backup encryption setup: PASS"
echo "Recipient key: $TARGET_FILE"
echo "Recipients: $recipient_count"
printf '%s\n' "$fingerprints" | sed 's/^/  /'
echo
echo "각 지문을 해당 개인키 보관자와 육성으로 대조한다."
echo "지문이 다르면 아무도 못 여는 backup이 쌓인다."
if [ "$recipient_count" -lt 2 ]; then
  echo
  echo "경고: 수신자가 한 명뿐이다. 그 사람이 빠지면 모든 backup을 열 수 없다."
  echo "두 번째 보관자의 공개키를 함께 넣어 다시 실행한다."
fi
