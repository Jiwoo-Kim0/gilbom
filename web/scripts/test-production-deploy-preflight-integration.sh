#!/bin/sh

set -eu

fail() {
  echo "Production deploy preflight integration failed: $1" >&2
  exit 1
}

[ "${GILBOM_CONTAINER_TEST:-}" = 1 ] \
  || fail "GILBOM_CONTAINER_TEST=1 is required"
[ -f /.dockerenv ] \
  || fail "this destructive fixture must run only inside a container"
[ "$(id -u)" -eq 0 ] \
  || fail "container fixture must run as root"

PROJECT_DIR=/workspace
release_sha=2222222222222222222222222222222222222222
current_sha=1111111111111111111111111111111111111111
artifact_dir="/opt/gilbom/artifacts/$release_sha"
release_record_dir="/var/lib/gilbom/releases/$release_sha"
fake_bin=/fixture-bin

rm -rf -- /opt/gilbom /var/lib/gilbom/releases "$fake_bin"
install -d -o root -g root -m 0700 \
  /opt/gilbom/artifacts \
  /opt/gilbom/backups \
  /opt/gilbom/deploy \
  /opt/gilbom/deploy-approvals \
  /opt/gilbom/migration-approvals \
  /opt/gilbom/secrets \
  /var/lib/gilbom/releases \
  /run/gilbom-deploy \
  "$artifact_dir/sbom" \
  "$artifact_dir/vulnerabilities" \
  "$fake_bin"

printf 'services: {}\n' > /opt/gilbom/deploy/compose.prod.yaml
printf 'RUNTIME=value\n' > /opt/gilbom/deploy/runtime.env
{
  printf 'BACKEND_IMAGE=gilbom-backend:%s\n' "$current_sha"
  printf 'FRONTEND_IMAGE=gilbom-frontend:%s\n' "$current_sha"
} > /opt/gilbom/deploy/image.env
install -d -o root -g root -m 0700 \
  /opt/gilbom/deploy/infra/scripts \
  /opt/gilbom/backups/fixture-bundle
printf 'backup-fixture\n' > /opt/gilbom/backups/fixture-bundle/state.txt
(
  cd /opt/gilbom/backups/fixture-bundle
  sha256sum state.txt > SHA256SUMS
)
{
  printf '%s\n' '#!/bin/sh'
  printf '%s\n' 'set -eu'
  printf '%s\n' \
    'printf "Backup bundle: /opt/gilbom/backups/fixture-bundle\\n"'
} > /opt/gilbom/deploy/infra/scripts/backup-volumes.sh
chmod 0700 /opt/gilbom/deploy/infra/scripts/backup-volumes.sh
chmod 0600 \
  /opt/gilbom/deploy/compose.prod.yaml \
  /opt/gilbom/deploy/runtime.env \
  /opt/gilbom/deploy/image.env

printf 'archive-fixture\n' > "$artifact_dir/images.tar.zst"
printf '{}\n' > "$artifact_dir/sbom/backend.sbom.json"
printf '{}\n' > "$artifact_dir/sbom/frontend.sbom.json"
printf 'backend findings: none\n' \
  > "$artifact_dir/vulnerabilities/backend.txt"
printf 'frontend findings: none\n' \
  > "$artifact_dir/vulnerabilities/frontend.txt"
jq -n \
  '{
    policyDate: "2026-08-02",
    exceptionCount: 0,
    expiredExceptionCount: 0,
    criticalCount: 0,
    highCount: 0,
    exceptedHighCount: 0,
    violations: [],
    status: "PASS"
  }' > "$artifact_dir/vulnerabilities/policy-summary.json"

backend_id="sha256:$(printf 'a%.0s' $(seq 1 64))"
frontend_id="sha256:$(printf 'b%.0s' $(seq 1 64))"
jq -n \
  --arg release_sha "$release_sha" \
  --arg backend_id "$backend_id" \
  --arg frontend_id "$frontend_id" \
  '
    {
      schemaVersion: 1,
      releaseSha: $release_sha,
      images: {
        backend: {
          name: ("gilbom-backend:" + $release_sha),
          id: $backend_id
        },
        frontend: {
          name: ("gilbom-frontend:" + $release_sha),
          id: $frontend_id
        }
      },
      flyway: {
        latestVersion: "V36",
        migrations: [
          range(1; 37) as $version
          | {
              version: $version,
              file: (
                "V" + ($version | tostring)
                + "__fixture_" + ($version | tostring) + ".sql"
              ),
              sha256: ("c" * 64)
            }
        ]
      }
    }
  ' > "$artifact_dir/build-metadata.json"

