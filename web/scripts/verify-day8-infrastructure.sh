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

monitoring_config="$(mktemp)"
jenkins_config="$(mktemp)"
trap 'rm -f "$monitoring_config" "$jenkins_config"' EXIT HUP INT TERM

GRAFANA_ADMIN_PASSWORD_SECRET_FILE=/dev/null \
  docker compose -f compose.monitoring.yaml config > "$monitoring_config"
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
assert_contains compose.jenkins.yaml \
  'COMPOSE_PARALLEL_LIMIT: "1"' \
  "Jenkins Compose concurrency must be one"
assert_contains compose.jenkins.yaml \
  'DOCKER_HOST: tcp://docker:2376' \
  "Jenkins must use the TLS DinD endpoint"
assert_contains compose.jenkins.yaml \
  'DOCKER_TLS_VERIFY: "1"' \
  "Jenkins must verify the DinD certificate"
assert_contains Jenkinsfile \
  '--add-host docker:host-gateway' \
  "DinD test container must resolve the TLS daemon through its host gateway"
assert_contains Jenkinsfile \
  '--env GRADLE_USER_HOME="$test_home/.gradle"' \
  "Jenkins non-root Backend test must use a writable isolated Gradle home"
assert_contains Jenkinsfile \
  '--tmpfs "$test_home:rw,nosuid,nodev,uid=$host_uid,gid=$host_gid,mode=0700"' \
  "Jenkins non-root Backend test must isolate writable build state"
assert_contains Jenkinsfile \
  '--volume "$project_dir/backend:$project_dir/backend:ro"' \
  "Jenkins Backend source mount must remain read-only"
assert_not_contains Jenkinsfile \
  '--network "$ci_network"' \
  "host Docker networks must not be passed to the nested DinD daemon"
assert_not_contains compose.jenkins.yaml \
  'CI_DOCKER_NETWORK:' \
  "Jenkins must not expose a host Docker network name to nested DinD"
assert_contains compose.jenkins.yaml \
  '${JENKINS_BIND_ADDRESS:-127.0.0.1}' \
  "Jenkins UI must default to loopback"
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
assert_contains infra/jenkins/jenkins.env.example \
  'JENKINS_HOME_DIR=/opt/jenkins/home' \
  "Jenkins persistent path must be under /opt/jenkins"
assert_contains Jenkinsfile \
  "disableConcurrentBuilds(abortPrevious: true)" \
  "Jenkins job concurrency guard is missing"
assert_contains Jenkinsfile \
  "grep -Ev '(^|/)secrets/\\\\.gitignore$'" \
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
for service in docker jenkins
do
  grep -Eq "^  ${service}:$" "$jenkins_config" \
    || fail "rendered Jenkins service is missing: $service"
done

echo "Day 8 infrastructure definition: PASS"
