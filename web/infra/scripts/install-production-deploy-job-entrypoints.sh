#!/bin/sh

set -eu
set +x
umask 077

PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
HOME=/root
export PATH HOME
unset CDPATH ENV BASH_ENV

fail() {
  echo "Production deploy Job entrypoint installation failed: $1" >&2
  exit 1
}

[ "$(id -u)" -eq 0 ] \
  || fail "this command must run as root"
[ "$#" -eq 0 ] \
  || fail "this installer accepts no arguments"

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH='' cd -- "$SCRIPT_DIR/../.." && pwd)"
ARTIFACT_OPERATOR_USER=ubuntu
SUDOERS_FILE=/etc/sudoers.d/gilbom-production-deploy-job

getent passwd "$ARTIFACT_OPERATOR_USER" >/dev/null 2>&1 \
  || fail "fixed artifact operator account is missing: $ARTIFACT_OPERATOR_USER"
[ "$ARTIFACT_OPERATOR_USER" != deploy ] \
  || fail "deploy account must not record approvals or retrieve manifests"

for source in \
  "$SCRIPT_DIR/gilbom-deploy-preflight.sh" \
  "$SCRIPT_DIR/gilbom-record-deploy-approval.sh" \
  "$SCRIPT_DIR/gilbom-release-manifest.sh" \
  "$SCRIPT_DIR/gilbom-rollback-preflight.sh" \
  "$SCRIPT_DIR/gilbom-record-rollback-approval.sh" \
  "$SCRIPT_DIR/gilbom-rollback-manifest.sh" \
  "$SCRIPT_DIR/gilbom-deploy.sh" \
  "$PROJECT_DIR/scripts/verify-production-deploy-job-host.sh"
do
  [ -f "$source" ] && [ ! -L "$source" ] && [ -s "$source" ] \
    || fail "required source is missing, empty, or symlinked: $source"
done

grep -Fxq \
  'deploy ALL=(root) NOPASSWD: /usr/local/sbin/gilbom-deploy' \
  /etc/sudoers.d/gilbom-production-deploy \
  || fail "Stage 10 deploy sudo boundary is missing"

install -d -o root -g root -m 0700 /opt/gilbom/deploy-approvals
install -d -o root -g root -m 0700 /opt/gilbom/rollback-approvals
install -o root -g root -m 0755 \
  "$SCRIPT_DIR/gilbom-deploy-preflight.sh" \
  /usr/local/sbin/gilbom-deploy-preflight
install -o root -g root -m 0755 \
  "$SCRIPT_DIR/gilbom-record-deploy-approval.sh" \
  /usr/local/sbin/gilbom-record-deploy-approval
install -o root -g root -m 0755 \
  "$SCRIPT_DIR/gilbom-release-manifest.sh" \
  /usr/local/sbin/gilbom-release-manifest
install -o root -g root -m 0755 \
  "$SCRIPT_DIR/gilbom-rollback-preflight.sh" \
  /usr/local/sbin/gilbom-rollback-preflight
install -o root -g root -m 0755 \
  "$SCRIPT_DIR/gilbom-record-rollback-approval.sh" \
  /usr/local/sbin/gilbom-record-rollback-approval
install -o root -g root -m 0755 \
  "$SCRIPT_DIR/gilbom-rollback-manifest.sh" \
  /usr/local/sbin/gilbom-rollback-manifest
install -o root -g root -m 0755 \
  "$SCRIPT_DIR/gilbom-deploy.sh" \
  /usr/local/sbin/gilbom-deploy
install -o root -g root -m 0755 \
  "$PROJECT_DIR/scripts/verify-production-deploy-job-host.sh" \
  /usr/local/sbin/gilbom-verify-production-deploy-job-host

sudoers_temp="$(mktemp)"
{
  printf 'Defaults:%s env_reset\n' "$ARTIFACT_OPERATOR_USER"
  printf 'Defaults:%s !setenv\n' "$ARTIFACT_OPERATOR_USER"
  printf '%s ALL=(root) NOPASSWD: %s, %s, %s, %s, %s, %s\n' \
    "$ARTIFACT_OPERATOR_USER" \
    /usr/local/sbin/gilbom-deploy-preflight \
    /usr/local/sbin/gilbom-record-deploy-approval \
    /usr/local/sbin/gilbom-release-manifest \
    /usr/local/sbin/gilbom-rollback-preflight \
    /usr/local/sbin/gilbom-record-rollback-approval \
    /usr/local/sbin/gilbom-rollback-manifest
} > "$sudoers_temp"
chmod 0440 "$sudoers_temp"
visudo -cf "$sudoers_temp" >/dev/null \
  || fail "generated deploy Job sudoers policy is invalid"
install -o root -g root -m 0440 "$sudoers_temp" "$SUDOERS_FILE"
rm -f -- "$sudoers_temp"
visudo -cf /etc/sudoers >/dev/null \
  || fail "installed sudoers policy is invalid"

/usr/local/sbin/gilbom-verify-production-deploy-job-host
echo "Production deploy Job entrypoint installation: PASS"