(
  cd "$artifact_dir"
  sha256sum images.tar.zst > images.tar.zst.sha256
  find . -type f ! -name SHA256SUMS -print \
    | LC_ALL=C sort \
    | xargs sha256sum > SHA256SUMS
)
chown -R root:root /opt/gilbom /var/lib/gilbom/releases
# Artifact upload root is intentionally root:<upload-group>:0710. The
# immutable per-SHA destination remains entirely root:root and read-only.
chown root:1000 /opt/gilbom/artifacts
chmod 0710 /opt/gilbom/artifacts
find "$artifact_dir" -type d -exec chmod 0550 {} +
find "$artifact_dir" -type f -exec chmod 0440 {} +

{
  printf '%s\n' '#!/bin/sh'
  printf '%s\n' 'set -eu'
  printf '%s\n' 'command_line="$*"'
  printf '%s\n' 'case "$command_line" in'
  printf '%s\n' '  *" config --quiet") exit 0 ;;'
  printf '%s\n' \
    '  *" up --detach --no-build") printf "36\\n" > /tmp/flyway-version; exit 0 ;;'
  printf '%s\n' '  *" ps -q db") printf "fixture-db\\n"; exit 0 ;;'
  printf '%s\n' \
    '  *" ps -q backend") printf "fixture-backend\\n"; exit 0 ;;'
  printf '%s\n' \
    '  *" ps -q frontend") printf "fixture-frontend\\n"; exit 0 ;;'
  printf '%s\n' \
    '  *" ps -q mosquitto") printf "fixture-mosquitto\\n"; exit 0 ;;'
  printf '%s\n' '  *"MAX(version::integer)"*)'
  printf '%s\n' '    if [ -f /tmp/flyway-version ]; then'
  printf '%s\n' '      cat /tmp/flyway-version'
  printf '%s\n' '    else'
  printf '%s\n' '      printf "35\\n"'
  printf '%s\n' '    fi'
  printf '%s\n' '    exit 0'
  printf '%s\n' '    ;;'
  printf '%s\n' '  *"COUNT("*"NOT success"*) printf "0\\n"; exit 0 ;;'
  printf '%s\n' '  *"version::integer, script"*)'
  printf '%s\n' '    maximum=35'
  printf '%s\n' \
    '    [ ! -f /tmp/flyway-version ] || maximum="$(cat /tmp/flyway-version)"'
  printf '%s\n' '    version=1'
  printf '%s\n' '    while [ "$version" -le "$maximum" ]; do'
  printf '%s\n' \
    '      printf "%s|V%s__fixture_%s.sql\\n" "$version" "$version" "$version"'
  printf '%s\n' '      version=$((version + 1))'
  printf '%s\n' '    done'
  printf '%s\n' '    exit 0'
  printf '%s\n' '    ;;'
  printf '%s\n' '  "load") cat >/dev/null; printf "Loaded\\n"; exit 0 ;;'
  printf '%s\n' '  "image inspect gilbom-backend:"*) exit 0 ;;'
  printf '%s\n' '  "image inspect gilbom-frontend:"*) exit 0 ;;'
  printf '%s\n' '  "image inspect --format {{json .RepoTags}} gilbom-backend:"*)'
  printf '%s\n' \
    "    image=\"\${command_line##* }\"; printf '[\"%s\"]\\n' \"\$image\"; exit 0 ;;"
  printf '%s\n' '  "image inspect --format {{json .RepoTags}} gilbom-frontend:"*)'
  printf '%s\n' \
    "    image=\"\${command_line##* }\"; printf '[\"%s\"]\\n' \"\$image\"; exit 0 ;;"
  printf '%s\n' '  "image inspect --format {{.Id}} gilbom-backend:"*)'
  printf '%s\n' \
    '    printf "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\\n"; exit 0 ;;'
  printf '%s\n' '  "image inspect --format {{.Id}} gilbom-frontend:"*)'
  printf '%s\n' \
    '    printf "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\\n"; exit 0 ;;'
  printf '%s\n' '  "inspect --format {{.State.Health.Status}} fixture-db")'
  printf '%s\n' '    printf "healthy\\n"; exit 0 ;;'
  printf '%s\n' '  "inspect --format {{.State.Status}} fixture-"*)'
  printf '%s\n' '    printf "running\\n"; exit 0 ;;'
  printf '%s\n' '  "inspect --format {{if .State.Health}}"*)'
  printf '%s\n' '    printf "healthy\\n"; exit 0 ;;'
  printf '%s\n' '  "exec fixture-backend wget "*"health/readiness")'
  printf '%s\n' \
    "    printf '%s\\n' '{\"status\":\"UP\"}'; exit 0 ;;"
  printf '%s\n' \
    '  "exec fixture-frontend /usr/local/bin/gilbom-nginx-healthcheck") exit 0 ;;'
  printf '%s\n' '  "exec fixture-frontend wget "*"backend:8080/api/health")'
  printf '%s\n' \
    "    printf '%s\\n' '{\"status\":\"UP\"}'; exit 0 ;;"
  printf '%s\n' '  "exec fixture-frontend wget "*"backend:8080/actuator/health")'
  printf '%s\n' \
    "    printf '%s\\n' '{\"status\":\"UP\",\"components\":{\"mqttConnection\":{\"status\":\"UP\"}}}'; exit 0 ;;"
  printf '%s\n' 'esac'
  printf '%s\n' 'echo "unexpected docker fixture invocation: $*" >&2'
  printf '%s\n' 'exit 1'
} > "$fake_bin/docker"
chmod 0755 "$fake_bin/docker"
install -o root -g root -m 0755 "$fake_bin/docker" /usr/local/sbin/docker
{
  printf '%s\n' '#!/bin/sh'
  printf '%s\n' 'set -eu'
  printf '%s\n' 'case "$*" in'
  printf '%s\n' '  "--quiet --test "*) exit 0 ;;'
  printf '%s\n' '  "--quiet -dc "*) cat "$3"; exit 0 ;;'
  printf '%s\n' 'esac'
  printf '%s\n' 'exit 1'
} > "$fake_bin/zstd"
install -o root -g root -m 0755 "$fake_bin/zstd" /usr/local/sbin/zstd

