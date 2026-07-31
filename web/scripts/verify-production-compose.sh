#!/bin/sh

set -eu

COMPOSE_FILE=${COMPOSE_FILE:-compose.prod.yaml}
ENV_FILE=${ENV_FILE:-.env.prod.example}

fail() {
  echo "Production Compose verification failed: $1" >&2
  exit 1
}

assert_contains() {
  file=$1
  expected=$2
  label=$3

  grep -Fq "$expected" "$file" || fail "$label"
}

assert_not_contains() {
  file=$1
  unexpected=$2
  label=$3

  if grep -Fq "$unexpected" "$file"; then
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
fi

compose() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

compose config --quiet
compose config > "$CHECK_DIR/full.yaml"

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
db
frontend
mosquitto
mosquitto-config-init'
actual_services="$(compose config --services | sort)"
[ "$actual_services" = "$expected_services" ] \
  || fail "service set differs from the production contract"

for service in backend db frontend mosquitto mosquitto-config-init
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

published_port_count="$(
  grep -c 'published:' "$CHECK_DIR/full.yaml" || true
)"
[ "$published_port_count" -eq 2 ] \
  || fail "only Frontend HTTP and MQTT TLS may publish ports"
assert_contains "$CHECK_DIR/frontend.yaml" "target: 8080" \
  "Frontend must publish Nginx port 8080"
assert_contains "$CHECK_DIR/mosquitto.yaml" "target: 8883" \
  "Mosquitto must publish only TLS port 8883"
assert_contains "$CHECK_DIR/mosquitto.yaml" "MQTT_TLS_SERVER_NAME:" \
  "Mosquitto health and data-network alias need a TLS server name"

assert_contains "$CHECK_DIR/backend.yaml" "condition: service_healthy" \
  "Backend must wait for healthy dependencies"
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
done

assert_contains "$CHECK_DIR/backend.yaml" "target: /run/secrets/" \
  "Backend Secrets must mount below /run/secrets"
assert_contains "$CHECK_DIR/db.yaml" "target: /run/secrets/" \
  "DB Secrets must mount below /run/secrets"
assert_contains "$CHECK_DIR/mosquitto.yaml" "target: /run/secrets/" \
  "Mosquitto Secrets must mount below /run/secrets"

echo "Production Compose contract verification: PASS"
