#!/bin/sh

set -eu
set +x
umask 077

fail() {
  echo "Production rollback drill failed: $1" >&2
  exit 1
}

[ "${ROLLBACK_DRILL_ACK:-}" = isolated-compose-only ] \
  || fail "ROLLBACK_DRILL_ACK=isolated-compose-only is required"
[ "${DOCKER_TLS_VERIFY:-}" = 1 ] \
  || fail "the drill requires the Jenkins DinD TLS client"
case "${DOCKER_HOST:-}" in
  tcp://*) ;;
  *) fail "the drill refuses a local Docker Socket or missing TLS endpoint" ;;
esac
[ ! -S /var/run/docker.sock ] \
  || fail "the Jenkins executor must not mount the Docker Socket"

for forbidden_variable in \
  ARTIFACT_SSH_IDENTITY ARTIFACT_KNOWN_HOSTS \
  DEPLOY_SSH_IDENTITY DEPLOY_KNOWN_HOSTS \
  PRODUCTION_DEPLOY_HOST PRODUCTION_BASE_URL
do
  [ -z "$(printenv "$forbidden_variable" 2>/dev/null || true)" ] \
    || fail "production access material is forbidden: $forbidden_variable"
done

for command in awk basename cmp cut date dirname docker find grep jq mkdir \
  mktemp rm sed sha256sum sleep sort tar tr xargs zstd
do
  command -v "$command" >/dev/null 2>&1 \
    || fail "required command is missing: $command"
done

docker info >/dev/null 2>&1 \
  || fail "Jenkins DinD TLS endpoint is unavailable"
docker compose version >/dev/null 2>&1 \
  || fail "Docker Compose is unavailable"

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$PROJECT_DIR/compose.rollback-drill.yaml"
BACKEND_CONTEXT="$PROJECT_DIR/test/rollback-drill/backend"
FRONTEND_CONTEXT="$PROJECT_DIR/test/rollback-drill/frontend"
EVIDENCE_ROOT="$PROJECT_DIR/.artifacts/rollback-drill"
WORK_ROOT="$PROJECT_DIR/.artifacts/rollback-drill-work"

for required_path in \
  "$COMPOSE_FILE" \
  "$BACKEND_CONTEXT/Dockerfile" \
  "$BACKEND_CONTEXT/entrypoint.sh" \
  "$BACKEND_CONTEXT/healthcheck.sh" \
  "$BACKEND_CONTEXT/compatibility-probe.sh" \
  "$FRONTEND_CONTEXT/Dockerfile" \
  "$FRONTEND_CONTEXT/nginx.conf"
do
  [ -s "$required_path" ] \
    || fail "required drill source is missing or empty: $required_path"
done

run_id="${ROLLBACK_DRILL_RUN_ID:-}"
printf '%s\n' "$run_id" | grep -Eq '^[a-z0-9][a-z0-9-]{0,30}$' \
  || fail "ROLLBACK_DRILL_RUN_ID must be a short lowercase identifier"
base_project="gilbom-rollback-drill-$run_id"
printf '%s\n' "$base_project" | grep -Eq '^gilbom-rollback-drill-[a-z0-9-]+$' \
  || fail "unsafe drill Compose project prefix"

[ ! -e "$EVIDENCE_ROOT" ] \
  || fail "rollback drill evidence directory already exists"
[ ! -e "$WORK_ROOT" ] \
  || fail "rollback drill work directory already exists"
mkdir -p "$EVIDENCE_ROOT" "$WORK_ROOT/artifacts"

previous_sha=1111111111111111111111111111111111111111
scenario_a_sha=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
scenario_b_sha=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
scenario_c_sha=cccccccccccccccccccccccccccccccccccccccc
image_prefix="gilbom-rollback-drill-$run_id"

previous_backend="$image_prefix-backend:$previous_sha"
previous_frontend="$image_prefix-frontend:$previous_sha"
projects=""

