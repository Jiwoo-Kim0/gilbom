#!/bin/sh

set -eu

fail() {
  echo "CD automation contract verification failed: $1" >&2
  exit 1
}

for file in \
  Jenkinsfile \
  Jenkinsfile.production-auto \
  infra/scripts/gilbom-deploy-preflight.sh \
  infra/scripts/gilbom-deploy.sh \
  scripts/sign-release-manifest.sh \
  scripts/generate-cicd-notification.py \
  docs/cd-automation.md
do
  [ -s "$file" ] || fail "required file is missing or empty: $file"
done

for removed_file in \
  Jenkinsfile.staging \
  scripts/verify-staging-host-identity-remote.sh \
  scripts/test-verify-staging-host-identity-remote.sh
do
  [ ! -e "$removed_file" ] \
    || fail "staging-only file must be removed: $removed_file"
done

assert_contains() {
  file="$1"
  text="$2"
  message="$3"
  grep -Fq "$text" "$file" || fail "$message"
}

assert_absent() {
  file="$1"
  text="$2"
  message="$3"
  if grep -Fq "$text" "$file"; then
    fail "$message"
  fi
}

if grep -Eq \
    'pollSCM|^[[:space:]]*triggers[[:space:]]*[{]' \
    Jenkinsfile.production-auto; then
  fail "production auto-CD Job must not define an independent trigger"
fi

if grep -Eq \
    '^[[:space:]]*(&&|\|\|)' \
    Jenkinsfile.production-auto; then
  fail "production auto-CD contains an invalid line-leading Groovy operator"
fi

assert_contains Jenkinsfile.production-auto \
  "name: 'FULL_COMMIT_SHA'" \
  "full SHA parameter is missing"
assert_contains Jenkinsfile.production-auto \
  'params.FULL_COMMIT_SHA.trim()' \
  "full SHA parameter is not explicitly resolved before shell execution"
assert_contains Jenkinsfile.production-auto \
  'env.FULL_COMMIT_SHA = requestedSha' \
  "validated full SHA is not exported to the deployment stages"
assert_contains Jenkinsfile.production-auto \
  'the first build only seeds the ' \
  "first-build parameter bootstrap failure is not explained"
assert_absent Jenkinsfile.production-auto \
  'printf '\''%s\\\\n'\'' "$FULL_COMMIT_SHA" \\' \
  "full SHA validation still relies on an unset shell parameter"
assert_contains Jenkinsfile.production-auto \
  'git merge-base --is-ancestor' \
  "master ancestry verification is missing"
assert_contains Jenkinsfile.production-auto \
  'scripts/run-production-deploy-preflight-remote.sh' \
  "root-owned deploy preflight is not reused"
assert_contains Jenkinsfile.production-auto \
  'scripts/run-production-deploy-remote.sh' \
  "fixed root deploy is not reused"
assert_contains Jenkinsfile.production-auto \
  'scripts/verify-external-https-smoke.py' \
  "external HTTPS smoke is missing"
assert_contains Jenkinsfile.production-auto \
  'scripts/verify-production-release-evidence.sh' \
  "release evidence verification is missing"
assert_contains Jenkinsfile.production-auto \
  "credentialsId: 'gilbom-mattermost-webhook'" \
  "Mattermost notification is missing"
assert_contains Jenkinsfile.production-auto \
  '.artifacts/cicd-notification/**' \
  "notification evidence is not archived"

assert_contains Jenkinsfile \
  "env.GILBOM_PRODUCTION_AUTO_CD_ENABLED == 'true'" \
  "CI does not require the production automatic CD gate"
assert_contains Jenkinsfile \
  "job: 'gilbom-production-auto-deploy'" \
  "CI does not trigger the exact production auto-CD Job"
assert_contains Jenkinsfile \
  'wait: false' \
  "CI does not queue production auto-CD asynchronously"
assert_contains Jenkinsfile \
  'propagate: false' \
  "CI downstream trigger may deadlock the single Jenkins executor"
assert_contains Jenkinsfile \
  'production-auto-deploy-trigger' \
  "CI does not record the production auto-CD trigger"

promotion_line="$(
  grep -nF "stage('Promote release artifact automatically')" Jenkinsfile \
    | cut -d: -f1
)"
console_scan_line="$(
  grep -nF "stage('Jenkins console Secret scan')" Jenkinsfile \
    | cut -d: -f1
)"
production_trigger_line="$(
  grep -nF "stage('Trigger automatic production deployment')" Jenkinsfile \
    | cut -d: -f1
)"
[ "$promotion_line" -lt "$console_scan_line" ] \
  && [ "$console_scan_line" -lt "$production_trigger_line" ] \
  || fail "CI promotion, console scan, and production trigger order is invalid"

for file in \
  Jenkinsfile \
  Jenkinsfile.production-auto \
  scripts/generate-cicd-notification.py \
  scripts/test-cicd-notification.py \
  docs/cd-automation.md
