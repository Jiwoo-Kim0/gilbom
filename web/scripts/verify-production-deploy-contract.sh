#!/bin/sh

set -eu

fail() {
  echo "Production deploy contract verification failed: $1" >&2
  exit 1
}

for file in \
  infra/scripts/gilbom-deploy.sh \
  infra/scripts/gilbom-approve-migrations.sh \
  infra/scripts/gilbom-deploy-ssh-command.sh \
  infra/scripts/install-cosign-release-verifier.sh \
  infra/scripts/install-production-deploy-entrypoint.sh \
  infra/cosign/version.env \
  infra/cosign/offline-signing-config.json \
  infra/scripts/prepare-production-deploy-account.sh \
  scripts/verify-production-deploy-account.sh \
  scripts/test-production-deploy-boundary.sh \
  docs/production-deploy-account.md \
  docs/production-root-deploy.md
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
grep -Fq 'RUNTIME_ENV_PATH="$DEPLOY_ROOT/runtime.env"' \
  infra/scripts/gilbom-deploy.sh \
  || fail "production runtime.env path is not hard-coded"
grep -Fq 'SECRET_ROOT=/opt/gilbom/secrets' \
  infra/scripts/gilbom-deploy.sh \
  || fail "production Secret path is not hard-coded"
grep -Fq 'flock --nonblock 9' infra/scripts/gilbom-deploy.sh \
  || fail "production deploy does not use flock"
grep -Fq 'atomic_line "$CURRENT_SHA_PATH"' \
  infra/scripts/gilbom-deploy.sh \
  || fail "current SHA is not atomically recorded"
current_line="$(
  grep -nF 'atomic_line "$CURRENT_SHA_PATH"' \
    infra/scripts/gilbom-deploy.sh | cut -d: -f1 | tail -n 1
)"
manifest_line="$(
  grep -nF 'mv -T -- "$manifest_temp" "$manifest_path"' \
    infra/scripts/gilbom-deploy.sh | cut -d: -f1
)"
[ "$current_line" -gt "$manifest_line" ] \
  || fail "current SHA must only change after the release manifest"
if grep -Fq \
    'NOPASSWD: /usr/local/sbin/gilbom-approve-migrations' \
    infra/scripts/prepare-production-deploy-account.sh; then
  fail "deploy account must not be allowed to approve migrations"
fi
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
grep -Fq 'migration-plan.json' docs/production-root-deploy.md \
  || fail "migration approval workflow is not documented"
grep -Fq 'DEPLOY_APPROVAL_ROOT=/opt/gilbom/deploy-approvals' \
  infra/scripts/gilbom-deploy.sh \
  || fail "Jenkins deployment approval root is not fixed"
grep -Fq 'Jenkins deployment approval is invalid or stale' \
  infra/scripts/gilbom-deploy.sh \
  || fail "stale Jenkins approval is not rejected"
grep -Fq \
  'docker image save "$1" "$2" | tar -xOf - manifest.json' \
  infra/scripts/gilbom-deploy.sh \
  || fail "portable loaded image config identity verification is missing"
grep -Fq 'engineId: $backend_engine_id' \
  infra/scripts/gilbom-deploy.sh \
  || fail "Docker storage-specific Backend engine ID evidence is missing"
grep -Fq 'verify_release_signature "$artifact_dir"' \
  infra/scripts/gilbom-deploy.sh \
  || fail "root deployment does not verify Cosign before docker load"
grep -Fq 'verify_release_manifest_signature "$artifact_dir"' \
  infra/scripts/gilbom-deploy.sh \
  || fail "root deployment does not verify the signed complete manifest"
signature_line="$(
  grep -nF 'verify_release_signature "$artifact_dir"' \
    infra/scripts/gilbom-deploy.sh | cut -d: -f1
)"
load_line="$(
  grep -nF 'gilbom-load "$artifact_dir/images.tar.zst"' \
    infra/scripts/gilbom-deploy.sh | cut -d: -f1
)"
[ "$signature_line" -lt "$load_line" ] \
  || fail "Cosign verification must happen before docker load"
manifest_signature_line="$(
  grep -nF 'verify_release_manifest_signature "$artifact_dir"' \
    infra/scripts/gilbom-deploy.sh | cut -d: -f1
)"
[ "$manifest_signature_line" -lt "$load_line" ] \
  || fail "complete manifest verification must happen before docker load"
grep -Fq 'verify_release_signature "$target_artifact"' \
  infra/scripts/gilbom-deploy.sh \
  || fail "automatic rollback archive is not verified with Cosign"
grep -Fq 'install_compose_file "$candidate_compose_path"' \
  infra/scripts/gilbom-deploy.sh \
  || fail "signed candidate Compose is not atomically installed"
grep -Fq 'prepare_release_source_tree' \
  infra/scripts/gilbom-deploy.sh \
  || fail "signed source archive is not safely staged"
grep -Fq 'activate_release_source_tree' \
  infra/scripts/gilbom-deploy.sh \
  || fail "signed source tree is not activated"
grep -Fq 'restore_deploy_source_tree' \
  infra/scripts/gilbom-deploy.sh \
  || fail "source tree activation is not rollback-safe"
grep -Fq 'previous-compose.prod.yaml' \
  infra/scripts/gilbom-deploy.sh \
  || fail "previous Compose snapshot and rollback are missing"

sh scripts/test-production-deploy-boundary.sh
echo "Production deploy contract verification: PASS"
