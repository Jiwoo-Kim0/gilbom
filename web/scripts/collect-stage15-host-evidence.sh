#!/bin/sh

set -eu
set +x

PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
HOME=/root
export PATH HOME
unset CDPATH ENV BASH_ENV

JENKINS_CONTAINER=gilbom-jenkins-jenkins-1
DOCKER_BUILDER_CONTAINER=gilbom-jenkins-docker-builder-1
RELEASE_ROOT=/var/lib/gilbom/releases
CURRENT_SHA_PATH="$RELEASE_ROOT/current.sha"
PREVIOUS_SHA_PATH="$RELEASE_ROOT/previous.sha"
DEPLOY_COMMAND=/usr/local/sbin/gilbom-deploy

fail() {
  echo "Stage 15 host evidence collection failed: $1" >&2
  exit 1
}

[ "$(id -u)" -eq 0 ] \
  || fail "run this collector as root"
[ "$#" -eq 0 ] \
  || fail "this collector accepts no arguments"
for command in docker jq stat id grep
do
  command -v "$command" >/dev/null 2>&1 \
    || fail "required command is missing: $command"
done

for container in "$JENKINS_CONTAINER" "$DOCKER_BUILDER_CONTAINER"
do
  docker inspect "$container" >/dev/null 2>&1 \
    || fail "required Jenkins container is missing: $container"
  [ "$(docker inspect --format '{{.State.Running}}' "$container")" = true ] \
    || fail "required Jenkins container is not running: $container"
done

jenkins_mounts="$(
  docker inspect \
    --format '{{range .Mounts}}{{println .Source "->" .Destination}}{{end}}' \
    "$JENKINS_CONTAINER"
)"
if printf '%s\n' "$jenkins_mounts" | grep -Fq '/var/run/docker.sock'; then
  fail "Jenkins has a Docker Socket mount"
fi
jenkins_environment="$(
  docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' \
    "$JENKINS_CONTAINER"
)"
printf '%s\n' "$jenkins_environment" \
  | grep -Fxq 'DOCKER_HOST=tcp://docker-builder:2376' \
  || fail "Jenkins does not use the fixed DinD TLS endpoint"
printf '%s\n' "$jenkins_environment" | grep -Fxq 'DOCKER_TLS_VERIFY=1' \
  || fail "Jenkins Docker TLS verification is disabled"
printf '%s\n' "$jenkins_environment" \
  | grep -Fxq 'DOCKER_CERT_PATH=/certs/client' \
  || fail "Jenkins Docker client certificate path is invalid"
docker exec "$JENKINS_CONTAINER" docker info >/dev/null 2>&1 \
  || fail "Jenkins cannot establish the DinD TLS Docker connection"

deploy_groups="$(id -nG deploy)"
case " $deploy_groups " in
  *" docker "*) fail "deploy account belongs to the Docker group" ;;
esac

for state_file in "$CURRENT_SHA_PATH" "$PREVIOUS_SHA_PATH"
do
  [ -f "$state_file" ] && [ ! -L "$state_file" ] \
    || fail "production state file is missing or symlinked: $state_file"
  [ "$(stat -c '%U:%G:%a' "$state_file")" = root:root:440 ] \
    || fail "production state file ownership or mode is invalid: $state_file"
  grep -Eq '^[0-9a-f]{40}$' "$state_file" \
    || fail "production state file does not contain a full SHA: $state_file"
done
current_sha="$(cat "$CURRENT_SHA_PATH")"
previous_sha="$(cat "$PREVIOUS_SHA_PATH")"
manifest="$RELEASE_ROOT/$current_sha/release-manifest.json"
[ -f "$manifest" ] && [ ! -L "$manifest" ] && [ -s "$manifest" ] \
  || fail "current Release Manifest is missing or symlinked"
[ -f "$DEPLOY_COMMAND" ] && [ ! -L "$DEPLOY_COMMAND" ] \
  || fail "fixed root deploy command is missing or symlinked"
grep -Fq 'compose up --detach --no-build' "$DEPLOY_COMMAND" \
  || fail "fixed root deploy command does not enforce --no-build"

jq -e \
  --arg current "$current_sha" \
  '
    .releaseSha == $current
    and .health.backendReadiness == "PASS"
    and .health.frontend == "PASS"
    and .health.frontendToBackend == "PASS"
    and .health.mqtt == "PASS"
  ' "$manifest" >/dev/null \
  || fail "current Release Manifest does not match state or health evidence"

for service in backend frontend
do
  container="gilbom-$service-1"
  docker inspect "$container" >/dev/null 2>&1 \
    || fail "production container is missing: $container"
  [ "$(docker inspect --format '{{.State.Running}}' "$container")" = true ] \
    || fail "production container is not running: $container"
  [ "$(docker inspect --format '{{.State.Health.Status}}' "$container")" = healthy ] \
    || fail "production container is not healthy: $container"
  actual_image="$(docker inspect --format '{{.Image}}' "$container")"
  expected_image="$(jq -r ".images.$service.engineId" "$manifest")"
  [ "$actual_image" = "$expected_image" ] \
    || fail "production container image differs from the Release Manifest: $service"
done

captured_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
jq -n \
  --arg capturedAtUtc "$captured_at" \
  --arg currentSha "$current_sha" \
  --arg previousSha "$previous_sha" \
  --arg releaseManifest "$manifest" \
  '{
    schemaVersion: 1,
    status: "PASS",
    capturedAtUtc: $capturedAtUtc,
    jenkins: {
      dockerSocketMounted: false,
      dindTls: true,
      dockerHost: "tcp://docker-builder:2376",
      tlsVerification: true
    },
    production: {
      deployDockerGroupMember: false,
      composeNoBuildContract: "PASS",
      currentSha: $currentSha,
      previousSha: $previousSha,
      releaseManifestPath: $releaseManifest,
      releaseManifest: "PASS",
      health: "PASS"
    }
  }'
