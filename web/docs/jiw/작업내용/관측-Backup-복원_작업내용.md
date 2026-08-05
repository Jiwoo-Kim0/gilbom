# 관측·Backup·복원(Day 10) 작업내용

기간: 2026-08-03 ~ 08-04
범위: Prometheus 수집·알람, Grafana 대시보드, Loki/Alloy 로그, Backup 암호화·반출,
   systemd 자동화, 복원 시험, host auditd

관련 문서: [day10-observability-backup-runbook.md](../../day10-observability-backup-runbook.md),
[day8-completion-runbook.md](../../day8-completion-runbook.md),
[RESTORE.md](../../../scripts/RESTORE.md),
[environment-configuration-strategy.md](../../environment-configuration-strategy.md)

---

## 1. 배경

CloudWatch를 쓰지 않기로 하면서 EC2 host·container·Backend·MQTT·이미지 저장공간을 볼
수단이 없어졌다. 동시에 backup은 만들어지기만 하고 **복원이 한 번도 검증되지 않은
상태**였다. Day 10은 이 둘을 닫는다.

Day 8(`bfc93d9`, A 작업)에서 Prometheus·Loki·Grafana·Alloy container와
`backup-db.sh`·`backup-volumes.sh`는 이미 서 있었다. 비어 있던 것은 그 사이를 잇는 배선이다.

| 영역 | Day 8까지 | Day 10에서 채운 것 |
| --- | --- | --- |
| Backend metric | actuator `health,info`만 | Micrometer registry, `/actuator/prometheus`, health gauge, 보안 counter |
| Prometheus | 자기 자신·Loki·Alloy 3개만 수집 | Backend·Blackbox·Alertmanager 추가, rule 30여 개 |
| 알람 | 없음 | Alertmanager → Mattermost |
| Grafana | datasource만 | 대시보드 4장 provisioning |
| Loki | docker 로그를 라벨 없이 통째로 | container 라벨, host journal, requestId, 민감값 차단 |
| Backup | dump + checksum | gpg 암호화, manifest, 보존기간, 성공·실패 metric |
| 자동화 | cert-renew timer 하나 | backup 2종 + host metric timer |
| 복원 | 절차 문서만 | 임시 DB·volume 복원 자동 검사 |

---

## 2. 무엇을 만들었나

### 새 파일

| 파일 | 역할 |
| --- | --- |
| `backend/.../health/HealthComponentMetrics.java` | health contributor별 gauge |
| `infra/monitoring/prometheus/rules/{host,container,backend,operations}.yml` | 알람 30여 개 |
| `infra/monitoring/alertmanager/alertmanager.yml` | Mattermost 라우팅·억제 규칙 |
| `infra/monitoring/blackbox/blackbox.yml` | HTTPS probe 모듈 |
| `infra/monitoring/grafana/dashboards/*.json` (4장) | 호스트·컨테이너·Backend·운영 |
| `infra/monitoring/grafana/provisioning/dashboards/gilbom.yml` | 대시보드 provider |
| `infra/scripts/backup-common.sh` | 암호화·checksum·manifest·보존기간·metric 공통 절차 |
| `infra/scripts/collect-host-metrics.sh` | host가 쓰는 textfile metric |
| `infra/scripts/gilbom-backup.sh` | backup 진입점(systemd·사람 공용) |
| `infra/scripts/prepare-backup-encryption.sh` | gpg 공개키 등록 |
| `infra/scripts/record-offhost-export.sh` | 반출 기록 + checksum 대조 |
| `infra/scripts/install-day10-host.sh` | host 경로·진입점·timer·auditd 설치 |
| `infra/systemd/gilbom-{db-backup,volume-backup,host-metrics}.{service,timer}` | 자동화 |
| `infra/audit/gilbom.rules` | host 감사 규칙 |
| `scripts/restore-drill.sh` | 임시 DB·volume 복원과 12개 항목 검사 |
| `scripts/verify-day10-observability.sh` | 정적 계약 검사 |
| `scripts/verify-day10-runtime.sh` | 운영 host 실행 검사 |
| `docs/day10-observability-backup-runbook.md` | 설치·시험·완료 판정 |

### 고친 파일

| 파일 | 변경 |
| --- | --- |
| `backend/build.gradle` | `micrometer-registry-prometheus` (runtimeOnly) |
| `backend/.../application.yml` | `include: health,info,prometheus`, `logging.pattern.level`에 `%X{requestId}` |
| `backend/.../SecurityConfig.java` | `/actuator/prometheus` permitAll |
| `backend/.../AuthenticationAuditService.java` | `gilbom.security.auth.events` counter |
| `backend/.../SecurityAuditService.java` | `gilbom.security.denials` counter |
| `compose.monitoring.yaml` | alertmanager·blackbox 추가, app/probe-egress network, textfile·journal 마운트 |
| `compose.{prod,monitoring,jenkins}.yaml` | logging `labels` 옵션 |
| `infra/monitoring/prometheus/prometheus.yml` | scrape 5종, rule·alerting 절 |
| `infra/monitoring/alloy/config.alloy` | textfile collector |
| `infra/monitoring/alloy/config-logs.alloy` | 라벨·journal·requestId·민감값 차단 |
| `infra/scripts/backup-{db,volumes}.sh` | 암호화·manifest·보존기간·metric |
| `frontend/nginx/nginx.conf` | access log에 `$sent_http_x_request_id` |

---

## 3. 설계 판단과 그 이유

### 3.1 cAdvisor를 쓰지 않고 host가 metric을 쓴다

계획에서는 container CPU·메모리·재시작·OOM을 cAdvisor가 담당하기로 했다. **막힌 지점은
Docker socket이었다.** cAdvisor 계열은 별도 container든 Alloy의
`prometheus.exporter.cadvisor`든 container 이름을 얻으려고 socket을 요구한다. socket은
read-only로 마운트해도 Docker API 전체가 열려서, 그 container를 잡으면 host를 잡는 것과 같다.

Day 8이 이 stack을 `cap_drop: ALL`·`read_only`·loopback bind로 굳혀 뒀고,
`verify-day8-infrastructure.sh`가 host 루트 마운트까지 금지한다. socket을 넣는 것은 그
경계를 정면으로 되돌리는 일이다.

**그래서 방향을 뒤집었다 — 이미 권한을 가진 host가 값을 만들고, container는 결과만 읽는다.**
`gilbom-host-metrics.timer`가 2분마다 `docker inspect`·`docker stats`를 돌려
`/opt/gilbom/monitoring/textfile/*.prom`에 쓰고, Alloy의 unix exporter textfile collector가
그 디렉터리를 **read-only**로 읽어 노출한다.

같은 판단이 디스크에도 적용된다. node exporter의 filesystem collector는 host mount point를
container 안에서 stat해야 해서 루트 파일시스템 마운트를 요구한다. 그래서 `df` 결과도 같은
textfile 경로로 보낸다.

- 얻은 것: **Day 10에서 늘어난 권한면이 0이다.**
- 잃은 것: container별 network·disk I/O 상세, cgroup 단위 정밀도. 이번 알람 목록에는 안 쓰인다.
- 대가: 수집기가 죽으면 디스크 알람이 조용히 사라진다. 그것을 잡으려고 `HostMetricsStale`을
  따로 뒀다. **이 알람만은 무시하면 안 된다.**

### 3.2 Docker socket 없이 container를 구분하는 법

`loki.source.docker`도 socket을 요구한다. 대신 json-file 드라이버의 `labels` 옵션을 썼다.

