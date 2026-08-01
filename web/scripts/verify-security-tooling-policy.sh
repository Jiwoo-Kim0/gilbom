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
  scripts/scan-image-security.sh \
  scripts/build-release-artifacts.sh \
  scripts/transfer-release-artifacts.sh \
  infra/scripts/promote-release-artifacts.sh \
  docs/security-tooling-policy.md
do
  test -s "$file" || fail "required file is missing or empty: $file"
done

for file in \
  scripts/security_policy.py \
  scripts/verify-source-security.sh \
  scripts/scan-image-security.sh \
  scripts/build-release-artifacts.sh \
  scripts/transfer-release-artifacts.sh \
  infra/scripts/promote-release-artifacts.sh
do
  test -x "$file" || fail "required command is not executable: $file"
done

if grep -R -n -F 'docker scout' \
    Jenkinsfile scripts/verify-production-images.sh \
    docs/jenkins-pipeline.md docs/day7-production-image-verification.md; then
  fail "Docker Scout remains in the active security contract"
fi

grep -Fq "stage('Source security policy')" Jenkinsfile \
  || fail "Jenkins source security stage is missing"
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

sh -n \
  scripts/verify-source-security.sh \
  scripts/scan-image-security.sh \
  scripts/build-release-artifacts.sh \
  scripts/transfer-release-artifacts.sh \
  infra/scripts/promote-release-artifacts.sh
python3 scripts/test-security-policy.py

echo "Security tooling policy definition: PASS"
