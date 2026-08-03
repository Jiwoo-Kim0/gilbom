#!/bin/sh

set -eu

fail() {
  echo "Production read-only verification contract failed: $1" >&2
  exit 1
}

for file in \
  scripts/collect-production-readonly-evidence.py \
  scripts/test-production-readonly-evidence.py \
  infra/scripts/install-production-readonly-verifier.sh \
  docs/day10-production-readonly-verification.md
do
  [ -s "$file" ] || fail "required file is missing or empty: $file"
done

assert_contains() {
  grep -Fq -- "$2" "$1" || fail "$3"
}

assert_not_contains() {
  if grep -Fq -- "$2" "$1"; then
    fail "$3"
  fi
}

collector=scripts/collect-production-readonly-evidence.py
for required in \
  'EXPECTED_PUBLIC_PORTS = {80, 443}' \
  'FORBIDDEN_EXTERNAL_PORTS' \
  'chainVerification": "PASS"' \
  '"anonymousSessionCreated": False' \
  '"deployDockerGroupMember": False' \
  '"jenkinsDockerSocketMounted": False' \
  '"jenkinsDindTls": True' \
  '"backupRestored": False' \
  '"ec2Rebooted": False' \
  '"certificateReplaced": False' \
  '"s3": {"status": "N/A"'
do
  assert_contains "$collector" "$required" \
    "collector is missing required read-only evidence: $required"
done

for prohibited in \
  'docker restart' \
  'docker stop' \
  'docker compose up' \
  'docker compose down' \
  'aws ec2 authorize-security-group-ingress' \
  'aws ec2 revoke-security-group-ingress' \
  'certbot renew' \
  'shutdown -r' \
  'reboot'
do
  assert_not_contains "$collector" "$prohibited" \
    "collector contains a prohibited mutating command: $prohibited"
done

python3 -m py_compile "$collector"
python3 scripts/test-production-readonly-evidence.py
sh -n infra/scripts/install-production-readonly-verifier.sh

echo "Production read-only verification contract: PASS"
