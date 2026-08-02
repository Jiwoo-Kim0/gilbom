#!/bin/sh

set -eu

TASK="${1:-}"
case "$TASK" in
  test|lint|build) ;;
  *)
    echo "Frontend CI task must be test, lint, or build" >&2
    exit 1
    ;;
esac

PROJECT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
SOURCE_DIR="$PROJECT_DIR/frontend/gilbom"
WORK_ROOT="$PROJECT_DIR/.artifacts/frontend-ci"
HOST_UID="$(id -u)"
HOST_GID="$(id -g)"

# Docker must see nested bind targets before mounting the source tree
# read-only at /app. A clean checkout does not contain these ignored paths.
for source_mountpoint in \
  "$SOURCE_DIR/node_modules" \
  "$SOURCE_DIR/dist"
do
  [ ! -L "$source_mountpoint" ] || {
    echo "Frontend CI mountpoint must not be a symlink: $source_mountpoint" >&2
    exit 1
  }
  mkdir -p "$source_mountpoint"
  [ -d "$source_mountpoint" ] || {
    echo "Frontend CI mountpoint is not a directory: $source_mountpoint" >&2
    exit 1
  }
done

mkdir -p \
  "$WORK_ROOT/home" \
  "$WORK_ROOT/node_modules" \
  "$WORK_ROOT/dist"
chmod 0700 \
  "$WORK_ROOT" \
  "$WORK_ROOT/home" \
  "$WORK_ROOT/node_modules" \
  "$WORK_ROOT/dist"

if [ "$TASK" != test ]; then
  test -s "$WORK_ROOT/node_modules/.package-lock.json" \
    || {
      echo "Frontend dependencies are missing; the test stage must run first" >&2
      exit 1
    }
fi

docker run --rm \
  --user "$HOST_UID:$HOST_GID" \
  --env HOME=/ci-home \
  --env npm_config_cache=/ci-home/.npm \
  --env CI_FRONTEND_TASK="$TASK" \
  --env VITE_NAVER_MAP_CLIENT_ID="${VITE_NAVER_MAP_CLIENT_ID:-}" \
  --volume "$SOURCE_DIR:/app:ro" \
  --volume "$WORK_ROOT/home:/ci-home" \
  --volume "$WORK_ROOT/node_modules:/app/node_modules" \
  --volume "$WORK_ROOT/dist:/app/dist" \
  --workdir /app \
  node:24.18.0-alpine3.23 \
  sh -ec '
    if [ "$CI_FRONTEND_TASK" = test ]; then
      npm ci
      npm test
    else
      npm run "$CI_FRONTEND_TASK"
    fi
  '

case "$TASK" in
  build)
    find "$WORK_ROOT/dist" -type f -size +0c -print -quit | grep -q . \
      || {
        echo "Frontend production build output is empty" >&2
        exit 1
      }
    ;;
esac