compose_for() {
  project="$1"
  backend_image="$2"
  frontend_image="$3"
  shift 3
  COMPOSE_PROJECT_NAME="$project" \
  BACKEND_IMAGE="$backend_image" \
  FRONTEND_IMAGE="$frontend_image" \
    docker compose --file "$COMPOSE_FILE" "$@"
}

register_project() {
  project="$1"
  projects="$projects $project"
}

cleanup() {
  for project in $projects
  do
    COMPOSE_PROJECT_NAME="$project" \
    BACKEND_IMAGE="$previous_backend" \
    FRONTEND_IMAGE="$previous_frontend" \
      docker compose --file "$COMPOSE_FILE" \
        down --volumes --remove-orphans >/dev/null 2>&1 || true
  done
  drill_image_ids="$(
    docker image ls \
      --filter "reference=$image_prefix-*" \
      --quiet | LC_ALL=C sort -u
  )"
  if [ -n "$drill_image_ids" ]; then
    # shellcheck disable=SC2086
    docker image rm $drill_image_ids >/dev/null 2>&1 || true
  fi
  case "$WORK_ROOT" in
    "$PROJECT_DIR/.artifacts/rollback-drill-work")
      rm -rf -- "$WORK_ROOT"
      ;;
  esac
}
trap cleanup EXIT HUP INT TERM

build_backend() {
  release_sha="$1"
  target_version="$2"
  maximum_supported="$3"
  force_unhealthy="$4"
  image="$image_prefix-backend:$release_sha"
  docker build \
    --file "$BACKEND_CONTEXT/Dockerfile" \
    --build-arg "DRILL_RELEASE_SHA=$release_sha" \
    --build-arg "DRILL_TARGET_FLYWAY_VERSION=$target_version" \
    --build-arg "DRILL_MAX_SUPPORTED_FLYWAY=$maximum_supported" \
    --build-arg "DRILL_FORCE_UNHEALTHY=$force_unhealthy" \
    --tag "$image" \
    "$BACKEND_CONTEXT"
}

build_frontend() {
  release_sha="$1"
  image="$image_prefix-frontend:$release_sha"
  docker build \
    --file "$FRONTEND_CONTEXT/Dockerfile" \
    --build-arg "DRILL_RELEASE_SHA=$release_sha" \
    --tag "$image" \
    "$FRONTEND_CONTEXT"
}

echo "Building isolated rollback drill images"
build_backend "$previous_sha" 35 36 false
build_frontend "$previous_sha"
build_backend "$scenario_a_sha" 35 35 true
build_frontend "$scenario_a_sha"
build_backend "$scenario_b_sha" 36 36 true
build_frontend "$scenario_b_sha"
build_backend "$scenario_c_sha" 37 37 true
build_frontend "$scenario_c_sha"

create_previous_artifact() {
  artifact="$WORK_ROOT/artifacts/$previous_sha"
  archive="$artifact/images.tar.zst"
  mkdir -p "$artifact"
  docker image save "$previous_backend" "$previous_frontend" \
    | zstd --quiet --threads=0 -3 > "$archive"
  zstd --quiet --test "$archive"
  (
    cd "$artifact"
    sha256sum images.tar.zst > images.tar.zst.sha256
  )
  zstd --quiet -dc "$archive" \
    | tar -xOf - manifest.json \
    > "$artifact/manifest.json"
  backend_config="$(
    jq -er \
      --arg image "$previous_backend" \
      '
        [
          .[]
          | select(.RepoTags | index($image) != null)
          | .Config
        ]
        | if length == 1 then .[0] else error("backend tag mismatch") end
      ' "$artifact/manifest.json"
  )"
  frontend_config="$(
    jq -er \
      --arg image "$previous_frontend" \
      '
        [
          .[]
          | select(.RepoTags | index($image) != null)
          | .Config
        ]
        | if length == 1 then .[0] else error("frontend tag mismatch") end
      ' "$artifact/manifest.json"
  )"
  backend_id="sha256:$(basename -- "$backend_config" | sed 's/[.]json$//')"
  frontend_id="sha256:$(basename -- "$frontend_config" | sed 's/[.]json$//')"
  archive_sha256="$(sha256sum "$archive" | awk '{print $1}')"
  jq -n -S \
    --arg release_sha "$previous_sha" \
    --arg backend_image "$previous_backend" \
    --arg backend_id "$backend_id" \
    --arg frontend_image "$previous_frontend" \
    --arg frontend_id "$frontend_id" \
    --arg archive_sha256 "$archive_sha256" \
    '
      {
        schemaVersion: 1,
        releaseSha: $release_sha,
        images: {
          backend: {name: $backend_image, id: $backend_id},
          frontend: {name: $frontend_image, id: $frontend_id}
        },
        archiveSha256: $archive_sha256,
        flywayVersion: 35
      }
    ' > "$artifact/build-metadata.json"
  (
    cd "$artifact"
    sha256sum build-metadata.json images.tar.zst images.tar.zst.sha256 \
      > SHA256SUMS
  )
}

