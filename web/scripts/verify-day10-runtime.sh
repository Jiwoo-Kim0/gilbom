#!/bin/sh

# 운영 host에서 Day 10 관측 경로가 실제로 도는지 확인한다.
#
# loopback에 bind된 monitoring port로 직접 물어본다. SSH 터널 없이 host에서 실행한다.
# 정적 계약은 scripts/verify-day10-observability.sh가 본다.

set -eu

PROMETHEUS_URL="${PROMETHEUS_URL:-http://127.0.0.1:9090}"
ALERTMANAGER_URL="${ALERTMANAGER_URL:-http://127.0.0.1:9093}"
LOKI_URL="${LOKI_URL:-http://127.0.0.1:3100}"
GRAFANA_URL="${GRAFANA_URL:-http://127.0.0.1:3001}"
TEXTFILE_DIR="${TEXTFILE_DIR:-/opt/gilbom/monitoring/textfile}"
FAILURES=0

fail() {
  echo "Day 10 runtime verification failed: $1" >&2
  exit 1
}

check() {
  label=$1
  outcome=$2
  detail=${3:-}
  if [ "$outcome" = PASS ]; then
    printf '  PASS  %s%s\n' "$label" "${detail:+ — $detail}"
  else
    printf '  FAIL  %s%s\n' "$label" "${detail:+ — $detail}"
    FAILURES=$((FAILURES + 1))
  fi
}

command -v curl >/dev/null 2>&1 || fail "curl is required"
command -v jq >/dev/null 2>&1 || fail "jq is required"

query() {
  curl --silent --show-error --fail --max-time 10 \
    --get "$PROMETHEUS_URL/api/v1/query" \
    --data-urlencode "query=$1" 2>/dev/null || echo '{}'
}

scalar() {
  query "$1" | jq -r '.data.result[0].value[1] // "none"'
}

echo "1. Prometheus 수집 대상"
targets="$(curl --silent --fail --max-time 10 "$PROMETHEUS_URL/api/v1/targets" 2>/dev/null || echo '{}')"
down="$(printf '%s' "$targets" \
  | jq -r '[.data.activeTargets[]? | select(.health != "up")] | length' 2>/dev/null || echo unknown)"
total="$(printf '%s' "$targets" \
  | jq -r '[.data.activeTargets[]?] | length' 2>/dev/null || echo 0)"
if [ "$down" = 0 ] && [ "$total" -gt 0 ]; then
  check "모든 수집 대상 up" PASS "$total개"
else
  check "모든 수집 대상 up" FAIL "전체 $total, 비정상 $down"
  printf '%s' "$targets" \
    | jq -r '.data.activeTargets[]? | select(.health != "up") | "        " + .labels.job + " " + .scrapeUrl + " — " + .lastError' \
    2>/dev/null || true
fi

for job in backend blackbox-https alloy loki alertmanager
do
  value="$(scalar "up{job=\"$job\"}")"
  if [ "$value" = 1 ]; then
    check "job $job" PASS
  else
    check "job $job" FAIL "up=$value"
  fi
done

echo
echo "2. 알람 규칙"
rules="$(curl --silent --fail --max-time 10 "$PROMETHEUS_URL/api/v1/rules" 2>/dev/null || echo '{}')"
rule_count="$(printf '%s' "$rules" \
  | jq -r '[.data.groups[]?.rules[]?] | length' 2>/dev/null || echo 0)"
if [ "$rule_count" -ge 20 ]; then
  check "alert rule 로드" PASS "$rule_count개"
else
  check "alert rule 로드" FAIL "$rule_count개만 로드됐다"
fi

firing="$(printf '%s' "$rules" \
  | jq -r '[.data.groups[]?.rules[]? | select(.state == "firing")] | length' 2>/dev/null || echo 0)"
echo "  현재 firing: $firing건"
printf '%s' "$rules" \
  | jq -r '.data.groups[]?.rules[]? | select(.state == "firing") | "        " + .name' \
  2>/dev/null || true

echo
echo "3. Alertmanager"
if curl --silent --fail --max-time 10 "$ALERTMANAGER_URL/-/ready" >/dev/null 2>&1; then
  check "Alertmanager ready" PASS
else
  check "Alertmanager ready" FAIL "$ALERTMANAGER_URL/-/ready 응답 없음"
fi
receivers="$(curl --silent --fail --max-time 10 "$ALERTMANAGER_URL/api/v2/receivers" 2>/dev/null || echo '[]')"
if printf '%s' "$receivers" | grep -q mattermost; then
  check "Mattermost receiver" PASS
else
  check "Mattermost receiver" FAIL "receiver 목록에 없다"
fi

