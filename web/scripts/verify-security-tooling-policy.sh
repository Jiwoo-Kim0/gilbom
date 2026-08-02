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
  scripts/security_policy.py \
  scripts/verify-source-security.sh \
  scripts/verify-repository-policy.sh \
  scripts/scan-image-security.sh \
  scripts/run-frontend-ci-task.sh \
  scripts/test-run-frontend-ci-task.sh \
  scripts/render-vulnerability-report.py \
  scripts/prepare-release-security-artifacts.sh \
  scripts/create-release-image-archive.sh \
  scripts/generate-build-metadata.py \
  scripts/generate-build-metadata.sh \
  scripts/verify-release-artifacts.sh \
  scripts/verify-ci-release-contract.sh \
  scripts/build-release-artifacts.sh \
  scripts/transfer-release-artifacts.sh \
  scripts/upload-ci-release-artifacts.sh \
  scripts/promote-ci-release-artifacts-remote.sh \
  scripts/test-promote-ci-release-artifacts.sh \
  scripts/verify-ci-artifact-transfer-contract.sh \
  infra/scripts/promote-release-artifacts.sh \
  infra/scripts/promote-ci-release-artifacts.sh \
  docs/security-tooling-policy.md \
  docs/ci-artifact-promotion.md
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

sh -n \
  scripts/verify-source-security.sh \
  scripts/verify-repository-policy.sh \
  scripts/scan-image-security.sh \
  scripts/run-backend-ci-task.sh \
  scripts/run-frontend-ci-task.sh \
  scripts/test-run-frontend-ci-task.sh \
  scripts/record-ci-result.sh \
  scripts/prepare-release-security-artifacts.sh \
  scripts/create-release-image-archive.sh \
  scripts/generate-build-metadata.sh \
  scripts/verify-release-artifacts.sh \
  scripts/verify-ci-release-contract.sh \
  scripts/build-release-artifacts.sh \
  scripts/transfer-release-artifacts.sh \
  scripts/upload-ci-release-artifacts.sh \
  scripts/promote-ci-release-artifacts-remote.sh \
  scripts/test-promote-ci-release-artifacts.sh \
  scripts/verify-ci-artifact-transfer-contract.sh \
  infra/scripts/promote-release-artifacts.sh \
  infra/scripts/promote-ci-release-artifacts.sh
python3 scripts/test-security-policy.py
python3 -m py_compile \
  scripts/security_policy.py \
  scripts/render-vulnerability-report.py \
  scripts/generate-build-metadata.py

echo "Security tooling policy definition: PASS"
