#!/bin/sh

set -eu

fail() {
  echo "Production deploy preflight integration failed: $1" >&2
  exit 1
}

[ "${GILBOM_CONTAINER_TEST:-}" = 1 ] \
  || fail "GILBOM_CONTAINER_TEST=1 is required"
if [ ! -f /.dockerenv ]; then
  [ "${GILBOM_USERNS_TEST:-}" = 1 ] \
    && grep -Eq '^[^ ]+ /opt tmpfs ' /proc/mounts \
    || fail \
      "this destructive fixture requires a container or isolated /opt tmpfs"
fi
[ "$(id -u)" -eq 0 ] \
  || fail "container fixture must run as root"

PROJECT_DIR="${GILBOM_TEST_PROJECT_DIR:-/workspace}"
release_sha=2222222222222222222222222222222222222222
current_sha=1111111111111111111111111111111111111111
artifact_dir="/opt/gilbom/artifacts/$release_sha"
source_archive_name="gilbom-web-$release_sha.tar.zst"
source_fixture="/var/tmp/gilbom-source-fixture-$release_sha"
release_record_dir="/var/lib/gilbom/releases/$release_sha"
fake_bin=/opt/gilbom/fixture-bin
fake_image_save=/opt/gilbom/fixture-image-save

rm -rf -- \
  /opt/gilbom /var/lib/gilbom/releases "$source_fixture"
install -d -o root -g root -m 0700 \
  /opt/gilbom/artifacts \
  /opt/gilbom/backups \
  /opt/gilbom/deploy \
  /opt/gilbom/deploy-approvals \
  /opt/gilbom/migration-approvals \
  /opt/gilbom/rollback-approvals \
  /opt/gilbom/secrets \
  /etc/gilbom \
  /var/lib/gilbom/releases \
  /run/gilbom-deploy \
  "$artifact_dir/sbom" \
  "$artifact_dir/vulnerabilities" \
  "$fake_bin" \
  "$fake_image_save"

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
printf '{"fixture":"cosign-bundle"}\n' \
  > "$artifact_dir/images.tar.zst.sigstore.json"
printf 'services: {}\n# signed release %s\n' "$release_sha" \
  > "$artifact_dir/compose.prod.yaml"
install -d "$source_fixture/infra/scripts"
chmod 0775 "$source_fixture/infra"
cp -- "$artifact_dir/compose.prod.yaml" "$source_fixture/compose.prod.yaml"
printf 'services: {}\n# monitoring source fixture\n' \
  > "$source_fixture/compose.monitoring.yaml"
printf '#!/bin/sh\nprintf "day10 fixture\\n"\n' \
  > "$source_fixture/infra/scripts/install-day10-host.sh"
printf '#!/bin/sh\nprintf "Backup bundle: /opt/gilbom/backups/fixture-bundle\\n"\n' \
  > "$source_fixture/infra/scripts/backup-volumes.sh"
chmod 0755 "$source_fixture/infra/scripts/"*.sh
tar -cf "$artifact_dir/$source_archive_name" \
  -C "$source_fixture" .
printf '%s\n' 'fixture-cosign-public-key' \
  > /etc/gilbom/cosign-release.pub
