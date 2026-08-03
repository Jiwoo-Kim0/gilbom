#!/bin/sh

set -eu

fail() {
  echo "Security tooling policy verification failed: $1" >&2
  exit 1
}

for file in \
  .gitleaks.toml \
  redocly.yaml \
  infra/security/tool-versions.env \
  infra/security/vulnerability-exceptions.yaml \
  infra/cosign/version.env \
  infra/cosign/offline-signing-config.json \
  scripts/security_policy.py \
  scripts/verify-source-security.sh \
  scripts/verify-repository-policy.sh \
  scripts/scan-image-security.sh \
  scripts/test-scan-image-security.sh \
  scripts/run-backend-ci-task.sh \
  scripts/run-frontend-ci-task.sh \
  scripts/test-run-frontend-ci-task.sh \
  scripts/run-mqtt-broker-e2e-ci.sh \
  scripts/auth-security-integration-test.py \
  scripts/generate-security-regression-baseline.py \
  scripts/test-security-regression-baseline.py \
  scripts/render-vulnerability-report.py \
  scripts/prepare-release-security-artifacts.sh \
  scripts/create-release-image-archive.sh \
  scripts/sign-release-image-archive.sh \
  scripts/verify-cosign-release-signature.sh \
  scripts/generate-build-metadata.py \
  scripts/generate-build-metadata.sh \
  scripts/verify-release-artifacts.sh \
  scripts/verify-ci-release-contract.sh \
  scripts/build-release-artifacts.sh \
  scripts/transfer-release-artifacts.sh \
  scripts/check-ci-release-artifact-remote.sh \
  scripts/verify-ci-artifact-remote-readiness.sh \
  scripts/upload-ci-release-artifacts.sh \
  scripts/promote-ci-release-artifacts-remote.sh \
  scripts/quarantine-ci-release-artifacts-remote.sh \
  scripts/test-ci-artifact-ssh-paths.sh \
  scripts/test-promote-ci-release-artifacts.sh \
  scripts/verify-ci-artifact-transfer-contract.sh \
  scripts/verify-production-deploy-contract.sh \
  scripts/verify-production-deploy-job-contract.sh \
  scripts/verify-production-rollback-contract.sh \
  scripts/verify-production-rollback-drill-contract.sh \
  scripts/test-production-deploy-boundary.sh \
  scripts/test-production-deploy-job.sh \
  scripts/test-production-rollback-job.sh \
  scripts/test-production-rollback-drill-evidence.sh \
  scripts/test-production-deploy-preflight-integration.sh \
  scripts/test-production-conditional-rollback-integration.sh \
  scripts/verify-production-deploy-account.sh \
  scripts/verify-production-deploy-job-host.sh \
  scripts/run-production-deploy-preflight-remote.sh \
  scripts/record-production-deploy-approval-remote.sh \
  scripts/run-production-deploy-remote.sh \
  scripts/fetch-production-release-manifest.sh \
  scripts/render-production-deploy-approval.sh \
  scripts/verify-production-release-evidence.sh \
  scripts/run-production-rollback-preflight-remote.sh \
  scripts/record-production-rollback-approval-remote.sh \
  scripts/fetch-production-rollback-manifest.sh \
  scripts/render-production-rollback-approval.sh \
  scripts/verify-production-rollback-evidence.sh \
  scripts/run-production-rollback-drill.sh \
  scripts/cleanup-production-rollback-drill.sh \
  scripts/verify-production-rollback-drill-evidence.sh \
  scripts/verify-external-https-smoke.py \
  infra/scripts/promote-release-artifacts.sh \
  infra/scripts/promote-ci-release-artifacts.sh \
  infra/scripts/install-cosign-release-verifier.sh \
  infra/scripts/gilbom-deploy.sh \
  infra/scripts/gilbom-approve-migrations.sh \
  infra/scripts/gilbom-deploy-ssh-command.sh \
  infra/scripts/install-production-deploy-entrypoint.sh \
  infra/scripts/gilbom-deploy-preflight.sh \
  infra/scripts/gilbom-record-deploy-approval.sh \
  infra/scripts/gilbom-release-manifest.sh \
  infra/scripts/gilbom-rollback-preflight.sh \
  infra/scripts/gilbom-record-rollback-approval.sh \
  infra/scripts/gilbom-rollback-manifest.sh \
  infra/scripts/install-production-deploy-job-entrypoints.sh \
  infra/scripts/prepare-production-deploy-account.sh \
  docs/security-tooling-policy.md \
  docs/cosign-blob-signing.md \
  docs/ci-artifact-promotion.md \
  docs/production-deploy-account.md \
  docs/production-root-deploy.md \
  docs/production-deploy-job.md \
  docs/production-conditional-rollback.md \
  docs/production-rollback-drill.md \
  Jenkinsfile.rollback-drill \
  compose.rollback-drill.yaml \
  test/rollback-drill/backend/Dockerfile \
  test/rollback-drill/backend/entrypoint.sh \
  test/rollback-drill/backend/healthcheck.sh \
  test/rollback-drill/backend/compatibility-probe.sh \
  test/rollback-drill/frontend/Dockerfile \
  test/rollback-drill/frontend/nginx.conf
