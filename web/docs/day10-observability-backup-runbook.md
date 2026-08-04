# Day 10 관측·Backup·복원 Runbook

CloudWatch 없이 EC2 host, Docker container, Spring Backend, MQTT, 이미지 저장공간을
관찰하고 **실제 복원 절차를 검증한다.**

전제: Day 8 배포가 끝나 `/opt/gilbom/deploy`에 현재 revision이 있고 운영 stack이 돌고 있다.

---

## 0. 이번에 추가된 것과 구조상 선택

Day 8에서 Prometheus·Loki·Grafana·Alloy container는 이미 서 있었다. Day 10은 그 골격을
**실제 수집·알람·복원까지** 잇는다.

| 영역 | 추가된 것 |
| --- | --- |
| Backend | `micrometer-registry-prometheus`, `/actuator/prometheus`, health 구성요소 gauge, 인증·거부 counter, 로그 pattern의 `requestId` |
| Prometheus | Backend·Blackbox·Alertmanager scrape, alert rule 4묶음(30여 개) |
| Alertmanager | Mattermost webhook 라우팅, critical/warning 분리, 억제 규칙 |
| Grafana | 대시보드 4장 provisioning (호스트·컨테이너·Backend·운영) |
| Loki·Alloy | container 라벨, host journal, requestId structured metadata, 민감값 차단 |
| Backup | gpg 공개키 암호화, SHA-256, manifest, 보존기간, 성공·실패 metric |
| 자동화 | `gilbom-db-backup` / `gilbom-volume-backup` / `gilbom-host-metrics` timer |
| 복원 | `scripts/restore-drill.sh` — 임시 DB·volume 복원과 12개 항목 검사 |
| 감사 | `/etc/audit/rules.d/gilbom.rules` |

**두 가지는 계획과 다르게 만들었다. 이유가 있다.**

### cAdvisor를 쓰지 않는다

container CPU·메모리·재시작·OOM은 계획상 cAdvisor가 담당하기로 했다. 그런데 cAdvisor
계열(별도 container든 Alloy의 `prometheus.exporter.cadvisor`든)은 container 이름을 얻으려고
**Docker socket을 요구한다.** socket은 read-only로 마운트해도 Docker API 전체가 열려서,
그 container를 잡으면 host를 잡는 것과 같다. Day 8이 이 stack을 `cap_drop: ALL`·`read_only`·
loopback bind로 굳혀 둔 것과 정면으로 어긋난다.

그래서 **이미 권한을 가진 host가 값을 만들고, container는 결과만 읽는다.**
`gilbom-host-metrics.timer`가 2분마다 `docker inspect`·`docker stats`를 돌려
`/opt/gilbom/monitoring/textfile/*.prom`에 쓰고, Alloy는 그 디렉터리를 **read-only**로
읽어 노출한다. 얻는 metric은 `gilbom_container_running`·`gilbom_container_restart_count`·
`gilbom_container_oom_killed`·`gilbom_container_started_seconds`·`_cpu_percent`·`_memory_percent`다.

같은 이유로 디스크 metric도 이 경로다. node exporter의 filesystem collector는 host 루트
파일시스템 마운트를 요구하는데, `verify-day8-infrastructure.sh`가 그것을 금지한다.

잃은 것: container별 network·disk I/O 상세, cgroup 단위 정밀도. 이번 알람 목록에는
쓰이지 않는다.

### port 발행에는 비-internal network가 하나 필요하다

**Docker 28부터 `internal: true` network만 가진 container는 port 발행이 적용되지 않는다.**
`docker inspect`에는 `HostConfig.PortBindings`가 그대로 보이는데 host에서는 실제로 열리지
않아서, 설정만 봐서는 원인을 찾기 어렵다. `ss -ltnp`에 해당 port가 없는 것으로만 드러난다.

Day 8은 monitoring stack을 `internal: true` 하나에만 두었고 당시 Docker 버전에서는
발행이 동작했다. 이 EC2가 Docker 29로 올라오면서 Prometheus·Loki·Grafana·Alloy의
loopback port가 전부 닫혔다.

