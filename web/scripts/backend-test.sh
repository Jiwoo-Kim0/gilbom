#!/bin/sh

set -eu

SCRIPT_DIR=$(
    CDPATH= cd -- "$(dirname -- "$0")" >/dev/null 2>&1
    pwd
)
PROJECT_DIR=$(
    CDPATH= cd -- "${SCRIPT_DIR}/.." >/dev/null 2>&1
    pwd
)
BACKEND_DIR="${PROJECT_DIR}/backend"
DOCKER_SOCKET=/var/run/docker.sock
JAVA_IMAGE=amazoncorretto:21.0.12-alpine3.22

if ! command -v docker >/dev/null 2>&1; then
    echo "Docker CLI is required to run the backend test environment." >&2
    exit 1
fi

if [ ! -S "${DOCKER_SOCKET}" ]; then
    echo "Docker socket was not found at ${DOCKER_SOCKET}." >&2
    exit 1
fi

if ! docker info >/dev/null 2>&1; then
    echo "Docker daemon is not reachable by the current user." >&2
    echo "Start Docker Desktop/Engine and grant this user access to ${DOCKER_SOCKET}." >&2
    exit 1
fi

DOCKER_SOCKET_GID=$(stat -c '%g' "${DOCKER_SOCKET}")
HOST_UID=$(id -u)
HOST_GID=$(id -g)

docker run --rm \
    --user "${HOST_UID}:${HOST_GID}" \
    --group-add "${DOCKER_SOCKET_GID}" \
    --add-host host.docker.internal:host-gateway \
    --env DOCKER_HOST=unix:///var/run/docker.sock \
    --env TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
    --env TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
    --env HOME=/test-home \
    --env GRADLE_USER_HOME=/test-home/.gradle \
    --volume "${DOCKER_SOCKET}:${DOCKER_SOCKET}" \
    --volume "${BACKEND_DIR}:${BACKEND_DIR}:ro" \
    --tmpfs "/test-home:rw,nosuid,nodev,uid=${HOST_UID},gid=${HOST_GID},mode=0700" \
    --workdir "${BACKEND_DIR}" \
    "${JAVA_IMAGE}" \
    sh -ec '
        printf "JAVA_HOME=%s\n" "$JAVA_HOME"
        java -version
        test -x "$JAVA_HOME/bin/java"
        sh ./gradlew \
            --project-cache-dir /test-home/project-cache \
            -PisolatedBuildDirectory=/test-home/build \
            test \
            --no-daemon \
            --rerun-tasks
    '
