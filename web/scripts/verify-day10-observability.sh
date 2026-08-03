#!/bin/sh

# Day 10 관측·backup 구성의 정적 계약을 검사한다.
#
# Docker 없이 저장소 파일만 본다. 실제 수집·알람·복원은
# scripts/verify-day10-runtime.sh가 운영 host에서 확인한다.

set -eu

PROJECT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$PROJECT_DIR"

fail() {
  echo "Day 10 observability verification failed: $1" >&2
  exit 1
}

assert_contains() {
  file=$1
  expected=$2
  label=$3
  [ -f "$file" ] || fail "$label (파일 없음: $file)"
  grep -Fq -- "$expected" "$file" || fail "$label"
}

assert_not_contains() {
  file=$1
  unexpected=$2
  label=$3
  [ -f "$file" ] || fail "$label (파일 없음: $file)"
  if grep -Fq -- "$unexpected" "$file"; then
    fail "$label"
  fi
}

assert_file() {
  [ -f "$1" ] || fail "$2"
}

# --- 1. Backend metric 노출 -------------------------------------------------

assert_contains backend/build.gradle \
  'io.micrometer:micrometer-registry-prometheus' \
  "Micrometer Prometheus registry가 없다"
assert_contains backend/src/main/resources/application.yml \
  'include: health,info,prometheus' \
  "actuator prometheus endpoint가 노출되지 않는다"
assert_contains backend/src/main/resources/application.yml \
  '%X{requestId' \
  "로그 pattern에 requestId가 없다"
assert_file backend/src/main/java/com/gilbom/health/HealthComponentMetrics.java \
  "health component gauge가 없다"
assert_contains backend/src/main/java/com/gilbom/security/config/SecurityConfig.java \
  '/actuator/prometheus' \
  "Prometheus가 metric endpoint에 인증 없이 접근할 수 없다"

# --- 2. Prometheus 수집과 알람 ---------------------------------------------

for target in backend blackbox-https alertmanager alloy loki
do
  assert_contains infra/monitoring/prometheus/prometheus.yml \
    "job_name: $target" \
    "Prometheus scrape 대상이 없다: $target"
done
assert_contains infra/monitoring/prometheus/prometheus.yml \
  '/etc/prometheus/rules/*.yml' \
  "alert rule 파일이 로드되지 않는다"
assert_contains infra/monitoring/prometheus/prometheus.yml \
  'alertmanager:9093' \
  "Alertmanager 연결이 없다"

for rule_file in host container backend operations
do
  assert_file "infra/monitoring/prometheus/rules/$rule_file.yml" \
    "alert rule 파일이 없다: $rule_file"
done

# 완료 기준이 직접 요구하는 알람은 이름으로 고정한다. 이름이 바뀌면 여기도 함께 고친다.
for alert in \
  BackendDown \
  HostDiskLow \
  HostDiskCritical \
  BackupFailed \
  BackupStale \
  RobotImagesStorageFillingUp \
  StorageReadinessDown \
  ContainerOomKilled \
  ContainerRestartLoop \
  HttpsProbeFailed \
  TlsCertificateExpiringSoon \
  LoginFailureSpike \
  SecurityDenialSpike \
  MqttInboundRejectionSpike \
  DatabasePoolExhausted \
  BackupNotExportedOffhost
do
  grep -Rq "alert: $alert" infra/monitoring/prometheus/rules \
    || fail "필수 알람이 없다: $alert"
done

assert_contains infra/monitoring/alertmanager/alertmanager.yml \
  'slack_api_url_file: /run/secrets/alertmanager_mattermost_webhook' \
  "Mattermost webhook이 파일 Secret으로 주입되지 않는다"
assert_not_contains infra/monitoring/alertmanager/alertmanager.yml \
  'https://' \
  "Alertmanager 설정에 webhook URL이 평문으로 들어 있다"
assert_contains infra/monitoring/blackbox/blackbox.yml \
  'insecure_skip_verify: false' \
  "blackbox가 TLS 검증을 건너뛴다"

# --- 3. Compose 구성 --------------------------------------------------------

assert_contains compose.monitoring.yaml \
  'alertmanager:' \
  "Alertmanager service가 없다"
assert_contains compose.monitoring.yaml \
  'blackbox:' \
  "Blackbox exporter service가 없다"
assert_contains compose.monitoring.yaml \
  'ALERTMANAGER_WEBHOOK_SECRET_FILE' \
  "Alertmanager webhook secret 정의가 없다"