그래서 발행 경로 전용 bridge network(`loopback-publish`)를 하나 두고 이 넷을 붙인다.
**노출 경계는 그대로 `127.0.0.1` bind다** — 이 network에 있다고 해서 밖에서 닿지 않는다.
`verify-day10-observability.sh`가 "port를 발행하는 service는 비-internal network를 가진다"를
검사하므로 같은 실수가 다시 나면 정적 검사에서 걸린다.

### PostgreSQL·Mosquitto Exporter를 쓰지 않는다

connection pool은 Micrometer의 HikariCP metric으로 이미 보인다. MQTT는
`gilbom.mqtt.inbound.rejected`와 credential registry metric이 이미 있다. Exporter를 두면
DB 계정과 broker 계정이 하나씩 더 생기는데, 그만큼 얻는 것이 없다.

---

## 1. Host 준비

### 먼저 — `/opt/gilbom/deploy`를 Day 10 revision으로 올린다

**CI·CD가 성공해도 이 디렉터리는 갱신되지 않는다.** `gilbom-deploy.sh`는 여기를 읽기만
하고, 배포가 실제로 바꾸는 것은 container image와 `image.env` 한 줄뿐이다. 이 단계를
건너뛰면 아래 설치 명령이 `No such file`로 멈춘다.

`/opt/gilbom`과 `/var/lib/gilbom`은 root 전용이라 확인 명령에도 `sudo`가 필요하다.

```sh
release_sha="$(sudo cat /var/lib/gilbom/releases/current.sha)"
sudo ls "/opt/gilbom/artifacts/$release_sha"
```

목록에 `gilbom-web-<sha>.tar.zst`가 **있는지 없는지로 경로가 갈린다.**

**(가) 소스 archive가 있는 경우** — 수동으로 승격한 release다. 그대로 푼다.

```sh
sudo sh -c '
  sha="$(cat /var/lib/gilbom/releases/current.sha)"
  cd "/opt/gilbom/artifacts/$sha"
  sha256sum -c SHA256SUMS
  zstd -dc "gilbom-web-$sha.tar.zst" \
    | tar --extract --file - --directory /opt/gilbom/deploy --no-same-owner
'
```

**(나) 없는 경우** — Jenkins 배포다. `promote-ci-release-artifacts.sh`의 허용 목록에
소스 archive가 없어서 host에 존재하지 않는다. 배포된 SHA로 로컬에서 같은 archive를 만들어
SFTP로 옮긴다. `build-release-artifacts.sh`와 같은 방식이라 내용은 공식 archive와 같다.

```sh
# 로컬 (저장소 root)
sha=<current.sha 값>
git archive --format=tar "$sha:web" | gzip -9 > "/tmp/gilbom-web-$sha.tar.gz"

# 운영 상태 파일이 섞이지 않았는지 — Day 8과 같은 검사
tar -tzf "/tmp/gilbom-web-$sha.tar.gz" \
  | grep -E '(^|/)(runtime\.env|image\.env)$|(^|/)secrets/[^/]+$' \
  | grep -Ev '(^|/)secrets/\.gitignore$' \
  && echo "운영 상태가 섞여 있다. 전송하지 않는다." || echo "안전"

sha256sum "/tmp/gilbom-web-$sha.tar.gz"
```

SFTP로 `/home/ubuntu`에 올린 뒤 EC2에서 checksum을 대조하고 푼다.

```sh
sha256sum "gilbom-web-$sha.tar.gz"          # 로컬 값과 일치해야 한다
sudo tar --extract --gzip --file "gilbom-web-$sha.tar.gz" \
  --directory /opt/gilbom/deploy --no-same-owner
rm -f "gilbom-web-$sha.tar.gz"
```

> **(나)는 우회다.** 감사 흔적이 남는 정공법은 CI 승격 목록에 소스 archive를 포함시키는
> 것이고, 그건 배포 파이프라인 소유자의 몫이다.

