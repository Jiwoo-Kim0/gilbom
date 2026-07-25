#!/bin/sh

set -eu

LOCAL_DIR=/mqtt-local
CERT_DIR="${LOCAL_DIR}/certs"
CERT_FILE="${CERT_DIR}/server-local.pem"
KEY_FILE="${CERT_DIR}/server-local-key.pem"
PASSWORD_FILE="${LOCAL_DIR}/passwd"

mkdir -p "${CERT_DIR}"

certificate_is_reusable() {
  [ -s "${CERT_FILE}" ] &&
    [ -s "${KEY_FILE}" ] &&
    openssl x509 -in "${CERT_FILE}" -checkend 86400 -noout >/dev/null 2>&1 &&
    openssl x509 -in "${CERT_FILE}" -pubkey -noout > /tmp/mqtt-cert.pub 2>/dev/null &&
    openssl pkey -in "${KEY_FILE}" -pubout > /tmp/mqtt-key.pub 2>/dev/null &&
    cmp -s /tmp/mqtt-cert.pub /tmp/mqtt-key.pub
}

if certificate_is_reusable; then
  echo "MQTT TLS certificate already exists; reusing server-local.pem."
else
  echo "Generating MQTT TLS certificate: server-local.pem"
  openssl req \
    -x509 \
    -nodes \
    -newkey rsa:2048 \
    -days 825 \
    -keyout /tmp/server-local-key.pem \
    -out /tmp/server-local.pem \
    -config "${LOCAL_DIR}/openssl-local.cnf" \
    -extensions server_extensions

  cp /tmp/server-local.pem "${CERT_FILE}"
  cp /tmp/server-local-key.pem "${KEY_FILE}"
fi

mosquitto_passwd -b -c /tmp/mqtt-passwd "${MQTT_USERNAME}" "${MQTT_PASSWORD}"
mosquitto_passwd -b /tmp/mqtt-passwd "${MQTT_ROBOT_USERNAME}" "${MQTT_ROBOT_PASSWORD}"
cp /tmp/mqtt-passwd "${PASSWORD_FILE}"

chown 1883:1883 "${CERT_FILE}" "${KEY_FILE}" "${PASSWORD_FILE}" 2>/dev/null || true
chmod 644 "${CERT_FILE}"
chmod 640 "${KEY_FILE}" "${PASSWORD_FILE}"

echo "MQTT local TLS and password files are ready."
