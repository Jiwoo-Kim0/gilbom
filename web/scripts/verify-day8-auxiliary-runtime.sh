#!/bin/sh

set -eu

DEPLOY_DIR="${DEPLOY_DIR:-/opt/gilbom/deploy}"
MONITORING_ENV_FILE="${MONITORING_ENV_FILE:-/opt/gilbom/monitoring/monitoring.env}"
JENKINS_ENV_FILE="${JENKINS_ENV_FILE:-/opt/jenkins/jenkins.env}"

fail() {
  echo "Day 8 auxiliary runtime verification failed: $1" >&2
  exit 1
}

monitoring() {
  docker compose \
    --project-name gilbom-monitoring \
    --env-file "$MONITORING_ENV_FILE" \
    -f "$DEPLOY_DIR/compose.monitoring.yaml" \
    "$@"
}

jenkins() {
  docker compose \
    --project-name gilbom-jenkins \
    --env-file "$JENKINS_ENV_FILE" \
    -f "$DEPLOY_DIR/compose.jenkins.yaml" \
    "$@"
}

container_id() {
  stack=$1
  service=$2
  case "$stack" in
    monitoring) id="$(monitoring ps -q "$service")" ;;
    jenkins) id="$(jenkins ps -q "$service")" ;;
    *) fail "unknown stack: $stack" ;;
  esac
  [ -n "$id" ] || fail "$stack/$service container is missing"
  printf '%s\n' "$id"
}

assert_limits() {
  id=$1
  label=$2
  values="$(
    docker inspect \
      --format '{{.HostConfig.Memory}} {{.HostConfig.NanoCpus}} {{.HostConfig.PidsLimit}} {{index .HostConfig.LogConfig.Config "max-size"}} {{index .HostConfig.LogConfig.Config "max-file"}}' \
      "$id"
  )"
  set -- $values
  [ "${1:-0}" -gt 0 ] || fail "$label memory ceiling is missing"
  [ "${2:-0}" -gt 0 ] || fail "$label CPU ceiling is missing"
  [ "${3:-0}" -gt 0 ] || fail "$label PID ceiling is missing"
  [ "${4:-}" = 10m ] || fail "$label log max-size is not 10m"
  [ "${5:-}" = 3 ] || fail "$label log max-file is not 3"
}

assert_loopback_binding() {
  id=$1
  label=$2
  bindings="$(
    docker inspect \
      --format '{{range $port, $list := .HostConfig.PortBindings}}{{range $list}}{{println .HostIp .HostPort}}{{end}}{{end}}' \
      "$id"
  )"
  [ -n "$bindings" ] || fail "$label has no management port"
  if printf '%s\n' "$bindings" \
      | awk '$1 != "127.0.0.1" {bad = 1} END {exit bad ? 0 : 1}'; then
    fail "$label management port is not loopback-only"
  fi
}

assert_no_host_binding() {
  id=$1
  label=$2
  bindings="$(
    docker inspect \
      --format '{{range $port, $list := .HostConfig.PortBindings}}{{if $list}}{{println $port}}{{end}}{{end}}' \
      "$id"
  )"
  [ -z "$bindings" ] || fail "$label unexpectedly publishes a host port"
}

wait_container_http() {
  probe_id=$1
  url=$2
  label=$3
  attempt=0
  until docker exec "$probe_id" \
    wget --quiet --timeout=5 --output-document=/dev/null "$url"
  do
    attempt=$((attempt + 1))
    [ "$attempt" -lt 30 ] || fail "$label did not become ready"
    sleep 2
  done
}

[ -r "$MONITORING_ENV_FILE" ] || fail "monitoring.env is missing"
[ -r "$JENKINS_ENV_FILE" ] || fail "jenkins.env is missing"

monitoring config --quiet
jenkins config --quiet

for service in prometheus loki grafana alloy
do
  id="$(container_id monitoring "$service")"
  [ "$(docker inspect --format '{{.State.Running}}' "$id")" = true ] \
    || fail "monitoring/$service is not running"
  assert_limits "$id" "monitoring/$service"
  assert_loopback_binding "$id" "monitoring/$service"
