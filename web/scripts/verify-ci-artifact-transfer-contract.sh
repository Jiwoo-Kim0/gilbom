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
  scripts/check-ci-release-artifact-remote.sh \
  scripts/verify-ci-artifact-remote-readiness.sh \
  scripts/upload-ci-release-artifacts.sh \
  scripts/promote-ci-release-artifacts-remote.sh \
  scripts/quarantine-ci-release-artifacts-remote.sh \
  scripts/test-ci-artifact-ssh-paths.sh \
  scripts/test-promote-ci-release-artifacts.sh \
  infra/scripts/promote-ci-release-artifacts.sh
do
  test -s "$file" || fail "required file is missing or empty: $file"
done

metadata_line="$(line_of "stage('Build metadata and release integrity')")"
upload_line="$(line_of "stage('Upload release artifacts to incoming')")"
promotion_line="$(line_of "stage('Promote release artifact automatically')")"
test -n "$metadata_line" && test -n "$upload_line" \
  && test -n "$promotion_line" \
  || fail "one or more automatic Stage 9 Jenkins stages are missing"
test "$metadata_line" -lt "$upload_line" \
  && test "$upload_line" -lt "$promotion_line" \
  || fail "Stage 9 upload and automatic promotion order is invalid"

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
  'sh scripts/check-ci-release-artifact-remote.sh' \
  "same-SHA CI retry does not reuse the trusted immutable Artifact"
assert_contains Jenkinsfile \
  'ci-release-already-promoted' \
  "same-SHA Artifact reuse is not propagated to automatic promotion"
if grep -Fq "stage('Approve release artifact promotion')" Jenkinsfile \
  || grep -Fq 'Promote release ${env.RELEASE_SHA}' Jenkinsfile; then
  fail "CI Artifact promotion must not wait for manual input"
fi
assert_contains Jenkinsfile \
  'scripts/quarantine-ci-release-artifacts-remote.sh' \
  "unsuccessful CI does not quarantine its incoming Artifact"
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
assert_contains infra/scripts/promote-ci-release-artifacts.sh \
  'images.tar.zst.sigstore.json' \
  "Cosign Bundle is not a required promoted Artifact"
assert_contains infra/scripts/promote-ci-release-artifacts.sh \
  'compose.prod.yaml' \
  "production Compose is not a required promoted Artifact"
assert_contains infra/scripts/promote-ci-release-artifacts.sh \
  'SHA256SUMS.sigstore.json' \
  "complete release manifest Cosign Bundle is not required"
assert_contains infra/scripts/promote-ci-release-artifacts.sh \
  '--bundle "$directory/SHA256SUMS.sigstore.json"' \
  "root promotion does not verify the complete signed release manifest"
assert_contains infra/scripts/promote-ci-release-artifacts.sh \
  '"$COSIGN_BIN" verify-blob' \
  "root promotion does not verify the release Blob signature"
assert_contains infra/scripts/prepare-day8-host.sh \
  '/opt/gilbom/artifacts/.incoming' \
  "EC2 preparation does not create the incoming boundary"
assert_contains scripts/verify-ci-artifact-remote-readiness.sh \
  'cosign_public_key=/etc/gilbom/cosign-release.pub' \
  "remote readiness does not require the installed release public key"

if grep -Fq 'StrictHostKeyChecking=no' \
  scripts/upload-ci-release-artifacts.sh \
  scripts/promote-ci-release-artifacts-remote.sh; then
  fail "SSH host-key verification must not be disabled"
fi

sh -n \
  scripts/check-ci-release-artifact-remote.sh \
  scripts/verify-ci-artifact-remote-readiness.sh \
  scripts/upload-ci-release-artifacts.sh \
  scripts/promote-ci-release-artifacts-remote.sh \
  scripts/quarantine-ci-release-artifacts-remote.sh \
  scripts/test-ci-artifact-ssh-paths.sh \
  scripts/test-promote-ci-release-artifacts.sh \
  infra/scripts/promote-ci-release-artifacts.sh

sh scripts/test-ci-artifact-ssh-paths.sh

echo "CI artifact transfer and atomic promotion contract: PASS"