assert_contains compose.monitoring.yaml \
  'APP_NETWORK_NAME' \
  "Prometheus가 운영 app network에 참여하지 않는다"
assert_contains compose.monitoring.yaml \
  '/var/log/journal:/var/log/journal:ro' \
  "host journal 수집 마운트가 없다"

# Day 8이 정한 경계를 넘지 않았는지 다시 확인한다.
assert_contains compose.monitoring.yaml 'internal: true' \
  "monitoring network가 internal이 아니다"
assert_not_contains compose.monitoring.yaml '/:/host/root' \
  "Alloy에 host 루트 파일시스템을 마운트했다"
assert_not_contains compose.monitoring.yaml '/var/run/docker.sock' \
  "관측 container에 Docker socket을 넣었다"
assert_not_contains compose.monitoring.yaml 'GF_SECURITY_ADMIN_PASSWORD:' \
  "Grafana 평문 비밀번호 환경변수가 생겼다"

# compose.monitoring.yaml에 필수(:?) 변수를 새로 넣으면 Day 8 게이트의
# `docker compose config` 호출이 그 변수 없이 돌다가 실패한다. Docker 없이도
# 그 조합을 잡으려고 두 파일을 대조한다.
for required_variable in $(
  sed -n 's/.*\${\([A-Z0-9_]*\):?.*/\1/p' compose.monitoring.yaml | sort -u
)
do
  assert_contains scripts/verify-day8-infrastructure.sh \
    "$required_variable=" \
    "Day 8 게이트가 필수 변수를 설정하지 않는다: $required_variable"
done

for compose_file in compose.prod.yaml compose.monitoring.yaml compose.jenkins.yaml
do
  assert_contains "$compose_file" \
    'labels: "com.docker.compose.project,com.docker.compose.service"' \
    "log label이 없어 Loki에서 container를 구분할 수 없다: $compose_file"
done

# --- 4. Grafana 대시보드 ----------------------------------------------------

assert_file infra/monitoring/grafana/provisioning/dashboards/gilbom.yml \
  "Grafana dashboard provider가 없다"
for dashboard in host containers backend operations
do
  assert_file "infra/monitoring/grafana/dashboards/$dashboard.json" \
    "대시보드가 없다: $dashboard"
done
assert_contains compose.monitoring.yaml \
  '/etc/grafana/dashboards' \
  "대시보드 디렉터리가 Grafana에 마운트되지 않는다"

# --- 5. 로그 파이프라인 -----------------------------------------------------

assert_contains infra/monitoring/alloy/config-logs.alloy \
  'stage.structured_metadata' \
  "requestId가 structured metadata로 들어가지 않는다"
assert_contains infra/monitoring/alloy/config-logs.alloy \
  'loki.source.journal' \
  "host journal 수집이 없다"
assert_contains infra/monitoring/alloy/config.alloy \
  'textfile' \
  "textfile collector가 없어 backup·디스크 metric이 수집되지 않는다"
assert_contains frontend/nginx/nginx.conf \
  '$sent_http_x_request_id' \
  "Nginx access log에 request id가 없다"

# 민감값 차단은 빠진 항목이 곧 유출 경로다. 목록으로 고정한다.
for secret_pattern in \
  password \
  authorization \
  cookie \
  GILBOM_SESSION \
  csrf \
  api \
  glpat- \
  sk- \
  totp
do
  grep -Fq -- "$secret_pattern" infra/monitoring/alloy/config-logs.alloy \
    || fail "로그 민감값 차단 규칙이 없다: $secret_pattern"
done

# --- 6. Backup과 복원 -------------------------------------------------------

assert_contains infra/scripts/backup-db.sh \
  'gilbom_backup_encrypt_file' \
  "DB backup이 암호화되지 않는다"
assert_contains infra/scripts/backup-volumes.sh \
  'gilbom_backup_encrypt_file' \
  "volume backup이 암호화되지 않는다"
assert_contains infra/scripts/backup-db.sh \
  'gilbom_backup_record_result' \
  "DB backup 결과가 metric으로 남지 않는다"
assert_contains infra/scripts/backup-volumes.sh \
  'gilbom_backup_record_result' \
  "volume backup 결과가 metric으로 남지 않는다"
assert_contains infra/scripts/backup-volumes.sh \
  'database.content.sha256' \
  "volume backup이 DB content hash manifest를 만들지 않는다"
