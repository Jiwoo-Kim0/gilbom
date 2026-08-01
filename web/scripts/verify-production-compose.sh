#!/bin/sh

set -eu

COMPOSE_FILE=${COMPOSE_FILE:-compose.prod.yaml}
BUILD_COMPOSE_FILE=${BUILD_COMPOSE_FILE:-compose.build.yaml}
ENV_FILE=${ENV_FILE:-}
RUNTIME_ENV_FILE=${RUNTIME_ENV_FILE:-}
IMAGE_ENV_FILE=${IMAGE_ENV_FILE:-}

if [ -z "$ENV_FILE" ] \
    && [ -z "$RUNTIME_ENV_FILE" ] \
    && [ -z "$IMAGE_ENV_FILE" ]; then
  ENV_FILE=.env.prod.example
fi

fail() {
  echo "Production Compose verification failed: $1" >&2
  exit 1
}

assert_contains() {
  file=$1
  expected=$2
  label=$3

  grep -Fq -- "$expected" "$file" || fail "$label"
}

assert_not_contains() {
  file=$1
  unexpected=$2
  label=$3

  if grep -Fq -- "$unexpected" "$file"; then
    fail "$label"
  fi
}

CHECK_DIR="$(mktemp -d)"
trap 'rm -r "$CHECK_DIR" >/dev/null 2>&1 || true' EXIT HUP INT TERM

# Tracked example의 의도적인 빈 공개 필수값만 contract 검증용 값으로 채운다.
# 운영 .env.prod를 지정한 경우에는 누락을 감추지 않고 Compose가 실패하게 둔다.
if [ "$ENV_FILE" = ".env.prod.example" ]; then
  export VITE_NAVER_MAP_CLIENT_ID="${VITE_NAVER_MAP_CLIENT_ID:-production-compose-check}"
  export MQTT_PUBLIC_BROKER_URI="${MQTT_PUBLIC_BROKER_URI:-ssl://mqtt.example.invalid:8883}"
  export MQTT_TLS_SERVER_NAME="${MQTT_TLS_SERVER_NAME:-mqtt.example.invalid}"
  export CERTBOT_EMAIL="${CERTBOT_EMAIL:-production-compose@example.invalid}"
fi

compose() {
  if [ -n "$ENV_FILE" ]; then
    docker compose \
      --env-file "$ENV_FILE" \
      -f "$COMPOSE_FILE" \
      "$@"
  else
    [ -n "$RUNTIME_ENV_FILE" ] \
      || fail "RUNTIME_ENV_FILE is required when ENV_FILE is not used"
    [ -n "$IMAGE_ENV_FILE" ] \
      || fail "IMAGE_ENV_FILE is required when ENV_FILE is not used"
    docker compose \
      --env-file "$RUNTIME_ENV_FILE" \
      --env-file "$IMAGE_ENV_FILE" \
      -f "$COMPOSE_FILE" \
      "$@"
  fi
}

build_compose() {
  if [ -n "$ENV_FILE" ]; then
    docker compose \
      --env-file "$ENV_FILE" \
      -f "$BUILD_COMPOSE_FILE" \
      "$@"
  else
    docker compose \
      --env-file "$RUNTIME_ENV_FILE" \
      --env-file "$IMAGE_ENV_FILE" \
      -f "$BUILD_COMPOSE_FILE" \
      "$@"
  fi
}

compose config --quiet
compose --profile certificate config --quiet
compose --profile certificate config > "$CHECK_DIR/full.yaml"
build_compose config --quiet
build_compose config > "$CHECK_DIR/build.yaml"

# LLM 비활성 배포는 실제 API key file 없이도 Compose가 해석되어야 한다.
# /dev/null은 빈 read-only file mount 역할만 하며 image ENV에는 값이 들어가지 않는다.
GILBOM_LLM_API_KEY_SECRET_FILE=/dev/null \
  compose config > "$CHECK_DIR/no-llm-secret.yaml"