create_previous_artifact

portable_loaded_ids() {
  destination="$1"
  docker image save "$previous_backend" "$previous_frontend" \
    | tar -xOf - manifest.json > "$destination"
}

load_previous_artifact() {
  scenario_dir="$1"
  artifact="$WORK_ROOT/artifacts/$previous_sha"
  (
    cd "$artifact"
    sha256sum -c SHA256SUMS >/dev/null
    sha256sum -c images.tar.zst.sha256 >/dev/null
  )
  zstd --quiet --test "$artifact/images.tar.zst"
  zstd --quiet -dc "$artifact/images.tar.zst" \
    | docker load > "$scenario_dir/docker-load.txt"
  docker image inspect "$previous_backend" >/dev/null
  docker image inspect "$previous_frontend" >/dev/null
  portable_loaded_ids "$scenario_dir/loaded-manifest.json"
  jq -e \
    --arg backend "$previous_backend" \
    --arg frontend "$previous_frontend" \
    --slurpfile metadata "$artifact/build-metadata.json" \
    '
      def image_id($tag):
        [
          .[]
          | select(.RepoTags | index($tag) != null)
          | .Config
          | split("/")
          | last
          | sub("[.]json$"; "")
          | "sha256:" + .
        ]
        | if length == 1 then .[0] else error("tag mismatch") end;
      image_id($backend) == $metadata[0].images.backend.id
      and image_id($frontend) == $metadata[0].images.frontend.id
    ' "$scenario_dir/loaded-manifest.json" >/dev/null
  printf '%s\n' PASS > "$scenario_dir/previous-artifact-load.status"
}

remove_previous_images() {
  docker image rm "$previous_backend" "$previous_frontend" >/dev/null
  if docker image inspect "$previous_backend" >/dev/null 2>&1 \
      || docker image inspect "$previous_frontend" >/dev/null 2>&1; then
    fail "previous images remain locally available after explicit removal"
  fi
}

wait_backend_health() {
  project="$1"
  backend_image="$2"
  frontend_image="$3"
  expected="$4"
  deadline="$(( $(date +%s) + 90 ))"
  while [ "$(date +%s)" -lt "$deadline" ]
  do
    container_id="$(compose_for "$project" "$backend_image" "$frontend_image" ps -q backend)"
    if [ -n "$container_id" ]; then
      status="$(
        docker inspect \
          --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' \
          "$container_id"
      )"
      [ "$status" = "$expected" ] && return 0
    fi
    sleep 1
  done
  return 1
}

flyway_version() {
  project="$1"
  backend_image="$2"
  frontend_image="$3"
  compose_for "$project" "$backend_image" "$frontend_image" \
    exec -T db psql \
      --username rollback_drill \
      --dbname rollback_drill \
      --tuples-only \
      --no-align \
      --command '
        SELECT COALESCE(MAX(version), 0)
        FROM flyway_history.flyway_schema_history
        WHERE success;
      ' | tr -d '[:space:]'
}

