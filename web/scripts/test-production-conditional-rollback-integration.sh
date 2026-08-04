#!/bin/sh

set -eu

fail() {
  echo "Production conditional rollback integration failed: $1" >&2
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
  || fail "fixture must run as root"

scenario="${GILBOM_ROLLBACK_SCENARIO:-}"
case "$scenario" in
  automatic-no-migration) failed_target_version=35 ;;
  changed-migration-manual) failed_target_version=36 ;;
  *) fail "unknown GILBOM_ROLLBACK_SCENARIO" ;;
esac

PROJECT_DIR="${GILBOM_TEST_PROJECT_DIR:-/workspace}"
previous_sha=1111111111111111111111111111111111111111
failed_sha=2222222222222222222222222222222222222222
fake_bin=/opt/gilbom/fixture-bin
fake_image_save=/opt/gilbom/fixture-image-save
fake_state=/opt/gilbom/fixture-state

rm -rf -- /opt/gilbom /var/lib/gilbom/releases
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
  "$fake_bin" \
  "$fake_state" \
  "$fake_image_save/previous" \
  "$fake_image_save/failed"

printf 'services: {}\n# active previous compose\n' \
  > /opt/gilbom/deploy/compose.prod.yaml
printf 'RUNTIME=value\n' > /opt/gilbom/deploy/runtime.env
{
  printf 'BACKEND_IMAGE=gilbom-backend:%s\n' "$previous_sha"
  printf 'FRONTEND_IMAGE=gilbom-frontend:%s\n' "$previous_sha"
} > /opt/gilbom/deploy/image.env
printf '%s\n' "$previous_sha" > /var/lib/gilbom/releases/current.sha
printf '%s\n' "$previous_sha" > /var/lib/gilbom/releases/previous.sha

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
chmod 0440 \
  /var/lib/gilbom/releases/current.sha \
  /var/lib/gilbom/releases/previous.sha
printf '%s\n' 'fixture-cosign-public-key' \
  > /etc/gilbom/cosign-release.pub
chmod 0444 /etc/gilbom/cosign-release.pub
cosign_public_key_sha256="$(
  sha256sum /etc/gilbom/cosign-release.pub | awk '{print $1}'
)"

