#!/bin/sh

set -eu

LOCAL_DIR=/mqtt-local
RUNTIME_DIR="${LOCAL_DIR}/runtime"
CERT_DIR="${RUNTIME_DIR}/certs"
CREDENTIAL_DIR="${RUNTIME_DIR}/credentials"
CA_DIR="${LOCAL_DIR}/ca"
CA_CERT_FILE="${CERT_DIR}/local-ca-cert.pem"
CA_KEY_FILE="${CA_DIR}/local-ca-key.pem"
SERVER_CERT_FILE="${CERT_DIR}/local-broker-cert.pem"
SERVER_KEY_FILE="${CERT_DIR}/local-broker-key.pem"
PASSWORD_FILE="${CREDENTIAL_DIR}/passwd"
LEGACY_PASSWORD_FILE="${RUNTIME_DIR}/passwd"
ACL_SOURCE_FILE=/mqtt-input/acl
ACL_RUNTIME_FILE="${RUNTIME_DIR}/acl"
RENEW_BEFORE_SECONDS=2592000
MQTT_LAN_IP="${MQTT_LAN_IP:-}"
MQTT_PUBLIC_HOSTNAME="${MQTT_PUBLIC_HOSTNAME:-}"

read_secret_file() {
  secret_name=$1
  secret_file=$2

  case "$secret_file" in
    /*) ;;
    *)
      echo "${secret_name} file path must be absolute." >&2
      exit 1
      ;;
  esac
  if [ ! -f "$secret_file" ] || [ ! -r "$secret_file" ]; then
    echo "${secret_name} file is missing or unreadable." >&2
    exit 1
  fi
  secret_size="$(wc -c < "$secret_file")"
  if [ "$secret_size" -gt 16384 ] \
    || ! awk 'NR > 1 { exit 1 } END { if (NR == 0) exit 1 }' "$secret_file"; then
    echo "${secret_name} file must contain one line of at most 16 KiB." >&2
    exit 1
  fi
  cat "$secret_file"
}

if [ -n "${MQTT_PASSWORD_FILE:-}" ]; then
  if [ -n "${MQTT_PASSWORD:-}" ]; then
    echo "Set only MQTT_PASSWORD_FILE, not the direct value." >&2
    exit 1
  fi
  MQTT_PASSWORD="$(
    read_secret_file MQTT_PASSWORD "$MQTT_PASSWORD_FILE"
  )"
  export MQTT_PASSWORD
fi

if [ -n "${MQTT_ROBOT_PASSWORD_FILE:-}" ]; then
  if [ -n "${MQTT_ROBOT_PASSWORD:-}" ]; then
    echo "Set only MQTT_ROBOT_PASSWORD_FILE, not the direct value." >&2
    exit 1
  fi
  MQTT_ROBOT_PASSWORD="$(
    read_secret_file MQTT_ROBOT_PASSWORD "$MQTT_ROBOT_PASSWORD_FILE"
  )"
  export MQTT_ROBOT_PASSWORD
fi

: "${MQTT_PASSWORD:?MQTT_PASSWORD or MQTT_PASSWORD_FILE must be set}"
: "${MQTT_ROBOT_PASSWORD:?MQTT_ROBOT_PASSWORD or MQTT_ROBOT_PASSWORD_FILE must be set}"

mkdir -p "${CERT_DIR}" "${CREDENTIAL_DIR}" "${CA_DIR}"

WORK_DIR="$(mktemp -d /tmp/mqtt-local-init.XXXXXX)"
SERVER_OPENSSL_CONFIG="${WORK_DIR}/openssl-local-runtime.cnf"

cleanup() {
  rm -rf "${WORK_DIR}"
}

trap cleanup EXIT
trap 'exit 1' HUP INT TERM

validate_lan_ip() {
  [ -z "${MQTT_LAN_IP}" ] && return 0

  printf '%s\n' "${MQTT_LAN_IP}" | awk -F. '
    NF != 4 { exit 1 }
    {
      for (i = 1; i <= 4; i++) {
        if ($i !~ /^[0-9]+$/ || $i < 0 || $i > 255) {
          exit 1
        }
      }
    }
  ' || {
    echo "MQTT_LAN_IP must be a valid IPv4 address." >&2
    exit 1
  }
}

validate_public_hostname() {
  [ -z "${MQTT_PUBLIC_HOSTNAME}" ] && return 0

  printf '%s\n' "${MQTT_PUBLIC_HOSTNAME}" | awk '
    length($0) < 1 || length($0) > 253 { exit 1 }
    $0 ~ /^\./ || $0 ~ /\.$/ || $0 ~ /\.\./ { exit 1 }
    {
      count = split($0, labels, ".")
      for (i = 1; i <= count; i++) {
        if (length(labels[i]) < 1 || length(labels[i]) > 63) {
          exit 1
        }
        if (labels[i] !~ /^([A-Za-z0-9]|[A-Za-z0-9][A-Za-z0-9-]*[A-Za-z0-9])$/) {
          exit 1
        }
      }
    }
  ' || {
    echo "MQTT_PUBLIC_HOSTNAME must be a valid DNS hostname without a scheme or port." >&2
    exit 1
  }
}

prepare_server_openssl_config() {
  cp "${LOCAL_DIR}/openssl-local.cnf" "${SERVER_OPENSSL_CONFIG}"
  if [ -n "${MQTT_LAN_IP}" ]; then
    printf 'IP.2 = %s\n' "${MQTT_LAN_IP}" >> "${SERVER_OPENSSL_CONFIG}"
  fi
  if [ -n "${MQTT_PUBLIC_HOSTNAME}" ]; then
    printf 'DNS.3 = %s\n' "${MQTT_PUBLIC_HOSTNAME}" >> "${SERVER_OPENSSL_CONFIG}"
  fi
}

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

certificate_has_requested_lan_ip() {
  [ -z "${MQTT_LAN_IP}" ] ||
    openssl x509 -in "${SERVER_CERT_FILE}" -checkip "${MQTT_LAN_IP}" \
      -noout >/dev/null 2>&1
}

certificate_has_requested_public_hostname() {
  [ -z "${MQTT_PUBLIC_HOSTNAME}" ] ||
    openssl x509 -in "${SERVER_CERT_FILE}" -checkhost "${MQTT_PUBLIC_HOSTNAME}" \
      -noout >/dev/null 2>&1
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
    openssl verify -CAfile "${CA_CERT_FILE}" "${SERVER_CERT_FILE}" >/dev/null 2>&1 &&
    certificate_has_requested_lan_ip &&
    certificate_has_requested_public_hostname
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
    -config "${SERVER_OPENSSL_CONFIG}" \
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
    -extfile "${SERVER_OPENSSL_CONFIG}" \
    -extensions server_extensions \
    -out "${WORK_DIR}/local-broker-cert.pem"

  cp "${WORK_DIR}/local-broker-cert.pem" "${SERVER_CERT_FILE}"
  cp "${WORK_DIR}/local-broker-key.pem" "${SERVER_KEY_FILE}"
}

validate_lan_ip
validate_public_hostname
prepare_server_openssl_config

if ca_is_reusable; then
  echo "MQTT local CA already exists; reusing it."
else
  echo "Generating the MQTT local development CA."
  generate_ca
fi

if broker_certificate_is_reusable; then
  echo "MQTT broker certificate already exists; reusing it."
else
  if [ -n "${MQTT_PUBLIC_HOSTNAME}" ] && [ -n "${MQTT_LAN_IP}" ]; then
    echo "Issuing the MQTT broker certificate for localhost, ${MQTT_LAN_IP}, and ${MQTT_PUBLIC_HOSTNAME}."
  elif [ -n "${MQTT_PUBLIC_HOSTNAME}" ]; then
    echo "Issuing the MQTT broker certificate for localhost and ${MQTT_PUBLIC_HOSTNAME}."
  elif [ -n "${MQTT_LAN_IP}" ]; then
    echo "Issuing the MQTT broker certificate for localhost and ${MQTT_LAN_IP}."
  else
    echo "Issuing the MQTT broker certificate for localhost."
  fi
  generate_broker_certificate
fi

if [ -s "${PASSWORD_FILE}" ]; then
  cp "${PASSWORD_FILE}" "${WORK_DIR}/passwd"
elif [ -s "${LEGACY_PASSWORD_FILE}" ]; then
  echo "Migrating the legacy MQTT password file."
  cp "${LEGACY_PASSWORD_FILE}" "${WORK_DIR}/passwd"
else
  mosquitto_passwd -b -c \
    "${WORK_DIR}/passwd" \
    "${MQTT_USERNAME}" \
    "${MQTT_PASSWORD}"
fi

# Refresh the two local bootstrap accounts without deleting issued robot accounts.
mosquitto_passwd -b \
  "${WORK_DIR}/passwd" \
  "${MQTT_USERNAME}" \
  "${MQTT_PASSWORD}"
mosquitto_passwd -b \
  "${WORK_DIR}/passwd" \
  "${MQTT_ROBOT_USERNAME}" \
  "${MQTT_ROBOT_PASSWORD}"
cp "${WORK_DIR}/passwd" "${PASSWORD_FILE}"
cp "${ACL_SOURCE_FILE}" "${ACL_RUNTIME_FILE}"

chown 0:1883 "${CREDENTIAL_DIR}" 2>/dev/null || true
chown 1883:1883 \
  "${CA_CERT_FILE}" \
  "${SERVER_CERT_FILE}" \
  "${SERVER_KEY_FILE}" \
  "${ACL_RUNTIME_FILE}" 2>/dev/null || true
chown 0:1883 "${PASSWORD_FILE}" 2>/dev/null || true
chown 0:0 "${CA_KEY_FILE}" 2>/dev/null || true

chmod 644 "${CA_CERT_FILE}" "${SERVER_CERT_FILE}"
chmod 640 "${SERVER_KEY_FILE}"
chmod 770 "${CREDENTIAL_DIR}"
chmod 660 "${PASSWORD_FILE}"
chmod 700 "${ACL_RUNTIME_FILE}"
chmod 600 "${CA_KEY_FILE}"

echo "MQTT local TLS assets and password file are ready."
