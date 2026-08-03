#!/bin/sh

set -eu

PROJECT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
EVIDENCE_DIR="${MQTT_E2E_EVIDENCE_DIR:-$PROJECT_DIR/.artifacts/security-regression}"
EVIDENCE_FILE="$EVIDENCE_DIR/mqtt-broker-e2e.json"
PUBLISHED_HOST="${MQTT_E2E_PUBLISHED_HOST:-127.0.0.1}"
CA_CERTIFICATE="$PROJECT_DIR/infra/mosquitto/local/runtime/certs/local-ca-cert.pem"
PASSWORD_FILE="${MQTT_PASSWORD_SECRET_FILE:-$PROJECT_DIR/secrets/mqtt_backend_password}"
SERVER_USERNAME="${MQTT_USERNAME:-gilbom}"
STARTED_AT="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
ROBOT_USERNAME="robot-e2e-$(openssl rand -hex 8)"
ROBOT_PASSWORD=""
CREDENTIAL_ADDED=false
ACL_PROBE_PASSED=false
RESULT=FAIL
FAILURE_REASON=mqtt_broker_e2e_failed

fail() {
  FAILURE_REASON="$1"
  echo "MQTT Broker E2E failed: $1" >&2
  exit 1
}

broker_reload_count() {
  broker_id="$(docker compose ps -q mosquitto)"
  [ -n "$broker_id" ] || return 1
  docker logs "$broker_id" 2>&1 \
    | awk '
        /MQTT password file changed; reloading broker configuration\./ {
          count += 1
        }
        END { print count + 0 }
      '
}

wait_for_broker_reload() {
  previous_count="$1"
  attempts=0
  while [ "$attempts" -lt 10 ]; do
    current_count="$(broker_reload_count)" || return 1
    if [ "$current_count" -gt "$previous_count" ]; then
      return 0
    fi
    attempts=$((attempts + 1))
    sleep 1
  done
  return 1
}

write_evidence() {
  exit_status="$1"
  result_files=0
  if [ -d "$PROJECT_DIR/.artifacts/backend-ci/build/test-results/mqttBrokerE2E" ]; then
    result_files="$(
      find "$PROJECT_DIR/.artifacts/backend-ci/build/test-results/mqttBrokerE2E" \
        -type f -name '*.xml' -size +0c | wc -l | tr -d ' '
    )"
  fi
  mkdir -p "$EVIDENCE_DIR" || return 1
  case "$RESULT" in PASS|FAIL) ;; *) return 1 ;; esac
  case "$result_files" in *[!0-9]*|"") return 1 ;; esac
  case "$FAILURE_REASON" in *[!A-Za-z0-9_]* ) return 1 ;; esac
  cleanup_passed=false
  [ "$CREDENTIAL_ADDED" = false ] && cleanup_passed=true
  acl_cross_robot_publish_denied=false
  acl_reverse_direction_publish_denied=false
  acl_cross_robot_subscribe_denied=false
  acl_reverse_direction_subscribe_denied=false
  if [ "$ACL_PROBE_PASSED" = true ]; then
    acl_cross_robot_publish_denied=true
    acl_reverse_direction_publish_denied=true
    acl_cross_robot_subscribe_denied=true
    acl_reverse_direction_subscribe_denied=true
  fi
  failure_json=null
  [ "$RESULT" = PASS ] || failure_json="\"$FAILURE_REASON\""
  printf '%s\n' \
    '{' \
    '  "schemaVersion": 1,' \
    "  \"status\": \"$RESULT\"," \
    "  \"startedAtUtc\": \"$STARTED_AT\"," \
    "  \"completedAtUtc\": \"$(date -u '+%Y-%m-%dT%H:%M:%SZ')\"," \
    '  "brokerTransport": "TLS",' \
    '  "credentialScope": "temporary",' \
    '  "aclProbeMode": "real-broker",' \
    '  "acl": {' \
    "    \"crossRobotPublishDenied\": $acl_cross_robot_publish_denied," \
    "    \"reverseDirectionPublishDenied\": $acl_reverse_direction_publish_denied," \
    "    \"crossRobotSubscribeDenied\": $acl_cross_robot_subscribe_denied," \
    "    \"reverseDirectionSubscribeDenied\": $acl_reverse_direction_subscribe_denied" \
    '  },' \
    "  \"resultFiles\": $result_files," \
    "  \"cleanupPassed\": $cleanup_passed," \
    "  \"failureReason\": $failure_json" \
    '}' > "$EVIDENCE_FILE.tmp" || return 1
  mv -f -- "$EVIDENCE_FILE.tmp" "$EVIDENCE_FILE" || return 1
  return "$exit_status"
}