```yaml
logging:
  options:
    labels: "com.docker.compose.project,com.docker.compose.service"
```

이러면 드라이버가 매 줄에 `attrs`를 함께 쓴다. Alloy는 `stage.json`으로 그 `attrs`를 먼저
꺼내고, 그다음 `stage.docker`로 봉투를 벗긴다. **순서가 중요하다** — `stage.docker`가 먼저
돌면 남는 것은 애플리케이션 로그 본문이라 `attrs`가 사라진다.

### 3.3 requestId는 label이 아니라 structured metadata

`requestId`는 요청마다 다르다. Loki label로 만들면 stream이 무한히 갈라져 index가 터진다.
`stage.structured_metadata`로 넣으면 검색은 되면서 stream은 하나로 유지된다.
`loki.yml`에 `allow_structured_metadata: true`가 이미 있어 설정 변경이 필요 없었다.

edge와 애플리케이션을 잇는 값이라 양쪽에 같은 값을 찍었다 — Backend는
`logging.pattern.level`의 `%X{requestId:-}`, Nginx는 access log 끝의
`$sent_http_x_request_id`. `RequestIdFilter`가 이미 MDC와 응답 헤더를 채우고 있어서
새로 만든 것은 없다.

### 3.4 민감값은 저장 전에 지운다

Loki에 들어간 뒤에 지우는 것은 사실상 불가능하다. `loki.process "redact"`의 10개 규칙이
**유일한 방어선**이고, 여기 빠진 항목이 곧 유출 경로다. 그래서
`verify-day10-observability.sh`가 규칙 목록을 이름으로 고정해 검사한다. 새 비밀 형식이
생기면 두 곳을 함께 고쳐야 한다.

### 3.5 알람용 counter는 기존 단일 진입점에만 붙였다

로그인 실패·계정 잠금·CSRF·권한 거부는 metric이 없어 알람을 만들 수 없었다. 호출부마다
counter를 넣는 대신, 모든 감사 이벤트가 지나는 지점 하나씩만 골랐다.

- `AuthenticationAuditService.appendRequired` / `appendBestEffort` → `gilbom.security.auth.events{action,result}`
- `SecurityAuditService.recordSafely` → `gilbom.security.denials{type}`

둘 다 action·result·denialType이 enum이거나 고정 문자열이라 label 조합이 유한하다.
**요청 경로는 label에 넣지 않았다** — 임의 URL이 곧 label 값이 되어 시계열이 무한히 늘어난다.

### 3.6 개인키를 서버에 두지 않는다

gpg 공개키로만 잠근다. EC2가 통째로 털려도 반출본을 열 수 없어야 하고, 복호화는 키를 가진
사람의 PC에서만 일어나야 한다. `prepare-backup-encryption.sh`는 **개인키가 섞여 들어오면
거부한다** — 그 자체가 이 설계의 반대이기 때문이다.

대가는 복원 시험 위치다. 서버에서 drill을 돌리려면 임시 `GNUPGHOME`으로 개인키를 잠시
올려야 하고, 끝난 뒤 지워야 한다. runbook 6절에 그 절차를 적었다.

TOTP keyring은 DB backup에 넣지 않고 **다른 경로로 따로** 반출한다. 같은 곳에 두면 둘을
한 번에 잃는다.

### 3.7 backup은 dump를 뜨기 전에 키를 확인한다

암호화 수단이 없는데 dump부터 뜨면, 실패했을 때 개인정보가 든 평문 파일이 디스크에 남는다.
그래서 `gilbom_backup_require_gpg`를 `pg_dump` 앞에 뒀다. volume backup도 마찬가지로
**서비스를 멈추기 전에** 확인한다 — 멈춘 뒤 실패하면 정지 시간만 늘어난다.

### 3.8 실패도 metric으로 남긴다

실패했을 때 metric을 안 쓰면 "timer가 죽은 것"과 구분되지 않는다. 그래서 `fail()`에서도
`gilbom_backup_record_result ... 0`을 쓴다. 다만 **실패가 직전 성공 시각을 덮으면 안 되므로**
이전 파일에서 값을 읽어 이어받는다. 알람은 두 방향으로 건다 — 실행했는데 실패한 경우
(`BackupFailed`)와 아예 실행되지 않은 경우(`BackupStale`·`BackupMissing`).

---

## 4. 작업 중 겪은 함정

**EC2는 자기 공인 IP로 되돌아오는 접속을 받지 못한다.** blackbox가 공개 도메인을 그대로
찌르면 hairpin NAT 미지원으로 항상 실패해 `HttpsProbeFailed`가 상시 발화한다. container에
`extra_hosts`로 도메인을 host-gateway에 묶어 published 443 port를 치게 했다. 인증서 SNI와
Host 헤더는 도메인 그대로라 TLS 검증·만료일은 실제 값으로 나온다. **다만 공개 DNS와
보안그룹 경로는 이 probe로 검증되지 않는다.**

**PromQL은 label 집합이 다르면 비교가 매칭되지 않는다.** `predict_linear(...{path="robot_images"})`와
`gilbom_filesystem_size_bytes{mountpoint="/"}`를 그냥 비교하면 결과가 빈다. `on() group_left()`
대신 `scalar()`로 감싸는 쪽이 짧고 확실하다. `node_load1 / count(...)`도 같은 이유로 고쳤다.

**Prometheus를 운영 app network에 붙이면 기동 순서가 생긴다.** `external: true`라
운영 stack이 먼저 올라와 있어야 monitoring stack이 뜬다. 반대로 운영 stack을 `down` 할 때는
network 삭제만 실패하고 나머지는 정상 진행된다(다음 `up`이 같은 network를 다시 쓴다).
`down` 로그의 network 경고는 이 이유다.

**textfile collector는 부분적으로 쓰인 파일도 그대로 읽는다.** 임시 파일에 만든 뒤 같은
파일시스템 안에서 `mv`로 교체해야 반쯤 쓰인 metric이 노출되지 않는다.

**`du`는 비싸다.** 2분마다 `/var/lib/docker`와 robot_images를 훑으면 디스크가 계속 돈다.
파일시스템·container 상태는 매 실행, 경로별 사용량은 30분 주기로 나눴다.

**생성자에 인자를 하나 더하면 직접 생성하는 테스트가 깨진다.** `AuthenticationAuditService`와
`SecurityAuditService`를 `new`로 만드는 단위 테스트 두 개를 `SimpleMeterRegistry`와 함께
고쳤다.

---

## 5. 검증 상태

**여기서 통과한 것**

- `scripts/verify-day10-observability.sh` — PASS
- Day 8 게이트(`verify-day8-infrastructure.sh`)의 파일 기반 단언 80개 재실행 — 전부 통과
- 신규 셸 스크립트 9개 `sh -n`, 모든 YAML과 대시보드 JSON 파싱

**여기서 못 한 것 — 작업 환경에 Java도 Docker도 없다.**

`java: command not found`이고 docker socket 권한도 없어, 백엔드 컴파일·테스트와 스택 기동을
확인하지 못했다. 그래서 Java 쪽은 컴파일 위험이 낮은 API만 쓰도록 한 번 다시 썼다 —
`HealthContributorRegistry`(Boot 버전에 따라 패키지가 움직인다) 대신
`Map<String, HealthIndicator>` 주입으로 바꾸고, actuator가 bean 이름에서 `HealthIndicator`
접미사를 떼는 규칙을 그대로 흉내 냈다.

