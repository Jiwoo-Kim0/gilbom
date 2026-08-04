# MQTT v2 임베디드 대조·수정 구현 내용

> 2026-08-02 후속 정정: 아래에 기록된 `zone_id`의 `A-01` 형식 검증과 경고는
> 제거되었다. 현재 `zone_id`는 Patrol Area 업무 코드를 해석하지 않고 그대로
> 전달·보존하는 불투명 값이다.

> 담당: 임베디드 — 로봇 통신
> 작업일: 2026-08-01
> 대상 계약: `tbs/v2` (`schema_version: "2.0"`, 2026-07-30 FROZEN — **이번에 열지 않았다**)
> 최종 상태: **DONE (LOCAL)** — 계약 대조·수정·주입 배선 완료, 실기 EC2 왕복 대기

대조 기준 문서:
[웹 통신계약 로봇 수신 정리](웹_통신계약_로봇_수신_정리.md) (2026-08-01 15:44, **정본**),
[MQTT v2 계약과 통신보안](MQTT-v2-계약과-통신보안_구현내용.md),
[로봇 등록과 MQTT Credential 관리](로봇-등록과-MQTT-Credential-관리_구현내용.md),
[로봇 명령 왕복과 ACK](로봇-명령-왕복과-ACK_구현내용.md),
[Jetson MQTT v2 Day 7 런북](2026-08-01_1515_jetson-mqtt-v2-day7-runbook.md)

---

## 0. 3분 요약

`dddd/`의 웹팀 문서를 최신순으로 읽고 임베디드 통신 코드 전체를 대조한 뒤,
발견을 고치고 실기 주입 경로까지 배선했다.

기준 문서는 **`웹_통신계약_로봇_수신_정리.md` (2026-08-01 15:44)** 다. 웹 담당자가
서버 코드와 임베디드 코드를 직접 대조해 §5-1에 "임베디드에서 고칠 것"을 확정해 둔
문서이고 `dddd/` 안에서 가장 최신이다.

계약의 뼈대는 이미 맞아 있었다. Topic·QoS·retain, `mqtt_id` 필드명, `boot_id`·`seq`
규칙, 6개 명령, 접속 파라미터는 손댈 것이 없었다. 대신 고친 것:

- **결함 이미지가 100% 유실되는 블로커 1건** — 업로드 form의 `robot_id`에 `mqtt_id`를 보내고 있었다
- **서버가 조용히 거부하는 payload를 만들 수 있던 검증 구멍 3건**
- **운영 브로커에 접속 자체가 불가능하던 TLS 설정**
- 다음 사람을 잘못된 구현으로 이끌던 stale 주석 5건
- 웹팀이 확정해 준 **9개 주입값을 설정으로 받는 배선**과 배포 산출물

> **⚠️ 496 passed는 로컬 코드 테스트다. EC2 왕복 성공 증거가 아니다.**
> 실기 E2E는 아직 시작 전이며 순서는 §8에 있다.

---

## 1. 대조 방법

### 1.1 최신 우선 원칙

`dddd/`에는 서로 모순되는 문서가 섞여 있다. 날짜 기준 최신을 정본으로 삼았다.

| 문서 | 시각 | 판정 |
| --- | --- | --- |
| `웹_통신계약_로봇_수신_정리.md` | 2026-08-01 15:44 | **정본** |
| `CHANGLOGS/2026-08-01_1515_jetson-mqtt-v2-day7-runbook.md` | 2026-08-01 15:15 | 유효 |
| `로봇-상태수집과-실시간-관제_구현내용.md` | 2026-07-30 | 일부 무효 (아래) |
| `길봄_MQTT 통신_SWAGGER REST SSE_API 명세서.pdf` | 2026-07-23 | **폐기본 (v1)** |

함정 두 개:

- **PDF는 v1이다.** `tbs/v1`, `robot_id`, `schema_version "1.0"`만 있고 v2와 다르다.
  계약 문서 자체가 "검색하면 v1이 나오니 동결 묶음만 사용"하라고 경고한다.
  참고만 하고 근거로 쓰지 않았다.
