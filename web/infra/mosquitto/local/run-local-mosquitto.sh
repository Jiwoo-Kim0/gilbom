#!/bin/sh

set -eu

CONFIG_FILE=/mosquitto/config/mosquitto.conf
PASSWORD_FILE=/mosquitto/local/credentials/passwd

password_checksum() {
  sha256sum "${PASSWORD_FILE}" | awk '{print $1}'
}

mosquitto -c "${CONFIG_FILE}" &
BROKER_PID=$!

stop_broker() {
  kill -TERM "${BROKER_PID}" 2>/dev/null || true
  wait "${BROKER_PID}" 2>/dev/null || true
  exit 0
}

trap stop_broker INT TERM

LAST_CHECKSUM="$(password_checksum)"

while kill -0 "${BROKER_PID}" 2>/dev/null; do
  sleep 1 &
  wait "$!" 2>/dev/null || true

  if CURRENT_CHECKSUM="$(password_checksum 2>/dev/null)" &&
      [ "${CURRENT_CHECKSUM}" != "${LAST_CHECKSUM}" ]; then
    echo "MQTT password file changed; reloading broker configuration."
    kill -HUP "${BROKER_PID}"
    LAST_CHECKSUM="${CURRENT_CHECKSUM}"
  fi
done

wait "${BROKER_PID}"
