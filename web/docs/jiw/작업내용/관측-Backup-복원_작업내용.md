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

**남은 검증은 EC2에서** — runbook 3·5·6절이 순서대로 그 절차다.

---

## 6. 미완 항목

### 내 몫

| 항목 | 상태 |
| --- | --- |
| ~~백엔드 테스트 그린~~ | **완료** — Jenkins CI 796 tests |
| ~~host 설치·timer·auditd~~ | **완료** — `install-day10-host.sh`, timer 3개 활성 |
| ~~monitoring stack 기동~~ | **완료** — 컨테이너 8개 Up |
| `verify-day10-runtime.sh` 통과 | port 발행 문제(§7.2) 수정 적용 후 재실행 |
| Grafana 대시보드 4장 눈으로 확인 | SSH 터널(local 3000 → remote 3001) |
| 테스트 알람 실제 수신 | runbook 5절 4종 |
| `restore-drill.sh` 실제 실행 | 개인키가 있는 위치에서 |
| 복원된 결함 이미지 화면 확인 | 사람이 봐야 끝난다 |

### 팀 결정이 필요한 것

| 항목 | 내용 |
| --- | --- |
| gpg 키 보관자 | 공개키는 등록됐다. **개인키를 최소 두 사람이 각자 보관**하는 것은 아직 정해지지 않았다. 한 명이 빠지면 복원이 막힌다 |
| ~~Mattermost 알람 채널~~ | **해소(8/4)** — `d101_alertmanager`로 확정. §7.3 |
| volume backup 시각 | 04:10에 Frontend·Backend·Mosquitto를 잠시 멈춘다. 시연 주간에도 그대로 둘지 |
| 커널 업그레이드 재부팅 | `6.17.0-1019-aws` 실행 중, `7.0.0-1009-aws` 대기. 시연 전 여유 있는 시점에 잡아야 한다 |

### A(김채운)와 겹치는 지점

`compose.monitoring.yaml`·`infra/` 대부분이 A의 Day 8 산출물이다. Day 8 게이트를 깨지 않는
것을 제약으로 두고 작업했다. 아래는 **A에게 반드시 알려야 하는 것들**이다.

**A의 파일을 직접 고친 것 2개**

- `scripts/verify-day8-infrastructure.sh` — `docker compose config` 호출에
  `ALERTMANAGER_WEBHOOK_SECRET_FILE=/dev/null` 추가. **고치지 않으면 Day 8 게이트가 깨진다.**
  compose에 새로 넣은 필수(`:?`) 변수를 이 호출이 모르기 때문이다. 같은 실수가 다시 나지
  않도록 `verify-day10-observability.sh`가 두 파일을 대조하는 검사를 추가했다
  (일부러 지워서 실제로 잡히는 것까지 확인).
- `compose.prod.yaml` — logging `labels` 옵션 추가. Loki가 Docker socket 없이 container를
  구분하는 유일한 경로다.

**운영 절차가 바뀐 것 2개**

- `monitoring.env`에 다섯 줄(`ALERTMANAGER_PORT`, `ALERTMANAGER_WEBHOOK_SECRET_FILE`,
  `APP_NETWORK_NAME`, `MONITORING_TEXTFILE_DIR`, `PROBE_DOMAIN`)을 넣기 전에는
  **`verify-day8-auxiliary-runtime.sh`가 실패한다.**
- Prometheus가 `gilbom_app` network에 external로 참여해 **운영 stack이 먼저 떠 있어야**
  monitoring stack이 뜬다. 운영 stack `down` 시 network 삭제만 실패하고 나머지는 정상이다.

**A가 판단해야 하는 것 2개 (8/4 추가)**

- **CI 승격 목록에 소스 archive가 없다.** `build-release-artifacts.sh`는
  `gilbom-web-<sha>.tar.zst`를 만들지만 `promote-ci-release-artifacts.sh`가 허용하는 것은
  images 계열뿐이다. 그래서 **Jenkins로 배포해도 `/opt/gilbom/deploy`가 갱신되지 않는다.**
  이번에는 배포된 SHA로 같은 archive를 만들어 손으로 옮겼다. 우회다 (§7.1)
