#!/bin/sh

set -eu
set +x
umask 077

PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
HOME=/root
export PATH HOME
unset CDPATH ENV BASH_ENV

fail() {
  echo "Production read-only verifier installation failed: $1" >&2
  exit 1
}

[ "$(id -u)" -eq 0 ] || fail "this installer must run as root"
[ "$#" -eq 0 ] || fail "this installer accepts no arguments"

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH='' cd -- "$SCRIPT_DIR/../.." && pwd)"
SOURCE="$PROJECT_DIR/scripts/collect-production-readonly-evidence.py"
TARGET=/usr/local/sbin/gilbom-production-readonly-evidence
SUDOERS_FILE=/etc/sudoers.d/gilbom-production-readonly-verifier

[ -f "$SOURCE" ] && [ ! -L "$SOURCE" ] && [ -s "$SOURCE" ] \
  || fail "collector source is missing, empty, or symlinked"
getent passwd ubuntu >/dev/null 2>&1 \
  || fail "fixed production evidence operator account is missing"
python3 -c \
  'import pathlib, sys; compile(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"), sys.argv[1], "exec")' \
  "$SOURCE" \
  || fail "collector source does not compile"

install -o root -g root -m 0755 "$SOURCE" "$TARGET"
sudoers_temp="$(mktemp)"
{
  printf 'Defaults:ubuntu env_reset\n'
  printf 'Defaults:ubuntu !setenv\n'
  printf 'ubuntu ALL=(root) NOPASSWD: %s\n' "$TARGET"
  printf 'ubuntu ALL=(root) NOPASSWD: %s --aws-security-groups /tmp/gilbom-security-groups.json\n' \
    "$TARGET"
} > "$sudoers_temp"
chmod 0440 "$sudoers_temp"
visudo -cf "$sudoers_temp" >/dev/null \
  || fail "generated sudoers policy is invalid"
install -o root -g root -m 0440 "$sudoers_temp" "$SUDOERS_FILE"
rm -f -- "$sudoers_temp"
visudo -cf /etc/sudoers >/dev/null \
  || fail "installed sudoers policy is invalid"

echo "Production read-only verifier installation: PASS"
