#!/bin/sh

set -eu
set +x

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"
SECRET_DIR="${SECRET_DIR:-$PROJECT_DIR/secrets}"
MQTT_CA_CERTIFICATE_SOURCE="${MQTT_CA_CERTIFICATE_SOURCE:-}"
MOSQUITTO_TLS_FULLCHAIN_SOURCE="${MOSQUITTO_TLS_FULLCHAIN_SOURCE:-}"
MOSQUITTO_TLS_PRIVATE_KEY_SOURCE="${MOSQUITTO_TLS_PRIVATE_KEY_SOURCE:-}"
GILBOM_LLM_API_KEY_SOURCE="${GILBOM_LLM_API_KEY_SOURCE:-}"
INITIAL_ADMIN_PASSWORD_SOURCE="${INITIAL_ADMIN_PASSWORD_SOURCE:-}"

fail() {
  echo "Production Secret generation failed: $1" >&2
  exit 1
}

require_source() {
  path="$1"
  label="$2"
  [ -f "$path" ] || fail "$label source file is required"
}

umask 077
command -v openssl >/dev/null 2>&1 || fail "OpenSSL is required"
command -v docker >/dev/null 2>&1 || fail "Docker CLI is required"
command -v sha256sum >/dev/null 2>&1 || fail "sha256sum is required"
require_source "$MQTT_CA_CERTIFICATE_SOURCE" "MQTT CA certificate"
require_source "$MOSQUITTO_TLS_FULLCHAIN_SOURCE" "Mosquitto full chain"
require_source "$MOSQUITTO_TLS_PRIVATE_KEY_SOURCE" "Mosquitto private key"

target_names='postgres_admin_password
postgres_flyway_password
postgres_app_password
mqtt_backend_password
mosquitto_passwd
mqtt_ca_certificate.pem
mosquitto_tls_fullchain.pem
mosquitto_tls_private_key.pem
gilbom_storage_signing_key
gilbom_auth_rate_limit_hmac_key
gilbom_mfa_totp_keyring
gilbom_llm_api_key
gilbom_initial_admin_password'

install -d -m 0700 "$SECRET_DIR"
for name in $target_names
do
  [ ! -e "$SECRET_DIR/$name" ] \
    || fail "refusing to overwrite existing Secret file: $name"
done

openssl rand -hex -out "$SECRET_DIR/postgres_admin_password" 32
openssl rand -hex -out "$SECRET_DIR/postgres_flyway_password" 32
openssl rand -hex -out "$SECRET_DIR/postgres_app_password" 32
openssl rand -hex -out "$SECRET_DIR/mqtt_backend_password" 32
openssl rand -base64 48 \
  | tr -d '\n' > "$SECRET_DIR/gilbom_storage_signing_key"
printf '\n' >> "$SECRET_DIR/gilbom_storage_signing_key"
openssl rand -base64 48 \
  | tr -d '\n' > "$SECRET_DIR/gilbom_auth_rate_limit_hmac_key"
printf '\n' >> "$SECRET_DIR/gilbom_auth_rate_limit_hmac_key"
printf '1=' > "$SECRET_DIR/gilbom_mfa_totp_keyring"
openssl rand -base64 32 \
  | tr -d '\n' >> "$SECRET_DIR/gilbom_mfa_totp_keyring"
printf '\n' >> "$SECRET_DIR/gilbom_mfa_totp_keyring"

cp "$MQTT_CA_CERTIFICATE_SOURCE" \
  "$SECRET_DIR/mqtt_ca_certificate.pem"
cp "$MOSQUITTO_TLS_FULLCHAIN_SOURCE" \
  "$SECRET_DIR/mosquitto_tls_fullchain.pem"
cp "$MOSQUITTO_TLS_PRIVATE_KEY_SOURCE" \
  "$SECRET_DIR/mosquitto_tls_private_key.pem"

if [ -n "$GILBOM_LLM_API_KEY_SOURCE" ]; then
  require_source "$GILBOM_LLM_API_KEY_SOURCE" "LLM API key"
  cp "$GILBOM_LLM_API_KEY_SOURCE" \
    "$SECRET_DIR/gilbom_llm_api_key"
else
  : > "$SECRET_DIR/gilbom_llm_api_key"
fi

if [ -n "$INITIAL_ADMIN_PASSWORD_SOURCE" ]; then
  require_source "$INITIAL_ADMIN_PASSWORD_SOURCE" \
    "initial administrator password"
  cp "$INITIAL_ADMIN_PASSWORD_SOURCE" \
    "$SECRET_DIR/gilbom_initial_admin_password"
else
  openssl rand -base64 24 \
    | tr -d '\n' > "$SECRET_DIR/gilbom_initial_admin_password"
  printf '\n' >> "$SECRET_DIR/gilbom_initial_admin_password"
fi

docker run \
  --rm \
  --user "$(id -u):$(id -g)" \
  --entrypoint /bin/sh \
  --mount "type=bind,src=$SECRET_DIR,dst=/work" \
  eclipse-mosquitto:2.1.2-alpine \
  -ec '
    mqtt_password="$(cat /work/mqtt_backend_password)"
    mosquitto_passwd \
      -b \
      -c \
      /work/mosquitto_passwd \
      gilbom \
      "$mqtt_password"
    unset mqtt_password
  ' >/dev/null

openssl x509 \
  -in "$SECRET_DIR/mosquitto_tls_fullchain.pem" \
  -noout >/dev/null \
  || fail "Mosquitto certificate is invalid"
openssl pkey \
  -in "$SECRET_DIR/mosquitto_tls_private_key.pem" \
  -check \
  -noout >/dev/null \
  || fail "Mosquitto private key is invalid"
openssl verify \
  -CAfile "$SECRET_DIR/mqtt_ca_certificate.pem" \
  "$SECRET_DIR/mosquitto_tls_fullchain.pem" >/dev/null \
  || fail "Mosquitto certificate is not trusted by the MQTT CA"
certificate_public_key="$(
  openssl x509 \
    -in "$SECRET_DIR/mosquitto_tls_fullchain.pem" \
    -pubkey \
    -noout \
    | openssl pkey -pubin -outform DER 2>/dev/null \
    | sha256sum \
    | awk '{print $1}'
)"
private_public_key="$(
  openssl pkey \
    -in "$SECRET_DIR/mosquitto_tls_private_key.pem" \
    -pubout \
    -outform DER 2>/dev/null \
    | sha256sum \
    | awk '{print $1}'
)"
[ "$certificate_public_key" = "$private_public_key" ] \
  || fail "Mosquitto certificate and private key do not match"
unset certificate_public_key private_public_key

chmod 0700 "$SECRET_DIR"
chmod 0444 "$SECRET_DIR"/*

echo "Production Secret generation: PASS"
echo "Secret directory: $SECRET_DIR"
echo "Secret values were not printed."
