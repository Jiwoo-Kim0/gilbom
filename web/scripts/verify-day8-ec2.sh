#!/bin/sh

set -eu

DEPLOY_DIR="${DEPLOY_DIR:-/opt/gilbom/deploy}"
RUNTIME_ENV_FILE="${RUNTIME_ENV_FILE:-$DEPLOY_DIR/runtime.env}"
IMAGE_ENV_FILE="${IMAGE_ENV_FILE:-$DEPLOY_DIR/image.env}"
COMPOSE_FILE="${COMPOSE_FILE:-$DEPLOY_DIR/compose.prod.yaml}"
DOMAIN="${DOMAIN:-i15d101.p.ssafy.io}"
EXPECTED_MIGRATION_VERSION="${EXPECTED_MIGRATION_VERSION:-35}"

fail() {
  echo "Day 8 EC2 verification failed: $1" >&2
  exit 1
}

compose() {
  docker compose \
    --env-file "$RUNTIME_ENV_FILE" \
    --env-file "$IMAGE_ENV_FILE" \
    -f "$COMPOSE_FILE" \
    "$@"
}

env_value() {
  key=$1
  awk -F= -v key="$key" '
    $1 == key {
      value = substr($0, index($0, "=") + 1)
      sub(/\r$/, "", value)
      if (value ~ /^".*"$/ || value ~ /^'\''.*'\''$/) {
        value = substr(value, 2, length(value) - 2)
      }
      print value
      exit
    }
  ' "$RUNTIME_ENV_FILE"
}

container_id() {
  id="$(compose ps -q "$1")"
  [ -n "$id" ] || fail "container is missing: $1"
  printf '%s\n' "$id"
}

assert_healthy() {
  service=$1
  id="$(container_id "$service")"
  status="$(docker inspect --format '{{.State.Health.Status}}' "$id")"
  [ "$status" = healthy ] || fail "$service is not healthy: $status"
}

assert_header() {
  file=$1
  name=$2
  expected=$3
  grep -Eiq "^${name}:[[:space:]]*.*${expected}" "$file" \
    || fail "response header is missing or invalid: $name"
}

assert_immutable_application_image() {
  service=$1
  id="$(container_id "$service")"
  configured="$(docker inspect --format '{{.Config.Image}}' "$id")"
  printf '%s\n' "$configured" \
    | grep -Eq '(@sha256:[0-9a-f]{64}|:[0-9a-f]{40})$' \
    || fail "$service image is not pinned by digest or full commit SHA: $configured"
  image_id="$(docker inspect --format '{{.Image}}' "$id")"
  printf '%s\n' "$image_id" | grep -Eq '^sha256:[0-9a-f]{64}$' \
    || fail "$service image ID is invalid"
}

assert_resource_boundary() {
  service=$1
  id="$(container_id "$service")"
  values="$(
    docker inspect \
      --format '{{.HostConfig.Memory}} {{.HostConfig.NanoCpus}} {{.HostConfig.PidsLimit}} {{index .HostConfig.LogConfig.Config "max-size"}} {{index .HostConfig.LogConfig.Config "max-file"}}' \
      "$id"
  )"
  set -- $values
  [ "${1:-0}" -gt 0 ] || fail "$service memory ceiling is missing"
  [ "${2:-0}" -gt 0 ] || fail "$service CPU ceiling is missing"
  [ "${3:-0}" -gt 0 ] || fail "$service PID ceiling is missing"
  [ "${4:-}" = 10m ] || fail "$service log max-size is not 10m"
  [ "${5:-}" = 3 ] || fail "$service log max-file is not 3"
}