done
alloy_logs_id="$(container_id monitoring alloy-logs)"
[ "$(docker inspect --format '{{.State.Running}}' "$alloy_logs_id")" = true ] \
  || fail "monitoring/alloy-logs is not running"
assert_limits "$alloy_logs_id" "monitoring/alloy-logs"
assert_no_host_binding "$alloy_logs_id" "monitoring/alloy-logs"
alloy_logs_mounts="$(
  docker inspect \
    --format '{{range .Mounts}}{{println .Destination}}{{end}}' \
    "$alloy_logs_id"
)"
if printf '%s\n' "$alloy_logs_mounts" \
    | grep -Eq '^/host/(proc|sys|root)(/|$)'; then
  fail "Alloy log reader can access host metrics or root mounts"
fi
alloy_logs_capabilities="$(
  docker inspect --format '{{json .HostConfig.CapAdd}}' "$alloy_logs_id"
)"
case "$alloy_logs_capabilities" in
  '["DAC_READ_SEARCH"]'|'["CAP_DAC_READ_SEARCH"]') ;;
  *) fail "Alloy log reader has an unexpected capability set" ;;
esac

monitoring_probe_id="$(container_id monitoring grafana)"
wait_container_http \
  "$monitoring_probe_id" http://prometheus:9090/-/ready Prometheus
wait_container_http \
  "$monitoring_probe_id" http://loki:3100/ready Loki
wait_container_http \
  "$monitoring_probe_id" http://127.0.0.1:3000/api/health Grafana
wait_container_http \
  "$monitoring_probe_id" http://alloy:12345/-/ready Alloy
wait_container_http \
  "$monitoring_probe_id" http://alloy-logs:12345/-/ready Alloy-logs

prometheus_id="$(container_id monitoring prometheus)"
prometheus_command="$(
  docker inspect --format '{{json .Config.Cmd}}' "$prometheus_id"
)"
printf '%s\n' "$prometheus_command" \
  | grep -Fq -- '--storage.tsdb.retention.time=15d' \
  || fail "Prometheus 15-day retention is not active"
printf '%s\n' "$prometheus_command" \
  | grep -Fq -- '--storage.tsdb.retention.size=2GB' \
  || fail "Prometheus 2GB retention ceiling is not active"

loki_id="$(container_id monitoring loki)"
loki_config_source="$(
  docker inspect \
    --format '{{range .Mounts}}{{if eq .Destination "/etc/loki/loki.yml"}}{{.Source}}{{end}}{{end}}' \
    "$loki_id"
)"
[ -f "$loki_config_source" ] \
  || fail "active Loki config bind mount is missing"
grep -Fq 'retention_period: 336h' "$loki_config_source" \
  || fail "Loki 14-day retention is not active"

jenkins_id="$(container_id jenkins jenkins)"
dind_id="$(container_id jenkins docker)"
for id in "$jenkins_id" "$dind_id"
do
  [ "$(docker inspect --format '{{.State.Health.Status}}' "$id")" = healthy ] \
    || fail "Jenkins or DinD is not healthy"
done
assert_limits "$jenkins_id" Jenkins
assert_limits "$dind_id" DinD
assert_loopback_binding "$jenkins_id" Jenkins

dind_bindings="$(
  docker inspect \
    --format '{{range $port, $list := .HostConfig.PortBindings}}{{if $list}}{{println $port}}{{end}}{{end}}' \
    "$dind_id"
)"
[ -z "$dind_bindings" ] || fail "DinD must not publish a host port"

docker exec "$jenkins_id" /bin/sh -ec '
  [ "$DOCKER_HOST" = tcp://docker:2376 ]
  [ "$DOCKER_TLS_VERIFY" = 1 ]
  [ "$COMPOSE_PARALLEL_LIMIT" = 1 ]
  grep -Fq "<numExecutors>1</numExecutors>" /var/jenkins_home/config.xml
  docker info >/dev/null
  docker compose version >/dev/null
' || fail "Jenkins TLS DinD or single-executor policy is not active"

echo "Day 8 monitoring and Jenkins runtime: PASS"
