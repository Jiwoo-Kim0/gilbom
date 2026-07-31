#!/bin/sh

set -eu

IMAGE_NAME=${1:-gilbom-frontend:local}
CHECK_PREFIX="gilbom-frontend-runtime-check-$$"
NETWORK_NAME="${CHECK_PREFIX}-network"
BACKEND_CONTAINER="${CHECK_PREFIX}-backend"
FRONTEND_CONTAINER="${CHECK_PREFIX}-frontend"
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
MOCK_BACKEND_CONFIG="${SCRIPT_DIR}/fixtures/frontend-mock-backend.conf"

fail() {
  echo "Frontend runtime image verification failed: $1" >&2
  docker logs "$FRONTEND_CONTAINER" >&2 2>&1 || true
  exit 1
}

cleanup() {
  docker rm --force \
    "$FRONTEND_CONTAINER" \
    "$BACKEND_CONTAINER" >/dev/null 2>&1 || true
  docker network rm "$NETWORK_NAME" >/dev/null 2>&1 || true
}

trap cleanup EXIT HUP INT TERM

[ -f "$MOCK_BACKEND_CONFIG" ] \
  || fail "mock Backend configuration is missing"

image_user="$(docker image inspect \
  --format '{{.Config.User}}' "$IMAGE_NAME")"
[ "$image_user" = "nginx" ] \
  || fail "image USER must be nginx"

stop_signal="$(docker image inspect \
  --format '{{.Config.StopSignal}}' "$IMAGE_NAME")"
[ "$stop_signal" = "SIGQUIT" ] \
  || fail "image STOPSIGNAL must be SIGQUIT"

exposed_ports="$(docker image inspect \
  --format '{{json .Config.ExposedPorts}}' "$IMAGE_NAME")"
printf '%s\n' "$exposed_ports" | grep -Fq '"8080/tcp"' \
  || fail "image must expose unprivileged port 8080"

healthcheck="$(docker image inspect \
  --format '{{json .Config.Healthcheck.Test}}' "$IMAGE_NAME")"
printf '%s\n' "$healthcheck" | grep -Fq '/healthz' \
  || fail "image healthcheck must use /healthz"

image_environment="$(docker image inspect \
  --format '{{range .Config.Env}}{{println .}}{{end}}' "$IMAGE_NAME")"
if printf '%s\n' "$image_environment" \
    | grep -Eiq '(PASSWORD|SECRET|TOKEN|API_KEY|SIGNING_KEY)='; then
  fail "image environment contains a Secret-like variable"
fi

docker network create "$NETWORK_NAME" >/dev/null
docker run --detach \
  --name "$BACKEND_CONTAINER" \
  --network "$NETWORK_NAME" \
  --network-alias backend \
  --mount "type=bind,src=${MOCK_BACKEND_CONFIG},dst=/etc/nginx/conf.d/default.conf,readonly" \
  nginx:1.30.4-alpine >/dev/null

docker run --detach \
  --name "$FRONTEND_CONTAINER" \
  --network "$NETWORK_NAME" \
  --read-only \
  --cap-drop ALL \
  --security-opt no-new-privileges \
  --tmpfs /tmp:rw,noexec,nosuid,nodev,size=32m,mode=1777 \
  "$IMAGE_NAME" >/dev/null

attempt=0
until docker exec "$FRONTEND_CONTAINER" \
    wget --quiet --spider http://127.0.0.1:8080/healthz
do
  attempt=$((attempt + 1))
  [ "$attempt" -lt 30 ] \
    || fail "Nginx did not become ready"
  sleep 1
done