run_mqtt_acl_probe() {
  set -- docker run --rm \
    --network host \
    --env MQTT_ACL_HOST \
    --env MQTT_ACL_PORT \
    --env MQTT_ACL_SERVER_USERNAME \
    --env MQTT_ACL_SERVER_PASSWORD \
    --env MQTT_ACL_ROBOT_USERNAME \
    --env MQTT_ACL_ROBOT_PASSWORD \
    --volume "$CA_CERTIFICATE:/mqtt-acl/ca.pem:ro"
  case "$MQTT_ACL_HOST" in
    127.0.0.1|localhost) ;;
    *)
      set -- "$@" --add-host "$MQTT_ACL_HOST:127.0.0.1"
      ;;
  esac
  set -- "$@" eclipse-mosquitto:2.1.2-alpine sh -ec '
    ca=/mqtt-acl/ca.pem
    own_prefix="tbs/v2/robots/$MQTT_ACL_ROBOT_USERNAME"
    other_prefix="tbs/v2/robots/${MQTT_ACL_ROBOT_USERNAME}-other"

    robot_publish() {
      mosquitto_pub -V mqttv5 \
        -h "$MQTT_ACL_HOST" -p "$MQTT_ACL_PORT" \
        --cafile "$ca" \
        -u "$MQTT_ACL_ROBOT_USERNAME" \
        -P "$MQTT_ACL_ROBOT_PASSWORD" \
        -q 1 -t "$1" -m "$2"
    }
    server_publish() {
      mosquitto_pub -V mqttv5 \
        -h "$MQTT_ACL_HOST" -p "$MQTT_ACL_PORT" \
        --cafile "$ca" \
        -u "$MQTT_ACL_SERVER_USERNAME" \
        -P "$MQTT_ACL_SERVER_PASSWORD" \
        -q 1 -t "$1" -m "$2"
    }
    server_subscribe_once() {
      mosquitto_sub -V mqttv5 \
        -h "$MQTT_ACL_HOST" -p "$MQTT_ACL_PORT" \
        --cafile "$ca" \
        -u "$MQTT_ACL_SERVER_USERNAME" \
        -P "$MQTT_ACL_SERVER_PASSWORD" \
        -q 1 -W "$1" -C 1 -t "$2"
    }
    robot_subscribe_once() {
      mosquitto_sub -V mqttv5 \
        -h "$MQTT_ACL_HOST" -p "$MQTT_ACL_PORT" \
        --cafile "$ca" \
        -u "$MQTT_ACL_ROBOT_USERNAME" \
        -P "$MQTT_ACL_ROBOT_PASSWORD" \
        -q 1 -W "$1" -C 1 -t "$2"
    }

    server_subscribe_once 5 "$own_prefix/telemetry" \
      > /tmp/allowed-publish 2>/dev/null &
    allowed_publish_pid=$!
    sleep 1
    robot_publish "$own_prefix/telemetry" allowed-publish \
      >/dev/null 2>&1
    wait "$allowed_publish_pid"
    grep -Fxq allowed-publish /tmp/allowed-publish

    robot_subscribe_once 5 "$own_prefix/commands/request" \
      > /tmp/allowed-subscribe 2>/dev/null &
    allowed_pid=$!
    sleep 1
    server_publish "$own_prefix/commands/request" allowed-subscribe \
      >/dev/null 2>&1
    wait "$allowed_pid"
    grep -Fxq allowed-subscribe /tmp/allowed-subscribe

    server_subscribe_once 3 "$other_prefix/telemetry" \
      > /tmp/cross-publish 2>/dev/null &
    cross_publish_pid=$!
    sleep 1
    robot_publish "$other_prefix/telemetry" cross-publish-denied \
      >/dev/null 2>&1 || :
    if wait "$cross_publish_pid"; then
      echo "MQTT ACL delivered a cross-robot publish" >&2
      exit 31
    fi
    test ! -s /tmp/cross-publish

    robot_subscribe_once 3 "$own_prefix/commands/request" \
      > /tmp/reverse-publish 2>/dev/null &
    reverse_publish_pid=$!
    sleep 1
    robot_publish "$own_prefix/commands/request" reverse-publish-denied \
      >/dev/null 2>&1 || :
    if wait "$reverse_publish_pid"; then
      echo "MQTT ACL delivered a reverse-direction publish" >&2
      exit 32
    fi
    test ! -s /tmp/reverse-publish

    robot_subscribe_once 2 "$other_prefix/commands/request" \
      > /tmp/cross-subscribe 2>/dev/null &
    cross_pid=$!
    sleep 1
    server_publish "$other_prefix/commands/request" cross-subscribe-denied \
      >/dev/null 2>&1
    if wait "$cross_pid"; then
      echo "MQTT ACL delivered a cross-robot subscription" >&2
      exit 33
    fi
    test ! -s /tmp/cross-subscribe

    robot_subscribe_once 2 "$own_prefix/telemetry" \
      > /tmp/reverse-subscribe 2>/dev/null &
    reverse_pid=$!
    sleep 1
    robot_publish "$own_prefix/telemetry" reverse-subscribe-denied \
      >/dev/null 2>&1
    if wait "$reverse_pid"; then
      echo "MQTT ACL delivered a reverse-direction subscription" >&2
      exit 34
    fi
    test ! -s /tmp/reverse-subscribe
  '
  "$@"
}

