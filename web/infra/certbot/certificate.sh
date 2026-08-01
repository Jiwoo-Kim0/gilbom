#!/bin/sh

set -eu
set +x

action=${1:-}
DEPLOY_DIR=${DEPLOY_DIR:-/opt/gilbom/deploy}
COMPOSE_FILE=${COMPOSE_FILE:-$DEPLOY_DIR/compose.prod.yaml}
RUNTIME_ENV_FILE=${RUNTIME_ENV_FILE:-$DEPLOY_DIR/runtime.env}
IMAGE_ENV_FILE=${IMAGE_ENV_FILE:-$DEPLOY_DIR/image.env}
ENV_FILE=${ENV_FILE:-}

fail() {
  echo "Certificate operation failed: $1" >&2
  exit 1
}

compose() {
  if [ -n "$ENV_FILE" ]; then
    docker compose \
      --env-file "$ENV_FILE" \
      -f "$COMPOSE_FILE" \
      "$@"
  else
    docker compose \
      --env-file "$RUNTIME_ENV_FILE" \
      --env-file "$IMAGE_ENV_FILE" \
      -f "$COMPOSE_FILE" \
      "$@"
  fi
}

sync_nginx_certificate() {
  compose \
    --profile certificate \
    run \
    --rm \
    --no-deps \
    --entrypoint /bin/sh \
    certbot \
    -ec '
      live_dir="/etc/letsencrypt/live/$CERTBOT_DOMAIN"
      [ -s "$live_dir/fullchain.pem" ]
      [ -s "$live_dir/privkey.pem" ]

      umask 027
      mkdir -p /etc/nginx/certs
      cp -L "$live_dir/fullchain.pem" /etc/nginx/certs/fullchain.pem.next
      cp -L "$live_dir/privkey.pem" /etc/nginx/certs/privkey.pem.next
      chown 0:"$NGINX_CERT_GID" \
        /etc/nginx/certs \
        /etc/nginx/certs/fullchain.pem.next \
        /etc/nginx/certs/privkey.pem.next
      chmod 0750 /etc/nginx/certs
      chmod 0640 \
        /etc/nginx/certs/fullchain.pem.next \
        /etc/nginx/certs/privkey.pem.next
      mv /etc/nginx/certs/fullchain.pem.next /etc/nginx/certs/fullchain.pem
      mv /etc/nginx/certs/privkey.pem.next /etc/nginx/certs/privkey.pem
    '
}

reload_nginx() {
  frontend_id="$(compose ps -q frontend)"
  [ -n "$frontend_id" ] \
    || fail "Frontend container is not running"
  compose exec -T frontend \
    /usr/local/bin/gilbom-nginx-configure reload
}

[ -f "$COMPOSE_FILE" ] \
  || fail "Compose file does not exist: $COMPOSE_FILE"
if [ -n "$ENV_FILE" ]; then
  [ -f "$ENV_FILE" ] \
    || fail "ENV_FILE does not exist: $ENV_FILE"
else
  [ -f "$RUNTIME_ENV_FILE" ] \
    || fail "runtime env does not exist: $RUNTIME_ENV_FILE"
  [ -f "$IMAGE_ENV_FILE" ] \
    || fail "image env does not exist: $IMAGE_ENV_FILE"
fi
command -v docker >/dev/null 2>&1 \
  || fail "Docker CLI is required"

case "$action" in
  issue)
    # Frontend bootstrap Nginx가 port 80에서 challenge directory를 제공하는 동안 실행한다.
    compose \
      --profile certificate \
      run \
      --rm \
      --no-deps \
      --entrypoint /bin/sh \
      certbot \
      -ec '
        exec certbot certonly \
          --non-interactive \
          --agree-tos \
          --no-eff-email \
          --email "$CERTBOT_EMAIL" \
          --webroot \
          --webroot-path /var/www/certbot \
          --domain "$CERTBOT_DOMAIN"
      '
    sync_nginx_certificate
    reload_nginx
    ;;
  renew)
    compose \
      --profile certificate \
      run \
      --rm \
      --no-deps \
      certbot \
      renew \
      --non-interactive \
      --webroot \
      --webroot-path /var/www/certbot \
      --quiet
    sync_nginx_certificate
    reload_nginx
    ;;
  dry-run)
    compose \
      --profile certificate \
      run \
      --rm \
      --no-deps \
      certbot \
      renew \
      --non-interactive \
      --webroot \
      --webroot-path /var/www/certbot \
      --dry-run
    ;;
  sync-reload)
    sync_nginx_certificate
    reload_nginx
    ;;
  *)
    fail "usage: certificate.sh issue|renew|dry-run|sync-reload"
    ;;
esac

echo "Certificate operation completed: $action"