docker exec "$FRONTEND_CONTAINER" /bin/sh -ec '
  [ "$(id -u)" = "101" ]
  [ "$(id -g)" = "101" ]
  grep -Eq "^CapEff:[[:space:]]+0{16}$" /proc/self/status
  grep -Eq "^NoNewPrivs:[[:space:]]+1$" /proc/self/status

  command -v nginx >/dev/null
  command -v wget >/dev/null
  for forbidden_tool in node npm npx yarn pnpm tsc vite git
  do
    ! command -v "$forbidden_tool" >/dev/null 2>&1
  done

  [ ! -e /app ]
  [ ! -e /usr/src/app ]
  ! find /usr/share/nginx/html \
      \( -type d -name node_modules \
      -o -type f \( -name "*.map" -o -name "*.ts" -o -name "*.tsx" \
      -o -name "*.test.*" -o -name "*.spec.*" \) \) \
      -print -quit | grep -q .

  if touch /usr/share/nginx/html/root-write-check 2>/dev/null; then
    echo "read-only static root accepted a write" >&2
    exit 1
  fi
  printf "runtime-check\n" > /tmp/runtime-check
  [ -s /tmp/runtime-check ]

  secret_pattern="(AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|glpat-[A-Za-z0-9_-]{20,}|github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9_-]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|-----BEGIN( [A-Z0-9]+)? PRIVATE KEY-----)"
  property_pattern="(GILBOM_LLM_API_KEY|POSTGRES_PASSWORD|MQTT_PASSWORD|MFA_TOTP_SECRET_KEYRING|INITIAL_ADMIN_PASSWORD|AUTH_RATE_LIMIT_HMAC_KEY)"
  if find /usr/share/nginx/html -type f \
      \( -name "*.html" -o -name "*.js" -o -name "*.css" \
      -o -name "*.json" -o -name "*.svg" -o -name "*.txt" \) \
      -exec grep -El -- "$secret_pattern|$property_pattern" {} + \
      | grep -q .; then
    echo "Secret-like string found in the static bundle" >&2
    exit 1
  fi

  nginx -T 2>&1 | grep -Fq "proxy_buffering off;"
  nginx -T 2>&1 | grep -Fq "proxy_request_buffering off;"
  nginx -T 2>&1 | grep -Fq "proxy_cache off;"
  nginx -T 2>&1 | grep -Fq "proxy_read_timeout 1h;"
  nginx -T 2>&1 | grep -Fq "worker_processes 2;"
'

health_body="$(docker exec "$FRONTEND_CONTAINER" \
  wget --quiet --output-document=- \
  http://127.0.0.1:8080/healthz)"
[ "$health_body" = "ok" ] \
  || fail "/healthz did not return the expected body"

index_headers="$(docker exec "$FRONTEND_CONTAINER" \
  wget --server-response --spider \
  http://127.0.0.1:8080/index.html 2>&1)"
printf '%s\n' "$index_headers" \
  | grep -Eiq 'Cache-Control:[[:space:]]*no-cache' \
  || fail "index.html must be revalidated"

asset_path="$(docker exec "$FRONTEND_CONTAINER" \
  find /usr/share/nginx/html/assets -type f -name '*.js' -print \
  | head -n 1)"
[ -n "$asset_path" ] \
  || fail "hashed JavaScript asset is missing"
asset_url="${asset_path#/usr/share/nginx/html}"
asset_headers="$(docker exec "$FRONTEND_CONTAINER" \
  wget --server-response --spider \
  "http://127.0.0.1:8080${asset_url}" 2>&1)"
printf '%s\n' "$asset_headers" \
  | grep -Eiq 'Cache-Control:[[:space:]]*max-age=[0-9]{8,}' \
  || fail "hashed asset must use a long cache lifetime"

docker exec "$FRONTEND_CONTAINER" \
  wget --quiet --output-document=- \
  http://127.0.0.1:8080/robots \
  | grep -Fq '<div id="root"></div>' \
  || fail "BrowserRouter fallback did not return index.html"

api_headers="$(docker exec "$FRONTEND_CONTAINER" \
  wget --server-response --spider \
  http://127.0.0.1:8080/api/runtime-check 2>&1)"
printf '%s\n' "$api_headers" \
  | grep -Eiq 'X-Mock-Upstream:[[:space:]]*backend' \
  || fail "/api was not proxied to Backend"
api_body="$(docker exec "$FRONTEND_CONTAINER" \
  wget --quiet --output-document=- \
  http://127.0.0.1:8080/api/runtime-check)"
[ "$api_body" = '{"status":"UP"}' ] \
  || fail "/api proxy response body changed"

sse_headers="$(docker exec "$FRONTEND_CONTAINER" \
  wget --server-response --spider \
  http://127.0.0.1:8080/api/v1/stream/runtime-check 2>&1)"
printf '%s\n' "$sse_headers" \
  | grep -Eiq 'Content-Type:[[:space:]]*text/event-stream' \
  || fail "SSE content type was not preserved"
printf '%s\n' "$sse_headers" \
  | grep -Eiq 'Cache-Control:[[:space:]]*no-cache' \
  || fail "SSE response must use Cache-Control: no-cache"
sse_body="$(docker exec "$FRONTEND_CONTAINER" \
  wget --quiet --output-document=- \
  http://127.0.0.1:8080/api/v1/stream/runtime-check)"
printf '%s\n' "$sse_body" | grep -Fq 'event: runtime-check' \
  || fail "SSE response was not proxied"

image_size="$(docker image inspect \
  --format '{{.Size}}' "$IMAGE_NAME")"
echo "Frontend runtime image verification: PASS (bytes=${image_size})"