어느 쪽이든 archive에는 Git이 추적하는 파일만 들어 있어 `runtime.env`, `image.env`,
`/opt/gilbom/secrets`, named volume은 보존된다. 푼 뒤 확인한다.

```sh
cd /opt/gilbom/deploy
test -f infra/scripts/install-day10-host.sh \
  || echo "이 release에는 Day 10이 없다. master 기준으로 CI를 다시 돌려야 한다."
sh scripts/verify-day10-observability.sh
sudo docker compose --env-file runtime.env --env-file image.env \
  -f compose.prod.yaml config --quiet && echo "운영 compose 정상"
```

### 설치

```sh
cd /opt/gilbom/deploy
sudo sh infra/scripts/install-day10-host.sh
```

이 스크립트가 하는 일:

- `/opt/gilbom/monitoring/textfile` 생성 (0755 — container 안의 비-root가 읽어야 한다)
- `/usr/local/sbin/gilbom-host-metrics`, `/usr/local/sbin/gilbom-backup`,
  `/usr/local/sbin/gilbom-record-offhost-export` 설치
- systemd unit 6개 설치 후 timer 3개 enable
- auditd가 설치돼 있으면 `/etc/audit/rules.d/gilbom.rules` 적용

`monitoring.env`에 Day 10 값을 채운다.

```sh
sudo diff /opt/gilbom/monitoring/monitoring.env \
  /opt/gilbom/deploy/infra/monitoring/monitoring.env.example
```

새로 필요한 항목은 다섯이다.

```
ALERTMANAGER_PORT=9093
ALERTMANAGER_WEBHOOK_SECRET_FILE=/opt/gilbom/secrets/alertmanager_mattermost_webhook
APP_NETWORK_NAME=gilbom_app
MONITORING_TEXTFILE_DIR=/opt/gilbom/monitoring/textfile
PROBE_DOMAIN=i15d101.p.ssafy.io
```

`APP_NETWORK_NAME`은 실제 network 이름과 같아야 한다. 다르면 Prometheus가 Backend를
못 긁는다.

```sh
docker network ls --filter name=_app --format '{{.Name}}'
```

> **기동 순서가 생겼다.** Prometheus가 운영 stack의 `app` network에 `external`로 참여하므로
> **운영 stack이 먼저 올라와 있어야** monitoring stack이 뜬다. 반대로 운영 stack을
> `down` 할 때 Prometheus가 붙어 있으면 network 삭제만 실패하고 나머지는 정상 진행된다
> (다음 `up`이 같은 network를 다시 쓴다). 재배포 순서를 굳이 바꿀 필요는 없지만,
> `down` 로그의 network 관련 경고는 이 이유다.

> **`monitoring.env`를 갱신하기 전에는 Day 8 보조 검사가 실패한다.**
> `verify-day8-auxiliary-runtime.sh`가 이 env 파일로 `docker compose config`를 돌리는데,
> `ALERTMANAGER_WEBHOOK_SECRET_FILE`이 없으면 필수 변수 누락으로 멈춘다. 위 다섯 줄을 먼저 넣는다.
> 이 스크립트는 Monitoring만 보지 않는다. Jenkins와 DinD도 함께 검사하므로 Jenkins가
> 내려가 있으면 Monitoring이 정상이어도 실패한다.

Mattermost webhook을 Secret 파일로 넣는다. 값은 저장소에 남기지 않는다.

```sh
sudo install -d -m 0700 /opt/gilbom/secrets
printf '%s\n' '<Mattermost incoming webhook URL>' \
  | sudo tee /opt/gilbom/secrets/alertmanager_mattermost_webhook >/dev/null
sudo chmod 0400 /opt/gilbom/secrets/alertmanager_mattermost_webhook
```

---

## 2. Backup 암호화 키 등록

**개인키는 이 서버에 두지 않는다.** EC2가 통째로 털려도 반출본을 열 수 없어야 한다.