- **속도·CPU·모터 온도.** 07-30 관제 문서는 "GPS, 배터리, 속도, CPU·Jetson·모터·배터리
  온도 저장"이라 적혀 있지만, 08-01 런북이 "v2에서 제거된 `position.accuracy_m`과
  계약에 없는 CPU·GPU·모터 온도를 실기 Payload에 임의로 추가하지 말 것"으로 뒤집는다.
  최신을 따랐다 — 현재 payload는 `system.jetson_temp_c` 하나만 싣는다.

### 1.2 대조 범위

8개 계약 영역을 문서↔코드로 대조하고, 각 발견을 반대 입장에서 다시 검증했다.

`topics` / `identifiers` / `presence-state` / `telemetry` / `events` /
`commands` / `security-connection` / `images-upload`

---

## 2. 이미 맞아 있던 것 (수정 없음)

| 항목 | 확인 내용 |
| --- | --- |
| Namespace·버전 | `tbs/v2/robots/{mqtt_id}/...`, `schema_version: "2.0"` |
| 발행 8종 | presence·state·telemetry·events 4종·commands/result |
| QoS·Retain | presence·state QoS1+retain, telemetry QoS0, 나머지 QoS1 retain false |
| 식별자 | payload 키가 `mqtt_id` (wire 정상) |
| `boot_id` | 프로세스당 uuid4 1회 |
| `seq` | state·telemetry·event 공유 단조증가, 첫 값 1, 새 boot에서 리셋 |
| 접속 | MQTT 5, `CLEAN_START_FIRST_ONLY`, SessionExpiry 3600s, keepalive 30 |
| LWT | `presence OFFLINE / UNGRACEFUL_DISCONNECT`, QoS1 retain |
| SUBACK | 구독 거부를 `client.subscribed`·`subscribe_reason_codes`로 확인 |
| 명령 7종 | START/PAUSE/RESUME/COMPLETE/CANCEL_MISSION, EMERGENCY_STOP, CLEAR_EMERGENCY_STOP |
| 예약 명령 | `RETURN_TO_CHARGE`·`UPDATE_CONFIG`는 `RESERVED_COMMAND_TYPES`로 미실행 |
| 필수 parameters | 서버 `ttl()`과 로봇 `REQUIRED_PARAMETERS` 일치 |
| `ttl_sec` | 60~300, 기본 300 (서버 `@Min(60) @Max(300)`과 동일) |
| Event 재전송 | `event_id`·`occurred_at`·`ttl_sec` 불변, `sent_at`만 갱신 |
| `mission.elapsed_sec` | monotonic 벽시계, PAUSED 중에도 증가 (서버 진행률 계산의 전제) |
| `zone_id` | START_MISSION 값을 좌표 계산 없이 그대로 echo |

---

## 3. 무엇을 고쳤나

### 3.1 블로커 — 증거 이미지 100% 유실

`events.py`가 업로드 form의 `robot_id`에 `mqtt_id`("RBT-001")를 넣고 있었다.
서버 `ImageUploadController`는 그 필드를 `UUID.fromString()`으로 파싱한다. UUID가
아니므로 400 `COMMON_INVALID_REQUEST`가 떨어지고, 400은 `RETRYABLE_CLIENT_STATUSES`가
아니라 재시도 없이 포기한다. `evidence.image_id`가 **항상 null**이었다.

MQTT의 `mqtt_id`와 이미지 업로드 form의 `robot_id`는 **이름도 값도 다른 채널**이다.

| 파일 | 변경 |
| --- | --- |
| `config.yaml` | `robot.uuid` 신설 (DB 내부 `robot.robot_id`) |
| `main.py` | `EventPublisher(upload_robot_uuid=...)` 배선 |
| `events.py` | 업로드 호출이 mqtt_id 대신 UUID 전달 |
| `comms/uploader.py` | 파라미터명 `robot_id` → `robot_uuid`, 전송 전 UUID 검증 |

전송 전 UUID 검증을 넣은 이유: 잘못된 값이면 네트워크를 타기 전에 끊고
"`robot.uuid`(ROBOT_UUID)가 비었다"는 원인을 로그에 남긴다. 무의미한 400 왕복과
그로 인한 조용한 유실을 없앤다.

### 3.2 서버가 조용히 거부하는 payload를 만들 수 있던 곳

