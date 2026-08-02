#!/bin/sh

set -eu

fail() {
  echo "Production deploy Job contract verification failed: $1" >&2
  exit 1
}

for file in \
  Jenkinsfile.deploy \
  infra/scripts/gilbom-deploy-preflight.sh \
  infra/scripts/gilbom-record-deploy-approval.sh \
  infra/scripts/gilbom-release-manifest.sh \
  infra/scripts/install-production-deploy-job-entrypoints.sh \
  scripts/run-production-deploy-preflight-remote.sh \
  scripts/record-production-deploy-approval-remote.sh \
  scripts/run-production-deploy-remote.sh \
  scripts/fetch-production-release-manifest.sh \
  scripts/render-production-deploy-approval.sh \
  scripts/verify-external-https-smoke.py \
  scripts/verify-production-release-evidence.sh \
  scripts/verify-production-deploy-job-host.sh \
  scripts/test-production-deploy-job.sh \
  scripts/test-production-deploy-preflight-integration.sh \
  docs/production-deploy-job.md
do
  [ -s "$file" ] || fail "required file is missing or empty: $file"
done

assert_contains() {
  file="$1"
  text="$2"
  message="$3"
  grep -Fq "$text" "$file" || fail "$message"
}

assert_contains Jenkinsfile.deploy \
  "name: 'FULL_COMMIT_SHA'" \
  "full commit SHA parameter is missing"
assert_contains Jenkinsfile.deploy \
  'deleteDir()' \
  "workspace is not cleared before collecting deployment evidence"
assert_contains Jenkinsfile.deploy \
  "submitter: env.PRODUCTION_DEPLOY_APPROVERS" \
  "authenticated approver allowlist is missing"
assert_contains Jenkinsfile.deploy \
  "submitterParameter: 'APPROVED_BY'" \
  "Jenkins approver identity is not captured"
assert_contains Jenkinsfile.deploy \
  "credentialsId: 'gilbom-production-deploy'" \
  "dedicated production deploy Credential is missing"
assert_contains Jenkinsfile.deploy \
  "credentialsId: 'gilbom-artifact-known-hosts'" \
  "strict SSH known_hosts Credential is missing"
assert_contains Jenkinsfile.deploy \
  'scripts/verify-external-https-smoke.py' \
  "external HTTPS smoke is missing"
assert_contains Jenkinsfile.deploy \
  'scripts/fetch-production-release-manifest.sh' \
  "release manifest retrieval is missing"
assert_contains Jenkinsfile.deploy \
  "artifacts: '.artifacts/deploy/**'" \
  "deployment evidence is not archived"

for label in \
  'Commit SHA:' \
  'Backend Image ID:' \
  'Frontend Image ID:' \
  'Archive SHA-256:' \
  'Critical:' \
  'High:' \
  '적용 예정 Flyway Migration:' \
  '현재 운영 SHA:' \
  '롤백 대상 SHA:' \
  'Migration 호환성:'
do
  assert_contains scripts/render-production-deploy-approval.sh \
    "$label" "approval screen field is missing: $label"
done

preflight_line="$(grep -nF "stage('Collect production deploy preflight')" Jenkinsfile.deploy | cut -d: -f1)"
approval_line="$(grep -nF "stage('Approve production deployment')" Jenkinsfile.deploy | cut -d: -f1)"
record_line="$(grep -nF "stage('Stage approved immutable Artifact')" Jenkinsfile.deploy | cut -d: -f1)"
deploy_line="$(grep -nF "stage('Run fixed root production deploy')" Jenkinsfile.deploy | cut -d: -f1)"
smoke_line="$(grep -nF "stage('External HTTPS smoke')" Jenkinsfile.deploy | cut -d: -f1)"
manifest_line="$(grep -nF "stage('Collect and verify release manifest')" Jenkinsfile.deploy | cut -d: -f1)"
[ "$preflight_line" -lt "$approval_line" ] \
  && [ "$approval_line" -lt "$record_line" ] \
  && [ "$record_line" -lt "$deploy_line" ] \
  && [ "$deploy_line" -lt "$smoke_line" ] \
  && [ "$smoke_line" -lt "$manifest_line" ] \
  || fail "preflight, approval, deploy, smoke, and manifest order is invalid"

assert_contains infra/scripts/gilbom-deploy.sh \
  'DEPLOY_APPROVAL_ROOT=/opt/gilbom/deploy-approvals' \
  "root deploy does not use the fixed approval root"
assert_contains infra/scripts/gilbom-deploy-preflight.sh \
  'assert_root_owned_directory "$ARTIFACT_ROOT"' \
  "preflight does not preserve the root-owned artifact upload group boundary"
assert_contains infra/scripts/gilbom-deploy.sh \
  'Jenkins deployment approval is invalid or stale' \
  "root deploy does not reject stale Jenkins approval"
assert_contains infra/scripts/gilbom-deploy.sh \
  'approval: $deploy_approval[0]' \
  "approver identity and time are not written to the release manifest"
assert_contains infra/scripts/gilbom-record-deploy-approval.sh \
  'source: "jenkins-production-deploy-approval"' \
  "Jenkins migration approval provenance is missing"
assert_contains Jenkinsfile.deploy \
  "approvedBy = approver['APPROVED_BY']" \
  "Jenkins Map approval result is not reduced to the authenticated user ID"
assert_contains infra/scripts/gilbom-record-deploy-approval.sh \
  'explode | all(. >= 32 and . != 127)' \
  "Unicode approver identity validation is missing"
assert_contains infra/scripts/gilbom-deploy.sh \
  'explode | all(. >= 32 and . != 127)' \
  "root deploy does not revalidate Unicode approver identity safely"
assert_contains scripts/run-production-deploy-remote.sh \
  'sudo --non-interactive /usr/local/sbin/gilbom-deploy $release_sha' \
  "remote Job does not invoke the fixed root deployment command"
assert_contains scripts/verify-external-https-smoke.py \
  'ssl.create_default_context()' \
  "external smoke does not enforce normal TLS verification"
assert_contains scripts/verify-external-https-smoke.py \
  'response.geturl() != origin + path' \
  "external smoke does not reject redirects"

if grep -Fq \
  'deploy ALL=(root) NOPASSWD: /usr/local/sbin/gilbom-record-deploy-approval' \
  infra/scripts/install-production-deploy-job-entrypoints.sh; then
  fail "deploy account must not record its own approval"
fi

sh -n \
  infra/scripts/gilbom-deploy-preflight.sh \
  infra/scripts/gilbom-record-deploy-approval.sh \
  infra/scripts/gilbom-release-manifest.sh \
  infra/scripts/install-production-deploy-job-entrypoints.sh \
  scripts/run-production-deploy-preflight-remote.sh \
  scripts/record-production-deploy-approval-remote.sh \
  scripts/run-production-deploy-remote.sh \
  scripts/fetch-production-release-manifest.sh \
  scripts/render-production-deploy-approval.sh \
  scripts/verify-production-release-evidence.sh \
  scripts/verify-production-deploy-job-host.sh \
  scripts/test-production-deploy-job.sh \
  scripts/test-production-deploy-preflight-integration.sh
python3 -m py_compile scripts/verify-external-https-smoke.py
sh scripts/test-production-deploy-job.sh

echo "Production deploy Job contract verification: PASS"
