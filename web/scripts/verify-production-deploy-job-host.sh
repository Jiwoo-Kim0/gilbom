#!/bin/sh

set -eu
set +x

fail() {
  echo "Production deploy Job host verification failed: $1" >&2
  exit 1
}

[ "$(id -u)" -eq 0 ] \
  || fail "run this verification as root"
[ "$#" -eq 0 ] \
  || fail "this verification accepts no arguments"

for file in \
  /usr/local/sbin/gilbom-deploy-preflight \
  /usr/local/sbin/gilbom-record-deploy-approval \
  /usr/local/sbin/gilbom-release-manifest \
  /usr/local/sbin/gilbom-deploy \
  /usr/local/sbin/gilbom-verify-production-deploy-job-host
do
  [ -f "$file" ] && [ ! -L "$file" ] \
    || fail "required root command is missing or symlinked: $file"
  [ "$(stat -c '%U:%G:%a' "$file")" = root:root:755 ] \
    || fail "root command ownership or mode is invalid: $file"
done
[ "$(stat -c '%U:%G:%a' /opt/gilbom/deploy-approvals)" = root:root:700 ] \
  || fail "deployment approval directory ownership or mode is invalid"
[ "$(stat -c '%U:%G:%a' /etc/sudoers.d/gilbom-production-deploy-job)" \
    = root:root:440 ] \
  || fail "deploy Job sudoers ownership or mode is invalid"
grep -Fq \
  '/usr/local/sbin/gilbom-deploy-preflight, /usr/local/sbin/gilbom-record-deploy-approval, /usr/local/sbin/gilbom-release-manifest' \
  /etc/sudoers.d/gilbom-production-deploy-job \
  || fail "deploy Job sudo command allowlist is invalid"
grep -Fxq \
  'deploy ALL=(root) NOPASSWD: /usr/local/sbin/gilbom-deploy' \
  /etc/sudoers.d/gilbom-production-deploy \
  || fail "deploy account sudo boundary changed unexpectedly"
visudo -cf /etc/sudoers >/dev/null \
  || fail "sudoers validation failed"

invalid_sha_output="$(
  runuser -u ubuntu -- \
    sudo --non-interactive \
      /usr/local/sbin/gilbom-deploy-preflight invalid 2>&1 \
    || true
)"
printf '%s\n' "$invalid_sha_output" \
  | grep -Fq 'release SHA must contain 40 lowercase hexadecimal characters' \
  || fail "preflight did not reject an invalid SHA"

extra_argument_output="$(
  runuser -u ubuntu -- \
    sudo --non-interactive \
      /usr/local/sbin/gilbom-release-manifest \
      0123456789abcdef0123456789abcdef01234567 extra 2>&1 \
    || true
)"
printf '%s\n' "$extra_argument_output" | grep -Fq 'usage:' \
  || fail "manifest retrieval did not reject an additional argument"

if runuser -u deploy -- \
    sudo --non-interactive \
      /usr/local/sbin/gilbom-record-deploy-approval \
      0123456789abcdef0123456789abcdef01234567 \
      0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef \
      >/dev/null 2>&1; then
  fail "deploy account can record its own deployment approval"
fi

echo "Production deploy Job host verification: PASS"