do
  test -s "$file" || fail "required file is missing or empty: $file"
done

if grep -R -n -F 'docker scout' \
    Jenkinsfile scripts/verify-production-images.sh \
    docs/jenkins-pipeline.md docs/day7-production-image-verification.md; then
  fail "Docker Scout remains in the active security contract"
fi

grep -Fq "stage('Git history Secret scan')" Jenkinsfile \
  || fail "Jenkins Git history Secret stage is missing"
grep -Fq "stage('Checkout workspace Secret scan')" Jenkinsfile \
  || fail "Jenkins workspace Secret stage is missing"
grep -Fq 'IMAGE_SCANNER_MODE=required' Jenkinsfile \
  || fail "Jenkins image scanner is not fail-closed"
if grep -Eq "\\.env|secrets/|mosquitto/local" .gitleaks.toml; then
  fail "runtime Secret stores must not be allowlisted from workspace scanning"
fi
grep -Fq 'StrictHostKeyChecking=yes' scripts/transfer-release-artifacts.sh \
  || fail "SSH host-key verification is not mandatory"
grep -Fq 'sha256sum --check SHA256SUMS' \
  infra/scripts/promote-release-artifacts.sh \
  || fail "root promotion does not verify SHA-256"
grep -Fq 'chown -R root:root' infra/scripts/promote-release-artifacts.sh \
  || fail "root ownership promotion is missing"
grep -Fq 'StrictHostKeyChecking=yes' scripts/upload-ci-release-artifacts.sh \
  || fail "CI Artifact upload does not enforce SSH host-key verification"
grep -Fq 'sha256sum -c SHA256SUMS' \
  infra/scripts/promote-ci-release-artifacts.sh \
  || fail "CI root promotion does not verify SHA-256"
grep -Fq 'mv -T -- "$incoming" "$destination"' \
  infra/scripts/promote-ci-release-artifacts.sh \
  || fail "CI root promotion is not an atomic same-filesystem rename"
grep -Fq 'COSIGN_VERSION=3.1.2' infra/cosign/version.env \
  || fail "Cosign version is not pinned"
grep -Fq \
  'COSIGN_LINUX_AMD64_SHA256=f7622ed3cf22e55e1ae6377c080979ff77a22da9981c11df222a2e444991e7cf' \
  infra/cosign/version.env \
  || fail "Cosign linux-amd64 Digest is not pinned"
grep -Fq '"$COSIGN_BIN" verify-blob' \
  infra/scripts/gilbom-deploy.sh \
  || fail "root deployer does not verify the Cosign Blob signature"