create_artifact() {
  release_sha="$1"
  target_version="$2"
  backend_character="$3"
  frontend_character="$4"
  artifact_dir="/opt/gilbom/artifacts/$release_sha"
  install -d -o root -g root -m 0750 \
    "$artifact_dir/sbom" "$artifact_dir/vulnerabilities"
  printf 'archive-%s\n' "$release_sha" > "$artifact_dir/images.tar.zst"
  printf '{"fixture":"cosign-bundle","releaseSha":"%s"}\n' "$release_sha" \
    > "$artifact_dir/images.tar.zst.sigstore.json"
  printf 'services: {}\n# signed release %s\n' "$release_sha" \
    > "$artifact_dir/compose.prod.yaml"
  printf '{}\n' > "$artifact_dir/sbom/backend.sbom.json"
  printf '{}\n' > "$artifact_dir/sbom/frontend.sbom.json"
  printf 'backend findings: none\n' \
    > "$artifact_dir/vulnerabilities/backend.txt"
  printf 'frontend findings: none\n' \
    > "$artifact_dir/vulnerabilities/frontend.txt"
  jq -n \
    '{
      criticalCount: 0,
      highCount: 0,
      exceptedHighCount: 0,
      status: "PASS"
    }' > "$artifact_dir/vulnerabilities/policy-summary.json"
  jq -n \
    --arg release_sha "$release_sha" \
    --arg backend_character "$backend_character" \
    --arg frontend_character "$frontend_character" \
    --arg cosign_public_key_sha256 "$cosign_public_key_sha256" \
    --arg compose_sha256 "$(
      sha256sum "$artifact_dir/compose.prod.yaml" | awk '{print $1}'
    )" \
    --argjson target_version "$target_version" \
    '
      {
        schemaVersion: 1,
        releaseSha: $release_sha,
        images: {
          backend: {
            name: ("gilbom-backend:" + $release_sha),
            id: ("sha256:" + ($backend_character * 64))
          },
          frontend: {
            name: ("gilbom-frontend:" + $release_sha),
            id: ("sha256:" + ($frontend_character * 64))
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
          compose: {
            path: "compose.prod.yaml",
            sha256: $compose_sha256
          }
        },
        flyway: {
          latestVersion: ("V" + ($target_version | tostring)),
          migrations: [
            range(1; $target_version + 1) as $version
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
  printf '{"fixture":"manifest-cosign-bundle","releaseSha":"%s"}\n' \
    "$release_sha" > "$artifact_dir/SHA256SUMS.sigstore.json"
}

create_artifact "$previous_sha" 35 e f
create_artifact "$failed_sha" "$failed_target_version" a b

jq -n \
  --arg previous_sha "$previous_sha" \
  '[
    {
      Config: (
        "blobs/sha256/"
        + "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
      ),
      RepoTags: ["gilbom-backend:" + $previous_sha],
      Layers: []
    },
    {
      Config: (
        "blobs/sha256/"
        + "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
      ),
      RepoTags: ["gilbom-frontend:" + $previous_sha],
      Layers: []
    }
  ]' > "$fake_image_save/previous/manifest.json"
jq -n \
  --arg failed_sha "$failed_sha" \
  '[
    {
      Config: (
        "blobs/sha256/"
        + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
      ),
      RepoTags: ["gilbom-backend:" + $failed_sha],
      Layers: []
    },
    {
      Config: (
        "blobs/sha256/"
        + "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
      ),
      RepoTags: ["gilbom-frontend:" + $failed_sha],
      Layers: []
    }
  ]' > "$fake_image_save/failed/manifest.json"

printf '%s\n' "$scenario" > "$fake_state/scenario"
printf '%s\n' "$failed_sha" > "$fake_state/failed-sha"
printf '%s\n' "$previous_sha" > "$fake_state/previous-sha"
printf '35\n' > "$fake_state/flyway-version"

{
  printf '%s\n' '#!/bin/sh'
  printf '%s\n' 'set -eu'
  printf '%s\n' 'command_line="$*"'
  printf '%s\n' 'scenario="$(cat /opt/gilbom/fixture-state/scenario)"'
  printf '%s\n' 'failed_sha="$(cat /opt/gilbom/fixture-state/failed-sha)"'
  printf '%s\n' 'previous_sha="$(cat /opt/gilbom/fixture-state/previous-sha)"'
  printf '%s\n' \
    'active_sha="$(sed -n "s/^BACKEND_IMAGE=gilbom-backend:\\([0-9a-f]\\{40\\}\\)$/\\1/p" /opt/gilbom/deploy/image.env)"'
  printf '%s\n' 'case "$command_line" in'
  printf '%s\n' '  *" config --quiet") exit 0 ;;'
  printf '%s\n' '  *" logs --no-color --timestamps "*)'
  printf '%s\n' '    printf "fixture failure logs\\n"; exit 0 ;;'
  printf '%s\n' '  *" stop frontend")'
  printf '%s\n' \
    '    touch /opt/gilbom/fixture-state/frontend-stopped; exit 0 ;;'
  printf '%s\n' '  *" up --detach --no-build"*)'
  printf '%s\n' \
    '    if [ "$active_sha" = "$failed_sha" ] && [ "$scenario" = changed-migration-manual ]; then'
  printf '%s\n' \
    '      printf "36\\n" > /opt/gilbom/fixture-state/flyway-version'
  printf '%s\n' '    fi'
  printf '%s\n' '    exit 0'
  printf '%s\n' '    ;;'
  printf '%s\n' '  *" ps -q db") printf "fixture-db\\n"; exit 0 ;;'
  printf '%s\n' \
    '  *" ps -q backend") printf "fixture-backend\\n"; exit 0 ;;'
  printf '%s\n' \
    '  *" ps -q frontend") printf "fixture-frontend\\n"; exit 0 ;;'
  printf '%s\n' \
    '  *" ps -q mosquitto") printf "fixture-mosquitto\\n"; exit 0 ;;'
  printf '%s\n' '  *"MAX(version::integer)"*)'
  printf '%s\n' \
    '    cat /opt/gilbom/fixture-state/flyway-version; exit 0 ;;'
  printf '%s\n' '  *"COUNT("*"NOT success"*) printf "0\\n"; exit 0 ;;'
  printf '%s\n' '  *"version::integer, script"*)'
  printf '%s\n' \
    '    maximum="$(cat /opt/gilbom/fixture-state/flyway-version)"'
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
  printf '%s\n' \
    '  "image inspect --format {{json .RepoTags}} gilbom-backend:"*)'
  printf '%s\n' \
    "    image=\"\${command_line##* }\"; printf '[\"%s\"]\\n' \"\$image\"; exit 0 ;;"
  printf '%s\n' \
    '  "image inspect --format {{json .RepoTags}} gilbom-frontend:"*)'
  printf '%s\n' \
    "    image=\"\${command_line##* }\"; printf '[\"%s\"]\\n' \"\$image\"; exit 0 ;;"
  printf '%s\n' '  "image inspect --format {{.Id}} gilbom-backend:"*)'
  printf '%s\n' \
    '    printf "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc\\n"; exit 0 ;;'
  printf '%s\n' '  "image inspect --format {{.Id}} gilbom-frontend:"*)'
  printf '%s\n' \
    '    printf "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd\\n"; exit 0 ;;'
  printf '%s\n' '  "image save gilbom-backend:"*)'
  printf '%s\n' '    case "$command_line" in'
  printf '%s\n' '      *"$failed_sha"*)'
  printf '%s\n' \
    '        exec tar -cf - -C /opt/gilbom/fixture-image-save/failed manifest.json ;;'
  printf '%s\n' '      *"$previous_sha"*)'
  printf '%s\n' \
    '        exec tar -cf - -C /opt/gilbom/fixture-image-save/previous manifest.json ;;'
  printf '%s\n' '    esac'
  printf '%s\n' '    ;;'
  printf '%s\n' '  "inspect --format {{.State.Health.Status}} fixture-db")'
  printf '%s\n' '    printf "healthy\\n"; exit 0 ;;'
  printf '%s\n' '  "inspect --format {{.State.Status}} fixture-"*)'
  printf '%s\n' '    printf "running\\n"; exit 0 ;;'
  printf '%s\n' '  "inspect --format {{if .State.Health}}"*)'
  printf '%s\n' \
    '    if [ "$command_line" = "inspect --format {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} fixture-backend" ] && [ "$active_sha" = "$failed_sha" ]; then'
  printf '%s\n' '      printf "unhealthy\\n"'
  printf '%s\n' '    else'
  printf '%s\n' '      printf "healthy\\n"'
  printf '%s\n' '    fi'
  printf '%s\n' '    exit 0'
  printf '%s\n' '    ;;'
  printf '%s\n' '  "exec fixture-backend wget "*"health/readiness")'
  printf '%s\n' \
    "    printf '%s\\n' '{\"status\":\"UP\"}'; exit 0 ;;"
  printf '%s\n' \
    '  "exec fixture-frontend /usr/local/bin/gilbom-nginx-healthcheck") exit 0 ;;'
  printf '%s\n' '  "exec fixture-frontend /bin/sh -ec "*)'
  printf '%s\n' \
    "    printf '%s\\n' '{\"status\":\"UP\"}'; exit 0 ;;"
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
  printf '%s\n' 'case "$1" in'
  printf '%s\n' \
    '  version) printf "GitVersion:    v3.1.2\\n"; exit 0 ;;'
  printf '%s\n' '  verify-blob) exit 0 ;;'
  printf '%s\n' 'esac'
  printf '%s\n' 'exit 1'
} > "$fake_bin/cosign"
install -o root -g root -m 0755 "$fake_bin/cosign" /usr/local/bin/cosign

chown -R root:root /opt/gilbom /var/lib/gilbom/releases
find "/opt/gilbom/artifacts/$previous_sha" \
  "/opt/gilbom/artifacts/$failed_sha" \
  -type d -exec chmod 0550 {} +
find "/opt/gilbom/artifacts/$previous_sha" \
  "/opt/gilbom/artifacts/$failed_sha" \
  -type f -exec chmod 0440 {} +

sh "$PROJECT_DIR/infra/scripts/gilbom-deploy-preflight.sh" \
  "$failed_sha" > /var/tmp/deploy-preflight.json
approved_at_utc="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
jq -n \
  --arg approved_at_utc "$approved_at_utc" \
  '{
    approvedBy: "CHAEUN KIM",
    approvedAtUtc: $approved_at_utc
  }' > /var/tmp/approval-input.json