키 보관자의 PC에서 (한 번만):

```sh
gpg --full-generate-key            # RSA 4096 또는 ed25519
gpg --armor --export <키 ID> > backup-recipient-public.asc
```

공개키만 서버로 올려 등록한다.

```sh
cd /opt/gilbom/deploy
sudo sh infra/scripts/prepare-backup-encryption.sh ~/backup-recipient-public.asc
```

출력된 지문을 **키 보관자와 육성으로 대조한다.** 지문이 다르면 아무도 열 수 없는 backup만
쌓인다. 개인키가 섞여 있으면 스크립트가 거부한다.

> TOTP keyring(`/opt/gilbom/secrets/gilbom_mfa_totp_keyring`)은 DB backup에 들어가지 않는다.
> keyring을 잃으면 `auth.mfa_factor`의 암호문은 복구 불가다. **DB backup과 다른 경로로
> 따로 반출해 따로 보관한다.**

---

## 3. Monitoring stack 재기동과 확인

```sh
cd /opt/gilbom/deploy
sudo docker compose \
  --project-name gilbom-monitoring \
  --env-file /opt/gilbom/monitoring/monitoring.env \
  -f compose.monitoring.yaml \
  up --detach --wait --wait-timeout 180
```

Backend는 새 image가 필요하다(Micrometer registry가 들어갔다). Day 8 배포 절차로
새 revision을 올린다.

### 첫 검사 전에 metric을 한 번씩 만들어 둔다

timer는 주기가 와야 처음 돈다. 그전에 검사하면 **정상인데도 실패한다.**

```sh
# host metric을 즉시 한 번 생성 (timer의 첫 주기를 기다리지 않는다)
sudo systemctl start gilbom-host-metrics.service

# backup metric도 한 번 실행해야 생긴다
sudo /usr/local/sbin/gilbom-backup database
```

volume backup은 Frontend·Backend·Mosquitto를 잠시 멈춘다. **시연 시간에는 실행하지 않는다.**
유지보수 시간에 따로 돌린다. 그전까지는 검사에서 `backup volumes` 항목이 실패하는 것이 정상이다.

```sh
sudo /usr/local/sbin/gilbom-backup volumes
```

### 검사

```sh
cd /opt/gilbom/deploy
sudo sh scripts/verify-day8-auxiliary-runtime.sh
sudo sh scripts/verify-day10-runtime.sh
```

수집 대상 전부 up, alert rule 20개 이상, Alertmanager ready, Loki에 `compose_service`
label, host metric 신선도, backup metric까지 한 번에 본다.

### Grafana

Grafana는 `127.0.0.1:3001`에만 bind돼 있다. MobaXterm SSH 터널로 연다.

```
Local port : 3000
Remote host: 127.0.0.1
Remote port: 3001
```

브라우저에서 `http://127.0.0.1:3000` → 길봄 폴더에 4장이 있어야 한다.

| 대시보드 | 확인할 것 |
| --- | --- |
| 길봄 · 호스트 | CPU·메모리·Swap·Load, 파일시스템 여유, robot_images 사용량과 하루 증가량 |
| 길봄 · 컨테이너 | 실행 상태, CPU·메모리, 재시작, OOM |
| 길봄 · Backend | up, readiness 구성요소, HTTP 요청·p95·5xx, JVM heap, Hikari, MQTT, 인증·권한 이벤트 |
| 길봄 · 운영 | 마지막 backup 성공·결과·반출, TLS 만료, 배포 시각, backup 용량 |

대시보드는 저장소가 정본이다(`allowUiUpdates: false`). 화면에서 고치면 다음 배포에 덮인다.

---

## 4. Loki에서 requestId로 요청 추적

한 요청이 Nginx access log와 Backend 로그에 같은 값으로 찍히는지 본다.

```sh
curl -sS -D- -o /dev/null https://i15d101.p.ssafy.io/api/v1/auth/csrf-token \
  | grep -i x-request-id
```