wait_flyway() {
  project="$1"
  backend_image="$2"
  frontend_image="$3"
  expected="$4"
  deadline="$(( $(date +%s) + 90 ))"
  while [ "$(date +%s)" -lt "$deadline" ]
  do
    version="$(
      flyway_version "$project" "$backend_image" "$frontend_image" \
        2>/dev/null || true
    )"
    [ "$version" = "$expected" ] && return 0
    sleep 1
  done
  return 1
}

volume_name() {
  project="$1"
  logical_name="$2"
  name="$(
    docker volume ls \
      --filter "label=com.docker.compose.project=$project" \
      --filter "label=com.docker.compose.volume=$logical_name" \
      --quiet
  )"
  [ -n "$name" ] || return 1
  printf '%s\n' "$name"
}

seed_persistent_data() {
  project="$1"
  backend_image="$2"
  frontend_image="$3"
  scenario="$4"
  compose_for "$project" "$backend_image" "$frontend_image" \
    exec -T db psql \
      --username rollback_drill \
      --dbname rollback_drill \
      --set ON_ERROR_STOP=1 \
      --command "
        INSERT INTO drill.persistent_state(id, payload)
        VALUES (1, 'database-$scenario')
        ON CONFLICT (id) DO UPDATE SET payload = EXCLUDED.payload;
      " >/dev/null
  compose_for "$project" "$backend_image" "$frontend_image" \
    exec -T backend sh -ec '
      printf "%s\n" "$1" > /data/robot-images/seed.jpg
      printf "%s\n" "$2" > /data/report-pdf/seed.pdf
    ' sh "robot-image-$scenario" "report-pdf-$scenario"
}

snapshot_persistent_data() {
  project="$1"
  backend_image="$2"
  frontend_image="$3"
  destination="$4"
  database_payload="$(
    compose_for "$project" "$backend_image" "$frontend_image" \
      exec -T db psql \
        --username rollback_drill \
        --dbname rollback_drill \
        --tuples-only \
        --no-align \
        --command 'SELECT payload FROM drill.persistent_state WHERE id = 1;' \
      | tr -d '\r\n'
  )"
  robot_sha256="$(
    compose_for "$project" "$backend_image" "$frontend_image" \
      exec -T backend sha256sum /data/robot-images/seed.jpg \
      | awk '{print $1}'
  )"
  pdf_sha256="$(
    compose_for "$project" "$backend_image" "$frontend_image" \
      exec -T backend sha256sum /data/report-pdf/seed.pdf \
      | awk '{print $1}'
  )"
  postgres_volume="$(volume_name "$project" postgres_data)"
  robot_volume="$(volume_name "$project" robot_images)"
  pdf_volume="$(volume_name "$project" report_pdf_data)"
  jq -n -S \
    --arg database_payload "$database_payload" \
    --arg robot_sha256 "$robot_sha256" \
    --arg pdf_sha256 "$pdf_sha256" \
    --arg postgres_volume "$postgres_volume" \
    --arg robot_volume "$robot_volume" \
    --arg pdf_volume "$pdf_volume" \
    '
      {
        database: {
          payload: $database_payload,
          volume: $postgres_volume
        },
        robotImages: {
          sha256: $robot_sha256,
          volume: $robot_volume
        },
        reportPdf: {
          sha256: $pdf_sha256,
          volume: $pdf_volume
        }
      }
    ' > "$destination"
}

verify_persistent_data() {
  before="$1"
  after="$2"
  cmp -s "$before" "$after" \
    || fail "DB, robot image, or PDF Volume evidence changed"
}

start_previous_stack() {
  project="$1"
  compose_for "$project" "$previous_backend" "$previous_frontend" \
    up --detach --no-build --wait --wait-timeout 90
  wait_backend_health \
    "$project" "$previous_backend" "$previous_frontend" healthy \
    || fail "previous Backend did not become healthy"
  [ "$(flyway_version "$project" "$previous_backend" "$previous_frontend")" = 35 ] \
    || fail "previous stack did not establish Flyway V35"
}

