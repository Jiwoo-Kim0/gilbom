#!/bin/sh

set -eu

IMAGE_NAME=${1:-gilbom-backend:local}
VOLUME_PREFIX="gilbom-backend-runtime-check-$$"
PDF_VOLUME="${VOLUME_PREFIX}-pdf"
IMAGE_VOLUME="${VOLUME_PREFIX}-images"
MQTT_VOLUME="${VOLUME_PREFIX}-mqtt"

fail() {
  echo "Backend runtime image verification failed: $1" >&2
  exit 1
}

cleanup() {
  docker volume rm \
    "$PDF_VOLUME" \
    "$IMAGE_VOLUME" \
    "$MQTT_VOLUME" >/dev/null 2>&1 || true
}

trap cleanup EXIT HUP INT TERM

image_user="$(docker image inspect \
  --format '{{.Config.User}}' "$IMAGE_NAME")"
[ "$image_user" = "spring" ] \
  || fail "image USER must be spring"

stop_signal="$(docker image inspect \
  --format '{{.Config.StopSignal}}' "$IMAGE_NAME")"
[ "$stop_signal" = "SIGTERM" ] \
  || fail "image STOPSIGNAL must be SIGTERM"

healthcheck="$(docker image inspect \
  --format '{{json .Config.Healthcheck.Test}}' "$IMAGE_NAME")"
printf '%s\n' "$healthcheck" \
  | grep -Fq '/actuator/health/readiness' \
  || fail "image healthcheck must use the readiness endpoint"

docker volume create "$PDF_VOLUME" >/dev/null
docker volume create "$IMAGE_VOLUME" >/dev/null
docker volume create "$MQTT_VOLUME" >/dev/null

docker run --rm \
  --read-only \
  --cap-drop ALL \
  --security-opt no-new-privileges \
  --tmpfs /tmp:rw,noexec,nosuid,nodev,size=64m,mode=1777 \
  --mount "type=volume,src=${PDF_VOLUME},dst=/app/data/report-pdf" \
  --mount "type=volume,src=${IMAGE_VOLUME},dst=/app/data/robot-images" \
  --mount "type=volume,src=${MQTT_VOLUME},dst=/app/config/mqtt/credentials" \
  --entrypoint /bin/sh \
  "$IMAGE_NAME" -ec '
    [ "$(id -u)" = "1883" ]
    [ "$(id -g)" = "1883" ]
    grep -Eq "^CapEff:[[:space:]]+0{16}$" /proc/self/status
    grep -Eq "^NoNewPrivs:[[:space:]]+1$" /proc/self/status

    for forbidden_tool in javac jdeps jlink jar gradle git curl
    do
      ! command -v "$forbidden_tool" >/dev/null 2>&1
    done
    command -v java >/dev/null
    command -v wget >/dev/null
    command -v mosquitto_passwd >/dev/null

    [ ! -e /workspace ]
    [ ! -e /app/src ]
    [ ! -e /app/test ]
    [ ! -e /app/build ]
    [ ! -e /app/.gradle ]
    [ ! -w /app/app.jar ]

    if touch /app/root-filesystem-write-check 2>/dev/null; then
      echo "read-only root filesystem accepted a write" >&2
      exit 1
    fi

    printf "runtime-check\n" > /tmp/runtime-check
    printf "%%PDF-1.7\nruntime-check\n" \
      > /app/data/report-pdf/runtime-check.pdf
    printf "runtime-check\n" \
      > /app/data/robot-images/runtime-check.img
    mosquitto_passwd -b -c \
      /app/config/mqtt/credentials/passwd \
      runtime-check \
      runtime-check-password

    [ -s /tmp/runtime-check ]
    [ -s /app/data/report-pdf/runtime-check.pdf ]
    [ -s /app/data/robot-images/runtime-check.img ]
    [ -s /app/config/mqtt/credentials/passwd ]
  '

image_size="$(docker image inspect \
  --format '{{.Size}}' "$IMAGE_NAME")"
echo "Backend runtime image verification: PASS (bytes=${image_size})"