**나중에 CI와 EC2에서 확인된 것** (8/3~8/4)

| 항목 | 결과 |
| --- | --- |
| 백엔드 컴파일·테스트 | Jenkins CI **796 tests 통과, 실패·스킵 0** — 위에서 못 한 검증이 여기서 끝났다 |
| `collect-host-metrics.sh` | 실제 host에서 동작. 디스크 289GB/331GB, container CPU·메모리 백분율 파싱 정상 |
| Prometheus alert rule | 기동 로그에 `rules=31ms` 오류 없음 — `promtool`을 못 돌린 채 남겨둔 위험이 해소됐다 |
| Alloy 설정 | `textfile`·`loki.source.journal`·`stage.structured_metadata`·`redact` 전부 로드 |
| Mattermost webhook | 실제 전송 확인 |
| auditd 규칙 | `auditctl -l`에 10개 watch 등록 |
| `verify-day10-runtime.sh` | **전 항목 PASS** (수집 대상 7, alert rule 37개, Loki, host metric, backup metric, Grafana) |
| requestId 추적 | Nginx access log 줄이 structured metadata로 검색됨 |
| 민감값 차단 | `?password=` 는 `[REDACTED]`, 경로의 `csrf-token`은 원형 유지 |
| 알람 발화·전달·해소 | `BackupFailed`·`HostDiskCritical`·`HostDiskLow` 왕복 확인 (통지 6건, 실패 0) |

**남은 검증은 EC2에서** — runbook 3·5·6절이 순서대로 그 절차다.

---

## 6. 미완 항목

### 내 몫

| 항목 | 상태 |
| --- | --- |
| ~~백엔드 테스트 그린~~ | **완료** — Jenkins CI 796 tests |
| ~~host 설치·timer·auditd~~ | **완료** — `install-day10-host.sh`, timer 3개 활성 |
| ~~monitoring stack 기동~~ | **완료** — 컨테이너 8개 Up |
| ~~`verify-day10-runtime.sh` 통과~~ | **완료** — 전 항목 PASS |
| ~~알람 실제 수신 (디스크·Backup)~~ | **완료** — 발화·전달·해소 왕복 확인 |
| ~~Grafana 대시보드 4장 눈으로 확인~~ | **완료** — 4장 모두 값이 들어오는 것을 확인 |
| ~~알람 시험 5.1 Backend Down~~ | **완료(2회차)** — 발화·전달·해소 확인. 1회차는 전달 실패했다 (§7.16) |
| ~~p95 값 확인~~ | **완료** — `a157ec7e` 배포부터 값이 들어온다 (§7.17) |
| ~~`restore-drill.sh` 실제 실행~~ | **완료** — DB·이미지 byte 단위 일치 (§7.19) |
| 복원된 결함 이미지 화면 확인 | 사람이 봐야 끝난다 |

### 팀 결정이 필요한 것

| 항목 | 내용 |
| --- | --- |
| gpg 키 보관자 | 8/5에 지우 키로 다시 등록했다(§7.18). **A 공개키를 받아 수신자를 둘로 만드는 것이 남았다** |
| ~~Mattermost 알람 채널~~ | **해소(8/4)** — `d101_alertmanager`로 확정. §7.3 |
| volume backup 시각 | 04:10에 Frontend·Backend·Mosquitto를 잠시 멈춘다. 시연 주간에도 그대로 둘지 |
| 커널 업그레이드 재부팅 | `6.17.0-1019-aws` 실행 중, `7.0.0-1009-aws` 대기. 시연 전 여유 있는 시점에 잡아야 한다 |

### A(김채운)와 겹치는 지점

`compose.monitoring.yaml`·`infra/` 대부분이 A의 Day 8 산출물이다. Day 8 게이트를 깨지 않는
것을 제약으로 두고 작업했다. 아래는 **A에게 반드시 알려야 하는 것들**이다.

**A의 파일을 직접 고친 것 3개**

- `scripts/verify-day8-infrastructure.sh` — `docker compose config` 호출에
  `ALERTMANAGER_WEBHOOK_SECRET_FILE=/dev/null` 추가. **고치지 않으면 Day 8 게이트가 깨진다.**
  compose에 새로 넣은 필수(`:?`) 변수를 이 호출이 모르기 때문이다. 같은 실수가 다시 나지
  않도록 `verify-day10-observability.sh`가 두 파일을 대조하는 검사를 추가했다
  (일부러 지워서 실제로 잡히는 것까지 확인).
- `compose.prod.yaml` — logging `labels` 옵션 추가. Loki가 Docker socket 없이 container를
  구분하는 유일한 경로다.
- `compose.monitoring.yaml` — 발행 경로 전용 `loopback-publish` bridge 추가.
  monitoring 격리 network는 `internal: true`로 유지하면서 Docker 28+에서도
  `127.0.0.1` port publication이 생성되게 한다.

**운영 절차가 바뀐 것 2개**

- `monitoring.env`에 다섯 줄(`ALERTMANAGER_PORT`, `ALERTMANAGER_WEBHOOK_SECRET_FILE`,
  `APP_NETWORK_NAME`, `MONITORING_TEXTFILE_DIR`, `PROBE_DOMAIN`)을 넣기 전에는
  **`verify-day8-auxiliary-runtime.sh`가 실패한다.**
- Prometheus가 `gilbom_app` network에 external로 참여해 **운영 stack이 먼저 떠 있어야**
  monitoring stack이 뜬다. 운영 stack `down` 시 network 삭제만 실패하고 나머지는 정상이다.

**A가 확인하고 반영한 것 2개 (8/4 추가)**

- **CI source archive 승격 누락을 해소했다.** Jenkins release에 exact commit의
  `gilbom-web-<sha>.tar.zst`를 추가하고 전체 manifest 서명·SFTP·root 승격·배포
  preflight를 같은 열세 파일 계약으로 묶었다. 성공한 운영 배포는 host 상태 파일을
  보존한 채 `/opt/gilbom/deploy` source tree와 `.release-source.sha`도 갱신한다 (§7.1).
- **Docker 28+에서 internal network 단독 구성은 port를 열지 못한다.** Day 8이 설계한
  `monitoring` 단독 구성이 그대로 걸렸다(§7.2). `compose.jenkins.yaml`은
  `internal: true`가 없는 일반 `ci` bridge라 같은 조건에 해당하지 않는 것도 확인했다.

**문서 정책 갱신 1개**

- `environment-configuration-strategy.md`·`llm-client-policy.md`의 actuator 노출을
  `health,info` → `health,info,prometheus`로 바꿨다.

---

## 7. 운영 반영에서 드러난 것 (2026-08-03 ~ 08-05)

코드는 8/3에 끝났고 CI·CD도 성공했다. 그런데 **EC2에 올려 실제로 돌리는 과정에서 스무 가지가
걸렸다.** 전부 "저장소에서는 알 수 없고 실제 host에서만 드러나는" 종류였고, 그중 여섯은
**정상으로 보이면서 아무 일도 하지 않고 있던** 것이었다 — 로그 수집, 알람 전달, 거짓 알람,
사라진 시계열, container가 보고 있던 옛 디렉터리, 그리고 빠진 `db` health 지표.

돌려보지 않았으면 시연 때까지 몰랐을 것들이다. 정적 검사로는 어느 것도 드러나지 않았다.

### 7.1 CI·CD source archive 누락과 해소

`install-day10-host.sh`를 실행했더니 `No such file`이 났다. 배포는 분명히 성공한 뒤였다.

