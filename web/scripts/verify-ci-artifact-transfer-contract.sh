#!/bin/sh

set -eu

fail() {
  echo "CI artifact transfer contract verification failed: $1" >&2
  exit 1
}

assert_contains() {
  file="$1"
  text="$2"
  message="$3"
  grep -Fq -- "$text" "$file" || fail "$message"
}

line_of() {
  pattern="$1"
  grep -n -F "$pattern" Jenkinsfile | head -1 | cut -d: -f1
}

for file in \
  scripts/verify-ci-artifact-remote-readiness.sh \
  scripts/upload-ci-release-artifacts.sh \
  scripts/promote-ci-release-artifacts-remote.sh \
  scripts/test-ci-artifact-ssh-paths.sh \
  scripts/test-promote-ci-release-artifacts.sh \
  infra/scripts/promote-ci-release-artifacts.sh
do
  test -s "$file" || fail "required file is missing or empty: $file"
done

metadata_line="$(line_of "stage('Build metadata and release integrity')")"
upload_line="$(line_of "stage('Upload release artifacts to incoming')")"
approval_line="$(line_of "stage('Approve release artifact promotion')")"
promotion_line="$(line_of "stage('Promote release artifact atomically')")"
test -n "$metadata_line" && test -n "$upload_line" \
  && test -n "$approval_line" && test -n "$promotion_line" \
  || fail "one or more Stage 9 Jenkins stages are missing"
test "$metadata_line" -lt "$upload_line" \
  && test "$upload_line" -lt "$approval_line" \
  && test "$approval_line" -lt "$promotion_line" \
  || fail "Stage 9 upload, approval, and promotion order is invalid"

assert_contains Jenkinsfile \
  "credentialsId: 'gilbom-artifact-upload-ssh'" \
  "dedicated artifact upload SSH Credential is missing"
assert_contains Jenkinsfile \
  "credentialsId: 'gilbom-artifact-known-hosts'" \
  "verified known_hosts Credential is missing"
assert_contains Jenkinsfile \
  "stage('Artifact transfer readiness')" \
  "artifact destination readiness is not checked before expensive tests"
assert_contains Jenkinsfile \
  'input message:' \
  "manual promotion approval is missing"
assert_contains scripts/upload-ci-release-artifacts.sh \
  'REMOTE_ARTIFACT_ROOT="${REMOTE_ARTIFACT_ROOT:-/opt/gilbom/artifacts}"' \
  "upload does not target the required artifact root"
assert_contains scripts/upload-ci-release-artifacts.sh \
  'REMOTE_INCOMING_ROOT="$REMOTE_ARTIFACT_ROOT/.incoming"' \
  "upload does not target the required incoming boundary"
assert_contains scripts/upload-ci-release-artifacts.sh \
  'StrictHostKeyChecking=yes' \
  "upload does not require SSH host-key verification"
assert_contains scripts/upload-ci-release-artifacts.sh \
  'scp $scp_options' \
  "upload does not use SCP/SFTP"
assert_contains scripts/promote-ci-release-artifacts-remote.sh \
  'sudo --non-interactive' \
  "remote promotion does not invoke the root boundary"
assert_contains infra/scripts/promote-ci-release-artifacts.sh \
  'destination="$ARTIFACT_ROOT/$release_sha"' \
  "final full-SHA destination is not contracted"
assert_contains infra/scripts/promote-ci-release-artifacts.sh \
  'cmp -s "$incoming/SHA256SUMS" "$destination/SHA256SUMS"' \
  "same-SHA reuse does not compare complete checksums"
assert_contains infra/scripts/promote-ci-release-artifacts.sh \
  'chown -R root:root "$incoming"' \
  "root ownership is not applied before promotion"
assert_contains infra/scripts/promote-ci-release-artifacts.sh \
  'find "$incoming" -type f -exec chmod 0440 {} +' \
  "final artifact files are not made read-only"
assert_contains infra/scripts/promote-ci-release-artifacts.sh \
  'mv -T -- "$incoming" "$destination"' \
  "promotion does not use a same-filesystem atomic rename"
assert_contains infra/scripts/prepare-day8-host.sh \
  '/opt/gilbom/artifacts/.incoming' \
  "EC2 preparation does not create the incoming boundary"

if grep -Fq 'StrictHostKeyChecking=no' \
  scripts/upload-ci-release-artifacts.sh \
  scripts/promote-ci-release-artifacts-remote.sh; then
  fail "SSH host-key verification must not be disabled"
fi

sh -n \
  scripts/verify-ci-artifact-remote-readiness.sh \
  scripts/upload-ci-release-artifacts.sh \
  scripts/promote-ci-release-artifacts-remote.sh \
  scripts/test-ci-artifact-ssh-paths.sh \
  scripts/test-promote-ci-release-artifacts.sh \
  infra/scripts/promote-ci-release-artifacts.sh

sh scripts/test-ci-artifact-ssh-paths.sh

echo "CI artifact transfer and atomic promotion contract: PASS"
