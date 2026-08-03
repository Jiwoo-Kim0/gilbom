#!/bin/sh

set -eu

JENKINS_CONTAINER="${JENKINS_CONTAINER:-gilbom-jenkins-jenkins-1}"
DOCKER_BUILDER_CONTAINER="${DOCKER_BUILDER_CONTAINER:-gilbom-jenkins-docker-builder-1}"

fail() {
  echo "Jenkins runtime verification failed: $1" >&2
  exit 1
}

command -v docker >/dev/null 2>&1 \
  || fail "Docker CLI is required"

for container in "$JENKINS_CONTAINER" "$DOCKER_BUILDER_CONTAINER"
do
  docker inspect "$container" >/dev/null 2>&1 \
    || fail "container is missing: $container"
  [ "$(docker inspect --format '{{.State.Running}}' "$container")" = true ] \
    || fail "container is not running: $container"
  [ "$(docker inspect --format '{{.State.Health.Status}}' "$container")" = healthy ] \
    || fail "container is not healthy: $container"
  [ "$(
    docker inspect \
      --format '{{index .Config.Labels "com.docker.compose.project"}}' \
      "$container"
  )" = gilbom-jenkins ] \
    || fail "container is outside the fixed gilbom-jenkins project: $container"
done

[ "$(
  docker inspect \
    --format '{{index .Config.Labels "com.docker.compose.service"}}' \
    "$JENKINS_CONTAINER"
)" = jenkins ] \
  || fail "Jenkins container has the wrong Compose service label"
[ "$(
  docker inspect \
    --format '{{index .Config.Labels "com.docker.compose.service"}}' \
    "$DOCKER_BUILDER_CONTAINER"
)" = docker-builder ] \
  || fail "Docker Builder container has the wrong Compose service label"

jenkins_mounts="$(
  docker inspect \
    --format '{{range .Mounts}}{{println .Source "->" .Destination}}{{end}}' \
    "$JENKINS_CONTAINER"
)"
if printf '%s\n' "$jenkins_mounts" | grep -Fq '/var/run/docker.sock'; then
  fail "Jenkins has a host Docker socket mount"
fi

jenkins_environment="$(
  docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' \
    "$JENKINS_CONTAINER"
)"
printf '%s\n' "$jenkins_environment" \
  | grep -Fxq 'DOCKER_HOST=tcp://docker-builder:2376' \
  || fail "Jenkins DOCKER_HOST is not the Docker Builder TLS endpoint"
printf '%s\n' "$jenkins_environment" \
  | grep -Fxq 'DOCKER_TLS_VERIFY=1' \
  || fail "Jenkins Docker TLS verification is disabled"
printf '%s\n' "$jenkins_environment" \
  | grep -Fxq 'DOCKER_CERT_PATH=/certs/client' \
  || fail "Jenkins Docker client certificate path is incorrect"

extra_hosts="$(
  docker inspect --format '{{json .HostConfig.ExtraHosts}}' "$JENKINS_CONTAINER"
)"
printf '%s\n' "$extra_hosts" \
  | grep -Fq 'host.docker.internal:host-gateway' \
  || fail "Jenkins host.docker.internal host-gateway mapping is missing"

port_bindings="$(
  docker inspect --format '{{json .HostConfig.PortBindings}}' "$JENKINS_CONTAINER"
)"
[ "$port_bindings" = \
    '{"8080/tcp":[{"HostIp":"127.0.0.1","HostPort":"8081"}]}' ] \
  || fail "Jenkins UI must be exposed only as 127.0.0.1:8081"

[ "$(
  docker inspect --format '{{.HostConfig.Privileged}}' \
    "$DOCKER_BUILDER_CONTAINER"
)" = true ] \
  || fail "Docker Builder DinD container is not privileged"
[ "$(
  docker inspect --format '{{.Config.Image}}' "$DOCKER_BUILDER_CONTAINER"
)" = docker:29.6.2-dind-alpine3.24 ] \
  || fail "Docker Builder does not use the pinned DinD image"
[ "$(
  docker inspect --format '{{.Config.Hostname}}' "$DOCKER_BUILDER_CONTAINER"
)" = docker-builder ] \
  || fail "Docker Builder hostname does not match its TLS certificate endpoint"

builder_port_bindings="$(
  docker inspect \
    --format '{{json .HostConfig.PortBindings}}' \
    "$DOCKER_BUILDER_CONTAINER"
)"
case "$builder_port_bindings" in
  '{}'|'null') ;;
  *) fail "Docker Builder must not publish a host port" ;;
esac

docker exec "$JENKINS_CONTAINER" /bin/sh -ec '
  for command_name in cosign git gzip zstd ssh scp sha256sum jq
  do
    command -v "$command_name" >/dev/null
  done
  jq --null-input --compact-output \
    "{jenkins: true, dockerTls: true}" >/dev/null
  docker info >/dev/null
  docker compose version >/dev/null
  cosign version | grep -Fq "GitVersion:    v3.1.2"
' || fail "Jenkins tools or TLS Docker Builder access are unavailable"

echo "Jenkins runtime verification: PASS"
