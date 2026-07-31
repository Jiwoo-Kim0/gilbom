#!/bin/sh

set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
CHECK_DIR="$(mktemp -d)"
SECRET_IMAGE="gilbom-image-verifier-secret-fixture-$$"
DOTENV_IMAGE="gilbom-image-verifier-dotenv-fixture-$$"
SENTINEL="day7-image-secret-sentinel-3b09c56d97af"

fail() {
  echo "Production image verifier self-test failed: $1" >&2
  exit 1
}

cleanup() {
  docker image rm --force \
    "$SECRET_IMAGE" \
    "$DOTENV_IMAGE" >/dev/null 2>&1 || true
  rm -r "$CHECK_DIR" >/dev/null 2>&1 || true
}

trap cleanup EXIT HUP INT TERM
umask 077
mkdir -p "$CHECK_DIR/secret" "$CHECK_DIR/dotenv" "$CHECK_DIR/secrets"
printf '%s\n' "$SENTINEL" > "$CHECK_DIR/secrets/canary"

cat > "$CHECK_DIR/secret/Dockerfile" <<EOF
FROM alpine:3.22
RUN adduser -D -u 10001 app
ENV DAY7_TEST_VALUE=$SENTINEL
USER app
EOF

cat > "$CHECK_DIR/dotenv/Dockerfile" <<'EOF'
FROM alpine:3.22
RUN adduser -D -u 10001 app \
    && mkdir -p /app \
    && touch /app/.env \
    && chown -R app:app /app
USER app
EOF

docker build --quiet \
  --tag "$SECRET_IMAGE" \
  "$CHECK_DIR/secret" >/dev/null
docker build --quiet \
  --tag "$DOTENV_IMAGE" \
  "$CHECK_DIR/dotenv" >/dev/null

secret_output="$CHECK_DIR/secret-output"
if BACKEND_IMAGE="$SECRET_IMAGE" \
    FRONTEND_IMAGE="$SECRET_IMAGE" \
    SECRET_SCAN_DIR="$CHECK_DIR/secrets" \
    IMAGE_VERIFY_MODE=static \
    IMAGE_SCANNER_MODE=off \
    sh "$SCRIPT_DIR/verify-production-images.sh" \
    >"$secret_output" 2>&1; then
  fail "a synthetic Secret image unexpectedly passed"
fi
grep -Fq 'Secret' "$secret_output" \
  || {
    sed -n '1,40p' "$secret_output" >&2
    fail "Secret rejection did not report its reason"
  }

dotenv_output="$CHECK_DIR/dotenv-output"
if BACKEND_IMAGE="$DOTENV_IMAGE" \
    FRONTEND_IMAGE="$DOTENV_IMAGE" \
    SECRET_SCAN_DIR="$CHECK_DIR/empty-secrets" \
    IMAGE_VERIFY_MODE=static \
    IMAGE_SCANNER_MODE=off \
    sh "$SCRIPT_DIR/verify-production-images.sh" \
    >"$dotenv_output" 2>&1; then
  fail "an image containing .env unexpectedly passed"
fi
grep -Fq '.env' "$dotenv_output" \
  || {
    sed -n '1,40p' "$dotenv_output" >&2
    fail ".env rejection did not report its reason"
  }

echo "Production image verifier self-test: PASS"
