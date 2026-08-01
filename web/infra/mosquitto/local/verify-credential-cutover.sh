#!/bin/sh

set -eu

PASSWORD_FILE="${MQTT_CUTOVER_PASSWORD_FILE:-/mqtt-local/runtime/credentials/passwd}"
CA_FILE="${MQTT_CUTOVER_CA_FILE:-/mqtt-local/runtime/certs/local-ca-cert.pem}"
BROKER_HOST="${MQTT_CUTOVER_BROKER_HOST:-mosquitto}"
BROKER_PORT="${MQTT_CUTOVER_BROKER_PORT:-8883}"
MAX_ATTEMPTS="${MQTT_CUTOVER_MAX_ATTEMPTS:-120}"
STATE_OWNER_UID="$(stat -c '%u' /mqtt-local)"
PASSWORD_FILE_OWNER="${MQTT_CUTOVER_PASSWORD_FILE_OWNER:-${STATE_OWNER_UID}:1883}"
LAST_AUTH_ERROR=""
TEST_USERNAME="cutover-$(openssl rand -hex 8)"
OLD_PASSWORD="$(openssl rand -hex 24)"
NEW_PASSWORD="$(openssl rand -hex 24)"
TEST_TOPIC="tbs/v2/robots/${TEST_USERNAME}/telemetry"

cleanup() {
  if [ -f "${PASSWORD_FILE}" ]; then
    mosquitto_passwd -D "${PASSWORD_FILE}" "${TEST_USERNAME}" \
      >/dev/null 2>&1 || true
  fi
}

trap cleanup EXIT HUP INT TERM

require_file() {
  if [ ! -f "$1" ]; then
    echo "Required cutover test file is missing: $1" >&2
    exit 1
  fi
}

can_authenticate() {
  password="$1"
  auth_options="$(mktemp /tmp/mqtt-cutover-auth.XXXXXX)"
  auth_error="$(mktemp /tmp/mqtt-cutover-error.XXXXXX)"
  chmod 600 "${auth_options}"
  printf '%s %s\n' '--username' "${TEST_USERNAME}" > "${auth_options}"
  printf '%s %s\n' '--pw' "${password}" >> "${auth_options}"

  result=0
  timeout 5 mosquitto_pub \
    -o "${auth_options}" \
    --host "${BROKER_HOST}" \
    --port "${BROKER_PORT}" \
    --cafile "${CA_FILE}" \
    --insecure \
    --topic "${TEST_TOPIC}" \
    --message '{"cutover_test":true}' \
    --qos 0 \
    >/dev/null 2>"${auth_error}" || result=$?
  LAST_AUTH_ERROR="$(
    tr '\r\n' '  ' < "${auth_error}" \
      | cut -c 1-200
  )"
  rm -f "${auth_options}"
  rm -f "${auth_error}"
  return "${result}"
}

expect_accepted() {
  password="$1"
  label="$2"
  attempts=0
  while [ "${attempts}" -lt "${MAX_ATTEMPTS}" ]; do
    if can_authenticate "${password}"; then
      echo "PASS: ${label}"
      return
    fi
    attempts=$((attempts + 1))
    sleep 1
  done
  echo "FAIL: ${label}" >&2
  if [ -n "${LAST_AUTH_ERROR}" ]; then
    echo "Last MQTT client error: ${LAST_AUTH_ERROR}" >&2
  fi
  exit 1
}

expect_rejected() {
  password="$1"
  label="$2"
  attempts=0
  while [ "${attempts}" -lt "${MAX_ATTEMPTS}" ]; do
    if ! can_authenticate "${password}"; then
      echo "PASS: ${label}"
      return
    fi
    attempts=$((attempts + 1))
    sleep 1
  done
  echo "FAIL: ${label}" >&2
  exit 1
}

restore_password_hash() {
  password_hash="$1"
  work_file="$(mktemp /tmp/mqtt-cutover-passwd.XXXXXX)"
  replaced=false
  while IFS= read -r line || [ -n "${line}" ]; do
    case "${line}" in
      "${TEST_USERNAME}:"*)
        printf '%s:%s\n' \
          "${TEST_USERNAME}" \
          "${password_hash}" >> "${work_file}"
        replaced=true
        ;;
      *)
        printf '%s\n' "${line}" >> "${work_file}"
        ;;
    esac
  done < "${PASSWORD_FILE}"
  if [ "${replaced}" != "true" ]; then
    rm -f "${work_file}"
    echo "FAIL: compensation target account was not found" >&2
    exit 1
  fi
  cp "${work_file}" "${PASSWORD_FILE}"
  chown "${PASSWORD_FILE_OWNER}" "${PASSWORD_FILE}"
  chmod 660 "${PASSWORD_FILE}"
  rm -f "${work_file}"
}

read_password_hash() {
  while IFS=: read -r username password_hash; do
    if [ "${username}" = "${TEST_USERNAME}" ]; then
      printf '%s' "${password_hash}"
      return
    fi
  done < "${PASSWORD_FILE}"
}

require_file "${PASSWORD_FILE}"
require_file "${CA_FILE}"

mosquitto_passwd -b "${PASSWORD_FILE}" "${TEST_USERNAME}" "${OLD_PASSWORD}"
OLD_PASSWORD_HASH="$(read_password_hash)"
if [ -z "${OLD_PASSWORD_HASH}" ]; then
  echo "FAIL: initial password verifier was not written" >&2
  exit 1
fi
expect_accepted "${OLD_PASSWORD}" "initial credential accepted over TLS"

mosquitto_passwd -b "${PASSWORD_FILE}" "${TEST_USERNAME}" "${NEW_PASSWORD}"
expect_rejected "${OLD_PASSWORD}" "old credential denied after rotation"
expect_accepted "${NEW_PASSWORD}" "new credential accepted after rotation"

restore_password_hash "${OLD_PASSWORD_HASH}"
if [ "$(read_password_hash)" != "${OLD_PASSWORD_HASH}" ]; then
  echo "FAIL: compensation verifier was not restored exactly" >&2
  exit 1
fi
expect_accepted "${OLD_PASSWORD}" "previous credential restored by compensation"
expect_rejected "${NEW_PASSWORD}" "rolled-back credential denied"

mosquitto_passwd -D "${PASSWORD_FILE}" "${TEST_USERNAME}"
expect_rejected "${OLD_PASSWORD}" "revoked credential denied on reconnect"

echo "MQTT TLS credential cutover verification completed."
