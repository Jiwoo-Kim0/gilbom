#!/bin/sh

set -eu

PROJECT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$PROJECT_DIR"

fail() {
  echo "Day 8 infrastructure verification failed: $1" >&2
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

command -v docker >/dev/null 2>&1 || fail "Docker CLI is required"
test -x scripts/verify-jenkins-runtime.sh \
  || fail "focused Jenkins runtime contract is missing or not executable"
sh -n scripts/verify-jenkins-runtime.sh

monitoring_config="$(mktemp)"
jenkins_config="$(mktemp)"
trap 'rm -f "$monitoring_config" "$jenkins_config"' EXIT HUP INT TERM

GRAFANA_ADMIN_PASSWORD_SECRET_FILE=/dev/null \
  docker compose -f compose.monitoring.yaml config > "$monitoring_config"
COMPOSE_PROJECT_NAME=gilbom-jenkins \
  docker compose -f compose.jenkins.yaml config > "$jenkins_config"

assert_contains compose.monitoring.yaml \
  '${MONITORING_BIND_ADDRESS:-127.0.0.1}' \
  "monitoring ports must default to loopback"
assert_contains compose.monitoring.yaml \
  '--storage.tsdb.retention.time=${PROMETHEUS_RETENTION_TIME:-15d}' \
  "Prometheus must retain at most 15 days by default"
assert_contains compose.monitoring.yaml \
  '--storage.tsdb.retention.size=${PROMETHEUS_RETENTION_SIZE:-2GB}' \
  "Prometheus must have a storage size ceiling"
assert_contains infra/monitoring/loki/loki.yml \
  'retention_period: 336h' \
  "Loki must retain at most 14 days"
assert_contains compose.monitoring.yaml \
  'internal: true' \
  "monitoring service network must be internal"
assert_contains compose.monitoring.yaml \
  'max-size: "${MONITORING_LOG_MAX_SIZE:-10m}"' \
  "monitoring Docker log size rotation is missing"
assert_contains compose.monitoring.yaml \
  'max-file: "${MONITORING_LOG_MAX_FILES:-3}"' \
  "monitoring Docker log file rotation is missing"
assert_contains compose.monitoring.yaml \
  'GF_SECURITY_ADMIN_PASSWORD__FILE: /run/secrets/grafana_admin_password' \
  "Grafana password must be a file Secret"
assert_contains compose.monitoring.yaml \
  'GF_PLUGINS_PREINSTALL_DISABLED: "true"' \
  "Grafana plugin auto-install must be disabled"
assert_not_contains compose.monitoring.yaml \
  'GF_SECURITY_ADMIN_PASSWORD:' \
  "Grafana plaintext password environment variable is forbidden"
assert_contains compose.monitoring.yaml \
  'alloy-data-init:' \
  "Alloy data ownership init service is missing"
assert_contains compose.monitoring.yaml \
  'user: "473:473"' \
  "Alloy must run as the image non-root user"
assert_contains compose.monitoring.yaml \
  'DAC_READ_SEARCH' \
  "Docker log reader capability is missing"
assert_not_contains compose.monitoring.yaml \
  '/:/host/root' \
  "Alloy must not mount the complete host filesystem"
assert_contains compose.monitoring.yaml \
  'config-logs.alloy:/etc/alloy/config.alloy:ro' \
  "Docker log collection must use the isolated Alloy config"

assert_contains compose.jenkins.yaml \
  'JENKINS_OPTS: --httpPort=8080' \
  "Jenkins HTTP port setting is missing"
assert_contains compose.jenkins.yaml \
  'name: gilbom-jenkins' \
  "Jenkins Compose project name must be fixed"
assert_not_contains compose.jenkins.yaml \
  'JENKINS_PROJECT_NAME' \
  "Jenkins Compose project name must not be configurable"
assert_contains "$jenkins_config" \
  'name: gilbom-jenkins' \
  "rendered Jenkins Compose project name must be gilbom-jenkins"
assert_not_contains .env.example \
  'COMPOSE_PROJECT_NAME' \
  "shared development env must not override Compose file project names"
assert_not_contains .env.prod.example \
  'COMPOSE_PROJECT_NAME' \
  "shared production env must not override Compose file project names"
assert_contains infra/jenkins/init.groovy.d/01-executor-count.groovy \
  'instance.setNumExecutors(1)' \
  "Jenkins executor count must be initialized to one"
assert_contains infra/jenkins/init.groovy.d/02-temporary-space-monitor.groovy \
  'def minimumFreeSpace = "64MB"' \
  "Jenkins temporary-space offline threshold must fit its bounded tmpfs"
assert_contains infra/jenkins/init.groovy.d/02-temporary-space-monitor.groovy \
  'def warningFreeSpace = "128MB"' \
  "Jenkins temporary-space warning threshold must fit its bounded tmpfs"
assert_contains infra/jenkins/Dockerfile \
  '02-temporary-space-monitor.groovy' \
  "Jenkins image must install the temporary-space monitor policy"
for command_package in coreutils git gzip jq openssh-client zstd
do
  assert_contains infra/jenkins/Dockerfile \
    "$command_package" \
    "Jenkins image package is missing: $command_package"
done
assert_contains compose.jenkins.yaml \
  'COMPOSE_PARALLEL_LIMIT: "1"' \
  "Jenkins Compose concurrency must be one"
assert_contains compose.jenkins.yaml \
  'DOCKER_HOST: tcp://docker-builder:2376' \
  "Jenkins must use the TLS DinD endpoint"
assert_contains compose.jenkins.yaml \
  'hostname: docker-builder' \
  "Docker Builder hostname must match the TLS endpoint certificate SAN"
assert_contains compose.jenkins.yaml \
  'DOCKER_TLS_VERIFY: "1"' \
  "Jenkins must verify the DinD certificate"
assert_contains compose.jenkins.yaml \
  'jenkins_docker_builder_certs:/certs' \
  "Docker Builder must use a service-specific TLS certificate volume"
assert_not_contains compose.jenkins.yaml \
  'jenkins_docker_certs:/certs' \
  "legacy Docker service certificates must not be reused after the rename"
[ "$(grep -Fc -- '${JENKINS_HOME_DIR:-/opt/jenkins/home}:/var/jenkins_home' compose.jenkins.yaml)" -eq 2 ] \
  || fail "Jenkins and DinD must share the same absolute workspace path"
assert_contains scripts/run-backend-ci-task.sh \
  '--add-host docker-builder:host-gateway' \
  "DinD test container must resolve the TLS daemon through its host gateway"
assert_contains scripts/run-backend-ci-task.sh \
  '--env GRADLE_USER_HOME=/ci-home/.gradle' \
  "Jenkins non-root Backend test must use a writable isolated Gradle home"
assert_contains scripts/run-backend-ci-task.sh \
  '--volume "$WORK_ROOT/build:/ci-build"' \
  "Jenkins non-root Backend test must isolate writable build state"
assert_contains scripts/run-backend-ci-task.sh \
  '--volume "$PROJECT_DIR/backend:/workspace:ro"' \
  "Jenkins Backend source mount must remain read-only"
assert_contains Jenkinsfile \
  'IMAGE_VERIFY_WORK_ROOT=.artifacts/image-verify-work' \
  "Jenkins image verification must not use its bounded /tmp tmpfs"
assert_contains scripts/verify-production-images.sh \
  'CHECK_DIR="$(mktemp -d "$IMAGE_VERIFY_WORK_ROOT/run.XXXXXX")"' \
  "image verification work must stay on the workspace filesystem"
assert_not_contains scripts/verify-production-images.sh \
  'CHECK_DIR="$(mktemp -d)"' \
  "image verification must not stage docker save archives in /tmp"
assert_contains Jenkinsfile \
  'DOCKER_BIND_WORK_ROOT=.artifacts/docker-bind-work' \
  "Jenkins Docker bind sources must use its shared workspace filesystem"
[ "$(grep -Fc -- 'DOCKER_BIND_WORK_ROOT=.artifacts/docker-bind-work' Jenkinsfile)" -eq 2 ] \
  || fail "Jenkins must isolate both TLS and report smoke bind sources"
assert_contains scripts/verify-frontend-tls.sh \
  'CHECK_DIR="$(mktemp -d "$DOCKER_BIND_WORK_ROOT/frontend-tls.XXXXXX")"' \
  "Frontend TLS bind sources must stay on a Docker-visible filesystem"
assert_not_contains scripts/verify-frontend-tls.sh \
  'CHECK_DIR="$(mktemp -d)"' \
  "Frontend TLS verification must not bind Jenkins-local /tmp paths"
assert_contains scripts/container-report-smoke.sh \
  'CHECK_DIR="$(mktemp -d "$DOCKER_BIND_WORK_ROOT/container-report.XXXXXX")"' \
  "container report bind sources must stay on a Docker-visible filesystem"
assert_not_contains scripts/container-report-smoke.sh \
  'CHECK_DIR="$(mktemp -d)"' \
  "container report verification must not bind Jenkins-local /tmp paths"
assert_contains Jenkinsfile \
  'REPORT_SMOKE_FRONTEND_BIND_ADDRESS=0.0.0.0' \
  "Jenkins report smoke must publish the DinD Frontend port on its reachable interface"
assert_contains Jenkinsfile \
  'REPORT_SMOKE_PUBLISHED_HOST=docker-builder' \
  "Jenkins report smoke must connect to the DinD service hostname"
assert_contains scripts/container-report-smoke.sh \
  'BASE_URL="http://$SMOKE_PUBLISHED_HOST:$frontend_port"' \
  "container report smoke must use its configured Docker host"
assert_contains scripts/verify-production-resilience.sh \
  'BASE_URL="http://$PUBLISHED_HOST:$published_port"' \
  "resilience smoke must preserve the configured Docker host"
assert_contains scripts/verify-production-runtime.sh \
  'CHECK_DIR="$(mktemp -d "$DOCKER_BIND_WORK_ROOT/production-runtime.XXXXXX")"' \
  "production runtime bind sources must stay on a Docker-visible filesystem"
assert_not_contains scripts/verify-production-runtime.sh \
  'CHECK_DIR="$(mktemp -d)"' \
  "production runtime verification must not bind Jenkins-local /tmp paths"
[ "$(grep -Fc -- '--volume "$mqtt_local:/mqtt-local"' Jenkinsfile)" -eq 2 ] \
  || fail "Jenkins must repair stale MQTT bind ownership before checkout and cleanup"
[ "$(grep -Fc -- 'for target in /mqtt-local/ca /mqtt-local/runtime' Jenkinsfile)" -eq 2 ] \
  || fail "Jenkins MQTT ownership repair must remain limited to ignored generated assets"
[ "$(grep -Fc -- 'chown -R "$WORKSPACE_UID:$WORKSPACE_GID" "$target"' Jenkinsfile)" -eq 2 ] \
  || fail "Jenkins MQTT ownership repair must return assets to the workspace owner"
assert_not_contains Jenkinsfile \
  '--volume "$WORKSPACE:/workspace"' \
  "Jenkins ownership repair must not mount the complete workspace"
repair_line="$(grep -nF -- '--volume "$mqtt_local:/mqtt-local"' Jenkinsfile \
  | sed -n '1s/:.*//p')"
checkout_line="$(grep -nF -- 'checkout scm' Jenkinsfile | sed -n '1s/:.*//p')"
[ -n "$repair_line" ] \
  && [ -n "$checkout_line" ] \
  && [ "$repair_line" -lt "$checkout_line" ] \
  || fail "stale MQTT ownership must be repaired before checkout"
assert_contains compose.yaml \
  '-i gilbom-local-broker-health' \
  "local Mosquitto must expose a TLS and authenticated Docker healthcheck"
assert_contains infra/mosquitto/local/compose.yaml \
  '-i gilbom-local-broker-health' \
  "standalone local Mosquitto must expose the same Docker healthcheck"
[ "$(grep -Fc -- 'condition: service_healthy' compose.yaml)" -ge 3 ] \
  || fail "local Backend must wait for both DB and Mosquitto health"
assert_contains infra/mosquitto/local/init-local-mqtt.sh \
  'STATE_OWNER_UID="$(stat -c' \
  "mqtt-init must discover the bind source owner"
assert_contains infra/mosquitto/local/init-local-mqtt.sh \
  'chown -R "${STATE_OWNER_UID}:1883" "${RUNTIME_DIR}"' \
  "mqtt-init runtime assets must remain removable by the workspace owner"
assert_contains infra/mosquitto/local/init-local-mqtt.sh \
  'chown -R "${STATE_OWNER_UID}:${STATE_OWNER_GID}" "${CA_DIR}"' \
  "mqtt-init CA assets must remain removable by the workspace owner"
assert_not_contains infra/mosquitto/local/init-local-mqtt.sh \
  'chown 0:0 "${CA_KEY_FILE}"' \
  "mqtt-init must not leave a root-owned CA key in the workspace"
assert_contains infra/mosquitto/local/verify-credential-cutover.sh \
  'PASSWORD_FILE_OWNER="${MQTT_CUTOVER_PASSWORD_FILE_OWNER:-${STATE_OWNER_UID}:1883}"' \
  "credential cutover compensation must preserve workspace ownership"
assert_not_contains Jenkinsfile \
  '--network "$ci_network"' \
  "host Docker networks must not be passed to the nested DinD daemon"
assert_not_contains compose.jenkins.yaml \
  'CI_DOCKER_NETWORK:' \
  "Jenkins must not expose a host Docker network name to nested DinD"
assert_contains compose.jenkins.yaml \
  '- "127.0.0.1:8081:8080"' \
  "Jenkins UI must be fixed to 127.0.0.1:8081"
assert_contains compose.jenkins.yaml \
  'host.docker.internal:host-gateway' \
  "Jenkins host gateway mapping is missing"
assert_contains compose.jenkins.yaml \
  'mem_limit: ${JENKINS_MEMORY_LIMIT:-2g}' \
  "Jenkins memory ceiling is missing"
assert_contains compose.jenkins.yaml \
  'mem_limit: ${JENKINS_DIND_MEMORY_LIMIT:-4g}' \
  "DinD memory ceiling is missing"
assert_contains compose.jenkins.yaml \
  'max-size: "${JENKINS_LOG_MAX_SIZE:-10m}"' \
  "Jenkins Docker log size rotation is missing"
assert_contains compose.jenkins.yaml \
  'max-file: "${JENKINS_LOG_MAX_FILES:-3}"' \
  "Jenkins Docker log file rotation is missing"
assert_contains compose.jenkins.yaml \
  '/tmp:rw,exec,nosuid,nodev,size=256m,mode=1777' \
  "Jenkins executable ephemeral tmpfs is required by Git Client askpass"
assert_not_contains compose.jenkins.yaml \
  '/var/run/docker.sock' \
  "Jenkins must not mount the host Docker socket"
assert_not_contains "$jenkins_config" \
  '/var/run/docker.sock' \
  "rendered Jenkins Compose must not mount the host Docker socket"
assert_contains "$jenkins_config" \
  'host_ip: 127.0.0.1' \
  "rendered Jenkins UI must bind only to loopback"
assert_contains "$jenkins_config" \
  'published: "8081"' \
  "rendered Jenkins UI must publish host port 8081"
assert_contains scripts/verify-jenkins-runtime.sh \
  'DOCKER_HOST=tcp://docker-builder:2376' \
  "runtime contract must enforce the Docker Builder TLS endpoint"
assert_contains scripts/verify-jenkins-runtime.sh \
  '/var/run/docker.sock' \
  "runtime contract must inspect Docker socket mounts"
assert_contains infra/jenkins/jenkins.env.example \
  'JENKINS_HOME_DIR=/opt/jenkins/home' \
  "Jenkins persistent path must be under /opt/jenkins"
assert_contains Jenkinsfile \
  "disableConcurrentBuilds(abortPrevious: true)" \
  "Jenkins job concurrency guard is missing"
assert_contains scripts/verify-repository-policy.sh \
  "grep -Ev '(^|/)secrets/\\.gitignore$'" \
  "Jenkins repository policy must allow only the Secret ignore policy file"
assert_contains Jenkinsfile \
  ".artifacts/runtime/build-resources.txt" \
  "build-time resource evidence is not collected"
assert_contains infra/postgres/verify-empty-database.sql \
  'SELECT COUNT(*) = 35' \
  "empty database gate must cover Flyway V1 through V35"
assert_contains infra/postgres/verify-production-database.sql \
  'Production PostGIS/Flyway/approved-system-seed/runtime-role contract: PASS' \
  "production database audit is missing"
assert_contains scripts/verify-day8-ec2.sh \
  '/actuator/health/mqtt' \
  "EC2 audit must verify Backend MQTT health directly"
assert_contains scripts/verify-day8-ec2.sh \
  'Content-Security-Policy' \
  "EC2 audit must verify the actual CSP response"

for service in prometheus loki grafana alloy alloy-logs
do
  grep -Eq "^  ${service}:$" "$monitoring_config" \
    || fail "rendered monitoring service is missing: $service"
done
for service in docker-builder jenkins
do
  grep -Eq "^  ${service}:$" "$jenkins_config" \
    || fail "rendered Jenkins service is missing: $service"
done

echo "Day 8 infrastructure definition: PASS"