# receiver가 설정에 있는 것과 실제로 전달되는 것은 다르다. secret 파일 권한 때문에
# 한 번도 전달되지 않은 채 설정만 정상으로 보인 적이 있어서 실패 누계도 함께 본다.
notification_failures="$(scalar 'sum(increase(alertmanager_notifications_failed_total[30m]))')"
case "$notification_failures" in
  none)
    check "알람 전달 실패 없음" PASS "최근 통지 시도 없음"
    ;;
  *)
    if [ "${notification_failures%%.*}" -eq 0 ] 2>/dev/null; then
      check "알람 전달 실패 없음" PASS
    else
      check "알람 전달 실패 없음" FAIL \
        "최근 30분 ${notification_failures%%.*}건 — docker logs에서 Notify for alerts failed를 확인한다"
    fi
    ;;
esac

echo
echo "4. Loki"
if curl --silent --fail --max-time 10 "$LOKI_URL/ready" >/dev/null 2>&1; then
  check "Loki ready" PASS
else
  check "Loki ready" FAIL "$LOKI_URL/ready 응답 없음"
fi

labels="$(curl --silent --fail --max-time 10 "$LOKI_URL/loki/api/v1/labels" 2>/dev/null || echo '{}')"
if printf '%s' "$labels" | grep -q compose_service; then
  check "compose_service label" PASS
else
  check "compose_service label" FAIL "container 구분 label이 없다"
fi

backend_lines="$(
  curl --silent --fail --max-time 15 --get "$LOKI_URL/loki/api/v1/query_range" \
    --data-urlencode 'query={compose_service="backend"}' \
    --data-urlencode 'limit=5' 2>/dev/null \
    | jq -r '[.data.result[]?.values[]?] | length' 2>/dev/null || echo 0
)"
if [ "${backend_lines:-0}" -gt 0 ]; then
  check "Backend 로그 수집" PASS "최근 $backend_lines줄"
else
  check "Backend 로그 수집" FAIL "Loki에 Backend 로그가 없다"
fi

echo
echo "  requestId 검색은 값이 있어야 확인된다. 아래를 순서대로 실행한다:"
echo "    curl -sS -D- -o /dev/null https://\$도메인/api/v1/auth/csrf-token | grep -i x-request-id"
echo "    그 값으로:"
echo "    curl -sS --get $LOKI_URL/loki/api/v1/query_range \\"
echo "      --data-urlencode '{compose_service=\"backend\"} | requestId=\"<값>\"'"

echo
echo "5. Host textfile metric"
for metric_file in gilbom-host.prom gilbom-host-paths.prom
do
  if [ -f "$TEXTFILE_DIR/$metric_file" ]; then
    check "$metric_file" PASS
  else
    check "$metric_file" FAIL "$TEXTFILE_DIR에 없다"
  fi
done

updated="$(scalar 'time() - gilbom_host_metrics_updated_seconds')"
case "$updated" in
  none)
    check "host metric 신선도" FAIL "Prometheus가 값을 못 받았다"
    ;;
  *)
    if [ "${updated%%.*}" -lt 600 ] 2>/dev/null; then
      check "host metric 신선도" PASS "${updated%%.*}초 전"
    else
      check "host metric 신선도" FAIL "${updated%%.*}초 전 — timer를 확인한다"
    fi
    ;;
esac

disk="$(scalar 'gilbom_filesystem_avail_bytes{mountpoint="/"} / gilbom_filesystem_size_bytes{mountpoint="/"}')"
check "루트 디스크 여유 metric" \
  "$([ "$disk" != none ] && echo PASS || echo FAIL)" "$disk"

containers="$(scalar 'count(gilbom_container_running)')"
check "container metric" \
  "$([ "$containers" != none ] && echo PASS || echo FAIL)" "${containers}개"

echo
echo "6. Backup metric"
for backup in postgres volumes
do
  status="$(scalar "gilbom_backup_last_status{backup=\"$backup\"}")"
  age="$(scalar "time() - gilbom_backup_last_success_seconds{backup=\"$backup\"}")"
  if [ "$status" = 1 ]; then
    check "backup $backup" PASS "마지막 성공 ${age%%.*}초 전"
  else
    check "backup $backup" FAIL "status=$status, 마지막 성공=$age"
  fi
done

echo
echo "7. Grafana"
if curl --silent --fail --max-time 10 "$GRAFANA_URL/api/health" >/dev/null 2>&1; then
  check "Grafana health" PASS
else
  check "Grafana health" FAIL "$GRAFANA_URL/api/health 응답 없음"
fi
echo "  대시보드 4장은 SSH 터널로 눈으로 확인한다(runbook 3절)."

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "Day 10 runtime verification: PASS"
else
  echo "Day 10 runtime verification: FAIL ($FAILURES건)"
  exit 1
fi