assert_secret_file() {
  key=$1
  required=${2:-true}
  path="$(env_value "$key")"
  if [ -z "$path" ]; then
    [ "$required" = false ] && return
    fail "runtime.env is missing $key"
  fi
  [ "$path" = /dev/null ] && return
  case "$path" in
    /*) ;;
    *) path="$DEPLOY_DIR/$path" ;;
  esac
  [ -f "$path" ] || fail "Secret file does not exist for $key"
  [ -r "$path" ] || fail "Secret file is not readable for $key"
  [ -z "$(find "$path" -maxdepth 0 -perm /022 -print)" ] \
    || fail "Secret file is group/other writable for $key"
  [ -z "$(find "$path" -maxdepth 0 -perm /111 -print)" ] \
    || fail "Secret file is executable for $key"
}

command -v docker >/dev/null 2>&1 || fail "Docker CLI is required"
command -v curl >/dev/null 2>&1 || fail "curl is required"
command -v systemctl >/dev/null 2>&1 || fail "systemd is required"
[ -r "$RUNTIME_ENV_FILE" ] || fail "runtime.env is missing"
[ -r "$IMAGE_ENV_FILE" ] || fail "image.env is missing"
[ -r "$COMPOSE_FILE" ] || fail "compose.prod.yaml is missing"
[ -r "$DEPLOY_DIR/infra/postgres/verify-production-database.sql" ] \
  || fail "production database contract SQL is missing"

for path in \
  /opt/gilbom/app \
  /opt/gilbom/deploy \
  /opt/gilbom/artifacts \
  /opt/gilbom/artifacts/.incoming \
  /opt/gilbom/secrets \
  /opt/gilbom/certbot \
  /opt/gilbom/backups \
  /opt/gilbom/monitoring \
  /opt/jenkins \
  /opt/jenkins/home
do
  [ -d "$path" ] || fail "required host path is missing: $path"
done

[ "$(stat -c '%a' /opt/gilbom/secrets)" = 700 ] \
  || fail "/opt/gilbom/secrets must be mode 0700"
[ "$(stat -c '%a' /opt/gilbom/backups)" = 700 ] \
  || fail "/opt/gilbom/backups must be mode 0700"
[ "$(stat -c '%U' /opt/gilbom/artifacts)" = root ] \
  || fail "/opt/gilbom/artifacts must be root-owned"
[ "$(stat -c '%a' /opt/gilbom/artifacts)" = 710 ] \
  || fail "/opt/gilbom/artifacts must be mode 0710"
[ "$(stat -c '%U' /opt/gilbom/artifacts/.incoming)" = root ] \
  || fail "/opt/gilbom/artifacts/.incoming must be root-owned"
[ "$(stat -c '%a' /opt/gilbom/artifacts/.incoming)" = 730 ] \
  || fail "/opt/gilbom/artifacts/.incoming must be mode 0730"

swap_file_active_bytes="$(
  swapon --show=NAME,SIZE --bytes --noheadings \
    | awk '$1 == "/swapfile" {total += $2} END {print total + 0}'
)"
[ -f /swapfile ] || fail "/swapfile is missing"
swap_file_bytes="$(stat -c '%s' /swapfile)"
[ "$swap_file_bytes" -ge 4294967296 ] \
  || fail "/swapfile must be at least 4 GiB"
# mkswap reserves space for its header, so the active SIZE is smaller than
# the source file. Verify the 4 GiB file and allow up to 1 MiB of metadata.
minimum_active_swap_bytes=$((swap_file_bytes - 1048576))
[ "$swap_file_active_bytes" -ge "$minimum_active_swap_bytes" ] \
  || fail "active swap is smaller than the 4 GiB swapfile usable area"
grep -Eq '^[[:space:]]*/swapfile[[:space:]]+none[[:space:]]+swap[[:space:]]+sw[[:space:]]+0[[:space:]]+0([[:space:]]|$)' \
  /etc/fstab \
  || fail "/swapfile persistence is missing from /etc/fstab"
[ "$(sysctl -n vm.swappiness)" -eq 10 ] \
  || fail "vm.swappiness must be 10"

compose config --quiet
for service in db mosquitto backend frontend
do
  assert_healthy "$service"
  assert_resource_boundary "$service"
done
assert_immutable_application_image backend
assert_immutable_application_image frontend

for service in backend db
do
  id="$(container_id "$service")"
  bindings="$(
    docker inspect \
      --format '{{range $port, $list := .HostConfig.PortBindings}}{{if $list}}{{println $port}}{{end}}{{end}}' \
      "$id"
  )"
  [ -z "$bindings" ] || fail "$service unexpectedly publishes a host port"
