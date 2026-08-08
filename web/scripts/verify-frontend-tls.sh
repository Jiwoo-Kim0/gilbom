#!/bin/sh

set -eu
umask 077

IMAGE_NAME=${1:-gilbom-frontend:local}
CHECK_PREFIX="gilbom-frontend-tls-check-$$"
NETWORK_NAME="${CHECK_PREFIX}-network"
BACKEND_CONTAINER="${CHECK_PREFIX}-backend"
FRONTEND_CONTAINER="${CHECK_PREFIX}-frontend"
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
MOCK_BACKEND_CONFIG="${SCRIPT_DIR}/fixtures/frontend-mock-backend.conf"
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
CHECK_DIR="$(mktemp -d "$DOCKER_BIND_WORK_ROOT/frontend-tls.XXXXXX")"
CERT_DIR="$CHECK_DIR/certs"
WEBROOT_DIR="$CHECK_DIR/www"
TOKEN=gilbom-acme-runtime-check

fail() {
  echo "Frontend TLS verification failed: $1" >&2
  docker logs "$FRONTEND_CONTAINER" >&2 2>&1 || true
  exit 1
}

cleanup() {
  docker rm --force \
    "$FRONTEND_CONTAINER" \
    "$BACKEND_CONTAINER" >/dev/null 2>&1 || true
  docker network rm "$NETWORK_NAME" >/dev/null 2>&1 || true
  rm -r "$CHECK_DIR" >/dev/null 2>&1 || true
}

trap cleanup EXIT HUP INT TERM

mkdir -p "$CERT_DIR" "$WEBROOT_DIR/.well-known/acme-challenge"
printf '%s\n' "$TOKEN" \
  > "$WEBROOT_DIR/.well-known/acme-challenge/$TOKEN"
chmod 0755 \
  "$CHECK_DIR" \
  "$CERT_DIR" \
  "$WEBROOT_DIR" \
  "$WEBROOT_DIR/.well-known" \
  "$WEBROOT_DIR/.well-known/acme-challenge"
chmod 0444 "$WEBROOT_DIR/.well-known/acme-challenge/$TOKEN"

docker network create "$NETWORK_NAME" >/dev/null
docker run --detach \
  --name "$BACKEND_CONTAINER" \
  --network "$NETWORK_NAME" \
  --network-alias backend \
  --mount "type=bind,src=${MOCK_BACKEND_CONFIG},dst=/etc/nginx/conf.d/default.conf,readonly" \
  nginx:1.30.4-alpine >/dev/null

# 인증서가 없는 최초 배포는 ACME와 health만 제공하고 애플리케이션 HTTP는 열지 않는다.
docker run --detach \
  --name "$FRONTEND_CONTAINER" \
  --network "$NETWORK_NAME" \
  --read-only \
  --cap-drop ALL \
  --security-opt no-new-privileges \
  --env GILBOM_NGINX_TLS_MODE=auto \
  --mount "type=bind,src=${CERT_DIR},dst=/etc/nginx/certs,readonly" \
  --mount "type=bind,src=${WEBROOT_DIR},dst=/var/www/certbot,readonly" \
  --tmpfs /tmp:rw,noexec,nosuid,nodev,size=32m,mode=1777 \
  "$IMAGE_NAME" >/dev/null

attempt=0
until docker exec "$FRONTEND_CONTAINER" \
  /usr/local/bin/gilbom-nginx-healthcheck
do
  attempt=$((attempt + 1))
  [ "$attempt" -lt 30 ] \
    || fail "bootstrap Nginx did not become ready"
  sleep 1
done

bootstrap_challenge="$(
  docker exec "$FRONTEND_CONTAINER" \
    wget \
      --quiet \
      --output-document=- \
      "http://127.0.0.1:8080/.well-known/acme-challenge/$TOKEN"
)"
[ "$bootstrap_challenge" = "$TOKEN" ] \
  || fail "bootstrap Nginx did not serve the ACME challenge"
bootstrap_headers="$(
  docker exec "$FRONTEND_CONTAINER" \
    wget --server-response --spider http://127.0.0.1:8080/robots 2>&1 \
    || true
)"
printf '%s\n' "$bootstrap_headers" \
  | grep -Eq 'HTTP/1\.[01] 503' \
  || fail "bootstrap Nginx exposed the application before certificate issuance"

docker rm --force "$FRONTEND_CONTAINER" >/dev/null

openssl req \
  -x509 \
  -newkey rsa:2048 \
  -sha256 \
  -nodes \
  -days 1 \
  -subj /CN=localhost \
  -addext subjectAltName=DNS:localhost,IP:127.0.0.1 \
  -keyout "$CERT_DIR/privkey.pem" \
  -out "$CERT_DIR/fullchain.pem" \
  >/dev/null 2>&1
chmod 0444 "$CERT_DIR/privkey.pem" "$CERT_DIR/fullchain.pem"