당시 `gilbom-deploy.sh`는 서명된 `compose.prod.yaml`과 `image.env`, container만
바꾸고 나머지 source tree는 갱신하지 않았다. Day 8 runbook 3절에 수동 source
archive 절차가 있었지만 Day 10 runbook은 그것을 전제로만 두고 적지 않았다.

**더 큰 문제는 그 archive가 host에 없다는 것이었다.** Jenkins가 쓰는
`promote-ci-release-artifacts.sh`의 허용 목록에는 `images.tar.zst` 계열과 metadata·sbom만
있다. 수동 승격 경로(`promote-release-artifacts.sh` → `/opt/gilbom/artifacts/releases/<sha>`)와
Jenkins 경로(`/opt/gilbom/artifacts/<sha>`)는 목적지도 내용도 다르다.

첫 반영은 배포된 SHA로 `git archive <sha>:web`을 만들어 SFTP로 옮겨 복구했다.
이후 정공법을 구현했다. Jenkins가 checked-out HEAD와 같은 SHA에서 archive를 만들고,
tracked `runtime.env`·`image.env`·Secret payload를 거부한다. archive checksum은
`build-metadata.json`과 Cosign 서명된 `SHA256SUMS` 양쪽에 결속된다.

운영에서는 root 배포기가 archive를 `/opt/gilbom` 아래 sibling directory에 먼저
전개해 필수 파일·symlink·mode·Secret·독립 서명된 Compose 일치를 검사한다. 모든
runtime health가 성공한 뒤에만 source tree를 교체하며, `runtime.env`·`image.env`는
host 상태로 보존한다. 교체 이후 Manifest나 `current.sha` 기록이 실패하면 이전
source tree를 먼저 복원하고 기존 container rollback으로 이어진다. 따라서 새
release부터 수동 SFTP 우회는 사용하지 않는다.

### 7.2 Docker 28+는 internal network 단독 container의 port를 열지 않는다

`verify-day10-runtime.sh`가 16건 실패했다. Alertmanager만 PASS였다.

처음에는 Prometheus가 alert rule을 읽다 죽은 줄 알았다. 로그를 보니 전부 정상 기동해
있었고 rule도 오류 없이 로드돼 있었다. `docker inspect`에는 `PortBindings`가 그대로
있는데 `ss -ltnp`에는 `9093`만 보였다.

갈린 기준이 network였다.

| 서비스 | network | port |
| --- | --- | --- |
| alertmanager | monitoring(internal) + alert-egress(일반) | 열림 |
| prometheus | monitoring(internal) + app(internal) | 닫힘 |
| loki·grafana·alloy | monitoring(internal) 단독 | 닫힘 |

**Docker 28부터 internal network만 가진 container는 port 발행이 적용되지 않는다.**
설정에는 bind가 남아 있어서 원인을 찾기 어렵다. Day 8은 이 stack을 `internal: true`
하나에만 두었고 당시 Docker에서는 동작했다. 이 EC2가 Docker 29로 올라오면서 걸렸다.

`monitoring`을 일반 network로 바꾸면 Day 8의 격리가 무너지고 게이트 단언도 깨진다.
그래서 **발행 경로 전용 bridge network(`loopback-publish`)를 하나 두고** 발행하는 넷을
붙였다. bind 주소는 그대로 `127.0.0.1`이라 외부 노출은 늘지 않는다.

같은 실수가 재발하지 않도록 `verify-day10-observability.sh`에 "port를 발행하는 service는
비-internal network를 가진다"를 검사로 넣었다. 일부러 되돌려 실제로 잡히는 것까지 확인했다.

### 7.3 Mattermost 채널 이름을 가정으로 두면 알람이 조용히 사라진다

`alertmanager.yml`에 채널을 `#gilbom-alerts`로 적어뒀는데 그런 채널이 없었다. Mattermost
incoming webhook은 payload의 채널이 실제와 다르면 **전송을 거부한다.** Alertmanager는
정상으로 보이고 알람만 사라지는 형태라 알아채기 어렵다.

`curl`로 직접 쏴서 확인한 `d101_alertmanager`로 바꿨다. Mattermost는 채널 이름을 앞의
`#` 없이 받는다.

### 7.4 timer는 첫 주기가 와야 돌고, 그전 검사는 정상인데도 실패한다

`gilbom-host-metrics.timer`는 2분마다, backup timer는 하루 한 번이다. 설치 직후 바로
`verify-day10-runtime.sh`를 돌리면 metric이 아직 없어 실패한다. **정상 상태와 실패가
구분되지 않는 것이 문제**라, runbook에 "검사 전에 `systemctl start`로 한 번 만들어 둔다"를
명시했다.

auditd도 기본 이미지에 없어 설치가 필요했다. 설치 스크립트는 없으면 건너뛰고 안내만
출력하도록 만들어 뒀던 것이 그대로 동작했다.

### 7.5 로그가 한 줄도 수집되고 있지 않았다

port를 연 뒤 `verify-day10-runtime.sh`가 "Loki에 Backend 로그가 없다"로 실패했다. 라벨
목록을 24시간 범위로 물어도 비어 있었다. **Day 8 이후 줄곧 그 상태였다.**

alloy-logs가 `/var/lib/docker/containers`를 읽지 못했다. 비-root 사용자에게 `cap_add`로
준 `DAC_READ_SEARCH`는 **effective set에 들어가지 않는다.** Docker가 비-root에 ambient
capability를 부여하지 않기 때문이다. 그 디렉터리는 `0710 root:root`라 보조 그룹을 붙여도
목록 조회가 되지 않는다. 실제로 확인해 보니 log 파일이 `root` 소유여서 **UID 0이면
capability 없이도 읽힌다.** 즉 필요한 것은 capability가 아니라 UID였다.

`user: "0:0"`으로 바꾸되 `cap_drop: ALL`·`no-new-privileges`·`read_only` rootfs와
읽기 전용 mount 두 개는 그대로 두어 권한면을 "그 두 경로를 읽는 것"으로 한정했다.

### 7.6 민감값 치환이 로그를 망가뜨리고 있었다

로그가 흐르기 시작하자마자 Nginx access log가 이렇게 찍혔다.

```
"GET /api/v1/auth/${1}[REDACTED]${1}[REDACTED]" 200 147
```

원래는 `/api/v1/auth/csrf-token`이다. 두 가지가 겹쳤다.

- `stage.replace`는 **캡처 그룹만 치환**한다. `${1}` 템플릿 문법을 쓰면 그 문자열이
  그대로 로그에 박힌다
- 구분자 조건 `["'=: ]+`가 느슨해 URL 경로의 `csrf-token`까지 비밀값으로 오인했다

캡처 그룹을 값 하나로 줄이고 구분자로 `=` 또는 `:`를 요구하도록 고쳤다. 운영에서
`?password=secret` 은 지워지고 경로의 `csrf-token`은 원형으로 남는 것을 확인했다.

### 7.7 알람은 한 번도 전달된 적이 없었다

시험 알람을 띄웠는데 Mattermost에 아무것도 오지 않았다. Prometheus에서 firing이 보이고
Alertmanager도 ready였다. 로그에 답이 있었다.

```
open /run/secrets/alertmanager_mattermost_webhook: permission denied
```

secret 파일이 `0400 root`인데 Alertmanager container는 `nobody`(65534)로 돈다. Grafana
비밀번호 파일이 되는 것은 `0444`(누구나 읽기)로 만들어져 있기 때문이었다. 그 차이를
놓쳤다.

