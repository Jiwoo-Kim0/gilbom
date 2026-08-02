#!/bin/sh

set -eu

fail() {
  echo "Production release evidence verification failed: $1" >&2
  exit 1
}

[ "$#" -eq 4 ] \
  || fail "usage: $0 PREFLIGHT APPROVAL MANIFEST EXTERNAL_SMOKE"

preflight="$1"
approval="$2"
manifest="$3"
external_smoke="$4"
for file in "$preflight" "$approval" "$manifest" "$external_smoke"
do
  [ -f "$file" ] && [ ! -L "$file" ] && [ -s "$file" ] \
    || fail "required evidence is missing, empty, or symlinked: $file"
  jq empty "$file" \
    || fail "evidence is not valid JSON: $file"
done

jq -e \
  --slurpfile preflight "$preflight" \
  --slurpfile approval "$approval" \
  '
    .schemaVersion == 1
    and .releaseSha == $preflight[0].releaseSha
    and .releaseSha == $approval[0].releaseSha
    and .previousSha == $preflight[0].production.currentSha
    and .images.backend.id == $preflight[0].images.backend.id
    and .images.frontend.id == $preflight[0].images.frontend.id
    and .artifact.sha256SumsSha256
      == $preflight[0].artifact.sha256SumsSha256
    and .approval.approvedBy == $approval[0].approvedBy
    and .approval.approvedAtUtc == $approval[0].approvedAtUtc
    and .approval.preflightSha256 == $approval[0].preflightSha256
    and .health.backendReadiness == "PASS"
    and .health.frontend == "PASS"
    and .health.frontendToBackend == "PASS"
    and .health.mqtt == "PASS"
  ' "$manifest" >/dev/null \
  || fail "release manifest does not match preflight and approval evidence"
jq -e \
  '.status == "PASS" and .tlsVerification == "PASS"' \
  "$external_smoke" >/dev/null \
  || fail "external HTTPS smoke evidence is not PASS"

echo "Production release evidence verification: PASS"
