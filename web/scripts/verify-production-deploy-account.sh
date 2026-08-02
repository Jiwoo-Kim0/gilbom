#!/bin/sh

set -eu
set +x

fail() {
  echo "Production deploy account verification failed: $1" >&2
  exit 1
}

[ "$(id -u)" -eq 0 ] \
  || fail "run this verification as root"
[ "$#" -eq 0 ] \
  || fail "this verification accepts no arguments"

for command in getent stat find runuser sudo sshd docker
do
  command -v "$command" >/dev/null 2>&1 \
    || fail "required command is missing: $command"
done

getent passwd deploy >/dev/null 2>&1 \
  || fail "deploy account is missing"
[ "$(getent passwd deploy | cut -d: -f6)" = /home/deploy ] \
  || fail "deploy home directory is not fixed"
[ "$(getent passwd deploy | cut -d: -f7)" = /bin/bash ] \
  || fail "deploy login shell is unexpected"

password_state="$(passwd -S deploy | awk '{print $2}')"
case "$password_state" in
  L|LK) ;;
  *) fail "deploy password is not locked" ;;
esac

deploy_groups="$(id -nG deploy)"
for forbidden_group in docker sudo admin wheel
do
  case " $deploy_groups " in
    *" $forbidden_group "*)
      fail "deploy belongs to a forbidden group: $forbidden_group"
      ;;
  esac
done

[ "$(stat -c '%U:%G:%a' /home/deploy/.ssh)" = deploy:deploy:700 ] \
  || fail "deploy .ssh ownership or mode is invalid"
[ "$(stat -c '%U:%G:%a' /home/deploy/.ssh/authorized_keys)" = deploy:deploy:600 ] \
  || fail "deploy authorized_keys ownership or mode is invalid"
[ "$(wc -l < /home/deploy/.ssh/authorized_keys)" -eq 1 ] \
  || fail "deploy must have exactly one authorized key"
grep -Eq \
  '^restrict,command="/usr/local/sbin/gilbom-deploy-ssh-command" ssh-ed25519 AAAA[A-Za-z0-9+/=]+( [A-Za-z0-9._@+-]+)?$' \
  /home/deploy/.ssh/authorized_keys \
  || fail "deploy authorized key is not forced-command restricted"

for root_file in \
  /usr/local/sbin/gilbom-deploy \
  /usr/local/sbin/gilbom-deploy-ssh-command \
  /usr/local/sbin/gilbom-verify-production-deploy-account
do
  [ "$(stat -c '%U:%G:%a' "$root_file")" = root:root:755 ] \
    || fail "root command ownership or mode is invalid: $root_file"
done
[ "$(stat -c '%U:%G:%a' /usr/local/sbin/gilbom-approve-migrations)" \
    = root:root:750 ] \
  || fail "migration approval command ownership or mode is invalid"
for root_directory in \
  /opt/gilbom/migration-approvals \
  /var/lib/gilbom/releases \
  /run/gilbom-deploy
do
  [ "$(stat -c '%U:%G:%a' "$root_directory")" = root:root:700 ] \
    || fail "root deployment directory ownership or mode is invalid: $root_directory"
done
[ "$(stat -c '%U:%G:%a' /etc/sudoers.d/gilbom-production-deploy)" \
    = root:root:440 ] \
  || fail "deploy sudoers ownership or mode is invalid"
grep -Fxq \
  'deploy ALL=(root) NOPASSWD: /usr/local/sbin/gilbom-deploy' \
  /etc/sudoers.d/gilbom-production-deploy \
  || fail "deploy sudoers command allowlist is invalid"
grep -Fxq 'Defaults:deploy !setenv' \
  /etc/sudoers.d/gilbom-production-deploy \
  || fail "deploy sudo environment override protection is missing"
visudo -cf /etc/sudoers >/dev/null \
  || fail "sudoers validation failed"
sshd -t || fail "SSH daemon configuration validation failed"
grep -Fxq 'Match all' \
  /etc/ssh/sshd_config.d/60-gilbom-production-deploy.conf \
  || fail "deploy SSH Match block is not reset"
[ "$(stat -c '%U:%G' /opt/gilbom/deploy)" = root:root ] \
  || fail "fixed deployment root is not root-owned"
[ -z "$(find /opt/gilbom/deploy -maxdepth 0 -perm /0022 -print -quit)" ] \
  || fail "fixed deployment root is group/world writable"

docker_path="$(command -v docker)"
if runuser -u deploy -- \
    sudo --non-interactive "$docker_path" version >/dev/null 2>&1; then
  fail "deploy can run sudo docker"
fi
if runuser -u deploy -- \
    sudo --non-interactive /bin/sh -c id >/dev/null 2>&1; then
  fail "deploy can open a root shell"
fi

invalid_output="$(
  runuser -u deploy -- \
    sudo --non-interactive \
      /usr/local/sbin/gilbom-deploy invalid 2>&1 \
    || true
)"
printf '%s\n' "$invalid_output" \
  | grep -Fq 'release SHA must contain 40 lowercase hexadecimal characters' \
  || fail "invalid SHA was not rejected by the allowed root command"

valid_sha=0123456789abcdef0123456789abcdef01234567
extra_output="$(
  runuser -u deploy -- \
    sudo --non-interactive \
      /usr/local/sbin/gilbom-deploy "$valid_sha" extra 2>&1 \
    || true
)"
printf '%s\n' "$extra_output" | grep -Fq 'usage:' \
  || fail "a second deploy argument was not rejected"

override_output="$(
  COMPOSE_FILE=/tmp/attacker-compose.yaml \
    /usr/local/sbin/gilbom-deploy "$valid_sha" 2>&1 \
    || true
)"
printf '%s\n' "$override_output" \
  | grep -Fq 'deployment path and image environment overrides are forbidden' \
  || fail "an arbitrary Compose path was not rejected"

if runuser -u deploy -- \
    sudo --non-interactive \
      COMPOSE_FILE=/tmp/attacker-compose.yaml \
      /usr/local/sbin/gilbom-deploy "$valid_sha" >/dev/null 2>&1; then
  fail "deploy can pass an arbitrary Compose path through sudo"
fi

echo "Production deploy account verification: PASS"