**0444로 여는 대신 소유자를 container 사용자로 바꾸고 0400을 유지**했다. 0444는 host의
모든 사용자가 webhook URL을 읽을 수 있게 만든다.

이 사건의 핵심은 **관측 시스템이 정상으로 보이면서 아무것도 알려주지 않았다**는 것이다.
`verify-day10-runtime.sh`의 receiver 검사도 설정에 receiver가 있는지만 봐서 PASS였다.
Alertmanager가 이미 `alertmanager_notifications_failed_total`로 실패를 세고 있었으므로
그 값을 알람(`AlertDeliveryFailing`)과 검사 양쪽에 넣었다.

> 이 알람 자체도 같은 경로로 나가므로 채널에는 못 올 수 있다. 그때는 Prometheus 알람
> 화면과 운영 대시보드가 유일한 신호다. 완전한 해결은 아니지만, 조용한 실패보다는 낫다.

### 7.8 영구히 발화하는 거짓 알람이 있었다

시험 중에 `ContainerNotRunning`이 두 건 떠 있는 것을 발견했다. `alloy-data-init`과
`mosquitto-config-init` — **실행 후 종료가 정상인 일회성 container**다.
`docker ps --all`이 이들을 포함하면서 장애로 집계됐다.

거짓 알람은 없느니만 못하다. 팀이 알람 자체를 무시하게 되고 그러면 진짜 장애도 함께
묻힌다. `restart` 정책이 `no`인 container를 수집에서 제외했다 — "계속 떠 있어야 하는
대상이 아니다"라는 뜻이 정책에 이미 들어 있다.

### 7.9 textfile은 HELP·TYPE이 어긋나면 조용히 시계열을 삼킨다

시험용 metric 파일을 넣으면서 `# HELP`·`# TYPE`을 "안전하게" 생략했더니, **실제 디스크·
backup 시계열이 통째로 사라졌다.** 두 파일이 같은 metric 이름을 선언하는데 메타데이터가
다르면 collector가 한쪽을 버린다.

가장 나쁜 점은 **`node_textfile_scrape_error`가 0으로 남는다**는 것이다. 오류 표시 없이
값만 없어진다. 문구를 실제 파일과 똑같이 맞추자 공존했다.

`backup-common.sh`가 두 backup 종류의 metric을 같은 함수로 찍는 것이 이 때문이다.
새 textfile을 손으로 추가할 때는 기존 파일의 HELP·TYPE을 그대로 복사해야 한다.

### 7.10 해소 메시지가 발생 메시지와 구분되지 않았다

`[RESOLVED]`가 하나만 온 것처럼 보였다. 실제로는 세 건 모두 전달됐고(slack 통지 6건,
실패 0건), critical 수신자의 제목을 `[CRITICAL]`로 **고정**해 둬서 해소 메시지가 발생
메시지와 글자까지 같았을 뿐이다. 색만 초록으로 바뀌는데 채널에서는 알아채기 어렵다.

두 수신자 모두 `[상태 · 심각도]` 형식으로 맞췄다. 심각도를 색에만 의존하지 않게 되는
부수 효과도 있다.

### 7.11 archive를 다시 풀면 container가 옛 디렉터리를 계속 본다

대시보드를 고쳐 배포한 뒤 Prometheus의 alert rule이 **0개**가 됐다. 그런데
`promtool check rules`는 36개를 정상으로 봤고, host에서 `ls`해도 파일이 다 있었다.
로그에도 오류가 없었다. container 안에서만 디렉터리가 비어 있었다.

그사이 누군가 `/opt/gilbom/deploy`에 소스 archive를 다시 풀었다. `tar`가 디렉터리를
**새 inode로 교체**하는데, container의 bind mount는 기동 시점 inode에 고정돼 있다.
그래서 떨어져 나간 옛 디렉터리를 계속 보게 된다.

파일 하나를 `install`로 바꿀 때도 같은 일이 일어난다 — 그래서 설정을 고칠 때마다
container를 재생성해 온 것이 결과적으로 맞았다. **archive를 다시 풀면 monitoring
stack 전체를 재생성해야 한다.** 확인은 반드시 container 안에서 한다.

**A가 배포 자동화를 고친 뒤 이 함정은 오히려 더 중요해졌다.** 이제 배포기가 매 배포마다
`/opt/gilbom/deploy`를 통째로 교체하므로, 배포할 때마다 이 일이 일어난다. runbook 1절에
"배포 뒤에는 monitoring stack을 재생성한다"를 상시 절차로 넣었다.

### 7.12 instance 라벨이 바뀌면 대시보드와 알람이 함께 흔들린다

"마지막 배포" 타일에 값이 둘로 나오고 이름이 `{instance="12909c9b468b", job=...}`처럼
찍혔다. Alloy container를 재생성할 때마다 `instance` 라벨(container ID)이 바뀌어
시계열이 갈라진 것이다.

표시만의 문제가 아니었다. **`scalar()`는 시계열이 둘 이상이면 `NaN`을 반환한다.**
`RobotImagesStorageFillingUp`과 `DockerStorageFillingUp`이 그 형태라, Alloy를 재생성한
뒤 옛 시계열이 만료되기까지 5분쯤 **조용히 평가되지 않는** 구간이 생긴다.

stat 패널 12개의 질의를 집계(`max`, `avg`, `max by (...)`)로 감싸 instance 라벨을
없애고 `legendFormat`을 붙였다. 알람 두 개의 `scalar()`도 `max()`로 감쌌다.

### 7.13 응답 시간 p95는 켜야 나온다

Backend 대시보드의 p95 패널이 `No data`였다. Spring은 기본적으로 요청 시간의
`count`·`sum`·`max`만 내보내고 **백분위 계산에 필요한 데이터를 내지 않는다.**

`percentiles-histogram`(bucket)과 `percentiles`(직접 계산) 중 후자를 골랐다. bucket은
endpoint 수만큼 시계열을 곱해 늘리는데, 인스턴스가 하나뿐이라 집계할 일이 없어 그
비용을 치를 이유가 없다. 인스턴스가 늘어나면 bucket으로 바꾸면 된다.

패널은 p95와 최대값을 함께 그리도록 바꿨다. p95는 Backend 재배포 후부터 값이 들어오고,
그전까지는 최대값이 빈 화면을 막는다.

**그런데 최대값을 켜자 그래프가 30분 단위로 치솟았다.** SSE 스트림
(`/api/v1/stream/*`)이 원인이었다. EventSource는 연결을 오래 유지하는 것이 정상인데
서버 입장에서는 "30분 걸린 요청"으로 기록된다. 느린 것이 아니라 원래 그런 것이다.

스트림을 빼자 이번에는 `/error`가 27초로 남았다. 확인해 보니
`status=200 outcome=SUCCESS exception=IOException` — **SSE 클라이언트가 연결을 끊을 때**
서버가 쓰기 중 IOException을 받고 error dispatch로 넘어간 것이다. 이것도 장애가 아니다.

결국 패널 범위를 `/api/` 요청으로 한정했다. actuator와 `/error`가 자연스럽게 빠지고
실제 API가 보인다(전부 6ms 이하). **한 종류의 요청이 다른 종류를 가리면 그 패널은
아무것도 알려주지 않는다** — 눈금이 30분이면 6ms는 0과 구분되지 않는다.

### 7.14 `topk`는 시계열 질의에서 상위 N을 고정하지 못한다

