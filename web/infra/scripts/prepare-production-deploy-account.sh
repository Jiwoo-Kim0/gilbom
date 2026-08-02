#!/bin/sh

set -eu
set +x
umask 077

fail() {
  echo "Production deploy account preparation failed: $1" >&2
  exit 1
}

[ "$(id -u)" -eq 0 ] \
  || fail "run this script as root"
[ "$#" -eq 1 ] \
  || fail "usage: $0 JENKINS_DEPLOY_PUBLIC_KEY_FILE"

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH='' cd -- "$SCRIPT_DIR/../.." && pwd)"
PUBLIC_KEY_FILE="$1"
DEPLOY_USER=deploy
DEPLOY_GROUP=deploy
DEPLOY_HOME=/home/deploy
AUTHORIZED_KEYS="$DEPLOY_HOME/.ssh/authorized_keys"
SUDOERS_FILE=/etc/sudoers.d/gilbom-production-deploy
SSHD_DROP_IN=/etc/ssh/sshd_config.d/60-gilbom-production-deploy.conf

for command in \
  getent groupadd useradd usermod install ssh-keygen visudo sshd \
  sudo runuser passwd docker flock jq zstd
do
  command -v "$command" >/dev/null 2>&1 \
    || fail "required command is missing: $command"
done

case "$PUBLIC_KEY_FILE" in
  /*) ;;
  *) fail "Jenkins deploy public key path must be absolute" ;;
esac
printf '%s\n' "$PUBLIC_KEY_FILE" \
  | grep -Eq '^/[A-Za-z0-9._/@+-]+$' \
  || fail "Jenkins deploy public key path contains unsafe characters"
[ -f "$PUBLIC_KEY_FILE" ] && [ ! -L "$PUBLIC_KEY_FILE" ] \
  || fail "Jenkins deploy public key file is missing or symlinked"
[ "$(wc -l < "$PUBLIC_KEY_FILE")" -eq 1 ] \
  || fail "Jenkins deploy public key file must contain exactly one line"
grep -Eq '^ssh-ed25519 AAAA[A-Za-z0-9+/=]+( [A-Za-z0-9._@+-]+)?$' \
  "$PUBLIC_KEY_FILE" \
  || fail "Jenkins deploy public key must be one OpenSSH Ed25519 key"
ssh-keygen -l -f "$PUBLIC_KEY_FILE" >/dev/null 2>&1 \
  || fail "Jenkins deploy public key is invalid"

for source in \
  "$SCRIPT_DIR/gilbom-deploy.sh" \
  "$SCRIPT_DIR/gilbom-approve-migrations.sh" \
  "$SCRIPT_DIR/gilbom-deploy-ssh-command.sh" \
  "$PROJECT_DIR/scripts/verify-production-deploy-account.sh"
do
  [ -f "$source" ] && [ ! -L "$source" ] \
    || fail "required source file is missing or symlinked: $source"
done

if ! getent group "$DEPLOY_GROUP" >/dev/null 2>&1; then
  groupadd --system "$DEPLOY_GROUP"
fi
if ! getent passwd "$DEPLOY_USER" >/dev/null 2>&1; then
  useradd \
    --create-home \
    --home-dir "$DEPLOY_HOME" \
    --gid "$DEPLOY_GROUP" \
    --shell /bin/bash \
    "$DEPLOY_USER"
fi

[ "$(getent passwd "$DEPLOY_USER" | cut -d: -f6)" = "$DEPLOY_HOME" ] \
  || fail "existing deploy account has an unexpected home directory"
usermod --gid "$DEPLOY_GROUP" --groups '' --shell /bin/bash "$DEPLOY_USER"
usermod --lock "$DEPLOY_USER"

deploy_groups="$(id -nG "$DEPLOY_USER")"
for forbidden_group in docker sudo admin wheel
do
  case " $deploy_groups " in
    *" $forbidden_group "*)
      fail "deploy remains in a forbidden group: $forbidden_group"
      ;;
  esac
done

install -d -o "$DEPLOY_USER" -g "$DEPLOY_GROUP" -m 0700 \
  "$DEPLOY_HOME" \
  "$DEPLOY_HOME/.ssh"
authorized_keys_temp="$(mktemp)"
printf 'restrict,command="/usr/local/sbin/gilbom-deploy-ssh-command" %s\n' \
  "$(cat "$PUBLIC_KEY_FILE")" > "$authorized_keys_temp"
install -o "$DEPLOY_USER" -g "$DEPLOY_GROUP" -m 0600 \
  "$authorized_keys_temp" "$AUTHORIZED_KEYS"
rm -f -- "$authorized_keys_temp"

install -o root -g root -m 0755 \
  "$SCRIPT_DIR/gilbom-deploy.sh" \
  /usr/local/sbin/gilbom-deploy
install -o root -g root -m 0750 \
  "$SCRIPT_DIR/gilbom-approve-migrations.sh" \
  /usr/local/sbin/gilbom-approve-migrations
install -o root -g root -m 0755 \
  "$SCRIPT_DIR/gilbom-deploy-ssh-command.sh" \
  /usr/local/sbin/gilbom-deploy-ssh-command
install -o root -g root -m 0755 \
  "$PROJECT_DIR/scripts/verify-production-deploy-account.sh" \
  /usr/local/sbin/gilbom-verify-production-deploy-account
install -d -o root -g root -m 0700 \
  /opt/gilbom/migration-approvals \
  /opt/gilbom/rollback-approvals \
  /var/lib/gilbom/releases \
  /run/gilbom-deploy
[ -d /opt/gilbom/deploy ] && [ ! -L /opt/gilbom/deploy ] \
  || fail "/opt/gilbom/deploy is missing or symlinked"
chown -R root:root /opt/gilbom/deploy
find /opt/gilbom/deploy -type d -exec chmod go-w {} +
find /opt/gilbom/deploy -type f -exec chmod go-w {} +

sudoers_temp="$(mktemp)"
{
  printf 'Defaults:deploy env_reset\n'
  printf 'Defaults:deploy !setenv\n'
  printf 'deploy ALL=(root) NOPASSWD: /usr/local/sbin/gilbom-deploy\n'
} > "$sudoers_temp"
chmod 0440 "$sudoers_temp"
visudo -cf "$sudoers_temp" >/dev/null \
  || fail "generated deploy sudoers policy is invalid"
install -o root -g root -m 0440 "$sudoers_temp" "$SUDOERS_FILE"
rm -f -- "$sudoers_temp"
visudo -cf /etc/sudoers >/dev/null \
  || fail "installed sudoers policy is invalid"

sshd_temp="$(mktemp)"
{
  printf 'Match User deploy\n'
  printf '    AuthenticationMethods publickey\n'
  printf '    PubkeyAuthentication yes\n'
  printf '    PasswordAuthentication no\n'
  printf '    KbdInteractiveAuthentication no\n'
  printf '    PermitEmptyPasswords no\n'
  printf '    AllowAgentForwarding no\n'
  printf '    AllowTcpForwarding no\n'
  printf '    X11Forwarding no\n'
  printf '    PermitTTY no\n'
  printf 'Match all\n'
} > "$sshd_temp"
install -d -o root -g root -m 0755 /etc/ssh/sshd_config.d
install -o root -g root -m 0644 "$sshd_temp" "$SSHD_DROP_IN"
rm -f -- "$sshd_temp"
sshd -t || fail "installed deploy SSH policy is invalid"

if command -v systemctl >/dev/null 2>&1 \
    && {
      systemctl reload ssh 2>/dev/null \
        || systemctl reload sshd 2>/dev/null
    }; then
  :
elif command -v service >/dev/null 2>&1 \
    && service ssh reload >/dev/null 2>&1; then
  :
else
  fail "SSH service reload failed"
fi

/usr/local/sbin/gilbom-verify-production-deploy-account
echo "Production deploy account preparation: PASS"
