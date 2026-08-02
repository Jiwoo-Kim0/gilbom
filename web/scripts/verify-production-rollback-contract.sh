#!/bin/sh

set -eu

fail() {
  echo "Production conditional rollback contract failed: $1" >&2
  exit 1
}

for file in \
  Jenkinsfile.rollback \
  infra/scripts/gilbom-deploy.sh \
  infra/scripts/gilbom-rollback-preflight.sh \
  infra/scripts/gilbom-record-rollback-approval.sh \
  infra/scripts/gilbom-rollback-manifest.sh \
  infra/scripts/install-production-deploy-entrypoint.sh \
  infra/scripts/install-production-deploy-job-entrypoints.sh \
  scripts/run-production-rollback-preflight-remote.sh \
  scripts/record-production-rollback-approval-remote.sh \
  scripts/fetch-production-rollback-manifest.sh \
  scripts/render-production-rollback-approval.sh \
  scripts/verify-production-rollback-evidence.sh \
  scripts/test-production-rollback-job.sh \
  scripts/test-production-conditional-rollback-integration.sh \
  docs/production-conditional-rollback.md
do
  [ -s "$file" ] || fail "required file is missing or empty: $file"
done

assert_contains() {
  file="$1"
  text="$2"
  message="$3"
  grep -Fq "$text" "$file" || fail "$message"
}

assert_contains infra/scripts/gilbom-deploy.sh \
  'rollback_armed=true' \
  "root deploy does not arm conditional rollback after image transition"
assert_contains infra/scripts/gilbom-deploy.sh \
  'AUTO_ALLOWED_NO_FLYWAY_CHANGE' \
  "no-migration automatic rollback decision is missing"
assert_contains infra/scripts/gilbom-deploy.sh \
  'ensure_rollback_images "$current_sha"' \
  "missing previous images are not restored from the previous Artifact"
assert_contains infra/scripts/gilbom-deploy.sh \
  'manual-recovery-required.json' \
  "manual recovery manifest is missing"
assert_contains infra/scripts/gilbom-deploy.sh \
  'compose stop frontend' \
  "Frontend containment is missing"
assert_contains infra/scripts/gilbom-deploy.sh \
  'APPROVED_PREVIOUS_BACKEND_COMPATIBLE' \
  "manual compatibility approval is not enforced"
assert_contains infra/scripts/gilbom-deploy.sh \
  'perform_approved_manual_rollback' \
  "same root deployer does not implement approved recovery mode"
assert_contains infra/scripts/gilbom-deploy.sh \
  'migrationUndoPerformed: false' \
  "rollback manifests do not prove that migrations were retained"
assert_contains infra/scripts/gilbom-deploy.sh \
  'backupRestorePerformed: false' \
  "rollback manifests do not prove that backup restore was skipped"

if grep -Eiq \
    'flyway[[:space:]-]+undo|pg_restore|restore-db|down[[:space:]]+-v' \
    infra/scripts/gilbom-deploy.sh \
    infra/scripts/gilbom-rollback-preflight.sh \
    infra/scripts/gilbom-record-rollback-approval.sh \
    Jenkinsfile.rollback; then
  fail "automatic database undo or restore command is forbidden"
fi

assert_contains Jenkinsfile.rollback \
  "name: 'FAILED_RELEASE_SHA'" \
  "failed release SHA parameter is missing"
assert_contains Jenkinsfile.rollback \
  "submitter: env.PRODUCTION_ROLLBACK_APPROVERS" \
  "authenticated rollback approver allowlist is missing"
assert_contains Jenkinsfile.rollback \
  "ok: 'Approve compatible rollback'" \
  "explicit previous Backend compatibility approval is missing"
assert_contains Jenkinsfile.rollback \
  'scripts/run-production-deploy-remote.sh' \
  "approved rollback does not reuse the fixed root deployer"
assert_contains Jenkinsfile.rollback \
  'scripts/verify-external-https-smoke.py' \
  "external HTTPS smoke after rollback is missing"
assert_contains Jenkinsfile.rollback \
  "artifacts: '.artifacts/rollback/**'" \
  "rollback evidence is not archived"

preflight_line="$(
  grep -nF "stage('Collect contained failure evidence')" \
    Jenkinsfile.rollback | cut -d: -f1
)"
approval_line="$(
  grep -nF "stage('Approve previous Backend compatibility')" \
    Jenkinsfile.rollback | cut -d: -f1
)"
record_line="$(
  grep -nF "stage('Record exact rollback approval')" \
    Jenkinsfile.rollback | cut -d: -f1
)"
deploy_line="$(
  grep -nF "stage('Run previous SHA through fixed root deployer')" \
    Jenkinsfile.rollback | cut -d: -f1
)"
[ "$preflight_line" -lt "$approval_line" ] \
  && [ "$approval_line" -lt "$record_line" ] \
  && [ "$record_line" -lt "$deploy_line" ] \
  || fail "rollback preflight, approval, recording, and deploy order is invalid"

if grep -Fq \
  'deploy ALL=(root) NOPASSWD: /usr/local/sbin/gilbom-record-rollback-approval' \
  infra/scripts/install-production-deploy-job-entrypoints.sh; then
  fail "deploy account must not record its own rollback approval"
fi
assert_contains infra/scripts/prepare-production-deploy-account.sh \
  'deploy ALL=(root) NOPASSWD: /usr/local/sbin/gilbom-deploy' \
  "deploy sudo boundary changed"

sh -n \
  infra/scripts/gilbom-deploy.sh \
  infra/scripts/gilbom-rollback-preflight.sh \
  infra/scripts/gilbom-record-rollback-approval.sh \
  infra/scripts/gilbom-rollback-manifest.sh \
  infra/scripts/install-production-deploy-entrypoint.sh \
  infra/scripts/install-production-deploy-job-entrypoints.sh \
  scripts/run-production-rollback-preflight-remote.sh \
  scripts/record-production-rollback-approval-remote.sh \
  scripts/fetch-production-rollback-manifest.sh \
  scripts/render-production-rollback-approval.sh \
  scripts/verify-production-rollback-evidence.sh \
  scripts/test-production-rollback-job.sh \
  scripts/test-production-conditional-rollback-integration.sh
sh scripts/test-production-rollback-job.sh

echo "Production conditional rollback contract: PASS"