범위를 좁혔는데도 범례가 5개를 훨씬 넘어 스크롤바가 생겼다. **`topk(5, ...)`는 시점마다
상위 5개를 따로 고른다.** 시간대에 따라 다른 endpoint가 뽑히고 Grafana는 그 합집합을
전부 그린다. 6시간 범위에서 36개 시계열이 나왔다.

`max_over_time(...[1h])`으로 기준 창을 줘 봐도 23개였다. 평가 시점마다 창이 함께 밀려서
집합이 계속 바뀐다. **PromQL만으로 시계열 질의의 상위 N을 고정하는 깔끔한 방법은 없다.**

대시보드의 목적은 "느려졌는가"를 한눈에 보는 것이다. 전체 최대와 가장 느린 endpoint의
p95, 두 줄로 줄이니 눈금과 범례가 언제나 읽힌다. 어느 endpoint인지는 그때 파고들면 되고,
그 질의를 패널 설명에 적어 뒀다.

### 7.15 느려 보이는 것이 정상인 경우 — 인증 경로

정리된 그래프에서 `/api/v1/auth/mfa/totp/confirm`이 1.2초, `/api/v1/auth/login`이 338ms로
튀었다. 나머지 조회 API는 최고값이 266~288ms이고 평소 0ms 부근이다.

**둘 다 Argon2id 비밀번호 해싱 경로다.** Argon2id는 대입 공격을 비싸게 만들려고 의도적으로
느리게 설계됐다. 수백 ms가 걸리는 것이 정상이고, 이 프로젝트는 `passwordHashLoadTest`로
그 비용을 따로 측정하고 있기도 하다.

그래프의 뾰족한 부분은 장애 신호가 아니라 **누군가 로그인한 흔적**이다. 관측을 붙이면
이렇게 "정상인데 이상해 보이는 것"이 먼저 눈에 띈다. 임계값을 정하기 전에 무엇이
정상인지부터 알아야 하는 이유다.

### 7.16 Backend Down 시험 — 첫 회차는 발화만 되고 전달은 실패했다

backend를 멈춰 `BackendDown`을 발화시켰다. **Prometheus에서는 떴는데 채널에는 오지
않았다.** webhook secret이 `nobody:nogroup`에서 `root:root`로 되돌아가 있었고, 그래서
Alertmanager가 읽지 못했다. `/opt/gilbom/deploy`가 `700 root:root`로 바뀐 것과 같은
보안 정리에 함께 쓸린 것으로 보인다.

§7.7에서 같은 증상을 이미 겪었는데 그때 고친 방식이 **host 파일 소유자에 기대는
것**이었다. 정리가 한 번 지나갈 때마다 무너지는 방식이었다. Compose로 container 안의
소유자를 지정해 보려 했으나 v5는 파일 secret의 `uid`·`gid`·`mode`를 무시하고 host
파일을 그대로 bind mount한다. `0444`로 여는 것은 host의 모든 사용자에게 webhook URL을
주는 것이라 더 나쁘다. 결국 **alloy-logs와 같은 방식**으로 UID 0으로 돌리되 capability를
전부 빼고 rootfs는 read-only로 뒀다(§7.5와 같은 판단이다).

**같은 실수를 두 번 했다.** 처음에는 권한을 몰라서, 두 번째는 고친 방식이 외부 변경에
견디지 못해서다. "동작하게 만들었다"와 "계속 동작한다"는 다르다.

시험 자체보다 그 과정에서 나온 것이 더 많았다.

**억제 규칙은 이번 경우에 필요가 없었다.** `StorageReadinessDown`·`ReadinessComponentDown`은
애초에 뜨지 않았다. backend가 죽으면 `gilbom_health_component_status` 시계열이 아예
사라져서 조건을 평가할 대상이 없기 때문이다. 억제로 막으려던 것이 데이터 부재로 이미
막혀 있었다. 규칙은 그대로 두되, **한 번도 발동한 적 없는 규칙을 "동작한다"고 적지 않는다.**

**대신 `ContainerNotRunning`이 함께 왔다.** 억제 대상 목록에 없었기 때문인데, 목록에
넣으면 무관한 container의 알람까지 함께 묻힌다(`equal`이 `environment`뿐이라 이름으로
구분하지 못한다). 지금은 그대로 두고 기록만 남긴다.

**진짜 발견은 두 가지였다.**

- `ContainerNotRunning`의 대상이 또 init container 두 개였다. §7.8에서 고친 것이
  **되돌아가 있었다.** archive를 다시 푼 뒤 누군가 `install-day10-host.sh`를 재실행하면서
  `/usr/local/sbin/gilbom-host-metrics`가 archive에 든 옛 버전으로 덮인 것이다. 내 수정이
  아직 CI 아티팩트에 없으니 당연한 결과다. **손으로 옮긴 수정은 다음 배포에 사라진다.**
- `ContainerMemoryNearLimit`이 Grafana를 가리키고 있었다. 242MiB/256MiB, **94%**였다.
  시험이 아니라 실제 경고다. Grafana 13은 대시보드 provisioning과 검색 index만으로도
  240MiB를 쓰는데, Day 8의 256m은 datasource만 있던 시절 기준이었다. 512m으로 올리자
  29%가 됐다.

**내 스크립트 문제도 하나 있었다.** 발화 감지용 grep이 JSON 필드 순서에 의존해 맞지
않았고, 폴링이 5분 타임아웃까지 돌고서야 trap이 backend를 되살렸다. **계획한 3분이 아니라
6분을 멈췄다.** 상태 확인은 문자열이 아니라 `jq`로 필드를 짚어야 한다.

**두 번째 회차 — 통과.** secret 권한을 고치고 폴링을 `jq`로 바꿔 다시 했다.

```
정지        12:56:25
발화·수신   12:59:05   (2분 40초)
재기동      12:59:48
복구 확인   13:00:12   (총 중단 3분 23초)

통지 누계 2 → 3,  실패 0
firing  BackendDown 하나뿐
```

Mattermost 수신까지 확인했다. `ContainerNotRunning`은 이번엔 `pending`에 머물러 함께
오지 않았다 — 첫 회차보다 조용했다. 시험을 다시 하기로 한 판단이 맞았다. **"발화했다"와
"전달됐다"는 다른 사실이고, 전자만 보고 통과로 적었으면 시연 중 장애가 나도 아무도
모르는 상태로 남았을 것이다.**

시험을 다시 하는 데도 대가가 있다. 3분간 실패한 요청은 되돌아오지 않고, 대시보드
그래프에는 끊긴 구간이 보관 기간 내내 남는다. 이번에는 요청률이 0.11 req/s이고 이미지
업로드가 0건이라 유실될 데이터가 사실상 없는 시점을 골랐다.

### 7.17 배포 자동화가 붙은 뒤 첫 배포에서 나온 것

A가 CD를 자동화한 뒤 처음으로 전체 흐름이 돌았다. 푸시 → CI(약 28분) → 자동 배포까지
사람 손이 닿지 않았다.

**배포 중 서비스가 멈추는 것은 정상이다.** 확인하던 중 backend가 `unhealthy`이고 공개
HTTPS가 응답하지 않아 장애로 오해했다. 실제로는 배포기가 **배포 직전 volume backup**을
뜨느라 frontend·backend·mosquitto를 멈춘 구간이었다. 내가 만든 `backup-volumes.sh`가
하는 일이다. 배포마다 짧은 무중단 아닌 구간이 생긴다는 뜻이고, 이는 설계된 동작이다.

