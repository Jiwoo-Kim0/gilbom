#!/bin/sh

set -eu

LOCAL_DIR=/mqtt-local
RUNTIME_DIR="${LOCAL_DIR}/runtime"
CERT_DIR="${RUNTIME_DIR}/certs"
CA_DIR="${LOCAL_DIR}/ca"
CA_CERT_FILE="${CERT_DIR}/local-ca-cert.pem"
CA_KEY_FILE="${CA_DIR}/local-ca-key.pem"
SERVER_CERT_FILE="${CERT_DIR}/local-broker-cert.pem"
SERVER_KEY_FILE="${CERT_DIR}/local-broker-key.pem"
PASSWORD_FILE="${RUNTIME_DIR}/passwd"
ACL_SOURCE_FILE=/mqtt-input/acl
ACL_RUNTIME_FILE="${RUNTIME_DIR}/acl"
RENEW_BEFORE_SECONDS=2592000

mkdir -p "${CERT_DIR}" "${CA_DIR}"

WORK_DIR="$(mktemp -d /tmp/mqtt-local-init.XXXXXX)"

cleanup() {
  rm -rf "${WORK_DIR}"
}

trap cleanup EXIT
trap 'exit 1' HUP INT TERM

certificate_matches_key() {
  certificate_file="$1"
  private_key_file="$2"
  output_prefix="$3"

  openssl x509 -in "${certificate_file}" -pubkey -noout \
    > "${WORK_DIR}/${output_prefix}-certificate.pub" 2>/dev/null &&
    openssl pkey -in "${private_key_file}" -pubout \
      > "${WORK_DIR}/${output_prefix}-private-key.pub" 2>/dev/null &&
    cmp -s \
      "${WORK_DIR}/${output_prefix}-certificate.pub" \
      "${WORK_DIR}/${output_prefix}-private-key.pub"
}

ca_is_reusable() {
  [ -s "${CA_CERT_FILE}" ] &&
    [ -s "${CA_KEY_FILE}" ] &&
    openssl x509 -in "${CA_CERT_FILE}" -checkend "${RENEW_BEFORE_SECONDS}" \
      -noout >/dev/null 2>&1 &&
    certificate_matches_key "${CA_CERT_FILE}" "${CA_KEY_FILE}" "ca"
}

broker_certificate_is_reusable() {
  [ -s "${CA_CERT_FILE}" ] &&
    [ -s "${SERVER_CERT_FILE}" ] &&
    [ -s "${SERVER_KEY_FILE}" ] &&
    openssl x509 -in "${SERVER_CERT_FILE}" -checkend "${RENEW_BEFORE_SECONDS}" \
      -noout >/dev/null 2>&1 &&
    certificate_matches_key "${SERVER_CERT_FILE}" "${SERVER_KEY_FILE}" "server" &&
    openssl verify -CAfile "${CA_CERT_FILE}" "${SERVER_CERT_FILE}" >/dev/null 2>&1
}

generate_ca() {
  openssl req \
    -x509 \
    -nodes \
    -newkey rsa:3072 \
    -days 3650 \
    -sha256 \
    -keyout "${WORK_DIR}/local-ca-key.pem" \
    -out "${WORK_DIR}/local-ca-cert.pem" \
    -config "${LOCAL_DIR}/openssl-local-ca.cnf" \
    -extensions ca_extensions

  cp "${WORK_DIR}/local-ca-cert.pem" "${CA_CERT_FILE}"
  cp "${WORK_DIR}/local-ca-key.pem" "${CA_KEY_FILE}"
}

generate_broker_certificate() {
  openssl req \
    -new \
    -nodes \
    -newkey rsa:2048 \
    -sha256 \
    -keyout "${WORK_DIR}/local-broker-key.pem" \
    -out "${WORK_DIR}/local-broker.csr" \
    -config "${LOCAL_DIR}/openssl-local.cnf" \
    -reqexts server_request_extensions

  certificate_serial="$(openssl rand -hex 16)"
  openssl x509 \
    -req \
    -in "${WORK_DIR}/local-broker.csr" \
    -CA "${CA_CERT_FILE}" \
    -CAkey "${CA_KEY_FILE}" \
    -set_serial "0x${certificate_serial}" \
    -days 825 \
    -sha256 \
    -extfile "${LOCAL_DIR}/openssl-local.cnf" \
    -extensions server_extensions \
    -out "${WORK_DIR}/local-broker-cert.pem"

  cp "${WORK_DIR}/local-broker-cert.pem" "${SERVER_CERT_FILE}"
  cp "${WORK_DIR}/local-broker-key.pem" "${SERVER_KEY_FILE}"
}

if ca_is_reusable; then
  echo "MQTT local CA already exists; reusing it."
else
  echo "Generating the MQTT local development CA."
  generate_ca
fi

if broker_certificate_is_reusable; then
  echo "MQTT broker certificate already exists; reusing it."
else
  echo "Issuing the MQTT broker certificate from the local CA."
  generate_broker_certificate
fi

mosquitto_passwd -b -c "${WORK_DIR}/passwd" "${MQTT_USERNAME}" "${MQTT_PASSWORD}"
mosquitto_passwd -b "${WORK_DIR}/passwd" "${MQTT_ROBOT_USERNAME}" "${MQTT_ROBOT_PASSWORD}"
cp "${WORK_DIR}/passwd" "${PASSWORD_FILE}"
cp "${ACL_SOURCE_FILE}" "${ACL_RUNTIME_FILE}"

chown 1883:1883 \
  "${CA_CERT_FILE}" \
  "${SERVER_CERT_FILE}" \
  "${SERVER_KEY_FILE}" \
  "${PASSWORD_FILE}" \
  "${ACL_RUNTIME_FILE}" 2>/dev/null || true
chown 0:0 "${CA_KEY_FILE}" 2>/dev/null || true

chmod 644 "${CA_CERT_FILE}" "${SERVER_CERT_FILE}"
chmod 640 "${SERVER_KEY_FILE}" "${PASSWORD_FILE}"
chmod 700 "${ACL_RUNTIME_FILE}"
chmod 600 "${CA_KEY_FILE}"

echo "MQTT local TLS assets and password file are ready."