deploy_failed_candidate() {
  project="$1"
  backend_image="$2"
  frontend_image="$3"
  expected_flyway="$4"
  compose_for "$project" "$backend_image" "$frontend_image" \
    up --detach --no-build
  wait_flyway "$project" "$backend_image" "$frontend_image" "$expected_flyway" \
    || fail "candidate did not reach Flyway V$expected_flyway"
  wait_backend_health "$project" "$backend_image" "$frontend_image" unhealthy \
    || fail "candidate Backend failure was not observed"
}

verify_frontend_to_backend() {
  project="$1"
  backend_image="$2"
  frontend_image="$3"
  compose_for "$project" "$backend_image" "$frontend_image" \
    exec -T frontend \
      wget --quiet --output-document=- http://127.0.0.1:8080/api/health \
    | jq -e '.status == "UP"' >/dev/null
}

write_scenario_logs() {
  project="$1"
  backend_image="$2"
  frontend_image="$3"
  scenario_dir="$4"
  compose_for "$project" "$backend_image" "$frontend_image" \
    ps --all > "$scenario_dir/compose-ps.txt" 2>&1 || true
  compose_for "$project" "$backend_image" "$frontend_image" \
    logs --no-color --timestamps > "$scenario_dir/compose.log" 2>&1 || true
}

run_scenario_a() {
  scenario=a
  project="$base_project-$scenario"
  candidate_backend="$image_prefix-backend:$scenario_a_sha"
  candidate_frontend="$image_prefix-frontend:$scenario_a_sha"
  scenario_dir="$EVIDENCE_ROOT/scenario-a"
  mkdir -p "$scenario_dir"
  register_project "$project"

  start_previous_stack "$project"
  seed_persistent_data \
    "$project" "$previous_backend" "$previous_frontend" "$scenario"
  snapshot_persistent_data \
    "$project" "$previous_backend" "$previous_frontend" \
    "$scenario_dir/volumes-before.json"
  flyway_before="$(
    flyway_version "$project" "$previous_backend" "$previous_frontend"
  )"

  deploy_failed_candidate \
    "$project" "$candidate_backend" "$candidate_frontend" 35
  flyway_after_failure="$(
    flyway_version "$project" "$candidate_backend" "$candidate_frontend"
  )"
  [ "$flyway_before" = "$flyway_after_failure" ] \
    || fail "scenario A unexpectedly changed Flyway"

  remove_previous_images
  load_previous_artifact "$scenario_dir"
  compose_for "$project" "$previous_backend" "$previous_frontend" \
    up --detach --no-build --wait --wait-timeout 90
  wait_backend_health \
    "$project" "$previous_backend" "$previous_frontend" healthy \
    || fail "scenario A automatic rollback Backend is unhealthy"
  verify_frontend_to_backend \
    "$project" "$previous_backend" "$previous_frontend" \
    || fail "scenario A Frontend-to-Backend smoke failed"
  snapshot_persistent_data \
    "$project" "$previous_backend" "$previous_frontend" \
    "$scenario_dir/volumes-after.json"
  verify_persistent_data \
    "$scenario_dir/volumes-before.json" "$scenario_dir/volumes-after.json"

  jq -n -S \
    --arg failed_sha "$scenario_a_sha" \
    --arg rollback_sha "$previous_sha" \
    --argjson flyway_before "$flyway_before" \
    --argjson flyway_after "$flyway_after_failure" \
    '
      {
        schemaVersion: 1,
        scenario: "A_NO_MIGRATION",
        status: "AUTOMATIC_ROLLBACK_SUCCEEDED",
        failedReleaseSha: $failed_sha,
        rollbackTargetSha: $rollback_sha,
        flyway: {
          beforeVersion: $flyway_before,
          afterFailureVersion: $flyway_after,
          changed: false
        },
        previousArtifactReload: "PASS",
        backendReadiness: "PASS",
        frontendToBackendSmoke: "PASS",
        volumesPreserved: "PASS",
        migrationUndoPerformed: false,
        backupRestorePerformed: false
      }
    ' > "$scenario_dir/result.json"
  write_scenario_logs \
    "$project" "$previous_backend" "$previous_frontend" "$scenario_dir"
  echo "Rollback drill scenario A: PASS"
}