세 곳 모두 **로봇은 성공으로 알고 서버는 버리는** 구조였다. 브로커가 이미 PUBACK을
보냈으므로 Outbox 행은 `sent`로 지워지고, 거부는 서버 로그와 메트릭에만 남는다.
`ingest/receipt`가 MVP 미사용이라 로봇이 알 방법이 없다. 그래서 발행 전에 막았다.

| # | 규칙 | 기존 코드 | 조치 |
| --- | --- | --- | --- |
| 1 | `x_min < x_max` 이고 `y_min < y_max` | `>` 만 검사해 폭·높이 0인 상자를 통과 | `comms/blocks.py:_bbox` 를 `>=` 로 |
| 2 | `battery.valid=true` → `state != UNKNOWN` | `valid=true` + `UNKNOWN` 통과 | `comms/blocks.py:_battery` 가드 |
| 3 | `completed_at >= received_at` | 미검사 | `comms/factory.py:command_result` 가드 |

3번이 특히 위험하다. NTP 스텝으로 시계가 뒤로 튀면 result가 거부되고, 관제 화면의
명령은 영원히 `ACCEPTED`에 멈춘다.

### 3.3 운영 접속 설정

`port: 8883`인데 `ca_cert: null` + `require_tls: false`였다. 운영 브로커는 TLS 전용이라
이 조합으로는 **절대 접속되지 않고** 평문으로 붙으려다 무한 재접속만 반복한다.

- `ca_cert` 기본값을 저장소 동봉 CA로, `MQTT_CA_CERT`로 덮어쓸 수 있게
- `require_tls: true`
- 상대경로를 **config.yaml이 있는 디렉터리 기준**으로 해석 (`comms/config.py:_resolve_path`)
  — systemd가 `WorkingDirectory` 없이 띄워도 CA를 찾는다

### 3.4 `zone_id` 형식 조기 경고

서버는 `incident.location.zone_id`에만 `^[A-Z]-[0-9]{2}$`를 강제하는데, 그 값의 출처인
`patrol_area.area_code`에는 제약이 없다. 벗어난 값이 오면 그 미션의 결함이 전부 거부된다.

명령은 거절하지 않는다 — 순찰을 못 할 이유가 없고 거절 권한도 로봇에 없다. 대신
**미션 시작 시점에** 경고를 남긴다. 첫 결함이 날 때가 아니라 그때 알아야 손을 쓸 수 있다.

### 3.5 stale 주석

동작은 정상이지만 문구가 옛날이라 그대로 따르면 계약을 깨는 것들이다.

| 위치 | 기존 | 수정 |
| --- | --- | --- |
| `config.yaml` | "topic 세그먼트 {robot_id}·payload 의 robot_id" | `mqtt_id` |
| `sensors/gps.py` | TODO가 `accuracy_m(hdop 기반)` 구현을 지시 | v2에서 삭제됨을 명시 |
| `comms/uploader.py` | form 필드·응답 스키마가 "⚠️ 추정치" | 확정 계약으로 교체 |
| `detect/inference.py` | bbox 규칙을 "min <= max"로 서술 | 강한 부등식으로 정정 |
| `test/test_events.py` | "서버 images 엔드포인트가 아직 없어" | 계약 규칙 서술로 교체 |

`sensors/gps.py`가 특히 위험했다. GPS를 구현하는 사람이 TODO를 따르면 v2에서 삭제된
`accuracy_m`을 넣게 되고, 서버 ObjectMapper가 `FAIL_ON_UNKNOWN_PROPERTIES`라
**telemetry 표본 전체가 거부**된다.

### 3.6 실기 주입값을 설정으로 받게 했다

웹팀이 Jetson에 넣을 값을 확정해 주었다. 전부 초기 환경변수로 들어온다.
`config.yaml`이 그 이름을 그대로 읽도록 고치고, 기본값은 코드가 아니라 파일에 적었다.

