#!/bin/sh

# 등록된 각 수신자가 **실제로 개인키를 갖고 있는지** 증명하게 한다.
#
# 왜 필요한가: 등록 스크립트는 공개키만 보므로 그 짝인 개인키가 세상에 존재하는지
# 알 수 없다. 실제로 개인키가 아무 데도 없는 공개키로 두 달치 backup이 쌓인 적이
# 있다. 암호화도 checksum도 manifest도 전부 정상이었고 알람도 뜨지 않았다.
# "backup이 있다"와 "backup을 열 수 있다"는 다른 사실이다.
#
# 이 스크립트는 수신자별로 무작위 문자열을 그 사람 키로만 암호화해 출력한다.
# 각 보관자가 자기 PC에서 복호화해 문자열을 돌려주면, 그 사람이 개인키를 갖고
# 있다는 것이 증명된다. 지문 대조와 달리 건너뛸 수 없다.
#
#   sh verify-backup-recipients.sh

set -eu
set +x

BACKUP_RECIPIENT_KEY_FILE="${BACKUP_RECIPIENT_KEY_FILE:-/opt/gilbom/secrets/backup-recipient.asc}"

fail() {
  echo "Backup recipient verification failed: $1" >&2
  exit 1
}

command -v gpg >/dev/null 2>&1 || fail "gpg is required"
[ -s "$BACKUP_RECIPIENT_KEY_FILE" ] \
  || fail "recipient key file is missing or empty: $BACKUP_RECIPIENT_KEY_FILE"

umask 077
home="$(mktemp -d)"
chmod 0700 "$home"
cleanup() {
  rm -rf "$home"
}
trap cleanup EXIT HUP INT TERM

gpg --batch --quiet --no-options --homedir "$home" \
  --import "$BACKUP_RECIPIENT_KEY_FILE" >/dev/null 2>&1 \
  || fail "recipient key file could not be imported"

fingerprints="$(
  gpg --batch --quiet --no-options --homedir "$home" \
    --list-keys --with-colons 2>/dev/null \
    | awk -F: '
        $1 == "pub" { want = 1; next }
        $1 == "fpr" && want { print $10; want = 0 }
      '
)"
[ -n "$fingerprints" ] || fail "no recipient fingerprint could be read"

count="$(printf '%s\n' "$fingerprints" | grep -c .)"
echo "등록된 수신자: $count"
echo

for fingerprint in $fingerprints
do
  uid="$(
    gpg --batch --quiet --no-options --homedir "$home" \
      --list-keys --with-colons "$fingerprint" 2>/dev/null \
      | awk -F: '$1 == "uid" { print $10; exit }'
  )"
  # 사람이 눈으로 옮겨 적을 수 있는 길이로 만든다.
  challenge="gilbom-$(head -c 9 /dev/urandom | base64 | tr -d '/+=' | cut -c1-12)"

  echo "────────────────────────────────────────────────────────"
  echo "수신자: ${uid:-(이름 없음)}"
  echo "지문:   $fingerprint"
  echo
  echo "아래 블록을 이 사람에게 보내고, 자기 PC에서 다음을 실행하게 한다."
  echo
  echo "  gpg --decrypt <<'EOF'"
  gpg --batch --quiet --no-options --homedir "$home" \
    --trust-model always \
    --recipient "$fingerprint" \
    --armor --encrypt <<CHALLENGE
$challenge
CHALLENGE
  echo "EOF"
  echo
  echo "돌려받은 값이 이것과 같아야 한다: $challenge"
  echo
done

echo "────────────────────────────────────────────────────────"
echo
echo "돌려받지 못한 수신자가 있으면 그 사람은 개인키를 갖고 있지 않다."
echo "그 상태로 쌓이는 backup은 그 사람 몫으로는 열리지 않는다."
if [ "$count" -lt 2 ]; then
  echo
  echo "경고: 수신자가 한 명뿐이다. 그 사람이 빠지면 모든 backup을 열 수 없다."
fi
