#!/bin/sh

set -eu
set +x

SECRET_DIR="${SECRET_DIR:-/opt/gilbom/secrets}"
SECRET_FILE="$SECRET_DIR/grafana_admin_password"

fail() {
  echo "Monitoring Secret generation failed: $1" >&2
  exit 1
}

command -v openssl >/dev/null 2>&1 || fail "OpenSSL is required"
umask 077
install -d -m 0700 "$SECRET_DIR"

if [ -e "$SECRET_FILE" ]; then
  [ -s "$SECRET_FILE" ] || fail "existing Grafana password is empty"
  echo "Monitoring Secret already exists; it was not changed."
else
  openssl rand -base64 32 \
    | tr -d '\n' > "$SECRET_FILE"
  printf '\n' >> "$SECRET_FILE"
  echo "Monitoring Secret created."
fi

chmod 0700 "$SECRET_DIR"
chmod 0444 "$SECRET_FILE"
echo "Secret value was not printed."