| 환경변수 | 출처 | 없으면 |
| --- | --- | --- |
| `MQTT_ID` | 등록 응답 `mqttId` | **기동 실패** |
| `MQTT_HOST` | 운영 `i15d101.p.ssafy.io` | **기동 실패** |
| `MQTT_PASSWORD` | Credential 1회 비밀번호 | **기동 실패** |
| `ROBOT_UUID` | 등록 응답 `robotId` | 기동 O, 이미지 업로드만 차단 |
| `MQTT_USERNAME` | Credential `username` | `MQTT_ID` 사용 |
| `MQTT_CLIENT_ID` | 직접 지정 | `MQTT_ID` 사용 |
| `MQTT_PORT` | 8883 | 8883 |
| `MQTT_CA_CERT` | Jetson 내부 CA 절대경로 | 저장소 동봉 CA |
| `UPLOAD_URL`·`UPLOAD_API_KEY` | 미정 (발급 경로 없음) | 기동 O, 업로드만 차단 |

필수 3개만 `${env:}`(없으면 즉시 죽음)이고 나머지는 `${env?:}`다. 기준은
**그 값 없이 MQTT E2E가 되느냐**다. `ROBOT_UUID`와 `UPLOAD_*`가 없어도 presence·
state·telemetry·command는 전부 돌아가므로 그것 때문에 로봇이 못 뜨면 안 된다.

기본값을 파일에 적기 위해 `${env?:NAME|기본값}` 형식을 추가했다. 기본값이 코드에
숨어 있으면 설정을 읽는 사람이 실제 동작을 알 수 없다.

`username`이 `mqtt_id`와 갈라지면 기동 시 거부한다. 브로커 ACL이 `%u`를 `mqtt_id`에
묶었고 서버가 topic·payload의 `mqtt_id`를 대조하므로, 달라지면 **접속은 되는데 발행이
전부 거부되는** 찾기 어려운 상태가 된다. 현재 서버는 둘 다 `mqttId`로 주지만 항목은
분리해 두었다.

`MQTT_QOS`는 **로봇에 만들지 않았다.** QoS는 topic마다 계약이 고정한다
(`comms/topics.py: PUBLISH_POLICY`). 설정으로 열면 값 하나로 QoS 1 topic 7종이
한꺼번에 강등돼 서버가 전부 거부한다 — 웹이 자기 쪽에서 경고한 바로 그 실패 모드다.
웹의 `MQTT_QOS=1`은 서버 구독 설정이다.

### 3.7 배포 산출물

`main.py`가 TODO로 남겨 두었던 항목을 실제로 만들었다.

```text
scripts/orincar.env.example   주입값 템플릿 (항목별 출처·없을 때 동작)
scripts/orincar.service       systemd unit
scripts/README.md             주입·기동·장애 분류
```

unit이 `time-sync.target`에 의존하는 이유는 `expires_at` 판정과 서버 freshness 검사가
둘 다 NTP 동기를 전제하기 때문이다. `KillSignal=SIGTERM` + `TimeoutStopSec=15`는
종료 시 `SHUTTING_DOWN` state와 `presence OFFLINE(GRACEFUL_SHUTDOWN)`을 내보낼
시간을 준다 — SIGKILL이면 브로커가 LWT를 대신 내보내 종료가 비정상으로 기록된다.

`.gitignore`에 `scripts/orincar.env`와 `var/`를 추가했다.

### 3.8 POSIX 절대경로 오인

`MQTT_CA_CERT=/etc/orincar/...`를 Windows에서 검증하면 drive가 없어 상대경로로
오해되고 `C:\etc\orincar\...`가 만들어진다. 설정은 Jetson(Linux) 기준으로 적히므로
선행 `/`를 절대경로로 인정하도록 고쳤다 (`comms/config.py:_resolve_path`).

### 3.9 계약 확정 반영

`웹_통신계약_로봇_수신_정리.md` §4가 이미지 업로드 계약을 확정해 주었다.

- form: `file`, `event_id`, `robot_id`(UUID), `captured_at`(선택) → `captured_at` 전송 추가
- 응답: camelCase `{imageId, eventId, objectKey, imageWidth, imageHeight}`
- 201 신규 / 200 멱등 재업로드 / 409 충돌 → 409는 재시도 무의미함을 명시

---

## 4. 설계 판단과 그 이유

### 4.1 없어도 뜨는 값과 없으면 죽는 값을 갈랐다