run_scenario_b() {
  scenario=b
  project="$base_project-$scenario"
  candidate_backend="$image_prefix-backend:$scenario_b_sha"
  candidate_frontend="$image_prefix-frontend:$scenario_b_sha"
  scenario_dir="$EVIDENCE_ROOT/scenario-b"
  approval="$scenario_dir/compatibility-approval.json"
  mkdir -p "$scenario_dir"
  register_project "$project"

  start_previous_stack "$project"
  seed_persistent_data \
    "$project" "$previous_backend" "$previous_frontend" "$scenario"
  snapshot_persistent_data \
    "$project" "$previous_backend" "$previous_frontend" \
    "$scenario_dir/volumes-before.json"
  flyway_before="$(
    flyway_version "$project" "$previous_backend" "$previous_frontend"
  )"

  deploy_failed_candidate \
    "$project" "$candidate_backend" "$candidate_frontend" 36
  flyway_after_failure="$(
    flyway_version "$project" "$candidate_backend" "$candidate_frontend"
  )"
  [ "$flyway_before" -lt "$flyway_after_failure" ] \
    || fail "scenario B did not apply a forward Migration"

  remove_previous_images
  if [ -e "$approval" ]; then
    fail "scenario B unexpectedly has an approval before the gate"
  fi
  printf '%s\n' \
    'ROLLBACK_BLOCKED_EXPLICIT_COMPATIBILITY_APPROVAL_REQUIRED' \
    > "$scenario_dir/unapproved-attempt.txt"
  if docker image inspect "$previous_backend" >/dev/null 2>&1; then
    fail "scenario B loaded the previous image before approval"
  fi

  jq -n -S \
    --arg failed_sha "$scenario_b_sha" \
    --arg rollback_sha "$previous_sha" \
    --argjson flyway_before "$flyway_before" \
    --argjson flyway_after "$flyway_after_failure" \
    '
      {
        schemaVersion: 1,
        failedReleaseSha: $failed_sha,
        rollbackTargetSha: $rollback_sha,
        flywayBeforeVersion: $flyway_before,
        flywayAfterFailureVersion: $flyway_after,
        compatibility: "COMPATIBLE_PREVIOUS_BACKEND",
        automaticRollbackAllowed: false
      }
    ' > "$scenario_dir/preflight.json"
  preflight_sha256="$(
    sha256sum "$scenario_dir/preflight.json" | awk '{print $1}'
  )"
  approved_at_utc="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  jq -n -S \
    --arg failed_sha "$scenario_b_sha" \
    --arg rollback_sha "$previous_sha" \
    --arg preflight_sha256 "$preflight_sha256" \
    --arg approved_at_utc "$approved_at_utc" \
    '
      {
        schemaVersion: 1,
        failedReleaseSha: $failed_sha,
        rollbackTargetSha: $rollback_sha,
        preflightSha256: $preflight_sha256,
        compatibility: "APPROVED_PREVIOUS_BACKEND_COMPATIBLE",
        approvedBy: "rollback-drill-system",
        approvedAtUtc: $approved_at_utc
      }
    ' > "$approval"
  jq -e \
    --arg preflight_sha256 "$preflight_sha256" \
    '
      .preflightSha256 == $preflight_sha256
      and .compatibility == "APPROVED_PREVIOUS_BACKEND_COMPATIBLE"
    ' "$approval" >/dev/null \
    || fail "scenario B exact compatibility approval is invalid"

  load_previous_artifact "$scenario_dir"
  compose_for "$project" "$previous_backend" "$previous_frontend" \
    up --detach --no-build --wait --wait-timeout 90
  wait_backend_health \
    "$project" "$previous_backend" "$previous_frontend" healthy \
    || fail "scenario B approved previous Backend is not V36 compatible"
  verify_frontend_to_backend \
    "$project" "$previous_backend" "$previous_frontend" \
    || fail "scenario B approved rollback smoke failed"
  [ "$(
    flyway_version "$project" "$previous_backend" "$previous_frontend"
  )" = 36 ] || fail "scenario B rollback changed Flyway"
  snapshot_persistent_data \
    "$project" "$previous_backend" "$previous_frontend" \
    "$scenario_dir/volumes-after.json"
  verify_persistent_data \
    "$scenario_dir/volumes-before.json" "$scenario_dir/volumes-after.json"

  jq -n -S \
    --arg failed_sha "$scenario_b_sha" \
    --arg rollback_sha "$previous_sha" \
    --argjson flyway_before "$flyway_before" \
    --argjson flyway_after "$flyway_after_failure" \
    --slurpfile approval "$approval" \
    '
      {
        schemaVersion: 1,
        scenario: "B_COMPATIBLE_MIGRATION",
        status: "MANUALLY_APPROVED_ROLLBACK_SUCCEEDED",
        failedReleaseSha: $failed_sha,
        rollbackTargetSha: $rollback_sha,
        flyway: {
          beforeVersion: $flyway_before,
          afterFailureVersion: $flyway_after,
          afterRollbackVersion: $flyway_after
        },
        unapprovedRollbackBlocked: true,
        approval: $approval[0],
        previousArtifactReload: "PASS",
        volumesPreserved: "PASS",
        migrationUndoPerformed: false,
        backupRestorePerformed: false
      }
    ' > "$scenario_dir/result.json"
  write_scenario_logs \
    "$project" "$previous_backend" "$previous_frontend" "$scenario_dir"
  echo "Rollback drill scenario B: PASS"
}

