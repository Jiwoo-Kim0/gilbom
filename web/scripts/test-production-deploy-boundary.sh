#!/bin/sh

set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
DEPLOY_SCRIPT="$PROJECT_DIR/infra/scripts/gilbom-deploy.sh"
SSH_COMMAND="$PROJECT_DIR/infra/scripts/gilbom-deploy-ssh-command.sh"
PREPARE_SCRIPT="$PROJECT_DIR/infra/scripts/prepare-production-deploy-account.sh"
VERIFY_SCRIPT="$PROJECT_DIR/scripts/verify-production-deploy-account.sh"

fail() {
  echo "Production deploy boundary self-test failed: $1" >&2
  exit 1
}

for file in \
  "$DEPLOY_SCRIPT" \
  "$SSH_COMMAND" \
  "$PREPARE_SCRIPT" \
  "$VERIFY_SCRIPT"
do
  [ -s "$file" ] || fail "required file is missing or empty: $file"
done

sh -n "$DEPLOY_SCRIPT" "$SSH_COMMAND" "$PREPARE_SCRIPT" "$VERIFY_SCRIPT"

invalid_output="$(sh "$DEPLOY_SCRIPT" invalid 2>&1 || true)"
printf '%s\n' "$invalid_output" \
  | grep -Fq 'release SHA must contain 40 lowercase hexadecimal characters' \
  || fail "invalid SHA is not rejected before deployment"

valid_sha=0123456789abcdef0123456789abcdef01234567
extra_output="$(sh "$DEPLOY_SCRIPT" "$valid_sha" extra 2>&1 || true)"
printf '%s\n' "$extra_output" | grep -Fq 'usage:' \
  || fail "a second argument is not rejected"

override_output="$(
  COMPOSE_FILE=/tmp/attacker-compose.yaml \
    sh "$DEPLOY_SCRIPT" "$valid_sha" 2>&1 \
    || true
)"
printf '%s\n' "$override_output" \
  | grep -Fq 'deployment path and image environment overrides are forbidden' \
  || fail "an arbitrary Compose path is not rejected"

bad_ssh_output="$(
  USER=deploy \
  SSH_ORIGINAL_COMMAND='sudo --non-interactive docker version' \
    sh "$SSH_COMMAND" 2>&1 \
    || true
)"
printf '%s\n' "$bad_ssh_output" \
  | grep -Fq 'only the fixed production deploy command is allowed' \
  || fail "SSH command restriction does not reject sudo docker"

extra_ssh_output="$(
  USER=deploy \
  SSH_ORIGINAL_COMMAND="sudo --non-interactive /usr/local/sbin/gilbom-deploy $valid_sha extra" \
    sh "$SSH_COMMAND" 2>&1 \
    || true
)"
printf '%s\n' "$extra_ssh_output" \
  | grep -Eq 'release SHA must|additional arguments' \
  || fail "SSH command restriction does not reject additional arguments"

grep -Fq 'ARTIFACT_ROOT=/opt/gilbom/artifacts' "$DEPLOY_SCRIPT" \
  || fail "artifact root is not fixed"
grep -Fq 'DEPLOY_ROOT=/opt/gilbom/deploy' "$DEPLOY_SCRIPT" \
  || fail "deployment root is not fixed"
grep -Fq 'COMPOSE_PATH="$DEPLOY_ROOT/compose.prod.yaml"' "$DEPLOY_SCRIPT" \
  || fail "production Compose path is not fixed"
grep -Fq "printf 'Defaults:deploy !setenv" "$PREPARE_SCRIPT" \
  || fail "sudo environment override protection is missing"
grep -Fq \
  "printf 'deploy ALL=(root) NOPASSWD: /usr/local/sbin/gilbom-deploy" \
  "$PREPARE_SCRIPT" \
  || fail "sudo command allowlist is missing"
grep -Fq \
  'restrict,command="/usr/local/sbin/gilbom-deploy-ssh-command"' \
  "$PREPARE_SCRIPT" \
  || fail "Jenkins SSH key is not forced-command restricted"
grep -Fq "usermod --lock" "$PREPARE_SCRIPT" \
  || fail "password locking is missing"
grep -Fq 'usermod --gid "$DEPLOY_GROUP" --groups '\'\''' "$PREPARE_SCRIPT" \
  || fail "supplementary group removal is missing"
grep -Fq "printf 'Match all" "$PREPARE_SCRIPT" \
  || fail "SSH Match block reset is missing"
grep -Fq 'chown -R root:root /opt/gilbom/deploy' "$PREPARE_SCRIPT" \
  || fail "fixed deployment root is not protected from deploy"

echo "Production deploy boundary self-test: PASS"