remove_robot_credential() {
  [ "$CREDENTIAL_ADDED" = true ] || return 0
  reload_count="$(broker_reload_count)" || return 1
  removal_result="$(
    docker compose run --rm -T --no-deps \
      --entrypoint /bin/sh mqtt-init \
      -ec '
        password_file=/mqtt-local/runtime/credentials/passwd
        state_owner_uid="$(stat -c "%u" /mqtt-local)"
        if cut -d: -f1 "$password_file" | grep -Fxq "$1"; then
          mosquitto_passwd -D "$password_file" "$1"
          printf "REMOVED\n"
        else
          printf "ABSENT\n"
        fi
        chown "$state_owner_uid:1883" "$password_file"
        chmod 0660 "$password_file"
      ' sh "$ROBOT_USERNAME"
  )" || return 1
  case "$removal_result" in
    *REMOVED*)
      wait_for_broker_reload "$reload_count" || return 1
      ;;
    *ABSENT*) ;;
    *) return 1 ;;
  esac
  if [ -n "$removal_result" ]; then
    CREDENTIAL_ADDED=false
    return 0
  fi
  return 1
}

on_exit() {
  exit_status="$1"
  trap - EXIT HUP INT TERM
  set +e
  unset \
    MQTT_E2E_SERVER_PASSWORD \
    MQTT_E2E_ROBOT_PASSWORD \
    MQTT_ACL_SERVER_PASSWORD \
    MQTT_ACL_ROBOT_PASSWORD \
    SERVER_PASSWORD \
    ROBOT_PASSWORD
  if ! remove_robot_credential; then
    RESULT=FAIL
    FAILURE_REASON=temporary_mqtt_credential_cleanup
    exit_status=1
  fi
  write_evidence "$exit_status"
  exit "$?"
}