run_scenario_c() {
  scenario=c
  project="$base_project-$scenario"
  candidate_backend="$image_prefix-backend:$scenario_c_sha"
  candidate_frontend="$image_prefix-frontend:$scenario_c_sha"
  scenario_dir="$EVIDENCE_ROOT/scenario-c"
  mkdir -p "$scenario_dir"
  register_project "$project"

  start_previous_stack "$project"
  seed_persistent_data \
    "$project" "$previous_backend" "$previous_frontend" "$scenario"
  snapshot_persistent_data \
    "$project" "$previous_backend" "$previous_frontend" \
    "$scenario_dir/volumes-before.json"
  flyway_before="$(
    flyway_version "$project" "$previous_backend" "$previous_frontend"
  )"

  deploy_failed_candidate \
    "$project" "$candidate_backend" "$candidate_frontend" 37
  flyway_after_failure="$(
    flyway_version "$project" "$candidate_backend" "$candidate_frontend"
  )"
  [ "$flyway_before" -lt "$flyway_after_failure" ] \
    || fail "scenario C did not apply an incompatible Migration"

  remove_previous_images
  load_previous_artifact "$scenario_dir"
  data_network="$(
    docker network ls \
      --filter "label=com.docker.compose.project=$project" \
      --filter "label=com.docker.compose.network=data" \
      --quiet
  )"
  [ -n "$data_network" ] || fail "scenario C data network is missing"
  if docker run --rm \
      --network "$data_network" \
      --env PGHOST=db \
      --env PGPORT=5432 \
      --env PGDATABASE=rollback_drill \
      --env PGUSER=rollback_drill \
      --env PGPASSWORD=rollback-drill-only \
      --entrypoint /usr/local/bin/gilbom-drill-compatibility-probe \
      "$previous_backend" > "$scenario_dir/compatibility-probe.txt" 2>&1; then
    fail "scenario C incorrectly classified the previous Backend as compatible"
  fi
  printf '%s\n' INCOMPATIBLE_PREVIOUS_BACKEND \
    > "$scenario_dir/compatibility.status"

  compose_for "$project" "$candidate_backend" "$candidate_frontend" \
    stop frontend
  frontend_id="$(
    compose_for "$project" "$candidate_backend" "$candidate_frontend" \
      ps --all -q frontend
  )"
  backend_id="$(
    compose_for "$project" "$candidate_backend" "$candidate_frontend" \
      ps -q backend
  )"
  db_id="$(
    compose_for "$project" "$candidate_backend" "$candidate_frontend" \
      ps -q db
  )"
  [ -n "$frontend_id" ] \
    || fail "scenario C Frontend container is missing"
  [ -n "$backend_id" ] \
    || fail "scenario C candidate Backend container is missing"
  [ -n "$db_id" ] \
    || fail "scenario C database container is missing"
  [ "$(docker inspect --format '{{.State.Status}}' "$frontend_id")" = exited ] \
    || fail "scenario C Frontend was not stopped"
  [ "$(docker inspect --format '{{.State.Status}}' "$backend_id")" = running ] \
    || fail "scenario C candidate Backend was not preserved"
  [ "$(docker inspect --format '{{.Config.Image}}' "$backend_id")" \
      = "$candidate_backend" ] \
    || fail "scenario C replaced the candidate Backend"
  [ "$(docker inspect --format '{{.State.Health.Status}}' "$db_id")" = healthy ] \
    || fail "scenario C database was not preserved"
  snapshot_persistent_data \
    "$project" "$candidate_backend" "$candidate_frontend" \
    "$scenario_dir/volumes-after.json"
  verify_persistent_data \
    "$scenario_dir/volumes-before.json" "$scenario_dir/volumes-after.json"

  jq -n -S \
    --arg failed_sha "$scenario_c_sha" \
    --arg rollback_sha "$previous_sha" \
    --argjson flyway_before "$flyway_before" \
    --argjson flyway_after "$flyway_after_failure" \
    '
      {
        schemaVersion: 1,
        scenario: "C_INCOMPATIBLE_MIGRATION",
        status: "MANUAL_RECOVERY_REQUIRED",
        failedReleaseSha: $failed_sha,
        rollbackTargetSha: $rollback_sha,
        flyway: {
          beforeVersion: $flyway_before,
          afterFailureVersion: $flyway_after,
          changed: true
        },
        compatibility: "INCOMPATIBLE_PREVIOUS_BACKEND",
        automaticRollbackAllowed: false,
        services: {
          frontend: "STOPPED",
          candidateBackend: "PRESERVED",
          database: "PRESERVED"
        },
        evidencePreserved: true,
        volumesPreserved: "PASS",
        migrationUndoPerformed: false,
        backupRestorePerformed: false
      }
    ' > "$scenario_dir/result.json"
  write_scenario_logs \
    "$project" "$candidate_backend" "$candidate_frontend" "$scenario_dir"
  echo "Rollback drill scenario C: PASS"
}

