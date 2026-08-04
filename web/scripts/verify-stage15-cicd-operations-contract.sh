#!/bin/sh

set -eu

fail() {
  echo "Stage 15 CI/CD operations contract failed: $1" >&2
  exit 1
}

for file in \
  Jenkinsfile \
  Jenkinsfile.deploy \
  Jenkinsfile.production-auto \
  Jenkinsfile.rollback \
  Jenkinsfile.rollback-drill \
  scripts/generate-cicd-notification.py \
  scripts/send-mattermost-notification.py \
  scripts/verify-jenkins-console-secret.sh \
  scripts/collect-stage15-host-evidence.sh \
  scripts/fetch-stage15-host-evidence.sh \
  scripts/generate-stage15-final-evidence.py \
  infra/scripts/install-stage15-operations-verifier.sh \
  docs/stage15-cicd-operations.md
do
  [ -s "$file" ] || fail "required file is missing or empty: $file"
done

assert_contains() {
  file="$1"
  text="$2"
  message="$3"
  grep -Fq "$text" "$file" || fail "$message"
}

[ "$(grep -Fc "pollSCM('H/5 * * * *')" Jenkinsfile)" -eq 1 ] \
  || fail "CI must define the exact SCM polling schedule once"
for pipeline in \
  Jenkinsfile.deploy \
  Jenkinsfile.production-auto \
  Jenkinsfile.rollback \
  Jenkinsfile.rollback-drill
do
  if grep -Eq 'pollSCM|^[[:space:]]*triggers[[:space:]]*[{]' "$pipeline"; then
    fail "downstream deployment/rollback Job must not define a trigger: $pipeline"
  fi
done

for pipeline in \
  Jenkinsfile \
  Jenkinsfile.deploy \
  Jenkinsfile.production-auto \
  Jenkinsfile.rollback \
  Jenkinsfile.rollback-drill
do
  assert_contains "$pipeline" \
    "credentialsId: 'gilbom-mattermost-webhook'" \
    "Mattermost Secret Text Credential is missing: $pipeline"
  assert_contains "$pipeline" \
    'scripts/send-mattermost-notification.py' \
    "Mattermost delivery is missing: $pipeline"
  assert_contains "$pipeline" \
    '.artifacts/cicd-notification/**' \
    "CI/CD diagnosis artifact is not archived: $pipeline"
done

assert_contains scripts/send-mattermost-notification.py \
  'ssl.create_default_context()' \
  "Mattermost delivery does not use normal TLS verification"
assert_contains scripts/send-mattermost-notification.py \
  'r"https://' \
  "Mattermost Webhook does not require HTTPS"
assert_contains scripts/generate-cicd-notification.py \
  '"containsConsoleExcerpt": False' \
  "Mattermost summary may copy unscanned console content"
assert_contains scripts/generate-cicd-notification.py \
  '"console": console_url' \
  "authenticated Jenkins console link is missing"
assert_contains scripts/generate-cicd-notification.py \
  '("flyway-v1-v41", "Flyway V1-V41"' \
  "Mattermost CI diagnosis does not match the current Flyway V1-V41 gate"
if grep -Fq 'flyway-v1-v35' scripts/generate-cicd-notification.py; then
  fail "Mattermost CI diagnosis still contains the obsolete Flyway V1-V35 gate"
fi
assert_contains scripts/verify-jenkins-console-secret.sh \
  'gitleaks dir' \
  "Jenkins console is not scanned by Gitleaks"
assert_contains scripts/verify-jenkins-console-secret.sh \
  'exact ephemeral CI Secret material' \
  "Jenkins console is not compared with exact ephemeral Secrets"

for result_id in \
  checkout-full \
  git-history-secret \
  workspace-secret \
  openapi-lint \
  backend-dependency-scan \
  frontend-dependency-scan \
  artifact-checksum \
  jenkins-log-secret
do
  assert_contains Jenkinsfile \
    "record-ci-result.sh $result_id" \
    "required Stage 15 CI evidence is not recorded: $result_id"
done

assert_contains compose.jenkins.yaml \
  'DOCKER_HOST: tcp://docker-builder:2376' \
  "Jenkins DinD TLS endpoint changed"
assert_contains compose.jenkins.yaml \
  'DOCKER_TLS_VERIFY: "1"' \
  "Jenkins DinD TLS verification changed"
if grep -Fq '/var/run/docker.sock' compose.jenkins.yaml; then
  fail "Jenkins Compose contains a Docker Socket mount"
fi

assert_contains scripts/collect-stage15-host-evidence.sh \
  'deployDockerGroupMember: false' \
  "host evidence does not prove deploy Docker-group exclusion"
assert_contains scripts/collect-stage15-host-evidence.sh \
  'composeNoBuildContract: "PASS"' \
  "host evidence does not prove the production --no-build contract"
assert_contains scripts/generate-stage15-final-evidence.py \
  '"AUTOMATIC_ROLLBACK_SUCCEEDED"' \
  "final evidence does not require migration-free automatic Rollback"
assert_contains scripts/generate-stage15-final-evidence.py \
  '"MANUAL_RECOVERY_REQUIRED"' \
  "final evidence does not require incompatible-migration containment"

sh -n \
  scripts/verify-jenkins-console-secret.sh \
  scripts/collect-stage15-host-evidence.sh \
  scripts/fetch-stage15-host-evidence.sh \
  infra/scripts/install-stage15-operations-verifier.sh
python3 -m py_compile \
  scripts/generate-cicd-notification.py \
  scripts/send-mattermost-notification.py \
  scripts/generate-stage15-final-evidence.py
python3 scripts/test-cicd-notification.py
python3 scripts/test-stage15-final-evidence.py
sh scripts/test-verify-jenkins-console-secret.sh

echo "Stage 15 CI/CD operations contract: PASS"