docker run --detach \
  --name "$FRONTEND_CONTAINER" \
  --network "$NETWORK_NAME" \
  --read-only \
  --cap-drop ALL \
  --security-opt no-new-privileges \
  --env GILBOM_NGINX_TLS_MODE=on \
  --mount "type=bind,src=${CERT_DIR},dst=/etc/nginx/certs,readonly" \
  --mount "type=bind,src=${WEBROOT_DIR},dst=/var/www/certbot,readonly" \
  --tmpfs /tmp:rw,noexec,nosuid,nodev,size=32m,mode=1777 \
  "$IMAGE_NAME" >/dev/null

attempt=0
until docker exec "$FRONTEND_CONTAINER" \
  /usr/local/bin/gilbom-nginx-healthcheck
do
  attempt=$((attempt + 1))
  [ "$attempt" -lt 30 ] \
    || fail "HTTPS Nginx did not become ready"
  sleep 1
done

challenge_body="$(
  docker exec "$FRONTEND_CONTAINER" \
    wget \
      --quiet \
      --output-document=- \
      "http://127.0.0.1:8080/.well-known/acme-challenge/$TOKEN"
)"
[ "$challenge_body" = "$TOKEN" ] \
  || fail "ACME HTTP-01 challenge was not served over HTTP"

redirect_headers="$(
  docker exec "$FRONTEND_CONTAINER" \
    wget \
      --server-response \
      --spider \
      --no-check-certificate \
      http://127.0.0.1:8080/robots 2>&1 \
      || true
)"
printf '%s\n' "$redirect_headers" \
  | grep -Eq 'HTTP/1\.[01] 308' \
  || fail "HTTP application request was not redirected to HTTPS"

health_body="$(
  docker exec "$FRONTEND_CONTAINER" \
    wget \
      --quiet \
      --no-check-certificate \
      --output-document=- \
      https://127.0.0.1:8443/healthz
)"
[ "$health_body" = ok ] \
  || fail "HTTPS /healthz response changed"

https_headers="$(
  docker exec "$FRONTEND_CONTAINER" \
    wget \
      --server-response \
      --spider \
      --no-check-certificate \
      https://127.0.0.1:8443/healthz 2>&1
)"
for required_header in \
  'Strict-Transport-Security: max-age=31536000' \
  'X-Content-Type-Options: nosniff' \
  'X-Frame-Options: DENY' \
  'Referrer-Policy: no-referrer' \
  'Permissions-Policy: camera=()' \
  "Content-Security-Policy: default-src 'self'"
do
  printf '%s\n' "$https_headers" \
    | grep -Fiq "$required_header" \
    || fail "HTTPS security header is missing: $required_header"
done
csp="$(
  printf '%s\n' "$https_headers" \
    | sed -n 's/^[[:space:]]*[Cc]ontent-[Ss]ecurity-[Pp]olicy:[[:space:]]*//p' \
    | tr -d '\r' \
    | head -n 1
)"
printf '%s\n' "$csp" | grep -Fq 'https://nrbe.pstatic.net' \
  || fail "Naver runtime style JSON host is missing from CSP"
printf '%s\n' "$csp" | grep -Fq 'https://oapi.map.naver.com' \
  || fail "Naver Maps SDK host is missing from CSP"
if printf '%s\n' "$csp" | grep -Fq 'http://'; then
  fail "CSP permits plaintext HTTP sources"
fi

api_body="$(
  docker exec "$FRONTEND_CONTAINER" \
    wget \
      --quiet \
      --no-check-certificate \
      --output-document=- \
      https://127.0.0.1:8443/api/runtime-check
)"
[ "$api_body" = '{"status":"UP"}' ] \
  || fail "HTTPS API reverse proxy failed"

signed_image_url='https://127.0.0.1:8443/api/v1/images/7c1e0000-0000-4000-8000-000000000051/content?token=tls-runtime-check'
signed_image_body="$(
  docker exec "$FRONTEND_CONTAINER" \
    wget \
      --quiet \
      --no-check-certificate \
      --header='Cookie: __Host-GILBOM_SESSION=administrator-session' \
      --output-document=- \
      "$signed_image_url"
)"
[ -z "$signed_image_body" ] \
  || fail "HTTPS signed image proxy forwarded the administrator session Cookie"
signed_image_headers="$(
  docker exec "$FRONTEND_CONTAINER" \
    wget \
      --server-response \
      --spider \
      --no-check-certificate \
      --header='Cookie: __Host-GILBOM_SESSION=administrator-session' \
      "$signed_image_url" 2>&1
)"
if printf '%s\n' "$signed_image_headers" \
    | grep -Eiq 'Set-Cookie:'; then
  fail "HTTPS signed image proxy exposed a Backend Set-Cookie response"
fi

docker exec "$FRONTEND_CONTAINER" /bin/sh -ec '
  nginx -T 2>&1 | grep -Fq "listen 8443 ssl;"
  nginx -T 2>&1 | grep -Fq "ssl_protocols TLSv1.2 TLSv1.3;"
  nginx -T 2>&1 | grep -Fq "Strict-Transport-Security"
  nginx -T 2>&1 | grep -Fq "style-src '\''self'\''"
  ! nginx -T 2>&1 | grep -Fq "unsafe-inline"
  /usr/local/bin/gilbom-nginx-configure reload
'

echo "Frontend TLS verification: PASS"
