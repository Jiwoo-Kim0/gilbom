#!/bin/sh

set -eu

fail() {
  echo "CI checkout contract verification failed: $1" >&2
  exit 1
}

assert_contains() {
  file=$1
  expected=$2
  label=$3

  grep -Fq -- "$expected" "$file" || fail "$label"
}

repository_root="$(git rev-parse --show-toplevel)"
project_dir="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
actual_shallow="$(git -C "$repository_root" rev-parse --is-shallow-repository)"
actual_sha="$(git -C "$repository_root" rev-parse HEAD)"

printf 'git rev-parse --is-shallow-repository: %s\n' "$actual_shallow"
[ "$actual_shallow" = false ] \
  || fail "the checked-out repository is shallow"

printf '%s\n' "$actual_sha" | grep -Eq '^[0-9a-f]{40}$' \
  || fail "HEAD is not a 40-character lowercase hexadecimal SHA"

if [ -n "${RELEASE_SHA:-}" ]; then
  [ "$RELEASE_SHA" = "$actual_sha" ] \
    || fail "RELEASE_SHA does not match the checked-out HEAD"
  [ "${BACKEND_IMAGE:-}" = "gilbom-backend:$RELEASE_SHA" ] \
    || fail "BACKEND_IMAGE does not use RELEASE_SHA"
  [ "${FRONTEND_IMAGE:-}" = "gilbom-frontend:$RELEASE_SHA" ] \
    || fail "FRONTEND_IMAGE does not use RELEASE_SHA"
fi

assert_contains "$project_dir/Jenkinsfile" \
  "script: 'git rev-parse --is-shallow-repository'" \
  "Jenkins must inspect the shallow-repository state after checkout"
assert_contains "$project_dir/Jenkinsfile" \
  "if (shallowRepository != 'false')" \
  "Jenkins must reject a shallow checkout"
assert_contains "$project_dir/Jenkinsfile" \
  "script: 'git rev-parse HEAD'" \
  "Jenkins must resolve the release SHA from the checked-out HEAD"
assert_contains "$project_dir/Jenkinsfile" \
  "releaseSha.matches('[0-9a-f]{40}')" \
  "Jenkins must require a 40-character lowercase hexadecimal SHA"
assert_contains "$project_dir/Jenkinsfile" \
  'env.BACKEND_IMAGE = "gilbom-backend:${releaseSha}"' \
  "Jenkins must derive the Backend image from RELEASE_SHA"
assert_contains "$project_dir/Jenkinsfile" \
  'env.FRONTEND_IMAGE = "gilbom-frontend:${releaseSha}"' \
  "Jenkins must derive the Frontend image from RELEASE_SHA"
assert_contains "$project_dir/compose.build.yaml" \
  'image: ${BACKEND_IMAGE:-gilbom-backend:local}' \
  "the build Compose file must consume BACKEND_IMAGE"
assert_contains "$project_dir/compose.build.yaml" \
  'image: ${FRONTEND_IMAGE:-gilbom-frontend:local}' \
  "the build Compose file must consume FRONTEND_IMAGE"
assert_contains "$project_dir/compose.yaml" \
  'image: ${BACKEND_IMAGE:-gilbom-backend:local}' \
  "the integration Compose file must consume BACKEND_IMAGE"
assert_contains "$project_dir/compose.yaml" \
  'image: ${FRONTEND_IMAGE:-gilbom-frontend:local}' \
  "the integration Compose file must consume FRONTEND_IMAGE"

echo "CI checkout contract: PASS ($actual_sha)"
