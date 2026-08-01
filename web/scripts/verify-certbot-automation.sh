#!/bin/sh

set -eu

CERTIFICATE_SCRIPT=infra/certbot/certificate.sh
SERVICE_UNIT=infra/systemd/gilbom-certificate-renew.service
TIMER_UNIT=infra/systemd/gilbom-certificate-renew.timer

fail() {
  echo "Certbot automation verification failed: $1" >&2
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

for required_file in \
  "$CERTIFICATE_SCRIPT" \
  "$SERVICE_UNIT" \
  "$TIMER_UNIT"
do
  [ -s "$required_file" ] || fail "missing file: $required_file"
done

sh -n "$CERTIFICATE_SCRIPT"

for action in issue renew dry-run sync-reload
do
  assert_contains "$CERTIFICATE_SCRIPT" "$action)" \
    "certificate action is missing: $action"
done

assert_contains "$CERTIFICATE_SCRIPT" \
  'exec certbot certonly' \
  "initial issuance must use certbot certonly"
assert_contains "$CERTIFICATE_SCRIPT" \
  '--webroot-path /var/www/certbot' \
  "certificate operations must use the shared ACME webroot"
assert_contains "$CERTIFICATE_SCRIPT" \
  '/usr/local/bin/gilbom-nginx-configure reload' \
  "successful certificate synchronization must reload Nginx"
assert_not_contains "$CERTIFICATE_SCRIPT" \
  'set -- docker compose' \
  "compose wrapper must preserve the caller's run/exec arguments"

assert_contains "$SERVICE_UNIT" \
  'Requires=docker.service' \
  "renewal service must require Docker"
assert_contains "$SERVICE_UNIT" \
  'ConditionPathExists=/opt/gilbom/deploy/runtime.env' \
  "renewal service must require runtime.env"
assert_contains "$SERVICE_UNIT" \
  'ConditionPathExists=/opt/gilbom/deploy/image.env' \
  "renewal service must require image.env"
assert_contains "$SERVICE_UNIT" \
  'ExecStart=/usr/local/sbin/gilbom-certificate renew' \
  "renewal service must call the host certificate wrapper"
assert_contains "$SERVICE_UNIT" \
  'NoNewPrivileges=true' \
  "renewal service must prevent privilege escalation"

assert_contains "$TIMER_UNIT" \
  'OnCalendar=*-*-* 00,12:00:00' \
  "renewal timer must run twice daily"
assert_contains "$TIMER_UNIT" \
  'RandomizedDelaySec=1h' \
  "renewal timer must spread ACME traffic"
assert_contains "$TIMER_UNIT" \
  'Persistent=true' \
  "renewal timer must catch up after downtime"
assert_contains "$TIMER_UNIT" \
  'Unit=gilbom-certificate-renew.service' \
  "renewal timer must target the renewal service"

echo "Certbot automation verification: PASS"