assert_contains infra/scripts/backup-volumes.sh \
  'report_pdf_data.database.sha256' \
  "volume backup이 승인 PDF DB 참조 hash를 만들지 않는다"
assert_contains infra/scripts/backup-volumes.sh \
  'report_pdf_data.volume.sha256' \
  "volume backup이 승인 PDF file hash를 만들지 않는다"
assert_contains infra/scripts/backup-common.sh \
  'rm -f "$plain"' \
  "암호화 후 평문 backup이 지워지지 않는다"
assert_contains infra/scripts/record-offhost-export.sh \
  'only encrypted (.gpg) artifacts may be exported off-host' \
  "평문 반출을 막는 검사가 없다"
assert_contains infra/scripts/record-offhost-export.sh \
  'BACKUP_TEXTFILE_DIR="${BACKUP_TEXTFILE_DIR:-/opt/gilbom/monitoring/textfile}"' \
  "단독 설치되는 off-host 반출 진입점에 metric 경로 기본값이 없다"
assert_not_contains infra/scripts/record-offhost-export.sh \
  '. "$SCRIPT_DIR/backup-common.sh"' \
  "단독 설치되는 off-host 반출 진입점이 미설치 저장소 파일을 source한다"

for script in \
  infra/scripts/backup-common.sh \
  infra/scripts/backup-db.sh \
  infra/scripts/backup-volumes.sh \
  infra/scripts/generate-database-content-manifest.sh \
  infra/scripts/collect-host-metrics.sh \
  infra/scripts/gilbom-backup.sh \
  infra/scripts/install-day10-host.sh \
  infra/scripts/prepare-backup-encryption.sh \
  infra/scripts/record-offhost-export.sh \
  scripts/restore-drill.sh \
  scripts/run-isolated-backup-restore-drill.sh
do
  assert_file "$script" "스크립트가 없다: $script"
  sh -n "$script" || fail "셸 문법 오류: $script"
done

assert_contains scripts/restore-drill.sh \
  'DB table content SHA-256' \
  "복원본 DB content hash 대조가 없다"
assert_contains scripts/restore-drill.sh \
  'robot_images restored file SHA-256' \
  "복원본 image file hash 대조가 없다"
assert_contains scripts/restore-drill.sh \
  'report_pdf_data restored file SHA-256' \
  "복원본 PDF file hash 대조가 없다"
assert_contains scripts/restore-drill.sh \
  'PostgreSQL init process complete; ready for start up.' \
  "복원 Drill이 PostgreSQL 초기화용 임시 server를 최종 server로 오인할 수 있다"
assert_contains scripts/restore-drill.sh \
  'DRILL_BOOTSTRAP_DB=postgres' \
  "PostGIS 초기 객체와 운영 dump가 충돌하지 않도록 bootstrap DB를 분리하지 않았다"
assert_contains scripts/restore-drill.sh \
  '--template template0' \
  "복원 대상 DB가 완전히 빈 template0에서 생성되지 않는다"
assert_file scripts/generate-restore-drill-evidence.py \
  "복원 JSON 증거 생성기가 없다"
assert_file scripts/test-database-content-manifest.py \
  "DB content manifest self-test가 없다"
assert_file scripts/test-restore-drill-evidence.py \
  "복원 JSON 증거 self-test가 없다"
python3 scripts/test-database-content-manifest.py \
  || fail "DB content manifest self-test 실패"
python3 scripts/test-restore-drill-evidence.py \
  || fail "복원 JSON 증거 self-test 실패"

for unit in \
  gilbom-db-backup.service \
  gilbom-db-backup.timer \
  gilbom-volume-backup.service \
  gilbom-volume-backup.timer \
  gilbom-host-metrics.service \
  gilbom-host-metrics.timer
do
  assert_file "infra/systemd/$unit" "systemd unit이 없다: $unit"
done

assert_file infra/audit/gilbom.rules "auditd 규칙이 없다"
for watched in \
  /opt/gilbom/secrets \
  /opt/gilbom/deploy \
  /opt/gilbom/monitoring \
  /opt/gilbom/certbot \
  /usr/local/sbin/gilbom-deploy \
  /usr/local/sbin/gilbom-backup
do
  assert_contains infra/audit/gilbom.rules "$watched" \
    "auditd 감시 대상이 없다: $watched"
done

echo "Day 10 observability verification: PASS"
