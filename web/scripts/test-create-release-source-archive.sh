#!/bin/sh

set -eu

case "$(basename -- "$0")" in
  zstd)
    if [ "${2:-}" = --test ]; then
      [ -s "${3:-}" ]
      exit
    fi
    [ "${4:-}" = -o ] && [ "$#" -eq 6 ] \
      || exit 1
    cp -- "$6" "$5"
    exit
    ;;
esac

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/gilbom-source-archive.XXXXXX")"
trap 'rm -rf -- "$TEST_ROOT"' EXIT HUP INT TERM

fail() {
  echo "Release source archive self-test failed: $1" >&2
  exit 1
}

fixture_root="$TEST_ROOT/repository"
fixture_project="$fixture_root/web"
fake_bin="$TEST_ROOT/bin"
test_path="$PATH"
mkdir -p \
  "$fixture_project/scripts" \
  "$fixture_project/infra/scripts" \
  "$fixture_project/secrets" \
  "$fixture_project/release" \
  "$fake_bin"
cp -- "$PROJECT_DIR/scripts/create-release-source-archive.sh" \
  "$fixture_project/scripts/"
chmod 0755 \
  "$fixture_project/scripts/create-release-source-archive.sh"
if [ "${GILBOM_TEST_REAL_ZSTD:-}" = 1 ]; then
  command -v zstd >/dev/null 2>&1 \
    || fail "GILBOM_TEST_REAL_ZSTD=1 requires zstd"
else
  cp -- "$0" "$fake_bin/zstd"
  chmod 0755 "$fake_bin/zstd"
  test_path="$fake_bin:$PATH"
fi
printf 'services: {}\n' > "$fixture_project/compose.monitoring.yaml"
printf 'services: {}\n' > "$fixture_project/compose.prod.yaml"
printf '#!/bin/sh\n' \
  > "$fixture_project/infra/scripts/install-day10-host.sh"
printf '*\n!.gitignore\n' > "$fixture_project/secrets/.gitignore"

git -C "$fixture_root" init --quiet
git -C "$fixture_root" config user.name fixture
git -C "$fixture_root" config user.email fixture@example.invalid
git -C "$fixture_root" add web
git -C "$fixture_root" commit --quiet -m fixture
release_sha="$(git -C "$fixture_root" rev-parse HEAD)"

PATH="$test_path" \
RELEASE_SHA="$release_sha" \
  sh "$fixture_project/scripts/create-release-source-archive.sh"

archive="$fixture_project/release/gilbom-web-$release_sha.tar.zst"
[ -s "$archive" ] \
  || fail "exact-SHA source archive was not created"
archive_tar="$archive"
if [ "${GILBOM_TEST_REAL_ZSTD:-}" = 1 ]; then
  archive_tar="$TEST_ROOT/gilbom-web.tar"
  zstd --quiet -dc "$archive" > "$archive_tar"
fi
tar -tf "$archive_tar" | grep -Fxq compose.monitoring.yaml \
  || fail "archive omitted monitoring Compose"
tar -tf "$archive_tar" | grep -Fxq infra/scripts/install-day10-host.sh \
  || fail "archive omitted the Day 10 host installer"
if tar -tf "$archive_tar" | grep -Fq 'web/'; then
  fail "archive unexpectedly retained the project directory prefix"
fi

rm -f -- "$archive"
printf 'must-not-cross-the-boundary\n' > "$fixture_project/runtime.env"
git -C "$fixture_root" add web/runtime.env
git -C "$fixture_root" commit --quiet -m unsafe-runtime-state
unsafe_sha="$(git -C "$fixture_root" rev-parse HEAD)"
if PATH="$test_path" \
   RELEASE_SHA="$unsafe_sha" \
    sh "$fixture_project/scripts/create-release-source-archive.sh" \
      >/dev/null 2>&1; then
  fail "tracked runtime.env was accepted"
fi

echo "Release source archive self-test: PASS"