assert_contains "$CHECK_DIR/no-llm-secret.yaml" "file: /dev/null" \
  "disabled LLM deployment must resolve without a real API key file"

extract_service() {
  service=$1
  awk -v service="$service" '
    $0 == "  " service ":" {
      in_service = 1
      next
    }
    in_service && /^  [a-zA-Z0-9_.-]+:$/ {
      exit
    }
    in_service {
      print
    }
  ' "$CHECK_DIR/full.yaml"
}

expected_services='backend
certbot
db
frontend
mosquitto
mosquitto-config-init'
actual_services="$(
  compose \
    --profile certificate \
    config --services \
    | sort
)"
[ "$actual_services" = "$expected_services" ] \
  || fail "service set differs from the production contract"

for service in backend certbot db frontend mosquitto mosquitto-config-init
do
  extract_service "$service" > "$CHECK_DIR/$service.yaml"
  [ -s "$CHECK_DIR/$service.yaml" ] \
    || fail "could not extract service $service from canonical Compose"
done

assert_not_contains "$CHECK_DIR/backend.yaml" "    ports:" \
  "Backend must not publish a host port"
assert_not_contains "$CHECK_DIR/db.yaml" "    ports:" \
  "PostgreSQL must not publish a host port"
assert_not_contains "$CHECK_DIR/mosquitto-config-init.yaml" "    ports:" \
  "Mosquitto init must not publish a host port"
assert_not_contains "$CHECK_DIR/certbot.yaml" "    ports:" \
  "Certbot must not publish a host port"
assert_not_contains "$CHECK_DIR/backend.yaml" "    build:" \
  "Backend must not build on the production host"
assert_not_contains "$CHECK_DIR/frontend.yaml" "    build:" \
  "Frontend must not build on the production host"
assert_contains "$CHECK_DIR/build.yaml" "build:" \
  "CI build Compose must declare application build contexts"

published_port_count="$(
  grep -c 'published:' "$CHECK_DIR/full.yaml" || true
)"
[ "$published_port_count" -eq 3 ] \
  || fail "only Frontend HTTP/HTTPS and MQTT TLS may publish ports"
assert_contains "$CHECK_DIR/frontend.yaml" "target: 8080" \
  "Frontend must publish Nginx port 8080"
assert_contains "$CHECK_DIR/frontend.yaml" "target: 8443" \
  "Frontend must publish Nginx TLS port 8443"
assert_contains "$CHECK_DIR/mosquitto.yaml" "target: 8883" \
  "Mosquitto must publish only TLS port 8883"
assert_contains "$CHECK_DIR/mosquitto.yaml" "MQTT_TLS_SERVER_NAME:" \
  "Mosquitto health and data-network alias need a TLS server name"

assert_contains "$CHECK_DIR/backend.yaml" "condition: service_healthy" \
  "Backend must wait for healthy dependencies"
assert_contains "$CHECK_DIR/backend.yaml" \
  "GILBOM_HEALTH_READINESS_COMPONENTS: readinessState,db,flywayReadiness,storageReadiness" \
  "Backend readiness must include DB, Flyway, and writable storage"
for llm_runtime_setting in \
  GILBOM_LLM_CONNECT_TIMEOUT \
  GILBOM_LLM_READ_TIMEOUT \
  GILBOM_LLM_MAX_OUTPUT_TOKENS \
  GILBOM_LLM_DAILY_REQUEST_LIMIT \
  GILBOM_LLM_DAILY_BUDGET \
  GILBOM_LLM_MAX_CONCURRENT_REQUESTS \
  GILBOM_LLM_PROMPT_VERSION
do
  assert_contains "$CHECK_DIR/backend.yaml" "$llm_runtime_setting:" \
    "Backend must receive $llm_runtime_setting at runtime"
done
healthy_dependency_count="$(
  grep -c 'condition: service_healthy' "$CHECK_DIR/backend.yaml" || true
)"
[ "$healthy_dependency_count" -eq 2 ] \
  || fail "Backend must wait for both DB and MQTT health"