done

for volume in \
  postgres_data \
  mosquitto_config \
  mosquitto_credentials \
  mosquitto_data \
  report_pdf_data \
  robot_images
do
  production_project="$(
    docker inspect \
      --format '{{index .Config.Labels "com.docker.compose.project"}}' \
      "$(container_id backend)"
  )"
  docker volume ls \
    --filter "label=com.docker.compose.project=$production_project" \
    --filter "label=com.docker.compose.volume=$volume" \
    --quiet \
    | grep -q . \
    || fail "named volume is missing: $volume"
done

assert_secret_file POSTGRES_ADMIN_PASSWORD_SECRET_FILE
assert_secret_file POSTGRES_FLYWAY_PASSWORD_SECRET_FILE
assert_secret_file POSTGRES_APP_PASSWORD_SECRET_FILE
assert_secret_file MQTT_PASSWORD_SECRET_FILE
assert_secret_file MOSQUITTO_PASSWORD_FILE_SECRET_FILE
assert_secret_file MOSQUITTO_TLS_PRIVATE_KEY_SECRET_FILE
assert_secret_file GILBOM_STORAGE_SIGNING_KEY_SECRET_FILE
assert_secret_file AUTH_RATE_LIMIT_HMAC_KEY_SECRET_FILE
assert_secret_file MFA_TOTP_KEYRING_SECRET_FILE
assert_secret_file INITIAL_ADMIN_PASSWORD_SECRET_FILE
assert_secret_file GILBOM_LLM_API_KEY_SECRET_FILE false

compose exec -T backend \
  wget -qO- http://127.0.0.1:8080/actuator/health/liveness \
  | grep -Fq '"status":"UP"' \
  || fail "Backend liveness failed"
compose exec -T backend \
  wget -qO- http://127.0.0.1:8080/actuator/health/readiness \
  | grep -Fq '"status":"UP"' \
  || fail "Backend readiness failed"
compose exec -T backend \
  wget -qO- http://127.0.0.1:8080/actuator/health/mqtt \
  | grep -Fq '"status":"UP"' \
  || fail "Backend MQTT health failed"

compose exec -T db /bin/sh -ec '
  pg_isready \
    --host 127.0.0.1 \
    --username "$POSTGRES_USER" \
    --dbname "$POSTGRES_DB"
  export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"
  exec psql \
    --host 127.0.0.1 \
    --username "$POSTGRES_USER" \
    --dbname "$POSTGRES_DB" \
    --no-password \
    --set "flyway_role=$POSTGRES_FLYWAY_USER" \
    --set "application_role=$POSTGRES_APP_USER"
' < "$DEPLOY_DIR/infra/postgres/verify-production-database.sql" \
  || fail "PostGIS, Flyway, approved seed, or DB role verification failed"

compose exec -T mosquitto /bin/sh -ec '
  exec mosquitto_sub \
    -h "$MQTT_TLS_SERVER_NAME" \
    -p 8883 \
    --cafile /run/secrets/mqtt_ca_certificate \
    -u gilbom \
    -P "$(cat /run/secrets/mqtt_backend_password)" \
    -i gilbom-day8-health \
    -t "\$SYS/broker/version" \
    -C 1 \
    -W 5
' >/dev/null \
  || fail "Mosquitto TLS authentication failed"

systemctl is-enabled --quiet gilbom-certificate-renew.timer \
  || fail "certificate renewal timer is not enabled"
systemctl is-active --quiet gilbom-certificate-renew.timer \
  || fail "certificate renewal timer is not active"
for certificate_file in fullchain.pem privkey.pem
do
  compose exec -T frontend test -r "/etc/nginx/certs/$certificate_file" \
    || fail "Frontend cannot read $certificate_file"
done

check_dir="$(mktemp -d)"
trap 'rm -rf "$check_dir"' EXIT HUP INT TERM

curl -fsS \
  -D "$check_dir/https-health.headers" \
  -o "$check_dir/https-health.body" \
  "https://$DOMAIN/healthz"
