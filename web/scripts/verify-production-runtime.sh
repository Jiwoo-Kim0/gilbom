#!/bin/sh

set -eu
umask 077

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
PROJECT_NAME="gilbom-prod-runtime-check-$$"
DOCKER_BIND_WORK_ROOT="${DOCKER_BIND_WORK_ROOT:-.artifacts/docker-bind-work}"
case "$DOCKER_BIND_WORK_ROOT" in
  /*)
    ;;
  *)
    DOCKER_BIND_WORK_ROOT="$PROJECT_DIR/$DOCKER_BIND_WORK_ROOT"
    ;;
esac
mkdir -p "$DOCKER_BIND_WORK_ROOT"
chmod 0700 "$DOCKER_BIND_WORK_ROOT"
DOCKER_BIND_WORK_ROOT="$(CDPATH= cd -- "$DOCKER_BIND_WORK_ROOT" && pwd -P)"
CHECK_DIR="$(mktemp -d "$DOCKER_BIND_WORK_ROOT/production-runtime.XXXXXX")"
SECRET_DIR="$CHECK_DIR/secrets"
CERTBOT_DIR="$CHECK_DIR/certbot"
RUNTIME_ENV_FILE="$CHECK_DIR/runtime.env"
IMAGE_ENV_FILE="$CHECK_DIR/image.env"
COMPOSE_FILE=${COMPOSE_FILE:-compose.prod.yaml}
STARTED=false

compose() {
  docker compose \
    --project-name "$PROJECT_NAME" \
    --env-file "$RUNTIME_ENV_FILE" \
    --env-file "$IMAGE_ENV_FILE" \
    -f "$COMPOSE_FILE" \
    "$@"
}

cleanup() {
  if [ "$STARTED" = true ]; then
    compose down --volumes --remove-orphans >/dev/null 2>&1 || true
  fi
  rm -r "$CHECK_DIR" >/dev/null 2>&1 || true
}

fail() {
  echo "Production runtime verification failed: $1" >&2
  if [ "$STARTED" = true ]; then
    compose ps >&2 || true
    compose logs --tail 120 >&2 || true
  fi
  exit 1
}

trap cleanup EXIT HUP INT TERM
install -d -m 0700 "$SECRET_DIR"
install -d -m 0755 \
  "$CERTBOT_DIR/conf" \
  "$CERTBOT_DIR/www" \
  "$CERTBOT_DIR/nginx"

openssl rand -hex -out "$SECRET_DIR/postgres_admin_password" 32
openssl rand -hex -out "$SECRET_DIR/postgres_flyway_password" 32
openssl rand -hex -out "$SECRET_DIR/postgres_app_password" 32
openssl rand -hex -out "$SECRET_DIR/mqtt_backend_password" 32
openssl rand -base64 -out "$SECRET_DIR/gilbom_storage_signing_key" 48
openssl rand -base64 -out "$SECRET_DIR/gilbom_auth_rate_limit_hmac_key" 48
openssl rand -base64 -out "$SECRET_DIR/gilbom_mfa_totp_keyring" 32
sed -i '1s/^/1=/' "$SECRET_DIR/gilbom_mfa_totp_keyring"
install -m 0400 /dev/null "$SECRET_DIR/gilbom_llm_api_key"
install -m 0400 /dev/null "$SECRET_DIR/gilbom_initial_admin_password"

openssl req \
  -x509 \
  -newkey rsa:2048 \
  -sha256 \
  -nodes \
  -days 1 \
  -subj /CN=mosquitto \
  -addext subjectAltName=DNS:mosquitto,DNS:localhost,IP:127.0.0.1 \
  -addext basicConstraints=critical,CA:TRUE \
  -keyout "$SECRET_DIR/mosquitto_tls_private_key.pem" \
  -out "$SECRET_DIR/mosquitto_tls_fullchain.pem" \
  >/dev/null 2>&1
cp "$SECRET_DIR/mosquitto_tls_fullchain.pem" \
  "$SECRET_DIR/mqtt_ca_certificate.pem"

openssl req \
  -x509 \
  -newkey rsa:2048 \
  -sha256 \
  -nodes \
  -days 1 \
  -subj /CN=localhost \
  -addext subjectAltName=DNS:localhost,IP:127.0.0.1 \
  -keyout "$CERTBOT_DIR/nginx/privkey.pem" \
  -out "$CERTBOT_DIR/nginx/fullchain.pem" \
  >/dev/null 2>&1

docker run --rm \
  --user "$(id -u):$(id -g)" \
  --entrypoint /bin/sh \
  --mount "type=bind,src=$SECRET_DIR,dst=/work" \
  eclipse-mosquitto:2.1.2-alpine \
  -ec '
    mqtt_password="$(cat /work/mqtt_backend_password)"
    mosquitto_passwd -b -c /work/mosquitto_passwd gilbom "$mqtt_password"
    unset mqtt_password
  '

chmod 0444 "$SECRET_DIR"/*
chmod 0444 \
  "$CERTBOT_DIR/nginx/fullchain.pem" \
  "$CERTBOT_DIR/nginx/privkey.pem"

cp .env.prod.example "$RUNTIME_ENV_FILE"
sed -i \
  -e "s|^COMPOSE_PROJECT_NAME=.*|COMPOSE_PROJECT_NAME=$PROJECT_NAME|" \
  -e 's|^FRONTEND_BIND_ADDRESS=.*|FRONTEND_BIND_ADDRESS=127.0.0.1|' \
  -e 's|^FRONTEND_HTTP_PORT=.*|FRONTEND_HTTP_PORT=0|' \
  -e 's|^FRONTEND_HTTPS_PORT=.*|FRONTEND_HTTPS_PORT=0|' \
  -e 's|^CERTBOT_EMAIL=.*|CERTBOT_EMAIL=runtime-check@example.invalid|' \
  -e "s|^CERTBOT_CONF_DIR=.*|CERTBOT_CONF_DIR=$CERTBOT_DIR/conf|" \
  -e "s|^CERTBOT_WWW_DIR=.*|CERTBOT_WWW_DIR=$CERTBOT_DIR/www|" \
  -e "s|^NGINX_CERT_DIR=.*|NGINX_CERT_DIR=$CERTBOT_DIR/nginx|" \
  -e 's|^MQTT_BIND_ADDRESS=.*|MQTT_BIND_ADDRESS=127.0.0.1|' \
  -e 's|^MQTT_TLS_PORT=.*|MQTT_TLS_PORT=0|' \
  -e 's|^MQTT_TLS_SERVER_NAME=.*|MQTT_TLS_SERVER_NAME=mosquitto|' \
  -e 's|^VITE_NAVER_MAP_CLIENT_ID=.*|VITE_NAVER_MAP_CLIENT_ID=runtime-public-id|' \
  -e 's|^MQTT_PUBLIC_BROKER_URI=.*|MQTT_PUBLIC_BROKER_URI=ssl://mqtt.example.invalid:8883|' \
  -e "s|^POSTGRES_ADMIN_PASSWORD_SECRET_FILE=.*|POSTGRES_ADMIN_PASSWORD_SECRET_FILE=$SECRET_DIR/postgres_admin_password|" \
  -e "s|^POSTGRES_FLYWAY_PASSWORD_SECRET_FILE=.*|POSTGRES_FLYWAY_PASSWORD_SECRET_FILE=$SECRET_DIR/postgres_flyway_password|" \
  -e "s|^POSTGRES_APP_PASSWORD_SECRET_FILE=.*|POSTGRES_APP_PASSWORD_SECRET_FILE=$SECRET_DIR/postgres_app_password|" \
  -e "s|^MQTT_PASSWORD_SECRET_FILE=.*|MQTT_PASSWORD_SECRET_FILE=$SECRET_DIR/mqtt_backend_password|" \
  -e "s|^MQTT_CA_CERTIFICATE_FILE=.*|MQTT_CA_CERTIFICATE_FILE=$SECRET_DIR/mqtt_ca_certificate.pem|" \
  -e "s|^MOSQUITTO_PASSWORD_FILE_SECRET_FILE=.*|MOSQUITTO_PASSWORD_FILE_SECRET_FILE=$SECRET_DIR/mosquitto_passwd|" \
  -e "s|^MOSQUITTO_TLS_FULLCHAIN_FILE=.*|MOSQUITTO_TLS_FULLCHAIN_FILE=$SECRET_DIR/mosquitto_tls_fullchain.pem|" \
  -e "s|^MOSQUITTO_TLS_PRIVATE_KEY_SECRET_FILE=.*|MOSQUITTO_TLS_PRIVATE_KEY_SECRET_FILE=$SECRET_DIR/mosquitto_tls_private_key.pem|" \
  -e "s|^GILBOM_STORAGE_SIGNING_KEY_SECRET_FILE=.*|GILBOM_STORAGE_SIGNING_KEY_SECRET_FILE=$SECRET_DIR/gilbom_storage_signing_key|" \
  -e "s|^AUTH_RATE_LIMIT_HMAC_KEY_SECRET_FILE=.*|AUTH_RATE_LIMIT_HMAC_KEY_SECRET_FILE=$SECRET_DIR/gilbom_auth_rate_limit_hmac_key|" \
  -e "s|^MFA_TOTP_KEYRING_SECRET_FILE=.*|MFA_TOTP_KEYRING_SECRET_FILE=$SECRET_DIR/gilbom_mfa_totp_keyring|" \
  -e "s|^INITIAL_ADMIN_PASSWORD_SECRET_FILE=.*|INITIAL_ADMIN_PASSWORD_SECRET_FILE=$SECRET_DIR/gilbom_initial_admin_password|" \
  -e "s|^GILBOM_LLM_API_KEY_SECRET_FILE=.*|GILBOM_LLM_API_KEY_SECRET_FILE=$SECRET_DIR/gilbom_llm_api_key|" \
  "$RUNTIME_ENV_FILE"

grep -E '^(BACKEND_IMAGE|FRONTEND_IMAGE)=' \
  "$RUNTIME_ENV_FILE" > "$IMAGE_ENV_FILE"
sed -i \
  -e '/^BACKEND_IMAGE=/d' \
  -e '/^FRONTEND_IMAGE=/d' \
  "$RUNTIME_ENV_FILE"

STARTED=true
compose config --quiet
compose up --detach --no-build --wait --wait-timeout 240 \
  || fail "stack did not become healthy"

db_id="$(compose ps -q db)"
mqtt_id="$(compose ps -q mosquitto)"
backend_id="$(compose ps -q backend)"
frontend_id="$(compose ps -q frontend)"

for id in "$db_id" "$mqtt_id" "$backend_id" "$frontend_id"
do
  [ -n "$id" ] || fail "a required service container is missing"
  [ "$(docker inspect --format '{{.State.Health.Status}}' "$id")" = healthy ] \
    || fail "a required service is not healthy"
done

docker exec "$backend_id" /bin/sh -ec '
  for secret_file in \
    /run/secrets/postgres_app_password \
    /run/secrets/postgres_flyway_password \
    /run/secrets/mqtt_backend_password \
    /run/secrets/gilbom_storage_signing_key
  do
    [ -r "$secret_file" ]
    [ ! -w "$secret_file" ]
  done
'
docker exec "$mqtt_id" /bin/sh -ec '
  for secret_file in \
    /run/secrets/mosquitto_tls_fullchain \
    /run/secrets/mosquitto_tls_private_key \
    /run/secrets/mqtt_ca_certificate \
    /run/secrets/mqtt_backend_password
  do
    [ -r "$secret_file" ]
    [ ! -w "$secret_file" ]
  done
'

# 빈 volume에 적용된 PostGIS, Flyway V1~V37, 승인된 시스템 seed와
# runtime DB role의 최소 권한을 실제 PostgreSQL parser/권한으로 검증한다.
compose exec -T db /bin/sh -ec '
  export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"
  exec psql \
    --host 127.0.0.1 \
    --username "$POSTGRES_USER" \
    --dbname "$POSTGRES_DB" \
    --no-password \
    --set "flyway_role=$POSTGRES_FLYWAY_USER" \
    --set "application_role=$POSTGRES_APP_USER"
' < infra/postgres/verify-empty-database.sql \
  || fail "empty database migration contract failed"
compose exec -T db /bin/sh -ec '
  export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"
  exec psql \
    --host 127.0.0.1 \
    --username "$POSTGRES_USER" \
    --dbname "$POSTGRES_DB" \
    --no-password \
    --set "flyway_role=$POSTGRES_FLYWAY_USER" \
    --set "application_role=$POSTGRES_APP_USER"
' < infra/postgres/verify-production-database.sql \
  || fail "production database seed or role contract failed"

credential_checksum_before="$(
  docker exec "$backend_id" \
    sha256sum /app/config/mqtt/credentials/passwd \
    | awk '{print $1}'
)"
compose run --rm --no-deps mosquitto-config-init >/dev/null
credential_checksum_after="$(
  docker exec "$backend_id" \
    sha256sum /app/config/mqtt/credentials/passwd \
    | awk '{print $1}'
)"
[ "$credential_checksum_before" = "$credential_checksum_after" ] \
  || fail "config init overwrote the existing MQTT credential registry"

published_bindings() {
  docker inspect --format \
    '{{range $port, $bindings := .HostConfig.PortBindings}}{{if $bindings}}{{println $port}}{{end}}{{end}}' \
    "$1"
}

[ -z "$(published_bindings "$db_id")" ] \
  || fail "PostgreSQL unexpectedly publishes a host port"
[ -z "$(published_bindings "$backend_id")" ] \
  || fail "Backend unexpectedly publishes a host port"
frontend_port="$(compose port frontend 8080)"
mqtt_port="$(compose port mosquitto 8883)"
[ -n "$frontend_port" ] || fail "Frontend host port is missing"
[ -n "$mqtt_port" ] || fail "MQTT TLS host port is missing"

docker exec "$frontend_id" \
  wget \
    --quiet \
    --no-check-certificate \
    --output-document=- \
    https://127.0.0.1:8443/healthz \
  | grep -Fxq ok \
  || fail "Frontend /healthz failed"
docker exec "$frontend_id" \
  wget \
    --quiet \
    --no-check-certificate \
    --output-document=- \
    https://127.0.0.1:8443/api/health \
  | grep -Fq '"status":"UP"' \
  || fail "Frontend to Backend API proxy failed"

# 운영 Runbook의 실제 backup 명령도 같은 임시 production-only stack에서
# 검증한다. bundle은 CHECK_DIR 아래에만 만들고 종료 시 volume과 함께 제거한다.
BACKUP_ROOT="$CHECK_DIR/backups" \
RUNTIME_ENV_FILE="$RUNTIME_ENV_FILE" \
IMAGE_ENV_FILE="$IMAGE_ENV_FILE" \
COMPOSE_FILE="$COMPOSE_FILE" \
  sh infra/scripts/backup-volumes.sh \
  || fail "production DB/volume backup workflow failed"

# stop/start 뒤 임의 publish port가 바뀔 수 있으므로 stale binding과 ID를
# 사용하지 않고 현재 runtime 상태를 다시 조회한다.
db_id="$(compose ps -q db)"
mqtt_id="$(compose ps -q mosquitto)"
backend_id="$(compose ps -q backend)"
frontend_id="$(compose ps -q frontend)"
frontend_port="$(compose port frontend 8080)"
mqtt_port="$(compose port mosquitto 8883)"

BACKEND_CONTAINER="$backend_id" \
FRONTEND_CONTAINER="$frontend_id" \
SECRET_SCAN_DIR="$SECRET_DIR" \
IMAGE_VERIFY_MODE=logs \
  sh scripts/verify-production-images.sh \
  || fail "production container log boundary failed"

mqtt_host_port="${mqtt_port##*:}"
openssl s_client \
  -connect "127.0.0.1:$mqtt_host_port" \
  -servername mosquitto \
  -CAfile "$SECRET_DIR/mqtt_ca_certificate.pem" \
  -verify_return_error \
  </dev/null 2>&1 \
  | grep -Fq 'Verify return code: 0 (ok)' \
  || fail "published MQTT TLS handshake failed"

BACKEND_CONTAINER="$backend_id" \
DB_CONTAINER="$db_id" \
MQTT_CONTAINER="$mqtt_id" \
  sh scripts/verify-backend-health-policy.sh \
  || fail "Backend health transition policy failed"

backend_started="$(
  docker inspect --format '{{.State.StartedAt}}' "$backend_id"
)"
frontend_started="$(
  docker inspect --format '{{.State.StartedAt}}' "$frontend_id"
)"
first_started="$(
  printf '%s\n%s\n' "$backend_started" "$frontend_started" \
    | sort \
    | head -n 1
)"
[ "$backend_started" != "$frontend_started" ] \
  && [ "$first_started" = "$backend_started" ] \
  || fail "Frontend started before Backend"

BACKEND_CONTAINER="$backend_id" \
DB_CONTAINER="$db_id" \
  sh scripts/verify-backend-graceful-shutdown.sh \
  || fail "Backend graceful shutdown failed"

echo "Production runtime verification: PASS"
echo "Frontend=${frontend_port} MQTT_TLS=${mqtt_port}"