그 값으로 Loki에 묻는다(터널을 3100으로 하나 더 열거나 host에서 직접).

```sh
curl -sS --get http://127.0.0.1:3100/loki/api/v1/query_range \
  --data-urlencode 'query={compose_service=~"backend|frontend"} | requestId="<값>"' \
  | jq -r '.data.result[].values[][1]'
```

Backend 줄과 Nginx access log 줄이 **둘 다** 나와야 한다.

### 민감값이 남지 않았는지

```sh
for pattern in password Authorization Cookie GILBOM_SESSION csrf sk- glpat-; do
  echo "== $pattern"
  curl -sS --get http://127.0.0.1:3100/loki/api/v1/query_range \
    --data-urlencode "query={job=~\"docker|systemd-journal\"} |= \"$pattern\"" \
    --data-urlencode 'limit=20' \
    | jq -r '.data.result[]?.values[]?[1]'
done
```

나오는 줄이 있다면 값 자리가 `[REDACTED]`여야 한다. 실제 값이 보이면
`infra/monitoring/alloy/config-logs.alloy`의 `loki.process "redact"`에 규칙을 추가한다.
**저장된 뒤에 지우는 것은 사실상 불가능하므로 여기서 막는 것이 유일한 방어선이다.**

---

## 5. 알람 실제 수신 시험

시험은 **채널로 실제 메시지가 오는 것까지** 봐야 끝난다. Prometheus 화면에서 firing만
보고 끝내면 webhook 오타를 못 잡는다.

### 5.1 Backend Down

```sh
cd /opt/gilbom/deploy
sudo docker compose --env-file runtime.env --env-file image.env \
  -f compose.prod.yaml stop backend
# 2분 뒤 BackendDown critical 수신 확인
sudo docker compose --env-file runtime.env --env-file image.env \
  -f compose.prod.yaml start backend
# 해제 메시지(send_resolved)까지 확인
```

`BackendDown`이 뜨면 `StorageReadinessDown` 등 하위 알람은 억제 규칙으로 조용해야 한다.
둘 다 오면 `alertmanager.yml`의 `inhibit_rules`가 적용되지 않은 것이다.

### 5.2 디스크 부족

임계를 넘기려고 실제 디스크를 채우지 않는다. textfile 값을 잠시 바꿔 넣는다.

```sh
sudo systemctl stop gilbom-host-metrics.timer
sudo tee /opt/gilbom/monitoring/textfile/gilbom-host.prom >/dev/null <<'EOF'
# HELP gilbom_filesystem_size_bytes Total size of a host filesystem.
# TYPE gilbom_filesystem_size_bytes gauge
gilbom_filesystem_size_bytes{mountpoint="/"} 100000000000
# HELP gilbom_filesystem_avail_bytes Available space on a host filesystem.
# TYPE gilbom_filesystem_avail_bytes gauge
gilbom_filesystem_avail_bytes{mountpoint="/"} 1000000000
# HELP gilbom_host_metrics_updated_seconds Last successful host metric collection.
# TYPE gilbom_host_metrics_updated_seconds gauge
gilbom_host_metrics_updated_seconds 0
EOF
# 여유 1% → 5분 안에 HostDiskCritical 수신 확인
sudo systemctl start gilbom-host-metrics.timer
sudo systemctl start gilbom-host-metrics.service   # 정상 값으로 즉시 복구
```

마지막 줄을 잊으면 `HostMetricsStale`이 계속 뜬다. 그것도 알람이 살아 있다는 증거다.

### 5.3 robot_images 용량

`gilbom_path_usage_bytes{path="robot_images"}`를 같은 방식으로 크게 써 넣으면
`RobotImagesStorageFillingUp`이 뜬다. 이 알람은 **6시간 기울기 예측**이라 값을 한 번
바꾸는 것으로는 30분 이상 기다려야 한다. 급히 확인하려면 Prometheus의 rule 화면에서
식을 직접 평가한다.

### 5.4 Backup 실패