기준은 **그 값 없이 MQTT E2E가 되느냐** 하나다. 브로커 비밀번호가 없으면 아무것도
안 되니 즉시 죽어야 하고(비어 있는 채 떠서 무한 재접속만 반복하는 것보다 낫다),
`ROBOT_UUID`·`UPLOAD_*`가 없으면 이미지만 막히니 떠야 한다.

### 4.2 v2 계약을 열지 않았다

`ROBOT_UUID`를 MQTT로 받으려면 `UPDATE_CONFIG` 신설이 필요하고, 그건 동결된 v2의
Major 변경이다. 등록 응답에 이미 `robotId`가 있으므로 Credential·CA와 같은 순간에
넣으면 된다. 계약을 여는 비용이 얻는 것보다 크다.

### 4.3 검증을 발행 직전 한 곳에 모았다

bbox·battery·zone_id 규칙을 탐지 모듈이 아니라 `comms/` 안에서 강제한다. 탐지 쪽에서
막으면 다른 경로로 들어온 값이 새고, 규칙이 두 곳으로 갈라진다.

### 4.4 명령을 거절하지 않고 경고만 한다

`zone_id` 형식은 서버가 만들어 보낸 값이다. 로봇이 명령을 거절하면 서버 설정 실수 하나로
순찰 자체가 멈춘다. 로봇은 계약대로 echo하고, 사람이 볼 수 있게 남기는 데까지가 몫이다.

### 4.5 이미지 실패가 결함을 삼키지 않게 유지했다

업로드가 실패해도 `image_id: null`로 incident event는 그대로 발행한다(계약 §8.3).
UUID나 API Key가 비어 있는 동안에도 결함 자체는 계속 올라간다.

---

## 5. 검증

```powershell
cd C:\Users\SSAFY\Desktop\comms+a
python -m pytest test -q
```

결과: **496 passed** (수정 전 479 → 신규·갱신 17개)

주입값이 실제로 어떻게 풀리는지 확인:

```powershell
$env:MQTT_ID='RBT-001'; $env:MQTT_HOST='i15d101.p.ssafy.io'
$env:MQTT_PASSWORD='...'; $env:MQTT_CLIENT_ID='jetson-01'
$env:MQTT_CA_CERT='/etc/orincar/gilbom-mqtt-ca.crt'
python -c "from comms.config import load_document, MqttConfig; from pathlib import Path; p=Path('config.yaml').resolve(); c=MqttConfig.from_document(load_document(p), base_dir=p.parent); print(c.robot_id, c.username, c.client_id, c.host, c.port, c.ca_cert, c.require_tls)"
```

확인된 결과: `MQTT_USERNAME`·`MQTT_PORT`를 생략하면 각각 `mqtt_id`·8883으로 떨어지고,
`MQTT_CLIENT_ID`는 준 값을 쓰며, POSIX 절대 CA 경로는 그대로 유지된다.

신규 테스트가 고정한 계약:

- 업로드 form의 `robot_id`가 mqtt_id가 아닌 UUID임
- mqtt_id·빈 값·비UUID는 전송 자체를 하지 않음
- `captured_at`은 줬을 때만 실림
- 폭·높이 0인 bbox 거부
- `battery.valid=true` + `state=UNKNOWN` 거부
- `completed_at < received_at` 거부, 같은 시각은 통과
- `${env?:}` 는 없으면 null, `${env?:|기본값}` 은 기본값
- `MQTT_USERNAME`이 `MQTT_ID`와 갈라지면 기동 거부
- `zone_id` 형식 이탈 시 경고하되 미션은 시작됨

> 기존 테스트 일부가 버그를 고정하고 있었다. `test_uploader.py`·`test_events.py`가
> `ROBOT_ID = "RBT-001"`을 업로드 form 값으로 단언하고 있어 함께 바로잡았다.
> 그린 스위트가 깨진 계약을 지키고 있으면 회귀를 못 잡는다.

**이 496개는 로컬 코드 테스트다. EC2 왕복 성공 증거가 아니다.**

---

## 6. 작업 중 겪은 함정

### 정본이 저장소 밖에 있다