**inode 분리가 예상대로 일어났다(§7.11).** 배포 직후 Prometheus·Grafana container가
마운트된 디렉터리를 빈 상태로 보고 있었다. alert rule은 메모리에 남아 36개로 보였지만
재시작하면 0이 될 상태였다. stack을 재생성하자 파일이 보이고 rule이 37개가 됐다 —
배포된 소스에 `AlertDeliveryFailing`이 들어 있다는 증거이기도 하다.

**alloy-logs를 UID 0으로 바꾼 것(§7.5)의 부작용이 여기서 드러났다.** 위치 파일을 쓰지
못하고 있었다.

```
error writing positions file ... permission denied
```

`alloy-data-init`이 데이터 볼륨을 `473:473 / 0700`으로 만드는데, `cap_drop: ALL`이라
UID 0이어도 `DAC_OVERRIDE`가 없어 남의 소유 디렉터리에 쓰지 못한다. 읽기는
`DAC_READ_SEARCH`로 되니 **수집은 되는데 위치만 기록되지 않아, 재시작마다 이전 로그를
잃는** 상태였다. log 볼륨만 `0:0`으로 분리해 고쳤다.

**권한을 바꿀 때는 읽기와 쓰기를 따로 봐야 한다.** 읽기가 되는 것을 확인하고 넘어갔는데
쓰기는 다른 capability였다.

**로그가 안 보인다고 파이프라인이 죽은 것은 아니다.** Backend 로그만 Loki에 없어서
한참 뒤졌는데, 원인은 **Backend가 조용했던 것**이었다. 이 애플리케이션은 운영에서
로그를 강하게 억제한다(`%nopex`, SQL OFF, 정상 요청 무로그). 실패한 로그인을 시도해도
한 줄도 남기지 않는다. frontend·mosquitto·grafana 로그는 그 시각에도 정상 수집되고
있었다. backend를 한 번 재시작(18초)하자 기동 로그가 들어왔고 requestId 검색도 확인됐다.

**p95는 이 배포부터 값이 들어온다.** `/api/v1/auth/csrf-token` 37ms. 배포 후
`verify-day10-runtime.sh` **전 항목 PASS**.

### 7.18 등록돼 있던 백업 키의 개인키가 아무 데도 없었다

이중 보관을 준비하며 **현재 등록된 공개키의 개인키를 누가 갖고 있는지** 확인했다.
아무도 없었다.

```
1BB46DDE25B21C2359712CAB44E07612A7B24611
uid  Gilbom Production Backup <backup@i15d101.invalid>
```

내 keyring에 없고, EC2 root keyring에도 없고, A도 갖고 있지 않았다. 이름의 `.invalid`
도메인으로 보아 스크립트의 "공개키가 없으면 backup을 시작하지 않는다" 요구를 통과시키려고
임시로 만든 키였고 개인키는 남기지 않은 것으로 보인다.

**그 결과 8/3부터 쌓인 backup 전부가 아무도 열 수 없는 상태였다.** 암호화도, checksum도,
manifest도, off-host 반출 기록도 전부 정상이었다. 검사도 `BackupFailed` 없이 통과했다.
**"backup이 있다"는 사실만으로는 아무것도 보장되지 않는다** — 열 수 있는지는 별개이고,
그것을 확인하는 유일한 방법은 실제로 열어 보는 것이다.

내가 §8에 "개인키를 잃으면 모든 backup을 열 수 없다"고 적어둔 위험이 **이미 현실이 돼
있었다.** 위험으로 적어두는 것과 그것이 지금 일어나고 있는지 확인하는 것은 다르다.

개인키를 실제로 보유한 키(`76EC8CCC…`)로 다시 등록하고 backup을 새로 떴다. 새 번들의
수신자 keyid(`9F086173131BE15D`)가 내 암호화 부키와 일치하는 것을 확인했다.

**8/5 저녁 이중 보관을 완성했다.** A의 공개키를 받아 수신자를 둘로 만들고, 두 사람 모두
개인키 보유를 증명했다. 지우는 새 backup의 수신자 keyid가 자기 암호화 부키와 일치하는
것으로, A는 시험 문자열을 복호화해 돌려주는 것으로 증명했다.

| 보관자 | 지문 | 증명 방법 |
| --- | --- | --- |
| 지우 | `76EC8CCC…FB6A0FEC` | backup 수신자 keyid 대조 |
| A | `C14D08DA…5CBC07B3` | 시험 문자열 복호화 |

증명 절차에서도 한 번 막혔다. 내가 안내한 `gpg --decrypt <<'EOF'` 형태는 표준입력이
암호문으로 차서 암호를 물어볼 통로가 없다. `Inappropriate ioctl for device`로 실패한다.
**암호를 거는 것은 내가 권한 사항이므로 그 안내는 사실상 항상 실패한다.** 암호문을
파일로 저장한 뒤 `GPG_TTY`를 설정해 복호화하도록 고쳤다.

**남은 조치**
- volume backup도 새 키로 다시 떠야 한다(서비스 정지가 필요해 유지보수 시간에)
- 기존 번들은 열 수 없으므로 정리 대상이다. `/home/ubuntu`에 반출 임시 사본도 남아 있다
- 복원 시험은 새 번들로 해야 의미가 있다

**절차에서 빠져 있던 것.** 키를 등록할 때 "이 공개키에 대응하는 개인키를 누가 갖고
있는가"를 확인하는 단계가 없었다. 등록 스크립트가 지문을 출력하고 "보관자와 대조하라"고
안내하지만, 대조했는지는 검사하지 않는다. 사람이 건너뛸 수 있는 절차는 언젠가 건너뛰어진다.

### 7.19 복원 시험 — 통과, 그리고 keyring에서 같은 문제가 또 나왔다

두 사람 키로 잠긴 첫 온전한 번들로 복원 시험을 돌렸다. **핵심 항목이 전부 통과했다.**

```
DB table content SHA-256      41개 table 완전 일치
DB sequence state SHA-256     1개 완전 일치
robot image DB 참조 ↔ 파일     9건 모두 일치
스키마 9종 · Flyway V42 · 실패 이력 없음
계정 7 · 역할 10 · 로봇 2 · 임무 15 · 결함 8 · 감사 로그 386
```

**backup에서 되돌린 DB가 원본과 byte 단위로 같다.** "backup이 있다"에서 "backup으로
되돌릴 수 있다"로 넘어온 순간이다. Day 10을 시작할 때 이것이 한 번도 검증된 적 없다고
적었는데, 이제 검증됐다.

**실패 1건 — TOTP keyring.** 기본 경로가 서버(`/opt/gilbom/secrets/...`)라 로컬에서는
읽을 수 없었다. 반출 사본이 있어 확인했더니 **그것도 고아 키로 잠겨 있었다**(§7.18).
수신자 keyid가 `3DF1535E9F1E7849`로 지우도 A도 아니었다. **keyring 반출본도 새 키로
다시 만들어야 한다.**

**그리고 keyring 자체가 `0444`였다.** MFA 비밀을 지키는 키인데 host의 모든 사용자가
읽을 수 있다. 원인은 webhook 때와 똑같다 — Backend container가 `spring`(1883)으로
돌아 root 소유 `0400`을 읽지 못하니 파일을 열어 우회한 것이다.