```sh
sudo mv /opt/gilbom/secrets/backup-recipient.asc /root/backup-recipient.asc.bak
sudo BACKUP_ROOT=/opt/gilbom/backups /usr/local/sbin/gilbom-backup database || true
# BackupFailed critical 수신 확인
sudo mv /root/backup-recipient.asc.bak /opt/gilbom/secrets/backup-recipient.asc
sudo /usr/local/sbin/gilbom-backup database
```

암호화 키가 없으면 backup은 **dump를 뜨기 전에** 멈춘다. 평문 dump를 만들어 놓고 실패하면
개인정보가 든 파일이 디스크에 남기 때문이다.

---

## 6. 복원 시험

`scripts/restore-drill.sh`는 운영 stack을 건드리지 않는다. 임시 network·container·volume만
만들고 끝나면 지운다.

**복호화에는 개인키가 필요하다.** 원칙대로라면 반출본을 내려받은 PC에서 돌린다.
운영 EC2에 복호화 개인키를 올려 Drill하지 않는다. 새 형식의 bundle에는
`database.content.sha256`, Robot image와 승인 PDF의 DB/Volume hash manifest가
모두 있어야 한다.

```sh
# GPG Private key와 별도 반출한 TOTP keyring이 있는 보안 복원 Runner에서 실행
export GNUPGHOME=/secure/gilbom-backup-keyring
export TOTP_KEYRING_FILE=/secure/gilbom_mfa_totp_keyring
RESTORE_DRILL_OUTPUT_DIR=.artifacts/day10/restore \
  sh scripts/run-isolated-backup-restore-drill.sh \
  /secure/backups/<timestamp>-gilbom
jq . .artifacts/day10/restore/summary.json
```

검사 항목:

1. 번들 SHA256SUMS 대조
2. dump·volume archive 복호화
3. 임시 PostgreSQL 기동, 임시 volume 복원
4. `pg_restore --exit-on-error`
5. 애플리케이션 전체 table별 행 수·정규화된 content SHA-256 완전 일치
6. 스키마 9종, Flyway 최신 version과 실패 이력 없음
7. 계정·역할·로봇·임무·결함·감사 로그·보고서 version 행 수
8. 승인 PDF 참조 건수, 감사 로그 연속성
9. TOTP `secret_key_version`이 keyring에 있는지
10. `storage.robot_image.object_key` ↔ 복원 파일 + SHA-256 전건 일치
11. 승인 PDF storage key ↔ 복원 파일 + SHA-256 전건 일치
12. Robot image·승인 PDF Volume 전체 파일 manifest 완전 일치
13. MQTT credential 파일 존재와 세션 처리 정책 안내

`--keep`을 붙이면 임시 자원을 남긴다. 실패 원인을 파고들 때 쓴다.
기본 운영 증거 실행은 wrapper를 사용하고 `--keep`을 사용하지 않는다.

### 복원된 이미지를 화면에서 확인

숫자만 맞고 파일이 깨져 있을 수 있다. 실제 복원(재해복구)에서는 마지막에 반드시
결함 상세 카드를 열어 사진이 뜨는 것까지 본다. 절차는
[scripts/RESTORE.md](../scripts/RESTORE.md) 5절과 같다.

### 세션 정책

**재해복구 후에는 세션을 폐기하고 전체 재로그인을 요구한다.** 복원 시점과 현재 사이의
권한 변경이 세션에 반영되지 않기 때문이다.

```sql
TRUNCATE session_store.spring_session_attributes,
         session_store.session_security_state,
         session_store.spring_session;
```

---

## 7. Off-host 반출

로컬 backup만 있으면 EC2가 사라질 때 함께 사라진다.

MobaXterm SFTP로 내려받는다 — `.gpg`와 `.sha256`, `manifest.json`을 함께 받는다.