`web/docs/final-mqtt-topic-schema-v2.md`는 이 저장소에 없고 참조만 된다. `dddd/`의
구현내용 문서들이 인용한 내용을 모아 재구성해야 했다.

### 가장 중요한 문서가 나중에 들어왔다

`웹_통신계약_로봇_수신_정리.md`는 초기 탐색 시점에 없었다. 파일 목록을 수정 시각순으로
다시 확인하고서야 찾았다. `dddd/`를 볼 때는 **mtime 정렬을 먼저** 하는 편이 안전하다.

### PDF가 검색을 오염시킨다

46페이지 v1 명세서라 `robot_id`·`tbs/v1`가 계속 잡힌다. `pdftoppm`이 없어 렌더링이
안 되므로 `pypdf`로 텍스트만 뽑아 확인했다.

### 상대경로 CA가 작업 디렉터리에 묶여 있었다

`ca_cert`를 켜자 테스트가 깨졌다. paho가 클라이언트 **생성 시점**에 `tls_set`을 부르고,
상대경로가 프로세스 CWD 기준이었기 때문이다. config 파일 기준으로 푸는 것이 근본 수정이다.

### Windows에서 POSIX 절대경로가 상대경로로 보인다

`/etc/...`는 drive가 없어 `Path.is_absolute()`가 False다. 개발 PC에서만 나타나는
증상이라 실기에서 안 걸리지만, 검증 결과를 믿을 수 없게 만든다.

### Git Bash가 POSIX 경로를 변환한다

`MQTT_CA_CERT=/etc/...`를 Git Bash에서 넘기면 MSYS가 `C:/Program Files/Git/etc/...`로
바꿔서 전달한다. 경로 관련 확인은 PowerShell에서 해야 한다.

### 모듈 전역 dict를 테스트가 공유한다

`test_commands.py`의 `command()`가 모듈 전역 parameters를 반환한다. 한 테스트에서
`zone_id`를 바꿨더니 다음 테스트로 샜다. 손댈 때는 `copy.deepcopy` 후 수정한다.

---

## 7. 미완 항목

### 임베디드 몫

1. **실기 E2E** — §8 순서대로. 이게 남은 전부다.
2. **`detect.model_version`이 `0.0.0-stub`이다.** 실제 export 버전으로 교체해야
   `incident.model.version` 추적이 의미를 갖는다. `config.yaml`과
   `detect/inference.py`(MODEL_VERSION) 두 곳을 같이 고친다.
   Jetson에서 근거값:
   ```bash
   python3 -c "import torch; c=torch.load('/home/ubuntu/SSAFY/models/best.pt', map_location='cpu', weights_only=False); print({k: c.get(k) for k in ('date','version','train_args')})"
   ```
   여기 나오는 `version`은 ultralytics 라이브러리 버전이지 제품 모델 버전이 아니다.
   `date`를 근거로 학습 담당이 정하되 **재학습마다 바뀌기만** 하면 된다.
3. Credential 회전 시 기존 연결 종료·재연결 절차 검증

### 웹팀 (확인·진행 중)

| 항목 | 상태 |
| --- | --- |
| `area_code` ↔ incident `zone_id` 형식 불일치 | **유효 확인됨, 웹에서 수정 예정.** 현재 `DEMO-ZONE-01` 같은 코드로 구역을 만들면 임무는 시작돼도 결함 event가 거부된다 |
| 로봇용 `X-API-Key` 발급 화면·API | **없음 — 이미지 포함 E2E의 별도 블로커.** MQTT E2E는 이것 없이 진행 가능 |
| `MQTT_QOS=1` 유지 | 확인됨 |
| 중복 `command_id` 재수신 정책 | 미확정. 현재 로봇은 조용히 무시(재실행 방지). 재발행으로 정해지면 결과 캐시를 붙인다 |
| `mqtt-device-db-mapping.md`가 `tbs/v1`·`robot_id` 표기 | 갱신 요청 |

### 확인만 하고 바꾸지 않은 것

- `command_id`가 없는 명령에 대한 `REJECTED`는 `command_id: null`이라 서버 `@NotNull`에
  걸려 거부된다. 서버가 모르는 명령이라 실해는 없고, 발행을 없애면 로봇 쪽에도 흔적이
  남지 않는다. 알고 둔다 (`commands.py`에 주석).