preflight_digest="$(
  sha256sum /var/tmp/deploy-preflight.json | awk '{print $1}'
)"
sh "$PROJECT_DIR/infra/scripts/gilbom-record-deploy-approval.sh" \
  "$failed_sha" "$preflight_digest" \
  < /var/tmp/approval-input.json > /var/tmp/deploy-approval.json

if sh "$PROJECT_DIR/infra/scripts/gilbom-deploy.sh" "$failed_sha" \
    > /var/tmp/failed-deploy-output.txt 2>&1; then
  fail "injected Backend health failure did not fail deployment"
fi
[ "$(cat /var/lib/gilbom/releases/current.sha)" = "$previous_sha" ] \
  || fail "failed deploy changed current.sha"

failed_record_dir="/var/lib/gilbom/releases/$failed_sha"
case "$scenario" in
  automatic-no-migration)
    jq -e \
      --arg failed_sha "$failed_sha" \
      --arg previous_sha "$previous_sha" \
      '
        .status == "AUTOMATIC_ROLLBACK_SUCCEEDED"
        and .failedReleaseSha == $failed_sha
        and .rollbackTargetSha == $previous_sha
        and .trigger.flywayBeforeVersion == 35
        and .trigger.flywayAfterFailureVersion == 35
        and .decision == "AUTO_ALLOWED_NO_FLYWAY_CHANGE"
        and .health.backendReadiness == "PASS"
        and .health.frontend == "PASS"
        and .health.internalHttps == "PASS"
        and .databaseRecovery.migrationUndoPerformed == false
        and .databaseRecovery.backupRestorePerformed == false
      ' "$failed_record_dir/rollback-manifest.json" >/dev/null \
      || fail "automatic rollback Manifest is invalid"
    [ "$(sed -n 's/^BACKEND_IMAGE=gilbom-backend://p' \
      /opt/gilbom/deploy/image.env)" = "$previous_sha" ] \
      || fail "automatic rollback did not restore image.env"
    grep -Fq '# active previous compose' \
      /opt/gilbom/deploy/compose.prod.yaml \
      || fail "automatic rollback did not restore previous Compose"
    [ ! -e "$failed_record_dir/manual-recovery-required.json" ] \
      || fail "automatic rollback incorrectly requested manual recovery"
    echo "Conditional rollback A integration: PASS"
    ;;

  changed-migration-manual)
    jq -e \
      --arg failed_sha "$failed_sha" \
      --arg previous_sha "$previous_sha" \
      '
        .status == "MANUAL_RECOVERY_REQUIRED"
        and .failedReleaseSha == $failed_sha
        and .rollbackTargetSha == $previous_sha
        and .flyway.beforeVersion == 35
        and .flyway.afterVersion == 36
        and .flyway.changed == true
        and .automaticRollbackAllowed == false
        and .services.frontend == "STOPPED"
        and .services.backend == "PRESERVED"
        and .services.database == "PRESERVED"
        and .databaseRecovery.migrationUndoPerformed == false
        and .databaseRecovery.backupRestorePerformed == false
      ' "$failed_record_dir/manual-recovery-required.json" >/dev/null \
      || fail "manual recovery Manifest is invalid"
    [ "$(sed -n 's/^BACKEND_IMAGE=gilbom-backend://p' \
      /opt/gilbom/deploy/image.env)" = "$failed_sha" ] \
      || fail "manual containment replaced the failed Backend"
    grep -Fq "# signed release $failed_sha" \
      /opt/gilbom/deploy/compose.prod.yaml \
      || fail "manual containment did not preserve the failed release Compose"
    [ -f "$fake_state/frontend-stopped" ] \
      || fail "manual containment did not stop Frontend"
    [ ! -e "$failed_record_dir/rollback-manifest.json" ] \
      || fail "Flyway-changing failure was rolled back automatically"

    if sh "$PROJECT_DIR/infra/scripts/gilbom-deploy.sh" "$previous_sha" \
        >/var/tmp/unapproved-rollback-output.txt 2>&1; then
      fail "previous SHA ran without a second compatibility approval"
    fi
    grep -Fq 'rollback-approvals' /var/tmp/unapproved-rollback-output.txt \
      || fail "unapproved rollback was not blocked on exact approval"

    sh "$PROJECT_DIR/infra/scripts/gilbom-rollback-preflight.sh" \
      "$failed_sha" > /var/tmp/rollback-preflight.json
    rollback_preflight_digest="$(
      sha256sum /var/tmp/rollback-preflight.json | awk '{print $1}'
    )"
    approved_at_utc="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    jq -n \
      --arg approved_at_utc "$approved_at_utc" \
      '{
        approvedBy: "CHAEUN KIM",
        approvedAtUtc: $approved_at_utc
      }' > /var/tmp/rollback-approval-input.json
    sh "$PROJECT_DIR/infra/scripts/gilbom-record-rollback-approval.sh" \
      "$failed_sha" "$rollback_preflight_digest" \
      < /var/tmp/rollback-approval-input.json \
      > /var/tmp/rollback-approval.json

    sh "$PROJECT_DIR/infra/scripts/gilbom-deploy.sh" "$previous_sha" \
      > /var/tmp/approved-rollback-output.txt
    grep -Fq \
      "Production approved rollback: PASS ($previous_sha)" \
      /var/tmp/approved-rollback-output.txt \
      || fail "approved rollback did not report PASS"
    [ "$(cat "$fake_state/flyway-version")" = 36 ] \
      || fail "approved image rollback changed Flyway"
    [ "$(sed -n 's/^BACKEND_IMAGE=gilbom-backend://p' \
      /opt/gilbom/deploy/image.env)" = "$previous_sha" ] \
      || fail "approved rollback did not restore previous image.env"
    grep -Fq '# active previous compose' \
      /opt/gilbom/deploy/compose.prod.yaml \
      || fail "approved rollback did not restore previous Compose"
    jq -e \
      --arg failed_sha "$failed_sha" \
      --arg previous_sha "$previous_sha" \
      '
        .status == "MANUALLY_APPROVED_ROLLBACK_SUCCEEDED"
        and .failedReleaseSha == $failed_sha
        and .rollbackTargetSha == $previous_sha
        and .decision == "APPROVED_PREVIOUS_BACKEND_COMPATIBLE"
        and .flyway.beforeRollbackVersion == 36
        and .flyway.afterRollbackVersion == 36
        and .approval.approvedBy == "CHAEUN KIM"
        and .databaseRecovery.migrationUndoPerformed == false
        and .databaseRecovery.backupRestorePerformed == false
      ' "$failed_record_dir/approved-rollback-manifest.json" >/dev/null \
      || fail "approved rollback Manifest is invalid"
    echo "Conditional rollback B/C integration: PASS"
    ;;
esac
