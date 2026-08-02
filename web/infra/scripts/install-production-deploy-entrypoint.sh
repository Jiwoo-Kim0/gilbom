#!/bin/sh

set -eu
set +x
umask 077

PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
HOME=/root
export PATH HOME
unset CDPATH ENV BASH_ENV

fail() {
  echo "Production deploy entrypoint installation failed: $1" >&2
  exit 1
}

[ "$(id -u)" -eq 0 ] \
  || fail "this command must run as root"
[ "$#" -eq 0 ] \
  || fail "this installer accepts no arguments"

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"

for source in \
  "$SCRIPT_DIR/gilbom-deploy.sh" \
  "$SCRIPT_DIR/gilbom-approve-migrations.sh" \
  "$PROJECT_DIR/scripts/verify-production-deploy-account.sh"
do
  [ -f "$source" ] && [ ! -L "$source" ] && [ -s "$source" ] \
    || fail "required source is missing, empty, or symlinked: $source"
done

getent passwd deploy >/dev/null 2>&1 \
  || fail "Stage 10 deploy account is missing"
grep -Fxq \
  'deploy ALL=(root) NOPASSWD: /usr/local/sbin/gilbom-deploy' \
  /etc/sudoers.d/gilbom-production-deploy \
  || fail "Stage 10 sudoers command boundary is missing"
visudo -cf /etc/sudoers >/dev/null \
  || fail "installed sudoers policy is invalid"

install -d -o root -g root -m 0700 \
  /opt/gilbom/migration-approvals \
  /var/lib/gilbom/releases \
  /run/gilbom-deploy
install -o root -g root -m 0755 \
  "$SCRIPT_DIR/gilbom-deploy.sh" \
  /usr/local/sbin/gilbom-deploy
install -o root -g root -m 0750 \
  "$SCRIPT_DIR/gilbom-approve-migrations.sh" \
  /usr/local/sbin/gilbom-approve-migrations
install -o root -g root -m 0755 \
  "$PROJECT_DIR/scripts/verify-production-deploy-account.sh" \
  /usr/local/sbin/gilbom-verify-production-deploy-account

/usr/local/sbin/gilbom-verify-production-deploy-account
echo "Production deploy entrypoint installation: PASS"