```
/opt/gilbom/backups/postgres/<타임스탬프>-gilbom.dump.gpg
/opt/gilbom/backups/postgres/<타임스탬프>-gilbom.dump.gpg.sha256
/opt/gilbom/backups/postgres/<타임스탬프>-gilbom.dump.gpg.manifest.json
/opt/gilbom/backups/<타임스탬프>-gilbom/          (volume 번들 전체)
```

받은 PC에서 checksum을 대조한 뒤 서버에 기록을 남긴다.

```sh
sudo /usr/local/sbin/gilbom-record-offhost-export postgres \
  /opt/gilbom/backups/postgres/<타임스탬프>-gilbom.dump.gpg
sudo /usr/local/sbin/gilbom-record-offhost-export volumes \
  /opt/gilbom/backups/<타임스탬프>-gilbom
```

이 명령은 checksum을 먼저 대조하고, **암호화되지 않은 파일은 거부한다.** 기록이 7일을
넘으면 `BackupNotExportedOffhost` 알람이 뜬다.

TOTP keyring은 **별도 경로로 따로** 반출한다. DB backup과 같은 곳에 두면 두 개를 한 번에
잃는다.

---

## 8. Day 10 완료 판정

| 항목 | 확인 방법 |
| --- | --- |
| 서버·컨테이너·Backend metric 조회 | 3절 — Grafana 4장 |
| Loki에서 requestId 검색 | 4절 — Backend와 Nginx 줄이 함께 나온다 |
| 테스트 Alert 실제 수신 | 5절 — Mattermost 채널에 메시지 도착 |
| Backend Down·디스크 부족·Backup 실패 Alert | 5.1 / 5.2 / 5.4 |
| robot_images 용량 Alert | 5.3 |
| PostgreSQL Backup 임시 DB 복원 | 6절 — `pg_restore` PASS |
| 이미지·보고서·MQTT Volume 임시 복원 | 6절 — 임시 volume 복원 PASS |
| DB Object Key와 실제 이미지 파일 일치 | 6절 9번 — 전건 일치 |
| 복원된 결함 이미지를 Frontend에서 출력 | 6절 — 사람이 화면으로 |
| 로그에 Password·Token·Session ID·LLM Key 없음 | 4절 — 전부 `[REDACTED]` |
| Backup 압축·암호화·Checksum 검증 | 2·6·7절 |
| 암호화된 Off-host Backup 최소 1개 | 7절 — 반출 기록 metric |

정적 계약은 언제든 다시 볼 수 있다.

```sh
sh scripts/verify-day10-observability.sh
sh scripts/verify-day8-infrastructure.sh   # Day 8 경계가 깨지지 않았는지
```

---

## 9. 남는 위험

- **개인키를 잃으면 모든 backup을 열 수 없다.** 공개키 지문을 등록할 때 대조하고, 개인키는
  최소 두 사람이 각자 보관한다.
- **TOTP keyring을 잃으면 MFA 암호문은 복구 불가다.** DB를 되살려도 사용자는 MFA를 다시
  등록해야 한다.
- **`gilbom-host-metrics.timer`가 멈추면 디스크 알람이 조용히 사라진다.** 그것을 잡으라고
  `HostMetricsStale`이 있다. 이 알람만은 무시하지 않는다.
- **Loki 민감값 차단은 목록 기반이다.** 새로운 형식의 비밀값이 생기면 규칙을 함께 늘려야
  한다. 늘리지 않으면 조용히 저장된다.
- **HTTPS probe는 공개 DNS 경로를 확인하지 않는다.** EC2가 자기 공인 IP로 되돌아오는 접속을
  받지 못해서, blackbox는 도메인을 host-gateway로 해석해 published 443 port를 친다.
  인증서·nginx·Backend 경로는 실제로 검증되지만 **DNS와 보안그룹이 잘못돼 밖에서 못 들어오는
  상황은 이 알람으로 잡히지 않는다.** 그것은 시연 전 외부 브라우저 확인으로 잡는다.
- Prometheus 보존은 15일, Loki는 14일이다. 그보다 오래된 사건은 남지 않는다.
