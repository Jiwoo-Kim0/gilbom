#!/bin/sh

set -eu

fail() {
  echo "Repository policy verification failed: $1" >&2
  exit 1
}

# 실제 Secret 파일·개인키·keystore는 추적되면 즉시 차단한다.
if git ls-files --full-name \
    | grep -E '(^|/)\.env$|(^|/)secrets/|\.(pem|key|p12|pfx|jks|keystore)$|(^|/)passwd$|(^|/)credentials\.json$|(^|/)service-account[^/]*\.json$' \
    | grep -Ev '(^|/)secrets/\.gitignore$'; then
  fail "tracked Secret file candidate detected"
fi

if git grep -IlE -e \
    'AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|glpat-[A-Za-z0-9_-]{20,}|github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9_-]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}' \
    -- . ':!frontend/gilbom/package-lock.json'; then
  fail "tracked token candidate detected"
fi

if git grep -IlE -e \
    '-----BEGIN( [A-Z0-9]+)? PRIVATE KEY-----' \
    -- . ':!backend/src/main/java/com/gilbom/robot/mqtt/strategy/PemSslContextSupport.java'; then
  fail "tracked private key candidate detected"
fi

sh scripts/verify-certbot-automation.sh
sh scripts/verify-day8-infrastructure.sh
sh scripts/verify-security-tooling-policy.sh
sh scripts/verify-ci-checkout-contract.sh
sh scripts/verify-ci-release-contract.sh
sh scripts/verify-ci-artifact-transfer-contract.sh
sh scripts/verify-production-deploy-contract.sh
sh scripts/verify-production-deploy-job-contract.sh
sh scripts/verify-production-rollback-contract.sh
sh scripts/test-run-frontend-ci-task.sh
sh scripts/test-scan-image-security.sh
sh scripts/verify-frontend-csp-source.sh
sh -n infra/scripts/*.sh
test -s docs/production-deployment-runbook.md
test -s docs/day7-completion-freeze.md
sh scripts/verify-statistics-contract.sh

echo "Repository policy verification: PASS"