assert_contains "$CHECK_DIR/frontend.yaml" "condition: service_healthy" \
  "Frontend must wait for Backend readiness"
assert_contains "$CHECK_DIR/mosquitto.yaml" \
  "condition: service_completed_successfully" \
  "Mosquitto must wait for config initialization"
assert_contains "$CHECK_DIR/certbot.yaml" "certificate" \
  "Certbot must be isolated behind the certificate profile"
assert_contains "$CHECK_DIR/certbot.yaml" "image: certbot/certbot:v5.7.0" \
  "Certbot image must use the validated fixed version"
assert_contains "$CHECK_DIR/frontend.yaml" "target: /var/www/certbot" \
  "Frontend must mount the ACME webroot"
assert_contains "$CHECK_DIR/frontend.yaml" "target: /etc/nginx/certs" \
  "Frontend must mount the Nginx certificate copy"
assert_contains "$CHECK_DIR/frontend.yaml" "read_only: true" \
  "Frontend certificate mounts must be read-only"
assert_contains "$CHECK_DIR/certbot.yaml" "target: /etc/letsencrypt" \
  "Certbot must persist its certificate state"

for network in edge app data llm-egress
do
  compose config --networks | grep -Fxq "$network" \
    || fail "network $network is missing"
done
assert_contains "$CHECK_DIR/full.yaml" "internal: true" \
  "app and data networks must be internal"
internal_network_count="$(
  grep -c 'internal: true' "$CHECK_DIR/full.yaml" || true
)"
[ "$internal_network_count" -eq 2 ] \
  || fail "exactly app and data networks must be internal"

for volume in \
  postgres_data \
  mosquitto_config \
  mosquitto_credentials \
  mosquitto_data \
  report_pdf_data \
  robot_images
do
  compose config --volumes | grep -Fxq "$volume" \
    || fail "named volume $volume is missing"
done

for service in backend frontend mosquitto
do
  assert_contains "$CHECK_DIR/$service.yaml" "restart: unless-stopped" \
    "$service restart policy"
  assert_contains "$CHECK_DIR/$service.yaml" "mem_limit:" \
    "$service memory ceiling"
  assert_contains "$CHECK_DIR/$service.yaml" "cpus:" \
    "$service CPU ceiling"
  assert_contains "$CHECK_DIR/$service.yaml" "pids_limit:" \
    "$service PID ceiling"
  assert_contains "$CHECK_DIR/$service.yaml" "max-size: 10m" \
    "$service log size ceiling"
  assert_contains "$CHECK_DIR/$service.yaml" 'max-file: "3"' \
    "$service rotated log count"
  assert_contains "$CHECK_DIR/$service.yaml" "stop_grace_period:" \
    "$service graceful stop window"
  assert_contains "$CHECK_DIR/$service.yaml" "read_only: true" \
    "$service root filesystem must be read-only"
  assert_contains "$CHECK_DIR/$service.yaml" "cap_drop:" \
    "$service capabilities must be declared"
  assert_contains "$CHECK_DIR/$service.yaml" "- ALL" \
    "$service must drop all Linux capabilities"
  assert_contains "$CHECK_DIR/$service.yaml" \
    "no-new-privileges:true" \
    "$service must prevent privilege escalation"
  assert_not_contains "$CHECK_DIR/$service.yaml" "privileged: true" \
    "$service must not run privileged"
done

assert_contains "$CHECK_DIR/backend.yaml" "target: /run/secrets/" \
  "Backend Secrets must mount below /run/secrets"
assert_contains "$CHECK_DIR/db.yaml" "target: /run/secrets/" \
  "DB Secrets must mount below /run/secrets"
assert_contains "$CHECK_DIR/mosquitto.yaml" "target: /run/secrets/" \
  "Mosquitto Secrets must mount below /run/secrets"

echo "Production Compose contract verification: PASS"
