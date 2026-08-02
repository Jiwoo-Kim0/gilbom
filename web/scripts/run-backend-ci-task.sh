#!/bin/sh

set -eu

TASK="${1:-}"
case "$TASK" in
  test|securityAcceptanceTest) ;;
  *)
    echo "Backend CI task must be test or securityAcceptanceTest" >&2
    exit 1
    ;;
esac

PROJECT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
WORK_ROOT="$PROJECT_DIR/.artifacts/backend-ci"
HOST_UID="$(id -u)"
HOST_GID="$(id -g)"

mkdir -p "$WORK_ROOT/home" "$WORK_ROOT/cache" "$WORK_ROOT/build"
chmod 0700 "$WORK_ROOT" "$WORK_ROOT/home" "$WORK_ROOT/cache" "$WORK_ROOT/build"

set -- docker run --rm \
  --user "$HOST_UID:$HOST_GID" \
  --env CI_GRADLE_TASK="$TASK" \
  --env HOME=/ci-home \
  --env GRADLE_USER_HOME=/ci-home/.gradle \
  --env JAVA_TOOL_OPTIONS=-Duser.home=/ci-home \
  --volume "$PROJECT_DIR/backend:/workspace:ro" \
  --volume "$WORK_ROOT/home:/ci-home" \
  --volume "$WORK_ROOT/cache:/ci-cache" \
  --volume "$WORK_ROOT/build:/ci-build" \
  --workdir /workspace

if [ -S /var/run/docker.sock ]; then
  DOCKER_SOCKET_GID="$(stat -c '%g' /var/run/docker.sock)"
  set -- "$@" \
    --group-add "$DOCKER_SOCKET_GID" \
    --add-host host.docker.internal:host-gateway \
    --env DOCKER_HOST=unix:///var/run/docker.sock \
    --env TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
    --env TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
    --volume /var/run/docker.sock:/var/run/docker.sock
else
  test -n "${DOCKER_HOST:-}"
  test "${DOCKER_TLS_VERIFY:-}" = 1
  test -r "${DOCKER_CERT_PATH:-/certs/client}/ca.pem"
  set -- "$@" \
    --add-host docker-builder:host-gateway \
    --env DOCKER_HOST="$DOCKER_HOST" \
    --env DOCKER_TLS_VERIFY=1 \
    --env DOCKER_CERT_PATH=/certs/client \
    --env TESTCONTAINERS_HOST_OVERRIDE=docker-builder \
    --volume /certs/client:/certs/client:ro
fi

set -- "$@" amazoncorretto:21.0.12-alpine3.22 sh -ec '
  sh ./gradlew \
    --project-cache-dir /ci-cache/project-cache \
    -PisolatedBuildDirectory=/ci-build \
    "$CI_GRADLE_TASK" \
    --no-daemon \
    --rerun-tasks
'
"$@"

find "$WORK_ROOT/build/test-results/$TASK" -type f -name '*.xml' -size +0c \
  -print -quit | grep -q . \
  || {
    echo "Backend CI task did not produce a non-empty XML result: $TASK" >&2
    exit 1
  }