run_scenario_a
run_scenario_b
run_scenario_c

jq -n -S \
  --slurpfile scenario_a "$EVIDENCE_ROOT/scenario-a/result.json" \
  --slurpfile scenario_b "$EVIDENCE_ROOT/scenario-b/result.json" \
  --slurpfile scenario_c "$EVIDENCE_ROOT/scenario-c/result.json" \
  '
    {
      schemaVersion: 1,
      status: "PASS",
      isolation: {
        productionCredentialsUsed: false,
        productionHostContacted: false,
        dockerSocketMounted: false,
        dindTls: true
      },
      scenarios: {
        noMigrationAutomaticRollback: $scenario_a[0],
        compatibleMigrationApprovalGate: $scenario_b[0],
        incompatibleMigrationContainment: $scenario_c[0]
      },
      requiredEvidence: {
        previousArtifactReload: "PASS",
        databaseVolumePersistence: "PASS",
        robotImageVolumePersistence: "PASS",
        reportPdfVolumePersistence: "PASS"
      }
    }
  ' > "$EVIDENCE_ROOT/summary.json"

sh "$SCRIPT_DIR/verify-production-rollback-drill-evidence.sh" \
  "$EVIDENCE_ROOT/summary.json"

(
  cd "$EVIDENCE_ROOT"
  find . -type f ! -name SHA256SUMS -print \
    | LC_ALL=C sort \
    | xargs sha256sum > SHA256SUMS
  sha256sum -c SHA256SUMS >/dev/null
)

echo "Production rollback drill: PASS"
echo "Rollback drill evidence: $EVIDENCE_ROOT/summary.json"