sh "$PROJECT_DIR/infra/scripts/gilbom-deploy-preflight.sh" \
  "$release_sha" > /tmp/deploy-preflight.json
jq -e \
  --arg release_sha "$release_sha" \
  --arg current_sha "$current_sha" \
  '
    .releaseSha == $release_sha
    and .production.currentSha == $current_sha
    and .production.rollbackTargetSha == $current_sha
    and .flyway.currentVersion == 35
    and .flyway.targetVersion == 36
    and (.flyway.pendingMigrations | length == 1)
    and .flyway.pendingMigrations[0].file == "V36__fixture_36.sql"
    and .flyway.compatibility
      == "COMPATIBLE_FORWARD_MIGRATION_REQUIRES_APPROVAL"
  ' /tmp/deploy-preflight.json >/dev/null \
  || fail "preflight evidence is incomplete or incorrect"

approved_at_utc="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
jq -n \
  --arg approved_by "김채운" \
  --arg approved_at_utc "$approved_at_utc" \
  '{
    approvedBy: $approved_by,
    approvedAtUtc: $approved_at_utc
  }' > /tmp/deploy-approval-input.json

bad_digest="$(printf 'd%.0s' $(seq 1 64))"
if sh "$PROJECT_DIR/infra/scripts/gilbom-record-deploy-approval.sh" \
    "$release_sha" "$bad_digest" \
    < /tmp/deploy-approval-input.json >/dev/null 2>&1; then
  fail "approval accepted a preflight digest that was not displayed"
fi
[ ! -e "/opt/gilbom/deploy-approvals/$release_sha.json" ] \
  || fail "bad digest created a deployment approval"

preflight_digest="$(sha256sum /tmp/deploy-preflight.json | awk '{print $1}')"
sh "$PROJECT_DIR/infra/scripts/gilbom-record-deploy-approval.sh" \
  "$release_sha" "$preflight_digest" \
  < /tmp/deploy-approval-input.json > /tmp/deploy-approval.json
jq -e \
  --arg release_sha "$release_sha" \
  '
    .releaseSha == $release_sha
    and .approvedBy == "김채운"
    and (.preflightSha256 | test("^[0-9a-f]{64}$"))
  ' /tmp/deploy-approval.json >/dev/null \
  || fail "deployment approval evidence is invalid"
jq -e \
  --arg release_sha "$release_sha" \
  '
    .releaseSha == $release_sha
    and .approvedBy == "김채운"
    and .source == "jenkins-production-deploy-approval"
  ' "/opt/gilbom/migration-approvals/$release_sha.json" >/dev/null \
  || fail "forward migration approval was not generated"
[ "$(stat -c '%U:%G:%a' "/opt/gilbom/deploy-approvals/$release_sha.json")" \
    = root:root:440 ] \
  || fail "deployment approval ownership or mode is invalid"

sh "$PROJECT_DIR/infra/scripts/gilbom-deploy.sh" "$release_sha" \
  > /tmp/deploy-output.txt
grep -Fq "Production deploy boundary: PASS ($release_sha)" \
  /tmp/deploy-output.txt \
  || fail "root deployment did not report PASS"
[ "$(cat /var/lib/gilbom/releases/current.sha)" = "$release_sha" ] \
  || fail "successful deployment did not update current.sha"
[ "$(cat /var/lib/gilbom/releases/previous.sha)" = "$current_sha" ] \
  || fail "successful deployment did not retain the rollback target"
jq -e \
  --arg release_sha "$release_sha" \
  '
    .releaseSha == $release_sha
    and .approval.approvedBy == "김채운"
    and .flyway.beforeVersion == 35
    and .flyway.afterVersion == 36
    and .health.backendReadiness == "PASS"
    and .health.frontend == "PASS"
    and .health.frontendToBackend == "PASS"
    and .health.mqtt == "PASS"
  ' "$release_record_dir/release-manifest.json" >/dev/null \
  || fail "release manifest does not contain approval and health evidence"

echo "Production deploy preflight and root deploy integration: PASS"