- **Docker 28+에서 internal network 단독 구성은 port를 열지 못한다.** Day 8이 설계한
  `monitoring` 단독 구성이 그대로 걸렸다(§7.2). `compose.jenkins.yaml`도 같은 구조라면
  같은 증상이 있을 수 있어 확인이 필요하다

**문서 정책 갱신 1개**

- `environment-configuration-strategy.md`·`llm-client-policy.md`의 actuator 노출을
  `health,info` → `health,info,prometheus`로 바꿨다.

---

## 7. 운영 반영에서 드러난 것 (2026-08-03 ~ 08-04)

코드는 8/3에 끝났고 CI·CD도 성공했다. 그런데 **EC2에 올리는 과정에서 네 가지가 걸렸다.**
전부 "저장소에서는 알 수 없고 실제 host에서만 드러나는" 종류였다.

### 7.1 CI·CD가 성공해도 `/opt/gilbom/deploy`는 갱신되지 않는다

`install-day10-host.sh`를 실행했더니 `No such file`이 났다. 배포는 분명히 성공한 뒤였다.

`gilbom-deploy.sh`는 그 디렉터리를 **읽기만** 하고, 배포가 실제로 바꾸는 것은 container
image와 `image.env` 한 줄뿐이다. 소스 트리는 release source archive를 사람이 풀어야
갱신된다. Day 8 runbook 3절에 그 절차가 있지만 Day 10 runbook은 그것을 전제로만 두고
적지 않았다. 내 문서의 구멍이었다.

**더 큰 문제는 그 archive가 host에 없다는 것이었다.** Jenkins가 쓰는
`promote-ci-release-artifacts.sh`의 허용 목록에는 `images.tar.zst` 계열과 metadata·sbom만
있다. 수동 승격 경로(`promote-release-artifacts.sh` → `/opt/gilbom/artifacts/releases/<sha>`)와
Jenkins 경로(`/opt/gilbom/artifacts/<sha>`)는 목적지도 내용도 다르다.

그래서 배포된 SHA로 `git archive <sha>:web`을 떠서 SFTP로 옮겼다. `build-release-artifacts.sh`와
같은 방식이라 내용은 공식 archive와 동일하고, 운영 상태 파일(`runtime.env`·`image.env`·
`secrets/*`)이 섞이지 않았는지도 Day 8과 같은 검사로 확인했다. **다만 이것은 우회다.**
정공법은 CI 승격 목록에 소스 archive를 넣는 것이고 그건 배포 파이프라인 소유자 몫이다.

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

### 후속 커밋

| 커밋 | Jira | 내용 |
| --- | --- | --- |
| `6cf949a` | 738 `#comment` | `.gitignore`에 `*.asc` — gpg 공개키가 저장소에 들어가지 않게 |
| `4fcc81a` | 741 `#comment` | runbook에 deploy 디렉터리 갱신 전제와 첫 검사 전 준비 추가 |
| `51916a2` | 734 `#comment` | 알람 채널을 `d101_alertmanager`로 |
| `f98aaee` | 734 `#comment` | port 발행 경로용 비-internal network 추가 + 정적 검사 |

---

## 8. 남는 위험

- **개인키를 잃으면 모든 backup을 열 수 없다.**
- **TOTP keyring을 잃으면 MFA 암호문은 복구 불가다.** DB를 되살려도 사용자는 다시 등록해야 한다.
- **`gilbom-host-metrics.timer`가 멈추면 디스크 알람이 조용히 사라진다.**
- **Loki 민감값 차단은 목록 기반이다.** 새 형식이 생기면 규칙을 함께 늘려야 한다.
- HTTPS probe는 공개 DNS·보안그룹 경로를 확인하지 않는다(§4). 시연 전 외부 브라우저 확인으로 잡는다.
- Prometheus 보존 15일, Loki 14일. 그보다 오래된 사건은 남지 않는다.
