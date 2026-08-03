#!/bin/sh

set -eu
set +x
umask 077

PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
HOME=/root
export PATH HOME
unset CDPATH ENV BASH_ENV

fail() {
  echo "Cosign release verifier installation failed: $1" >&2
  exit 1
}

[ "$(id -u)" -eq 0 ] \
  || fail "this command must run as root"
[ "$#" -eq 1 ] \
  || fail "usage: $0 RELEASE_PUBLIC_KEY_FILE"

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH='' cd -- "$SCRIPT_DIR/../.." && pwd)"
VERSION_FILE="$PROJECT_DIR/infra/cosign/version.env"
PUBLIC_KEY_SOURCE="$1"
COSIGN_BIN=/usr/local/bin/cosign
PUBLIC_KEY_DESTINATION=/etc/gilbom/cosign-release.pub

[ -f "$VERSION_FILE" ] && [ ! -L "$VERSION_FILE" ] \
  || fail "pinned Cosign version file is missing or symlinked"
# shellcheck disable=SC1090
. "$VERSION_FILE"

for command_name in awk chmod chown curl grep install mktemp openssl rm sha256sum uname
do
  command -v "$command_name" >/dev/null 2>&1 \
    || fail "$command_name is required"
done
[ -f "$PUBLIC_KEY_SOURCE" ] \
  && [ ! -L "$PUBLIC_KEY_SOURCE" ] \
  && [ -s "$PUBLIC_KEY_SOURCE" ] \
  || fail "release public key is missing, empty, or symlinked"
openssl pkey -pubin -in "$PUBLIC_KEY_SOURCE" -noout >/dev/null 2>&1 \
  || fail "release public key is not a valid PEM public key"

case "$(uname -m)" in
  x86_64)
    asset=cosign-linux-amd64
    expected_sha256="$COSIGN_LINUX_AMD64_SHA256"
    ;;
  aarch64|arm64)
    asset=cosign-linux-arm64
    expected_sha256="$COSIGN_LINUX_ARM64_SHA256"
    ;;
  *) fail "unsupported host architecture" ;;
esac

work_dir="$(mktemp -d /var/tmp/gilbom-cosign-install.XXXXXX)"
cleanup() {
  rm -rf -- "$work_dir" >/dev/null 2>&1 || true
}
trap cleanup EXIT HUP INT TERM
download="$work_dir/cosign"

curl \
  --proto '=https' \
  --tlsv1.2 \
  --fail \
  --silent \
  --show-error \
  --location \
  --output "$download" \
  "https://github.com/sigstore/cosign/releases/download/v$COSIGN_VERSION/$asset" \
  || fail "could not download the pinned Cosign binary"
printf '%s  %s\n' "$expected_sha256" "$download" \
  | sha256sum -c - >/dev/null \
  || fail "downloaded Cosign binary Digest does not match the pin"
chmod 0755 "$download"
"$download" version \
  | grep -Fq "GitVersion:    v$COSIGN_VERSION" \
  || fail "downloaded Cosign binary version does not match the pin"

install -d -o root -g root -m 0755 /etc/gilbom
install -o root -g root -m 0755 "$download" "$COSIGN_BIN"
install -o root -g root -m 0444 \
  "$PUBLIC_KEY_SOURCE" "$PUBLIC_KEY_DESTINATION"

[ "$(sha256sum "$COSIGN_BIN" | awk '{print $1}')" = "$expected_sha256" ] \
  || fail "installed Cosign binary Digest changed"
openssl pkey -pubin -in "$PUBLIC_KEY_DESTINATION" -noout >/dev/null 2>&1 \
  || fail "installed release public key is invalid"
public_key_sha256="$(
  sha256sum "$PUBLIC_KEY_DESTINATION" | awk '{print $1}'
)"

echo \
  "Cosign release verifier installation: PASS (v$COSIGN_VERSION, publicKeySha256=$public_key_sha256)"