chmod 0444 /etc/gilbom/cosign-release.pub
cosign_public_key_sha256="$(
  sha256sum /etc/gilbom/cosign-release.pub | awk '{print $1}'
)"
compose_sha256="$(
  sha256sum "$artifact_dir/compose.prod.yaml" | awk '{print $1}'
)"
source_archive_sha256="$(
  sha256sum "$artifact_dir/$source_archive_name" | awk '{print $1}'
)"
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
  --arg cosign_public_key_sha256 "$cosign_public_key_sha256" \
  --arg compose_sha256 "$compose_sha256" \
  --arg source_archive_name "$source_archive_name" \
  --arg source_archive_sha256 "$source_archive_sha256" \
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
      artifacts: {
        images: {
          signature: {
            mode: "offline-key",
            cosignVersion: "v3.1.2",
            publicKeySha256: $cosign_public_key_sha256,
            bundle: {path: "images.tar.zst.sigstore.json"}
          }
        },
        source: {
          path: $source_archive_name,
          sha256: $source_archive_sha256
        },
        compose: {
          path: "compose.prod.yaml",
          sha256: $compose_sha256
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
printf '{"fixture":"manifest-cosign-bundle"}\n' \
  > "$artifact_dir/SHA256SUMS.sigstore.json"
chown -R root:root /opt/gilbom /var/lib/gilbom/releases
# Artifact upload root is intentionally root:<upload-group>:0710. The
# immutable per-SHA destination remains entirely root:root and read-only.
if [ "${GILBOM_USERNS_TEST:-}" = 1 ]; then
  chown root:root /opt/gilbom/artifacts
  chmod 0700 /opt/gilbom/artifacts
else
  chown root:1000 /opt/gilbom/artifacts
  chmod 0710 /opt/gilbom/artifacts
fi
find "$artifact_dir" -type d -exec chmod 0550 {} +
find "$artifact_dir" -type f -exec chmod 0440 {} +

jq -n \
  --arg release_sha "$release_sha" \
  '[
    {
      Config: (
        "blobs/sha256/"
        + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
      ),
      RepoTags: ["gilbom-backend:" + $release_sha],
      Layers: []
    },
    {
      Config: (
        "blobs/sha256/"
        + "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
      ),
      RepoTags: ["gilbom-frontend:" + $release_sha],
      Layers: []
    }
  ]' > "$fake_image_save/manifest.json"

{
  printf '%s\n' '#!/bin/sh'
  printf '%s\n' 'set -eu'
  printf '%s\n' 'command_line="$*"'
  printf '%s\n' 'case "$command_line" in'
  printf '%s\n' '  *" config --quiet") exit 0 ;;'
  printf '%s\n' \
    '  *" up --detach --no-build") printf "36\\n" > /opt/gilbom/fixture-flyway-version; exit 0 ;;'
  printf '%s\n' '  *" ps -q db") printf "fixture-db\\n"; exit 0 ;;'
  printf '%s\n' \
    '  *" ps -q backend") printf "fixture-backend\\n"; exit 0 ;;'
  printf '%s\n' \
    '  *" ps -q frontend") printf "fixture-frontend\\n"; exit 0 ;;'
  printf '%s\n' \
    '  *" ps -q mosquitto") printf "fixture-mosquitto\\n"; exit 0 ;;'
  printf '%s\n' '  *"MAX(version::integer)"*)'
  printf '%s\n' '    if [ -f /opt/gilbom/fixture-flyway-version ]; then'
  printf '%s\n' '      cat /opt/gilbom/fixture-flyway-version'
  printf '%s\n' '    else'
  printf '%s\n' '      printf "35\\n"'
  printf '%s\n' '    fi'
  printf '%s\n' '    exit 0'
  printf '%s\n' '    ;;'
  printf '%s\n' '  *"COUNT("*"NOT success"*) printf "0\\n"; exit 0 ;;'
  printf '%s\n' '  *"version::integer, script"*)'
  printf '%s\n' '    maximum=35'
  printf '%s\n' \
    '    [ ! -f /opt/gilbom/fixture-flyway-version ] || maximum="$(cat /opt/gilbom/fixture-flyway-version)"'
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
    '    printf "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc\\n"; exit 0 ;;'
  printf '%s\n' '  "image inspect --format {{.Id}} gilbom-frontend:"*)'
  printf '%s\n' \
    '    printf "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd\\n"; exit 0 ;;'
  printf '%s\n' '  "image save gilbom-backend:"*" gilbom-frontend:"*)'
  printf '%s\n' \
    '    exec tar -cf - -C /opt/gilbom/fixture-image-save manifest.json ;;'
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
{
  printf '%s\n' '#!/bin/sh'
  printf '%s\n' 'set -eu'
  printf '%s\n' 'case "$1" in'
  printf '%s\n' \
    '  version) printf "GitVersion:    v3.1.2\\n"; exit 0 ;;'
  printf '%s\n' '  verify-blob)'
  printf '%s\n' \
    '    [ ! -e /opt/gilbom/cosign-reject ] || exit 1'
  printf '%s\n' '    exit 0'
  printf '%s\n' '    ;;'
  printf '%s\n' 'esac'
  printf '%s\n' 'exit 1'
} > "$fake_bin/cosign"
install -o root -g root -m 0755 "$fake_bin/cosign" /usr/local/bin/cosign

touch /opt/gilbom/cosign-reject
if sh "$PROJECT_DIR/infra/scripts/gilbom-deploy-preflight.sh" \
    "$release_sha" >/dev/null 2>&1; then
  fail "preflight accepted an invalid Cosign signature"
fi
rm -f -- /opt/gilbom/cosign-reject
sh "$PROJECT_DIR/infra/scripts/gilbom-deploy-preflight.sh" \
  "$release_sha" > /var/tmp/deploy-preflight.json
jq -e \
  --arg release_sha "$release_sha" \
  --arg current_sha "$current_sha" \
  --arg source_archive_sha256 "$source_archive_sha256" \
  '
    .releaseSha == $release_sha
    and .source.archive == ("gilbom-web-" + $release_sha + ".tar.zst")
    and .source.sha256 == $source_archive_sha256
    and .production.currentSha == $current_sha
    and .production.rollbackTargetSha == $current_sha
    and .flyway.currentVersion == 35
    and .flyway.targetVersion == 36
    and (.flyway.pendingMigrations | length == 1)
    and .flyway.pendingMigrations[0].file == "V36__fixture_36.sql"
    and .flyway.compatibility
      == "COMPATIBLE_FORWARD_MIGRATION_REQUIRES_APPROVAL"
  ' /var/tmp/deploy-preflight.json >/dev/null \
  || fail "preflight evidence is incomplete or incorrect"

approved_at_utc="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
jq -n \
  --arg approved_by "CHAEUN KIM" \
  --arg approved_at_utc "$approved_at_utc" \
  '{
    approvedBy: $approved_by,
    approvedAtUtc: $approved_at_utc
  }' > /var/tmp/deploy-approval-input.json

bad_digest="$(printf 'd%.0s' $(seq 1 64))"
if sh "$PROJECT_DIR/infra/scripts/gilbom-record-deploy-approval.sh" \
    "$release_sha" "$bad_digest" \
    < /var/tmp/deploy-approval-input.json >/dev/null 2>&1; then
  fail "approval accepted a preflight digest that was not displayed"
fi
[ ! -e "/opt/gilbom/deploy-approvals/$release_sha.json" ] \
  || fail "bad digest created a deployment approval"

preflight_digest="$(sha256sum /var/tmp/deploy-preflight.json | awk '{print $1}')"
sh "$PROJECT_DIR/infra/scripts/gilbom-record-deploy-approval.sh" \
  "$release_sha" "$preflight_digest" \
  < /var/tmp/deploy-approval-input.json > /var/tmp/deploy-approval.json
jq -e \
  --arg release_sha "$release_sha" \
  '
    .releaseSha == $release_sha
    and .approvedBy == "CHAEUN KIM"
    and (.preflightSha256 | test("^[0-9a-f]{64}$"))
  ' /var/tmp/deploy-approval.json >/dev/null \
  || fail "deployment approval evidence is invalid"
jq -e \
  --arg release_sha "$release_sha" \
  '
    .releaseSha == $release_sha
    and .approvedBy == "CHAEUN KIM"
    and .source == "jenkins-production-deploy-approval"
  ' "/opt/gilbom/migration-approvals/$release_sha.json" >/dev/null \
  || fail "forward migration approval was not generated"
[ "$(stat -c '%U:%G:%a' "/opt/gilbom/deploy-approvals/$release_sha.json")" \
    = root:root:440 ] \
  || fail "deployment approval ownership or mode is invalid"

cp /var/tmp/deploy-preflight.json /var/tmp/original-deploy-preflight.json
cp /var/tmp/deploy-approval.json /var/tmp/original-deploy-approval.json
sh "$PROJECT_DIR/infra/scripts/gilbom-deploy-preflight.sh" \
  "$release_sha" > /var/tmp/retry-deploy-preflight.json
cmp -s \
  /var/tmp/original-deploy-preflight.json \
  /var/tmp/retry-deploy-preflight.json \
  || fail "technical retry did not preserve the immutable approved preflight"
sh "$PROJECT_DIR/infra/scripts/gilbom-record-deploy-approval.sh" \
  "$release_sha" "$preflight_digest" \
  < /var/tmp/deploy-approval-input.json > /var/tmp/retry-deploy-approval.json
cmp -s \
  /var/tmp/original-deploy-approval.json \
  /var/tmp/retry-deploy-approval.json \
  || fail "technical retry did not reuse the immutable deployment approval"

sh "$PROJECT_DIR/infra/scripts/gilbom-deploy.sh" "$release_sha" \
  > /var/tmp/deploy-output.txt
grep -Fq "Production deploy boundary: PASS ($release_sha)" \
  /var/tmp/deploy-output.txt \
  || fail "root deployment did not report PASS"
[ "$(cat /var/lib/gilbom/releases/current.sha)" = "$release_sha" ] \
  || fail "successful deployment did not update current.sha"
[ "$(cat /var/lib/gilbom/releases/previous.sha)" = "$current_sha" ] \
  || fail "successful deployment did not retain the rollback target"
cmp -s "$artifact_dir/compose.prod.yaml" /opt/gilbom/deploy/compose.prod.yaml \
  || fail "successful deployment did not install signed release Compose"
[ "$(cat /opt/gilbom/deploy/.release-source.sha)" = "$release_sha" ] \
  || fail "successful deployment did not activate the release source tree"
[ -x /opt/gilbom/deploy/infra/scripts/install-day10-host.sh ] \
  || fail "successful deployment did not install the Day 10 host script"
[ "$(stat -c '%a' /opt/gilbom/deploy/infra)" = 755 ] \
  || fail "Git archive directory mode was not normalized to 0755"
grep -Fxq 'RUNTIME=value' /opt/gilbom/deploy/runtime.env \
  || fail "source tree activation did not preserve runtime.env"
grep -Fq 'services: {}' \
  "$release_record_dir/previous-compose.prod.yaml" \
  || fail "successful deployment did not preserve the prior Compose snapshot"
jq -e \
  --arg release_sha "$release_sha" \
  '
    .releaseSha == $release_sha
    and .approval.approvedBy == "CHAEUN KIM"
    and .images.backend.id
      == "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    and .images.backend.engineId
      == "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    and .images.frontend.id
      == "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    and .images.frontend.engineId
      == "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    and .flyway.beforeVersion == 35
    and .flyway.afterVersion == 36
    and .health.backendReadiness == "PASS"
    and .health.frontend == "PASS"
    and .health.frontendToBackend == "PASS"
    and .health.mqtt == "PASS"
    and .source.archive == ("gilbom-web-" + $release_sha + ".tar.zst")
    and (.source.sha256 | test("^[0-9a-f]{64}$"))
    and .compose.path == "compose.prod.yaml"
    and .compose.sha256 == .composeSha256
    and (
      .artifact.signature.manifestBundleSha256
      | test("^[0-9a-f]{64}$")
    )
  ' "$release_record_dir/release-manifest.json" >/dev/null \
  || fail "release manifest does not contain approval and health evidence"

echo "Production deploy preflight and root deploy integration: PASS"
