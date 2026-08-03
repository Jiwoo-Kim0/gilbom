#!/bin/sh

set -eu
set +x

fail() {
  echo "Production deploy Job host verification failed: $1" >&2
  exit 1
}

COSIGN_BIN=/usr/local/bin/cosign
COSIGN_PUBLIC_KEY=/etc/gilbom/cosign-release.pub

[ "$(id -u)" -eq 0 ] \
  || fail "run this verification as root"
[ "$#" -eq 0 ] \
  || fail "this verification accepts no arguments"

case "$(uname -m)" in
  x86_64)
    expected_cosign_sha256=f7622ed3cf22e55e1ae6377c080979ff77a22da9981c11df222a2e444991e7cf
    ;;
  aarch64|arm64)
    expected_cosign_sha256=90e7ae0b5dfd60f20816b52c012addf7fc055ebcc7bea4ce81c428ca8518c302
    ;;
  *) fail "unsupported host architecture for pinned Cosign verifier" ;;
esac

for file in \
  /usr/local/sbin/gilbom-deploy-preflight \
  /usr/local/sbin/gilbom-record-deploy-approval \
  /usr/local/sbin/gilbom-release-manifest \
  /usr/local/sbin/gilbom-rollback-preflight \
  /usr/local/sbin/gilbom-record-rollback-approval \
  /usr/local/sbin/gilbom-rollback-manifest \
  /usr/local/sbin/gilbom-deploy \
  /usr/local/sbin/gilbom-verify-production-deploy-job-host
do
  [ -f "$file" ] && [ ! -L "$file" ] \
    || fail "required root command is missing or symlinked: $file"
  [ "$(stat -c '%U:%G:%a' "$file")" = root:root:755 ] \
    || fail "root command ownership or mode is invalid: $file"
done
[ -f "$COSIGN_BIN" ] && [ ! -L "$COSIGN_BIN" ] \
  || fail "pinned Cosign verifier is missing or symlinked"
[ "$(stat -c '%U:%G:%a' "$COSIGN_BIN")" = root:root:755 ] \
  || fail "Cosign verifier ownership or mode is invalid"
[ "$(sha256sum "$COSIGN_BIN" | awk '{print $1}')" \
    = "$expected_cosign_sha256" ] \
  || fail "Cosign verifier Digest differs from the pinned release"
"$COSIGN_BIN" version \
  | grep -Fq 'GitVersion:    v3.1.2' \
  || fail "Cosign verifier version differs from v3.1.2"
[ -f "$COSIGN_PUBLIC_KEY" ] \
  && [ ! -L "$COSIGN_PUBLIC_KEY" ] \
  && [ -s "$COSIGN_PUBLIC_KEY" ] \
  || fail "release verification public key is missing, empty, or symlinked"
[ "$(stat -c '%U:%G:%a' "$COSIGN_PUBLIC_KEY")" = root:root:444 ] \
  || fail "release verification public key ownership or mode is invalid"
[ "$(stat -c '%U:%G:%a' /opt/gilbom/deploy-approvals)" = root:root:700 ] \
  || fail "deployment approval directory ownership or mode is invalid"
[ "$(stat -c '%U:%G:%a' /opt/gilbom/rollback-approvals)" = root:root:700 ] \
  || fail "rollback approval directory ownership or mode is invalid"
[ "$(stat -c '%U:%G:%a' /etc/sudoers.d/gilbom-production-deploy-job)" \
    = root:root:440 ] \
  || fail "deploy Job sudoers ownership or mode is invalid"
grep -Fq \
  '/usr/local/sbin/gilbom-deploy-preflight, /usr/local/sbin/gilbom-record-deploy-approval, /usr/local/sbin/gilbom-release-manifest, /usr/local/sbin/gilbom-rollback-preflight, /usr/local/sbin/gilbom-record-rollback-approval, /usr/local/sbin/gilbom-rollback-manifest' \
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

if runuser -u deploy -- \
    sudo --non-interactive \
      /usr/local/sbin/gilbom-record-rollback-approval \
      0123456789abcdef0123456789abcdef01234567 \
      0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef \
      >/dev/null 2>&1; then
  fail "deploy account can record its own rollback approval"
fi

echo "Production deploy Job host verification: PASS"