grep -Fxq ok "$check_dir/https-health.body" \
  || fail "external HTTPS health response is invalid"

curl -sS \
  -D "$check_dir/http-redirect.headers" \
  -o /dev/null \
  "http://$DOMAIN/healthz"
grep -Eq '^HTTP/[0-9.]+ 308([[:space:]]|$)' \
  "$check_dir/http-redirect.headers" \
  || fail "HTTP does not redirect with status 308"
assert_header "$check_dir/http-redirect.headers" "Location" \
  "https://$DOMAIN/healthz"

assert_header "$check_dir/https-health.headers" \
  "Strict-Transport-Security" "max-age=31536000"
assert_header "$check_dir/https-health.headers" \
  "Content-Security-Policy" "default-src 'self'"
assert_header "$check_dir/https-health.headers" \
  "X-Content-Type-Options" "nosniff"
assert_header "$check_dir/https-health.headers" \
  "X-Frame-Options" "DENY"
assert_header "$check_dir/https-health.headers" \
  "Referrer-Policy" "no-referrer"
assert_header "$check_dir/https-health.headers" \
  "Permissions-Policy" "camera=\\(\\)"

csp="$(
  sed -n 's/^[Cc]ontent-[Ss]ecurity-[Pp]olicy:[[:space:]]*//p' \
    "$check_dir/https-health.headers" \
    | tr -d '\r' \
    | head -n 1
)"
printf '%s\n' "$csp" | grep -Fq 'https://nrbe.pstatic.net' \
  || fail "Naver runtime style JSON host is missing from CSP"
printf '%s\n' "$csp" | grep -Fq 'https://oapi.map.naver.com' \
  || fail "Naver Maps SDK host is missing from CSP"
if printf '%s\n' "$csp" | grep -Fq 'http://'; then
  fail "CSP still permits plaintext HTTP sources"
fi
if grep -Eiq '^Server:[[:space:]]*[^[:space:]]+/' \
  "$check_dir/https-health.headers"; then
  fail "Server response exposes a version"
fi

curl -fsS \
  -D "$check_dir/csrf.headers" \
  -o "$check_dir/csrf.body" \
  "https://$DOMAIN/api/v1/auth/csrf-token"
assert_header "$check_dir/csrf.headers" "Cache-Control" "no-store"

error_status="$(
  curl -sS \
    -D "$check_dir/error.headers" \
    -o "$check_dir/error.body" \
    -w '%{http_code}' \
    "https://$DOMAIN/api/__day8_missing_resource__"
)"
case "$error_status" in
  400|401|403|404|405) ;;
  *) fail "unexpected API error status: $error_status" ;;
esac
if grep -Eiq \
  'stack.?trace|exception|org\\.springframework|java\\.[a-z]|jdbc:postgresql|/app/' \
  "$check_dir/error.body"; then
  fail "API error response exposes internal details"
fi

latest_checksum="$(
  find /opt/gilbom/backups \
    -mindepth 2 \
    -maxdepth 2 \
    -type f \
    -name SHA256SUMS \
    -print \
    | sort \
    | tail -n 1
)"
[ -n "$latest_checksum" ] || fail "no backup SHA256SUMS was found"
(
  cd "$(dirname "$latest_checksum")"
  sha256sum -c SHA256SUMS
  grep -Fxq 'database_image_coverage=PASS' robot_images.summary
  for archive in ./*.tar.gz
  do
    tar -tzf "$archive" >/dev/null
  done
  dump_file="$(find . -maxdepth 1 -type f -name '*.dump' -print)"
  [ "$(printf '%s\n' "$dump_file" | grep -c .)" -eq 1 ]
  docker run \
    --rm \
    --interactive \
    --entrypoint pg_restore \
    postgis/postgis:16-3.5 \
    --list < "$dump_file" >/dev/null
) || fail "latest backup integrity verification failed"

printf '%s\n' \
  "Day 8 EC2 runtime: PASS" \
  "domain=$DOMAIN" \
  "migration-version=$EXPECTED_MIGRATION_VERSION"
