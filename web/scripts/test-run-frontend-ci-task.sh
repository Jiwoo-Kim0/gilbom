#!/bin/sh

set -eu

if [ "$(basename -- "$0")" = docker ]; then
  source_mount_seen=false
  modules_mount_seen=false
  dist_mount_seen=false

  for argument
  do
    case "$argument" in
      "$EXPECTED_SOURCE_DIR:/app:ro")
        source_mount_seen=true
        ;;
      "$EXPECTED_WORK_ROOT/node_modules:/app/node_modules")
        modules_mount_seen=true
        ;;
      "$EXPECTED_WORK_ROOT/dist:/app/dist")
        dist_mount_seen=true
        ;;
    esac
  done

  [ "$source_mount_seen" = true ]
  [ "$modules_mount_seen" = true ]
  [ "$dist_mount_seen" = true ]
  [ -d "$EXPECTED_SOURCE_DIR/node_modules" ]
  [ -d "$EXPECTED_SOURCE_DIR/dist" ]
  exit 0
fi

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/gilbom-frontend-ci.XXXXXX")"
trap 'rm -rf -- "$TEST_ROOT"' EXIT HUP INT TERM

PROJECT_DIR="$TEST_ROOT/project"
FAKE_BIN="$TEST_ROOT/bin"
mkdir -p \
  "$PROJECT_DIR/scripts" \
  "$PROJECT_DIR/frontend/gilbom" \
  "$FAKE_BIN"
cp "$SCRIPT_DIR/run-frontend-ci-task.sh" "$PROJECT_DIR/scripts/"
cp "$SCRIPT_DIR/test-run-frontend-ci-task.sh" "$FAKE_BIN/docker"
chmod 0700 "$FAKE_BIN/docker"

RESOLVED_DOCKER="$(PATH="$FAKE_BIN:$PATH" command -v docker)"
[ "$RESOLVED_DOCKER" = "$FAKE_BIN/docker" ] || {
  echo "Frontend CI regression test did not resolve the fake docker command" >&2
  exit 1
}

EXPECTED_SOURCE_DIR="$PROJECT_DIR/frontend/gilbom"
EXPECTED_WORK_ROOT="$PROJECT_DIR/.artifacts/frontend-ci"
export EXPECTED_SOURCE_DIR EXPECTED_WORK_ROOT

PATH="$FAKE_BIN:$PATH" \
  sh "$PROJECT_DIR/scripts/run-frontend-ci-task.sh" test

[ -d "$EXPECTED_SOURCE_DIR/node_modules" ]
[ -d "$EXPECTED_SOURCE_DIR/dist" ]
[ -d "$EXPECTED_WORK_ROOT/node_modules" ]
[ -d "$EXPECTED_WORK_ROOT/dist" ]

echo "Frontend CI nested mountpoint regression test: PASS"