do
  assert_absent "$file" \
    'GILBOM_STAGING_' \
    "staging environment gate remains: $file"
  assert_absent "$file" \
    'gilbom-staging-' \
    "staging Job or Credential remains: $file"
done

assert_contains Jenkinsfile.production-auto \
  'currentBuild.getBuildCauses(' \
  "production auto-CD does not verify an upstream cause"
assert_contains Jenkinsfile.production-auto \
  'hudson.model.Cause$UpstreamCause' \
  "production auto-CD does not restrict the accepted cause type"
assert_contains Jenkinsfile.production-auto \
  'upstreamCauses.size() != 1' \
  "production auto-CD does not require exactly one upstream cause"
assert_contains Jenkinsfile.production-auto \
  'upstreamCauses[0].upstreamProject' \
  "production auto-CD does not resolve the trusted upstream project"
assert_contains Jenkinsfile.production-auto \
  '.toString()' \
  "production auto-CD does not normalize the upstream project value"
assert_contains Jenkinsfile.production-auto \
  "upstreamProject == null || upstreamProject.toString().trim() != 'gilbom-ci-baseline'" \
  "production auto-CD does not require the successful CI chain"
assert_absent Jenkinsfile.production-auto \
  'gilbom-staging-deploy' \
  "production auto-CD still requires a staging Job"
assert_contains Jenkinsfile.production-auto \
  '== "COMPATIBLE_NO_SCHEMA_CHANGE"' \
  "production auto-CD does not reject schema-changing releases"
assert_contains Jenkinsfile.production-auto \
  '(.flyway.pendingMigrations | length) == 0' \
  "production auto-CD does not require an empty migration plan"
assert_contains Jenkinsfile.production-auto \
  '.artifact.signature.status == "PASS"' \
  "production auto-CD does not require a valid Cosign signature"
assert_contains Jenkinsfile.production-auto \
  '.artifact.signature.manifestBundleSha256' \
  "production auto-CD does not require complete manifest signature evidence"
assert_contains Jenkinsfile.production-auto \
  '.compose.path == "compose.prod.yaml"' \
  "production auto-CD does not bind evidence to the signed Compose"
assert_contains Jenkinsfile.production-auto \
  '.vulnerabilities.critical == 0' \
  "production auto-CD does not require Critical 0"
assert_contains Jenkinsfile.production-auto \
  '.vulnerabilities.unapprovedHigh == 0' \
  "production auto-CD does not reject unapproved High findings"
assert_contains Jenkinsfile.production-auto \
  'jenkins-production-auto-cd' \
  "production automatic approval provenance is missing"

assert_contains infra/scripts/gilbom-deploy-preflight.sh \
  '"$COSIGN_BIN" verify-blob' \
  "preflight does not verify the Cosign Blob signature"
assert_contains infra/scripts/gilbom-deploy-preflight.sh \
  'SHA256SUMS.sigstore.json' \
  "preflight does not require the signed complete release manifest"
assert_contains infra/scripts/gilbom-deploy-preflight.sh \
  'signed release Compose configuration is invalid or runtime.env is incomplete' \
  "preflight does not validate the candidate Compose with host runtime.env"
assert_contains infra/scripts/gilbom-deploy.sh \
  'flock --nonblock 9' \
  "root deploy does not acquire a non-blocking deployment lock"
assert_contains infra/scripts/gilbom-deploy.sh \
  'compose up --detach --no-build' \
  "root deploy is not constrained to prebuilt images"
assert_contains infra/scripts/gilbom-deploy.sh \
  'status: "AUTOMATIC_ROLLBACK_SUCCEEDED"' \
  "migration-free automatic Rollback evidence is missing"
assert_contains infra/scripts/gilbom-deploy.sh \
  'status: "MANUAL_RECOVERY_REQUIRED"' \
  "migration-changing failure does not enter manual recovery"
assert_contains infra/scripts/gilbom-deploy.sh \
  'install_compose_file "$candidate_compose_path"' \
  "root deploy does not install the signed candidate Compose"
assert_contains infra/scripts/gilbom-deploy.sh \
  'previous-compose.prod.yaml' \
  "root deploy does not preserve Compose rollback state"
assert_contains Jenkinsfile \
  'sh scripts/sign-release-manifest.sh' \
  "CI does not sign the complete release manifest"

assert_contains docs/cd-automation.md \
  'GILBOM_PRODUCTION_AUTO_CD_ENABLED=true' \
  "documented initial automatic CD value is not true"
assert_contains docs/cd-automation.md \
  '신규 Flyway migration은 자동 운영 배포 대상이 아니다' \
  "manual migration boundary is not documented"

python3 -m py_compile \
  scripts/generate-cicd-notification.py \
  scripts/test-cicd-notification.py
python3 scripts/test-cicd-notification.py

echo "CD automation contract verification: PASS"