---

## 8. 실기 E2E 순서

웹팀이 확정한 순서. 임베디드는 4번부터 관여한다.

```text
1. Nginx HTTPS 완료 → 관리자 로그인 복구        (웹)
2. 관리자 화면에서 로봇 등록                     (웹)  → robotId, mqttId
3. MQTT Credential 최초 발급                     (웹)  → username, password, clientId
4. Jetson에 값 주입                              (임베디드)
5. MQTT TLS 연결 및 Presence·State·Telemetry 확인
6. 명령 → ACK/Result 왕복
7. 이미지 업로드 검증                            (X-API-Key 대기 — 별도 블로커)
```

4번 절차는 [`scripts/README.md`](scripts/README.md)에 있다. 요약:

```bash
sudo mkdir -p /etc/orincar && sudo cp gilbom-mqtt-ca.crt /etc/orincar/
cp scripts/orincar.env.example scripts/orincar.env && chmod 600 scripts/orincar.env
$EDITOR scripts/orincar.env          # 2·3번 응답값을 채운다
set -a; . scripts/orincar.env; set +a
python3 -u main.py
```

`MQTT_PASSWORD`는 발급 성공 Modal에서 **한 번만** 보인다. 놓치면 회전해서 새로 받아야
한다 — 서버는 원문을 보관하지 않는다.

5번에서 확인할 것:

- `connected to i15d101.p.ssafy.io:8883` — TLS 접속 성공
- SUBACK 거부 없음 — ACL이 `commands/request` 읽기를 막으면 명령을 영원히 못 받는다
- 브로커에서 `presence` ONLINE, 5초마다 `telemetry`, 30초마다 `state`

장애 분류표는 `scripts/README.md` §3에 있다. 핵심은 하나 —
**서버가 거부한 메시지를 로봇은 알 수 없다.** 브로커가 이미 PUBACK을 보냈으므로
Outbox는 `sent`로 지워지고 `ingest/receipt`가 MVP 미사용이라 메울 채널이 없다.
통합 기간에는 웹 쪽 `gilbom.mqtt.inbound.rejected` 메트릭(태그 `topic`, `reason`)을
같이 봐야 한다.

---

## 9. 변경 파일

| 파일 | 변경 |
| --- | --- |
| `config.yaml` | `robot.uuid` 신설, 9개 주입값 env 배선, 주석 `robot_id`→`mqtt_id`, TLS |
| `comms/config.py` | `${env?:}`·`${env?:\|기본값}`, `base_dir` 경로 해석, POSIX 절대경로 |
| `comms/contract.py` | `ZONE_ID_PATTERN` |
| `comms/blocks.py` | bbox 강한 부등식, battery `valid=true`+`UNKNOWN` 거부 |
| `comms/factory.py` | `completed_at >= received_at` |
| `comms/uploader.py` | UUID 검증, `captured_at`, 확정 계약 반영 |
| `events.py` | `upload_robot_uuid` 배선, `set_upload_robot_uuid()` |
| `commands.py` | `zone_id` 조기 경고, `command_id` null 주석 |
| `main.py` | `base_dir`·`upload_robot_uuid` 전달, 배포 TODO 정리 |
| `detect/inference.py` | bbox docstring 정정 |
| `sensors/gps.py` | `accuracy_m` TODO 제거 |
| `scripts/` | `orincar.env.example`, `orincar.service`, `README.md` (신규) |
| `.gitignore` | `scripts/orincar.env`, `var/` |
| `test/` 6개 파일 | 신규·갱신 17개 (479 → 496) |

---

## 10. 관련 문서

- [웹 통신계약 로봇 수신 정리](웹_통신계약_로봇_수신_정리.md) — 대조 정본
- [MQTT v2 계약과 통신보안](MQTT-v2-계약과-통신보안_구현내용.md)
- [로봇 등록과 MQTT Credential 관리](로봇-등록과-MQTT-Credential-관리_구현내용.md)
- [Jetson MQTT v2 Day 7 런북](2026-08-01_1515_jetson-mqtt-v2-day7-runbook.md)
- [Jetson 주입·기동 절차](scripts/README.md)
