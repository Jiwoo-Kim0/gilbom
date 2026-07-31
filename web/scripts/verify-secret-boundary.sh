#!/bin/sh

set -eu

fail() {
  echo "Secret boundary verification failed: $1" >&2
  exit 1
}

assert_contains() {
  file=$1
  expected=$2
  label=$3

  if ! grep -Fq "$expected" "$file"; then
    fail "${label} (${file})"
  fi
}

for file in .dockerignore backend/.dockerignore frontend/.dockerignore
do
  assert_contains "$file" ".env.*" ".env variants must be excluded"
  assert_contains "$file" "secrets/" "runtime Secret directory must be excluded"
  assert_contains "$file" "test-results/" "test output must be excluded"
  assert_contains "$file" ".idea" "IDE output must be excluded"
done

assert_contains compose.yaml \
  "GILBOM_LLM_API_KEY_FILE: /run/secrets/gilbom_llm_api_key" \
  "LLM API key Docker Secret target"
assert_contains compose.yaml \
  "GILBOM_STORAGE_URL_SIGNING_KEY_BASE64_FILE: /run/secrets/gilbom_storage_signing_key" \
  "storage signing key Docker Secret target"
assert_contains compose.yaml \
  "SPRING_DATASOURCE_PASSWORD_FILE: /run/secrets/postgres_app_password" \
  "runtime database password file"
assert_contains compose.yaml \
  "SPRING_FLYWAY_PASSWORD_FILE: /run/secrets/postgres_flyway_password" \
  "Flyway database password file"
assert_contains compose.yaml \
  "MQTT_PASSWORD_FILE: /run/secrets/mqtt_backend_password" \
  "MQTT password file"

assert_contains compose.prod.yaml \
  "MQTT_PASSWORD_FILE: /run/secrets/mqtt_backend_password" \
  "production MQTT password file"
assert_contains compose.prod.yaml \
  "MQTT_TRUST_CERTIFICATE: /run/secrets/mqtt_ca_certificate" \
  "production MQTT CA file"
assert_contains compose.prod.yaml \
  "file: \${MOSQUITTO_PASSWORD_FILE_SECRET_FILE:" \
  "production Mosquitto password file Secret"
assert_contains compose.prod.yaml \
  "file: \${MOSQUITTO_TLS_PRIVATE_KEY_SECRET_FILE:" \
  "production Mosquitto TLS private key Secret"

for compose_file in compose.yaml compose.prod.yaml
do
  if grep -En \
    '^[[:space:]]+(POSTGRES_PASSWORD|POSTGRES_FLYWAY_PASSWORD|POSTGRES_APP_PASSWORD|SPRING_DATASOURCE_PASSWORD|SPRING_FLYWAY_PASSWORD|MQTT_PASSWORD|MQTT_ROBOT_PASSWORD|GILBOM_LLM_API_KEY|GILBOM_STORAGE_URL_SIGNING_KEY_BASE64|AUTH_RATE_LIMIT_HMAC_KEY_BASE64|MFA_TOTP_SECRET_KEYRING|INITIAL_ADMIN_PASSWORD):' \
    "$compose_file"; then
    fail "Compose must not pass direct Secret values (${compose_file})"
  fi
done

if git ls-files -z '*Dockerfile*' \
  | xargs -0 grep -Ein \
    '^[[:space:]]*(ARG|ENV)[[:space:]].*(PASSWORD|PRIVATE_KEY|SECRET|LLM_API_KEY|SIGNING_KEY)' \
    2>/dev/null; then
  fail "Dockerfile ARG/ENV must not carry a Secret"
fi

if grep -En \
  '^(POSTGRES_ADMIN_PASSWORD|POSTGRES_FLYWAY_PASSWORD|POSTGRES_APP_PASSWORD|MQTT_PASSWORD|MQTT_ROBOT_PASSWORD|GILBOM_LLM_API_KEY|GILBOM_STORAGE_URL_SIGNING_KEY_BASE64|AUTH_RATE_LIMIT_HMAC_KEY_BASE64|MFA_TOTP_SECRET_KEYRING|INITIAL_ADMIN_PASSWORD)=' \
  .env.prod.example; then
  fail ".env.prod.example must contain paths, not direct Secret values"
fi

frontend_environment_references="$(
  grep -Rho \
    'import\.meta\.env\.[A-Za-z_][A-Za-z0-9_]*' \
    frontend/gilbom/src 2>/dev/null \
    || true
)"
if [ -n "$frontend_environment_references" ] \
  && printf '%s\n' "$frontend_environment_references" \
    | grep -Ev '^import\.meta\.env\.VITE_[A-Z0-9_]+$'; then
  fail "Frontend source may read only public VITE_* variables"
fi

echo "Container Secret boundary verification: PASS"