**같은 원인이 세 번째다.** webhook secret, alloy 위치 파일, TOTP keyring. Docker에서
"container가 host 파일을 읽어야 한다"를 만날 때마다 같은 함정에 빠졌고, 그때마다 다르게
우회했다(소유자 변경 → UID 0 → 0444). **셋 중 `0444`가 가장 나쁘다.** 이건 A의 보안
영역이라 사실만 남기고 판단은 넘긴다.

**시험 절차 자체도 세 번 막혔다.** `gpg --decrypt <<EOF` 형태, 복원 스크립트의 GPG_TTY,
그리고 내 실행 환경의 docker 그룹. 앞의 둘은 **암호를 걸라고 권해놓고 암호가 걸린
상태에서 절차가 도는지 확인하지 않은** 같은 실수였다.

### 7.20 `db` health 지표가 없었다 — DB가 죽어도 알람이 뜨지 않는 상태였다

팀원들이 쓸 수 있는 상태인지 확인하다가 나왔다. readiness 응답과 Prometheus 지표를
나란히 놓으니 어긋나 있었다.

```
/actuator/health/readiness   db: UP  ← 있다
gilbom_health_component_status  db 없음  ← 빠졌다
```

원인은 주입 타입이다. actuator는 `db`를 **반환형이 `HealthContributor`인 `@Bean`
메서드**로 선언한다. Spring은 아직 만들어지지 않은 bean의 타입을 그 선언 반환형으로
판단하므로, 내가 쓴 `Map<String, HealthIndicator>`에는 `db`가 들어오지 않는다.
`flywayReadiness`·`storageReadiness`·`mqttConnection`은 우리가 `HealthIndicator`로
선언해서 전부 잡혔고, **그래서 하나만 빠진 것을 눈치채기 어려웠다.**

**결과가 나쁘다.** `ReadinessComponentDown`은 `component=~"db|flywayReadiness"`를
보는데, `db` 시계열이 없으면 `== 0`을 만족하는 표본이 아예 없다. 규칙은 조용히 통과한다.
DB가 죽어도 `up{job="backend"}`는 1이므로 `BackendDown`도 뜨지 않는다. **DB 장애를
알려줄 알람이 하나도 없었다.**

`HealthContributor`로 주입 타입을 올리고, composite는 자식이 하나라도 DOWN이면 부모
이름으로 DOWN이 되게 접었다. 회귀 테스트는 Boot이 `db`를 선언하는 방식 그대로 —
반환형이 `HealthContributor`인 `@Bean` — 재현한다.

**교훈은 코드보다 규칙 쪽이다.** 여기서 진짜 문제는 오타가 아니라 **"감시 대상이
사라진 것을 감시하지 않았다"**는 것이다. `== 0` 형태의 알람은 시계열이 없으면 전부
같은 방식으로 무력화된다. 그래서 `HealthComponentMetricsMissing`을 넣었다 — Backend는
응답하는데 기대한 네 구성요소 gauge 수가 4 미만이면 발화한다. 살아 있는 Prometheus에
질의해보니 지금 **3**이 나온다. 새 규칙이 현재 상태를 정확히 집는다.

§7.18(개인키 없는 공개키)과 **같은 종류의 결함이다.** 검사도 알람도 전부 통과하는데
정작 필요한 순간에 동작하지 않는다. 통과 여부가 아니라 **무엇이 통과를 만들어내는지**를
봐야 잡힌다.

### 후속 커밋

| 커밋 | Jira | 내용 |
| --- | --- | --- |
| `6cf949a` | 738 `#comment` | `.gitignore`에 `*.asc` — gpg 공개키가 저장소에 들어가지 않게 |
| `4fcc81a` | 741 `#comment` | runbook에 deploy 디렉터리 갱신 전제와 첫 검사 전 준비 추가 |
| `51916a2` | 734 `#comment` | 알람 채널을 `d101_alertmanager`로 |
| `f98aaee` | 734 `#comment` | port 발행 경로용 비-internal network 추가 + 정적 검사 |
| `c26b31a` | 737 `#comment` | alloy-logs 권한(§7.5)과 민감값 치환 규칙(§7.6) |
| `1b62028` | 735 `#comment` | 일회성 init container를 감시 대상에서 제외 (§7.8) |
| `f5f00d7` | 734 `#comment` | 알람 전달 실패 감지 — 알람·검사·runbook (§7.7, §7.9) |
| `8743da0` | 734 `#comment` | 알람 제목에 상태 추가 (§7.10) |
| `479f06f` | 736 `#comment` | 현재 상태 타일을 시점 질의로 (알람 시험 잔상 제거) |
| `5b55bf4` | 733 `#comment` | 응답 시간 백분위 노출 (§7.13) |
| `62b278a` | 734 `#comment` | `scalar()`를 집계로 감싸 instance 분기에 견디게 (§7.12) |
| `affb60b` | 736 `#comment` | stat 패널 집계·legendFormat, p95·최대 패널 (§7.12, §7.13) |
| `aea5478` | 741 `#comment` | runbook에 inode 함정 추가 (§7.11~7.13) |
| `f54d8ef` | 736 `#comment` | 응답 시간 패널을 API 요청으로 한정 (§7.13) |
| `29285d0` | 736 `#comment` | 응답 시간 패널을 집계 두 줄로 (§7.14) |
| `fb043cd` | 741 `#comment` | 작업 기록에 대시보드 확인 결과 반영 (§7.14, §7.15) |
| `541caf4` | 734 `#comment` | Grafana 메모리 상한 512m (§7.16) |
| `06e839c` | 741 `#comment` | 작업 기록에 Backend Down 시험 결과 (§7.16) |
| `edcf156` | 734 `#comment` | Alertmanager를 UID 0으로 — host 소유자 변경 견딤 (§7.16) |
| `b3988db` | 741 `#comment` | Backend Down 1회차 전달 실패·2회차 통과 (§7.16) |
| `c3c4868` | 741 `#comment` | 새 배포 계약에 맞춰 runbook의 수동 우회 제거 (§7.1, §7.11) |
| `52ede9f` | 738 `#comment` | 증명 절차를 암호 걸린 키에서도 되게 (§7.18) |
| `7b21e11` | 738 `#comment` | 이중 보관 완성 기록 (§7.18) |
| `d0af5bc` | 740 `#comment` | 복원 시험 GPG_TTY 처리 (§7.19) |

---

## 8. 남는 위험

- **개인키를 잃으면 모든 backup을 열 수 없다.**
- **TOTP keyring을 잃으면 MFA 암호문은 복구 불가다.** DB를 되살려도 사용자는 다시 등록해야 한다.
- **`gilbom-host-metrics.timer`가 멈추면 디스크 알람이 조용히 사라진다.**
- **Loki 민감값 차단은 목록 기반이다.** 새 형식이 생기면 규칙을 함께 늘려야 한다.
- **알람 전달 경로가 끊기면 그 사실을 알리는 알람도 함께 끊긴다.** `AlertDeliveryFailing`은
  Prometheus 화면과 운영 대시보드에만 남는다. 주기적으로 눈으로 보는 수밖에 없다.
- **textfile을 손으로 추가하면 실제 metric을 지울 수 있다.** HELP·TYPE이 어긋나면 오류
  없이 사라진다(§7.9). 급할 때 임시 파일을 넣는 습관이 특히 위험하다.
- HTTPS probe는 공개 DNS·보안그룹 경로를 확인하지 않는다(§4). 시연 전 외부 브라우저 확인으로 잡는다.
- Prometheus 보존 15일, Loki 14일. 그보다 오래된 사건은 남지 않는다.