trap 'on_exit $?' EXIT
trap 'exit 1' HUP INT TERM

case "$PUBLISHED_HOST" in
  *[!A-Za-z0-9.-]*|""|.*|*.|-*|*-)
    fail invalid_published_host
    ;;
esac

test -s "$CA_CERTIFICATE" || fail missing_ca_certificate
test -r "$PASSWORD_FILE" || fail missing_backend_password
[ "$(wc -l < "$PASSWORD_FILE")" -le 1 ] || fail invalid_backend_password_file
[ "$(wc -c < "$PASSWORD_FILE")" -le 16384 ] || fail invalid_backend_password_file

port_binding="$(docker compose port mosquitto 8883)" \
  || fail missing_broker_port
[ -n "$port_binding" ] || fail missing_broker_port
first_binding="$(printf '%s\n' "$port_binding" | sed -n '1p')"
mqtt_port="${first_binding##*:}"
case "$mqtt_port" in
  *[!0-9]*|"") fail invalid_broker_port ;;
esac
[ "$mqtt_port" -ge 1 ] && [ "$mqtt_port" -le 65535 ] \
  || fail invalid_broker_port

set +x
SERVER_PASSWORD="$(cat "$PASSWORD_FILE")"
ROBOT_PASSWORD="$(openssl rand -base64 32 | tr -d '\n')"
[ -n "$SERVER_PASSWORD" ] || fail empty_backend_password
[ -n "$ROBOT_PASSWORD" ] || fail empty_robot_password

CREDENTIAL_ADDED=true
reload_count="$(broker_reload_count)" || fail missing_broker_container
printf '%s\n' "$ROBOT_PASSWORD" \
  | docker compose run --rm -T --no-deps \
      --entrypoint /bin/sh mqtt-init \
      -ec '
        IFS= read -r password
        password_file=/mqtt-local/runtime/credentials/passwd
        state_owner_uid="$(stat -c "%u" /mqtt-local)"
        mosquitto_passwd -b "$password_file" "$1" "$password"
        unset password
        chown "$state_owner_uid:1883" "$password_file"
        chmod 0660 "$password_file"
      ' sh "$ROBOT_USERNAME" >/dev/null

# run-local-mosquitto.sh watches the password checksum and HUP-reloads it.
wait_for_broker_reload "$reload_count" || fail broker_credential_reload

export MQTT_ACL_HOST="$PUBLISHED_HOST"
export MQTT_ACL_PORT="$mqtt_port"
export MQTT_ACL_SERVER_USERNAME="$SERVER_USERNAME"
export MQTT_ACL_SERVER_PASSWORD="$SERVER_PASSWORD"
export MQTT_ACL_ROBOT_USERNAME="$ROBOT_USERNAME"
export MQTT_ACL_ROBOT_PASSWORD="$ROBOT_PASSWORD"
run_mqtt_acl_probe || fail mqtt_acl_probe
ACL_PROBE_PASSED=true

export MQTT_E2E_BROKER="ssl://$PUBLISHED_HOST:$mqtt_port"
export MQTT_E2E_CA_CERTIFICATE_HOST="$CA_CERTIFICATE"
export MQTT_E2E_SERVER_USERNAME="$SERVER_USERNAME"
export MQTT_E2E_SERVER_PASSWORD="$SERVER_PASSWORD"
export MQTT_E2E_ROBOT_USERNAME="$ROBOT_USERNAME"
export MQTT_E2E_ROBOT_PASSWORD="$ROBOT_PASSWORD"

sh "$PROJECT_DIR/scripts/run-backend-ci-task.sh" mqttBrokerE2E

RESULT=PASS
FAILURE_REASON=""
echo "Actual TLS MQTT Broker E2E: PASS"
