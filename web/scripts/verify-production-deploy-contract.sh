#!/bin/sh

set -eu

fail() {
  echo "Production deploy contract verification failed: $1" >&2
  exit 1
}

for file in \
  infra/scripts/gilbom-deploy.sh \
  infra/scripts/gilbom-deploy-ssh-command.sh \
  infra/scripts/prepare-production-deploy-account.sh \
  scripts/verify-production-deploy-account.sh \
  scripts/test-production-deploy-boundary.sh \
  docs/production-deploy-account.md
do
  [ -s "$file" ] || fail "required file is missing or empty: $file"
done

if grep -Eq \
    'docker[[:space:]]+group|usermod[^#]*(--append|-a)[^#]*docker' \
    infra/scripts/prepare-production-deploy-account.sh; then
  fail "deploy account must never be added to the Docker group"
fi

grep -Fq \
  'deploy ALL=(root) NOPASSWD: /usr/local/sbin/gilbom-deploy' \
  infra/scripts/prepare-production-deploy-account.sh \
  || fail "exact deploy sudoers rule is missing"
grep -Fq \
  "grep -Eq '^[0-9a-f]{40}$'" \
  infra/scripts/gilbom-deploy.sh \
  || fail "full lowercase release SHA validation is missing"
grep -Fq '[ "$#" -eq 1 ]' infra/scripts/gilbom-deploy.sh \
  || fail "deploy command does not require exactly one argument"
grep -Fq 'COMPOSE_PATH="$DEPLOY_ROOT/compose.prod.yaml"' \
  infra/scripts/gilbom-deploy.sh \
  || fail "production Compose path is not hard-coded"
grep -Fq 'PasswordAuthentication no' \
  infra/scripts/prepare-production-deploy-account.sh \
  || fail "deploy password authentication is not disabled"
grep -Fq 'AuthenticationMethods publickey' \
  infra/scripts/prepare-production-deploy-account.sh \
  || fail "deploy public-key authentication is not required"
grep -Fq \
  'ID: gilbom-production-deploy' \
  docs/production-deploy-account.md \
  || fail "dedicated Jenkins Credential ID is not documented"

sh scripts/test-production-deploy-boundary.sh
echo "Production deploy contract verification: PASS"