sh -n \
  scripts/verify-source-security.sh \
  scripts/verify-repository-policy.sh \
  scripts/scan-image-security.sh \
  scripts/test-scan-image-security.sh \
  scripts/run-backend-ci-task.sh \
  scripts/run-frontend-ci-task.sh \
  scripts/test-run-frontend-ci-task.sh \
  scripts/run-mqtt-broker-e2e-ci.sh \
  scripts/record-ci-result.sh \
  scripts/prepare-release-security-artifacts.sh \
  scripts/create-release-image-archive.sh \
  scripts/sign-release-image-archive.sh \
  scripts/verify-cosign-release-signature.sh \
  scripts/generate-build-metadata.sh \
  scripts/verify-release-artifacts.sh \
  scripts/verify-ci-release-contract.sh \
  scripts/build-release-artifacts.sh \
  scripts/transfer-release-artifacts.sh \
  scripts/check-ci-release-artifact-remote.sh \
  scripts/verify-ci-artifact-remote-readiness.sh \
  scripts/upload-ci-release-artifacts.sh \
  scripts/promote-ci-release-artifacts-remote.sh \
  scripts/quarantine-ci-release-artifacts-remote.sh \
  scripts/test-ci-artifact-ssh-paths.sh \
  scripts/test-promote-ci-release-artifacts.sh \
  scripts/verify-ci-artifact-transfer-contract.sh \
  scripts/verify-production-deploy-contract.sh \
  scripts/verify-production-deploy-job-contract.sh \
  scripts/verify-production-rollback-contract.sh \
  scripts/verify-production-rollback-drill-contract.sh \
  scripts/test-production-deploy-boundary.sh \
  scripts/test-production-deploy-job.sh \
  scripts/test-production-rollback-job.sh \
  scripts/test-production-rollback-drill-evidence.sh \
  scripts/test-production-deploy-preflight-integration.sh \
  scripts/test-production-conditional-rollback-integration.sh \
  scripts/verify-production-deploy-account.sh \
  scripts/verify-production-deploy-job-host.sh \
  scripts/run-production-deploy-preflight-remote.sh \
  scripts/record-production-deploy-approval-remote.sh \
  scripts/run-production-deploy-remote.sh \
  scripts/fetch-production-release-manifest.sh \
  scripts/render-production-deploy-approval.sh \
  scripts/verify-production-release-evidence.sh \
  scripts/run-production-rollback-preflight-remote.sh \
  scripts/record-production-rollback-approval-remote.sh \
  scripts/fetch-production-rollback-manifest.sh \
  scripts/render-production-rollback-approval.sh \
  scripts/verify-production-rollback-evidence.sh \
  scripts/run-production-rollback-drill.sh \
  scripts/cleanup-production-rollback-drill.sh \
  scripts/verify-production-rollback-drill-evidence.sh \
  infra/scripts/promote-release-artifacts.sh \
  infra/scripts/promote-ci-release-artifacts.sh \
  infra/scripts/install-cosign-release-verifier.sh \
  infra/scripts/gilbom-deploy.sh \
  infra/scripts/gilbom-approve-migrations.sh \
  infra/scripts/gilbom-deploy-ssh-command.sh \
  infra/scripts/install-production-deploy-entrypoint.sh \
  infra/scripts/gilbom-deploy-preflight.sh \
  infra/scripts/gilbom-record-deploy-approval.sh \
  infra/scripts/gilbom-release-manifest.sh \
  infra/scripts/gilbom-rollback-preflight.sh \
  infra/scripts/gilbom-record-rollback-approval.sh \
  infra/scripts/gilbom-rollback-manifest.sh \
  infra/scripts/install-production-deploy-job-entrypoints.sh \
  infra/scripts/prepare-production-deploy-account.sh \
  test/rollback-drill/backend/entrypoint.sh \
  test/rollback-drill/backend/healthcheck.sh \
  test/rollback-drill/backend/compatibility-probe.sh
python3 scripts/test-security-policy.py
python3 scripts/test-security-regression-baseline.py
python3 -m py_compile \
  scripts/security_policy.py \
  scripts/render-vulnerability-report.py \
  scripts/generate-build-metadata.py \
  scripts/verify-external-https-smoke.py \
  scripts/auth-security-integration-test.py \
  scripts/generate-security-regression-baseline.py \
  scripts/test-security-regression-baseline.py

echo "Security tooling policy definition: PASS"
