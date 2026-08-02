#!/bin/sh

set -eu

case "$(basename -- "$0")" in
  ssh)
    printf 'ssh %s\n' "$*" >> "$SSH_CALL_LOG"
    case " $* " in
      *" test -d '/opt/gilbom/artifacts/.incoming/${RELEASE_SHA:-}'"*) exit 1 ;;
    esac
    exit 0
    ;;
  scp)
    printf 'scp %s\n' "$*" >> "$SSH_CALL_LOG"
    exit 0
    ;;
  jq)
    exit 0
    ;;
esac

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/gilbom-ci-ssh-paths.XXXXXX")"
trap 'rm -rf -- "$TEST_ROOT"' EXIT HUP INT TERM

RELEASE_SHA=0123456789abcdef0123456789abcdef01234567
RELEASE_DIR="$TEST_ROOT/release"
FAKE_BIN="$TEST_ROOT/bin"
CREDENTIAL_DIR="$TEST_ROOT/workspace/gilbom-ci-baseline@tmp/secretFiles/fixture-id"
KNOWN_HOSTS_FILE="$CREDENTIAL_DIR/known_hosts"
SSH_IDENTITY_FILE="$CREDENTIAL_DIR/id_ed25519"
SSH_CALL_LOG="$TEST_ROOT/ssh-calls.txt"
export SSH_CALL_LOG

mkdir -p \
  "$RELEASE_DIR/sbom" \
  "$RELEASE_DIR/vulnerabilities" \
  "$FAKE_BIN" \
  "$CREDENTIAL_DIR"
printf 'fixture-host-key\n' > "$KNOWN_HOSTS_FILE"
printf 'fixture-private-key\n' > "$SSH_IDENTITY_FILE"
chmod 0600 "$KNOWN_HOSTS_FILE" "$SSH_IDENTITY_FILE"

printf 'backend-sbom\n' > "$RELEASE_DIR/sbom/backend.sbom.json"
printf 'frontend-sbom\n' > "$RELEASE_DIR/sbom/frontend.sbom.json"
printf 'backend-vulnerabilities\n' \
  > "$RELEASE_DIR/vulnerabilities/backend.txt"
printf 'frontend-vulnerabilities\n' \
  > "$RELEASE_DIR/vulnerabilities/frontend.txt"
printf '{"status":"PASS"}\n' \
  > "$RELEASE_DIR/vulnerabilities/policy-summary.json"
printf 'images\n' > "$RELEASE_DIR/images.tar.zst"
printf '{"releaseSha":"%s"}\n' "$RELEASE_SHA" \
  > "$RELEASE_DIR/build-metadata.json"
(
  cd "$RELEASE_DIR"
  sha256sum images.tar.zst > images.tar.zst.sha256
  find . -type f ! -name SHA256SUMS -print0 \
    | LC_ALL=C sort -z \
    | xargs -0 sha256sum > SHA256SUMS
)

cp "$SCRIPT_DIR/test-ci-artifact-ssh-paths.sh" "$FAKE_BIN/ssh"
cp "$SCRIPT_DIR/test-ci-artifact-ssh-paths.sh" "$FAKE_BIN/scp"
cp "$SCRIPT_DIR/test-ci-artifact-ssh-paths.sh" "$FAKE_BIN/jq"
chmod 0700 "$FAKE_BIN/ssh" "$FAKE_BIN/scp" "$FAKE_BIN/jq"

PATH="$FAKE_BIN:$PATH" \
RELEASE_SHA="$RELEASE_SHA" \
KNOWN_HOSTS_FILE="$KNOWN_HOSTS_FILE" \
SSH_IDENTITY_FILE="$SSH_IDENTITY_FILE" \
  sh "$PROJECT_DIR/scripts/upload-ci-release-artifacts.sh" \
    "$RELEASE_DIR" \
    ubuntu@host.docker.internal

PATH="$FAKE_BIN:$PATH" \
KNOWN_HOSTS_FILE="$KNOWN_HOSTS_FILE" \
SSH_IDENTITY_FILE="$SSH_IDENTITY_FILE" \
  sh "$PROJECT_DIR/scripts/verify-ci-artifact-remote-readiness.sh" \
    ubuntu@host.docker.internal

PATH="$FAKE_BIN:$PATH" \
KNOWN_HOSTS_FILE="$KNOWN_HOSTS_FILE" \
SSH_IDENTITY_FILE="$SSH_IDENTITY_FILE" \
  sh "$PROJECT_DIR/scripts/promote-ci-release-artifacts-remote.sh" \
    "$RELEASE_SHA" \
    ubuntu@host.docker.internal

grep -Fq "UserKnownHostsFile=$KNOWN_HOSTS_FILE" "$SSH_CALL_LOG"
grep -Fq -- "-i $SSH_IDENTITY_FILE" "$SSH_CALL_LOG"
grep -Fq "sudo --non-interactive" "$SSH_CALL_LOG"
[ "$(grep -c '^scp ' "$SSH_CALL_LOG")" -eq 3 ]

echo "Jenkins @tmp SSH Credential path regression test: PASS"
