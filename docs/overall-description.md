# 길봄(GILBOM) 전체 구조 설명 — 발표·기술질의 대비

이 문서는 **저장소에 실제로 존재하는 파일의 내용만** 근거로 작성했다. 각 절 제목 옆
또는 본문에 근거 파일 경로를 붙였으므로, 질문이 들어오면 그 파일을 열어 그대로 보여
주면 된다. 계획에만 있고 코드가 없는 기능은 넣지 않았다.

---

## 0. 한 줄 요약과 규모

자율주행 로봇이 보행로를 순찰하면서 **점자블록 파손·미설치 구역과 보행 장애물을
탐지**하고, 관제 웹에서 **위치·사진·이동 궤적·로봇 상태를 실시간으로 확인**하는 서비스다.
(`README.md`)

| 구성 | 규모 (실측) |
|---|---|
| Backend Java 소스 | `web/backend/src/main/java` 411개 |
| Backend 테스트 | `web/backend/src/test` 158개 |
| Frontend 소스 | `web/frontend/gilbom/src` 129개 |
| Flyway migration | `V1`~`V45` 45개 |
| 로봇 런타임 Python | `embedded/jetson_main` 약 14,700줄 (모듈별 `wc -l` 기준) |
| Compose 파일 | 로컬/빌드/운영/모니터링/Jenkins/복원력/롤백드릴/스모크 8종 |

버전 고정값 (`README.md`, `web/frontend/gilbom/package.json`):
Java 21 / Spring Boot 3.5.16 / Gradle 8.14.3, React 19.2.8 / Node 24.18.0 / Vite 8,
Nginx 1.30.4, PostgreSQL 16 + PostGIS 3.5, Mosquitto 2.1.2, Jetson Orin Nano + JetPack
L4T R36.4.7 / Python 3.10.12.

---

## 1. 시스템 구성 — 무엇이 어디로 흐르는가

```text
Browser ──HTTPS 443──> Nginx ──/api──> Spring Backend ──JDBC──> PostgreSQL/PostGIS
Robot   ──MQTTS 8883─────────────────> Mosquitto ─────────────> Backend (Paho 구독)
Robot   ──HTTPS 이미지 업로드─────────> Nginx ────────────────> Backend (X-API-Key)
Backend ──HTTPS──> SSAFY GMS (LLM 보고서 생성, 승인된 경우)
Browser ──HTTPS──> Naver Maps API
```

- 외부 공개 포트는 `80`(HTTPS 전환/ACME), `443`, `8883`뿐이다. Backend `8080`과 DB
  `5432`는 운영 호스트에 publish하지 않는다. (`README.md`, `web/compose.yaml`)
- 로컬은 모든 포트를 `127.0.0.1`에 바인딩한다. MQTT만 `MQTT_BIND_ADDRESS=0.0.0.0`으로
  바꿔 LAN 로봇 테스트를 허용한다. (`web/compose.yaml`)
- 영속 데이터는 Docker named volume 3개다: `postgres_data`, `report_pdf_data`(승인
  PDF), `robot_images`(결함 이미지 원본). 이미지는 외부 오브젝트 스토리지를 쓰지
  않으므로 이 볼륨이 유일한 사본이고 DB `storage.robot_image`와 함께 백업해야 한다.
  `docker compose down -v`는 이 데이터를 지운다. (`web/compose.yaml` volumes 주석)

**핵심 데이터 흐름 3개**

1. **상태·텔레메트리**: 로봇 → MQTT → Backend 적재 → SSE → 브라우저 지도/차트
2. **결함 탐지**: 로봇이 사진을 HTTP로 먼저 올려 `imageId`를 받고 → MQTT
   `events/incident`로 그 `imageId`를 참조 → Backend가 결함 병합·생성 → SSE
3. **원격 제어**: 브라우저 → REST `POST /commands` → Backend가 MQTT
   `commands/request` 발행 → 로봇 실행 → `commands/result` 회신

---

## 2. 로봇 (임베디드) — `embedded/`

### 2.1 폴더 구성과 역할

| 경로 | 역할 |
|---|---|
| `embedded/jetson_main/` | **실제 순찰 로봇 런타임 통합본.** 인지 + 주행 FSM + 하드웨어 + 서버 통신이 한 프로세스 |
| `embedded/gseok/` | 통신(comms) 계층 단독 작업 저장소. `comms/`, `telemetry.py`, `commands.py`, `test/` |
| `embedded/driving_test/` | Flask 웹 UI로 주행을 시험하던 테스트 앱 (`app.py`, `curb_detector.py`, `ydlidar_driver.py`) |
| `embedded/driving_module/` | `segTest` 시절 주행 모듈 레퍼런스 보존본 (`motor_driver.py`, `auto_navigator.py`) |
| `embedded/DataRecorder/` | Xbox 컨트롤러 수동 조종 + 카메라 2대 녹화 (학습 데이터 수집) |
| `embedded/vision_ai/` | YOLO 학습·전처리·추론 데모 (`train_yolo.py`, `dataset_preprocessor.py`, `infer_demo.py`) |

`jetson_main/README.md`에 통합 내역이 남아 있다. `robot/`과 `SSAFY/`에 같은 파일이
여러 벌 있던 것을 MD5로 대조해 모델 파일 1벌씩(`models/braille_defect.pt`,
`models/segv2.pt`)으로 합쳤다.

### 2.2 한 프로세스, 한 스레드 (`jetson_main/main.py`)

진입점 docstring에 설계가 명시돼 있다: **인지 입력 → `fsm.step()` → 하드웨어 명령**을
단일 스레드 루프에서 돌린다. 하드웨어 쓰기 지점이 한 곳(single writer)이다. 예외는
조작자 입력 리더 하나이고 그쪽은 읽기 전용 래치다.

기본은 **dry-run**이다. 실행 플래그로 위험도를 계단식으로 올린다.

```bash
python3 main.py                          # 모의 장치 + 모의 인지, 로그만
python3 main.py --live                   # 실물 모터·서보 구동
python3 main.py --live --perception real  # 실추론(카메라+세그+검사) + 실구동
scripts/drive.sh --live --comms-config comms.yaml   # MQTT 원격 미션 모드
```

`--comms-config`를 주지 않으면 **통신 코드는 import조차 되지 않는다.** paho나
credential 없이도 주행만 돌 수 있게 한 의도적 분리다. 원격 미션 모드에서는 자동
출발하지 않고 서버의 `START_MISSION`을 기다린다.

카메라는 2대, 모델은 2개다 (`main.build_real_perception`):
- **주행 카메라 + 세그멘테이션 모델**(`perception/segmentation.py`, 1,513줄) — 인도를
  따라 달리고 점자블록을 찾는다
- **하향 검사 카메라 + 탐지 모델**(`perception/braille.py`) — 주행하면서 블록 파손을
  판정한다

클래스 역할(주행가능/차도/비용)은 `config.yaml`의 **7클래스 우선순위 표 하나**에서만
파생된다. 종전에 `drivable_classes`·`roadway_classes`·`costmap.class_costs` 세 곳에
흩어져 실제로 어긋나 있던 것을 합친 결과다. (`jetson_main/README.md`)

### 2.3 주행 FSM (`jetson_main/driving/`)

`driving/states.py`의 `DriveState`는 임베디드 명세서와 1:1이다.

| 상태 | 의미 |
|---|---|
| `IDLE` / `PAUSED` | 대기 / 원격 일시정지 |
| `CRUISE` | 인도 중심선 추종 |
| `TRACE` | 점자블록 추종 (미션 본체) |
| `INSPECT` | 파손 후보 확정 — 정차 → 정밀 판정 → 재개 |
| `TURN` | 좌/우회전 |
| `AVOID` | 장애물 회피 선회 (정지 없이) |
| `ESCAPE` | 차도 이탈 선회 |
| `SLOPE` | 경사 보정 (IMU 게이트) |
| `DEAD_END` | 막다른 길 재정향 → 안 풀리면 U턴 승격 |
| `ESTOP` | 비상 정지 — 수동 해제만 |

`AVOID`·`DEAD_END`는 상태 아래 **하위 국면(phase)** 을 둔다. `AvoidPhase`는
`HOLD → SWERVE → PASS → RETURN`, `DeadEndPhase`는
`REVERSE → TURN_ARC → RETRACE → STRAIGHTEN → UTURN`이다. 상태 전이가 아니라
국면 전환이므로 `DriveState`는 내내 그대로 유지된다.

`driving/` 패키지는 **순수 계산만** 한다 — I/O·`time.sleep`·시계 접근 금지가
`states.py` docstring에 규칙으로 박혀 있다. 그래서 `tests/test_state_machine.py`가
하드웨어 없이 FSM 전체를 돌릴 수 있다.

> **발표에서 강한 포인트:** README의 "0804 명세 반영", "0806 실기 반영" 표는
> **실외 주행에서 관찰된 증상 → 진짜 원인 → 바꾼 것**을 실측값과 함께 기록한
> 설계 변경 이력이다. 예를 들어
> - "차도를 인도로 봤다" → `sidewalk` 마스크를 전역 합집합으로 쓴 것이 원인 →
>   **자차 연결(ego-connected) 주행면**만 인정하도록 변경 (`tests/test_ego_connectivity.py`)
> - "회피하지 않고 정지만 했다" → 종료 판정이 전부 yaw 기반 = IMU 게이트 뒤였고
>   IMU 배선이 해제된 상태 → 종료 판정을 **시각 + 시간**으로 바꿔 IMU 의존 제거
> - "찔끔찔끔 움직인다" → SWERVE 종료가 한 틱(0.05초)에 끝날 수 있었음 →
>   `swerve_min_sec` 0.8초 **최소 커밋** 신설
> - 조향 슬루율 100°/s가 서보 사양(≈350°/s)의 1/3로 실제 병목 → **200°/s로 상향**,
>   대신 조향각은 실측 최적점으로 **하향**(스크럽 저항 때문에 깊게 꺾을수록 덜 돈다)

### 2.4 하드웨어 계층 (`jetson_main/hardware/`)

`hardware/interfaces.py`가 `DriveMotor`, `SteeringServo`, `Imu` 인터페이스를 정의하고
`hardware/jetson/`이 실물 구현(`drive_motor.py`, `steering_servo.py`, `imu.py`,
`pca9685_wake.py`, `touch.py`), `hardware/mocks.py`가 개발 PC용 모의 구현이다.
동일 인터페이스이므로 dry-run과 실주행이 같은 코드 경로를 쓴다.

터치 센서(`touch.py`)는 GPIO 폴링 + **누름 엣지 1회 소비**다. 누르고 있는 내내 시작
요청이 반복되면 "IDLE의 출구는 수동 재시작뿐"이라는 안전 설계가 무력해지기 때문이다.

정지 명령이 3종인 것도 설계 포인트다: 무동력 `stop`, 숏브레이크 `brake`, 그리고
**경사 비례 역스로틀 `hold`**. 내리막에서 INSPECT 정차 중 굴러가 모션 블러가 생기던
문제를 저듀티 역전 PWM(오프 구간이 모터 브레이크)으로 해결했다.

### 2.5 로봇 측 통신 계층 (`jetson_main/comms/`, `jetson_main/communication/`)

`comms/`는 전송, `communication/`은 도메인 조립이다.

| 파일 | 역할 |
|---|---|
| `comms/contract.py` | 계약 상수 단일 출처 (namespace, 이미지 최대 바이트, content-type) |
| `comms/topics.py` | **topic 문자열과 QoS·retain 정책을 정하는 유일한 곳** |
| `comms/codec.py` | 직렬화 / UTC 시각 |
| `comms/factory.py` | payload 조립 (`MessageFactory`) |
| `comms/client.py` | paho-mqtt 접속·발행·구독, 재접속, receipt 처리 (543줄) |
| `comms/outbox.py` | 유실 금지 event의 로컬 outbox + 재전송 |
| `comms/uploader.py` | `POST /api/v1/images` 증거 이미지 업로드 |
| `communication/runtime.py` | 통신 런타임 조립 (748줄) |
| `communication/robot_state.py` | 로봇 상태 → 계약 state 매핑 (892줄) |
| `communication/commands.py` | 서버 명령 수신·실행·결과 회신 (506줄) |
| `communication/events.py` | 사건 발행 (585줄) |
| `communication/telemetry.py` / `evidence.py` | 텔레메트리 주기 발행 / 증거 사진 |

**presence를 세 갈래로 낸다** (`comms/client.py` 클래스 docstring):

| 경로 | 시점 | 값 |
|---|---|---|
| LWT | CONNECT 때 등록, 비정상 종료 시 브로커가 발행 | `OFFLINE / UNGRACEFUL_DISCONNECT` |
| birth | 접속 성공 직후 직접 발행 | `ONLINE` |
| shutdown | 정상 종료 시 직접 발행 | `OFFLINE / GRACEFUL_SHUTDOWN` |

즉 로봇이 전원째 죽어도 브로커가 대신 OFFLINE을 알린다. 재접속은 1~30초
exponential backoff, outbox 재전송 주기 30초, 전송 완료 기록 보관 7일이다.

**Outbox 대상은 4종**(`comms/topics.py`의 `EVENT_KINDS`): incident, battery, safety,
mission. 유실되면 안 되는 사건만 디스크에 남기고 재전송한다. telemetry는 QoS 0이라
대상이 아니다 — 다음 샘플이 곧 오기 때문이다.

**업로더**(`comms/uploader.py`)는 서버 계약을 코드 주석으로 고정해 두고 있다.
- JPEG만, 최대 500KB. 서버 413을 기다리지 않고 클라이언트에서 먼저 거른다.
- 인증은 `X-API-Key` 헤더. 세션 쿠키·CSRF 대상이 아니다.
- form 필드는 `file`, `event_id`, `robot_id`, (선택) `captured_at`.
- ⚠️ `robot_id`에 넣는 값은 `mqtt_id`("RBT-001")가 **아니라** DB 내부
  `robot.robot_id` UUID다. MQTT payload는 `mqtt_id`, 업로드 form은 UUID —
  채널마다 이름과 값이 다르다는 것이 주석에 경고로 남아 있다.
- 재시도는 3회(1·2·4초). 429만 재시도하고 400·401·403·409·413·415는 재시도하지
  않는다(다시 보내도 결과가 같다).
- 200(멱등 재업로드)과 201(신규)을 **둘 다 성공**으로 처리한다.

> 실전 함정 기록: `comms/uploader.py`의 `DEFAULT_CONFIG_PATH` 주석에
> "0804 실외에서 매 업로드가 조용히 실패한 원인이 이 경로"라고 적혀 있다.
> 저장소 루트 `config.yaml`(주행 튜닝값)을 가리키고 있었고 그 파일엔 upload 절이
> 없어 `ConfigError`만 났다. 지금은 `comms.yaml`이 기본이고 runtime이 config를
> 직접 넘긴다.

### 2.6 로봇 측 도구·테스트

- `jetson_main/tests/` 25개 파스트: FSM, ego 연결성, 코스트맵, 횡단보도, 정지 분석,
  IMU, 터치, 업로더, **가짜 브로커 E2E**(`fake_broker.py` + `test_broker_e2e.py`)
- `jetson_main/scripts/`: `drive.sh`(주행), `preflight.sh`(사전 점검),
  `deploy.sh`, `imu_calibrate.py`, `analyze_run.py`, `web_test.py`
- `jetson_main/docs/`: `ALGORITHMS.md`, `HARDWARE.md`(배선), `IMU-ZERO-REFERENCE.md`,
  `INGEST-RECEIPT.md`, `MISSION-COMPLETION-CONTRACT-REVIEW.md`

---

## 3. MQTT 계약 v2

정본은 `web/docs/final-mqtt-topic-schema-v2.md`이고 AsyncAPI로도 있다
(`web/docs/asyncapi-mqtt-v2.yaml`). 서버·로봇 양쪽이 이 문서를 참조한다고 코드 주석에
명시돼 있다.

### 3.1 topic 구조

```
tbs/v2/robots/{mqtt_id}/{relative_path}
```

`web/backend/.../robot/mqtt/topic/MqttTopicParser.java`가 파싱한다.
- 세그먼트 5개 이상, `[0]=tbs`, `[2]=robots` 아니면 `MQTT_TOPIC_INVALID`
- `[1]`이 `v2`가 아니면 `MQTT_SCHEMA_VERSION_UNSUPPORTED` (버전 오류를 형식 오류와
  구분한다)
- `mqtt_id`는 `[A-Za-z0-9][A-Za-z0-9._-]{0,127}` 정규식 검증
- 발행 시 문자열 조립도 같은 클래스의 `format()`만 쓴다

### 3.2 topic 10종과 전송 속성

`MqttTopicType.java`(서버)와 `comms/topics.py`(로봇)가 **동일한 표**를 갖는다.

| topic | 방향 | QoS | retain |
|---|---|---:|---|
| `presence` | 로봇→서버 | 1 | ✅ |
| `state` | 로봇→서버 | 1 | ✅ |
| `telemetry` | 로봇→서버 | 0 | ❌ |
| `events/incident` | 로봇→서버 | 1 | ❌ |
| `events/battery` | 로봇→서버 | 1 | ❌ |
| `events/safety` | 로봇→서버 | 1 | ❌ |
| `events/mission` | 로봇→서버 | 1 | ❌ |
| `commands/result` | 로봇→서버 | 1 | ❌ |
| `commands/request` | 서버→로봇 | 1 | ❌ |
| `ingest/receipt` | 서버→로봇 | 1 | ❌ |

retain은 presence/state만이다 — 관제 웹이 새로 붙었을 때 "지금 상태"를 즉시 알아야
하는 두 개다. telemetry는 QoS 0 / retain 없음으로 흘려보낸다.

명령 6종 (`CommandRequestPayload.CommandType`): `START_MISSION`, `PAUSE_MISSION`,
`RESUME_MISSION`, `COMPLETE_MISSION`, `EMERGENCY_STOP`, `CLEAR_EMERGENCY_STOP`.
미션 상태 6종 (`MissionState`): `READY`, `RUNNING`, `PAUSED`, `COMPLETED`,
`CANCELLED`, `FAILED`.

### 3.3 계약 테스트 자산

- `test/mqtt_contract/test_mqtt_v2_contract.py` + `test/fixtures/mqtt/v2/`
  (valid/invalid fixture + `manifest.json`)
- `web/tools/mock-robot/`: `mock_robot.py`, `publisher.py`, Dockerfile,
  `tests/test_mock_robot_mission.py` — 로봇 없이 미션 전체를 돌리는 시뮬레이터
- `test/통신테스트/`: 외부 팀원 PC에서 실행하는 TLS 접속 테스트 패키지
  (`mqtt_tls_test.py`, CA 인증서, zip 배포본, `서버_담당자_준비.md`)

---

## 4. Backend — `web/backend`

Spring Boot 3.5.16 / Java 21. 패키지가 **도메인별 수직 분할**이다:
`api`, `audit`, `auth`, `common`, `config`, `defect`, `health`, `llm`, `report`,
`robot`, `security`, `statistics`, `storage`.

### 4.1 MQTT 수신 파이프라인 — 6단 방어

`web/backend/.../robot/mqtt/`. 이 부분이 백엔드에서 가장 설명할 게 많은 곳이다.

```
Mosquitto
  → MqttSubscriber            @ServiceActivator(inputChannel="mqttInboundChannel")
  → ApplicationEvent 발행     (MqttInboundMessage)
  → MqttInboundMessageDispatcher  @EventListener
       → MqttMessageParser          ① topic 파싱 ② QoS 일치 ③ 역직렬화
                                    ④ schema_version ⑤ Bean Validation ⑥ mqtt_id 일치
       → MqttInboundFreshnessGuard  ⑦ 신선도
       → topic별 Handler            ⑧ (핸들러 내부) replay 방어 → 적재
```

**① Spring Integration → ApplicationEvent 변환** (`client/MqttSubscriber.java`)
MQTT 수신을 Spring 이벤트로 바꿔 흘린다. 그래서 도메인 서비스가 MQTT 라이브러리를
알 필요가 없다. `log-payload`가 false면 payload 길이만 남긴다.

**② 핸들러 등록을 기동 시점에 검증** (`handler/MqttInboundMessageDispatcher.java`)
생성자에서 `List<MqttTopicMessageHandler>`를 받아 `EnumMap`으로 등록하면서
- 아웃바운드 topic을 인바운드 핸들러로 등록하면 → 기동 실패
- 중복 등록 → 기동 실패
- 로봇→서버 topic 8종 중 **하나라도 핸들러가 없으면** → 기동 실패

즉 topic을 새로 추가하고 핸들러를 잊으면 배포가 아니라 기동에서 막힌다.

**③ 파서의 엄격 모드** (`parser/MqttMessageParser.java`)
ObjectMapper를 복사해 `SNAKE_CASE` + `FAIL_ON_UNKNOWN_PROPERTIES` +
`FAIL_ON_TRAILING_TOKENS`를 켠다. 계약에 없는 필드가 오면 조용히 무시하지 않고
거부한다. QoS가 계약값과 다르면 `MQTT_QOS_MISMATCH`, topic의 `mqtt_id`와 payload의
`mqtt_id`가 다르면 `MQTT_ID_MISMATCH`다 (다른 로봇 사칭 차단).

**④ 신선도 게이트** (`parser/MqttInboundFreshnessGuard.java`)
topic별 기준시각으로부터 서버 수신까지 허용 지연을 다르게 둔다
(`application.yml` 기본값):

| 종류 | 기준 시각 | 최대 지연 |
|---|---|---|
| telemetry | `sampled_at` | 60s |
| state | `updated_at` | 300s |
| event | `occurred_at` | payload의 `ttl_sec` |
| command result | `completed_at` 또는 `received_at` | 60s |
| (공통) 미래 시각 허용 | — | 5s |

event는 **서버가 정한 상수가 아니라 payload의 TTL**을 쓴다. 거부할 때마다
`gilbom.mqtt.inbound.rejected{topic,reason}` 카운터를 올리므로 Prometheus에서
사유별로 보인다.

**⑤ Replay 방어** (`replay/MqttReplayProtectionService.java`, 617줄)
`(robot_id, boot_id, sequence)`를 DB에서 잠그고 클레임한다.
- boot session이 없는데 `sequence != 1` → 거부(`new_boot_sequence`)
- session이 `ONLINE`이 아님 → 거부(`closed_boot`)
- `sequence <= last_seen_sequence` → 거부(`non_increasing_sequence`)
- 같은 sequence에 **다른 payload** → `MQTT_SEQUENCE_CONFLICT`
- 같은 sequence에 **같은 payload** → 중복으로 판정해 재처리하지 않음(멱등)
- `boot_id`가 다른 로봇에 이미 할당돼 있으면 → `MQTT_ID_MISMATCH`

`V17__enforce_mqtt_sequence_starts_at_one.sql`이 DB 차원에서도 이를 강제한다.
재부팅하면 `boot_id`가 새로 생기고 sequence가 1부터 다시 시작하는 구조다.

**⑥ 아웃바운드**: `client/MqttPublisher.java` / `MqttV2Publisher.java`,
재연결 감시 `MqttReconnectWatchdog.java`, 클라이언트 수 상한
`config/BoundedMqttv5ClientManager.java`. TLS/mTLS는 전략 패턴이다
(`strategy/TlsMqttSecurityStrategy`, `MtlsMqttSecurityStrategy`,
`factory/MqttSecurityStrategyFactory`) — `application.yml`의
`mqtt.now-mqtt-security-strategy: tls`로 고른다.

### 4.2 결함 적재 — 트랜잭션 하나로 병합까지

`web/backend/.../defect/IncidentEventIngestionService.java`. 발표에서 가장 "설계했다"고
말할 수 있는 서비스다. `@Transactional` 하나 안에서 순서대로:

1. `RobotEventIngestionService.ingest()` — 이벤트 원문 적재.
   이미 있던 이벤트면(`!inserted`) 기존 `defectId`만 돌려주고 끝낸다 → **멱등**
2. **컨텍스트 해석** — `missionId`가 있으면 미션의 patrol area를 쓰고, payload의
   `zone_id`와 다르면 `MQTT_EVENT_CONFLICT`. 없으면 `zone_id`로 ACTIVE 구역을 찾는다
3. **이미지 연결** — `storage.robot_image`를 `FOR UPDATE`로 잡고 `AVAILABLE`이며
   같은 로봇의 것인지 확인 후 `event_id`를 붙인다. 다른 이벤트에 이미 붙어 있으면
   충돌. 이미지가 아직 없으면 `null`로 두고 계속 간다
   (`V42__accept_missing_incident_images.sql`)
4. **위치 검증** — GPS가 유효하지 않으면 `INVALID_GPS`, 순찰 구역을 못 찾으면
   `PATROL_AREA_UNRESOLVED`로 **`defect.incident_ingestion_issue`에 남기고** 결함은
   만들지 않는다. 버리지 않고 "검토 필요"로 격리하는 구조다
5. **병합 판정** — PostGIS `ST_DWithin(geography, 2.0m)`로 같은
   `defect_type`+`block_type`+구역의 기존 결함을 찾아 가장 가까운 것 1건을
   `FOR UPDATE`로 잡는다
6. 없으면 신규 결함 생성, 있으면 관측만 추가하고 집계 갱신
7. SSE 이벤트 발행

**병합 SQL의 주석이 곧 설계 근거다** (실제 코드에 이렇게 적혀 있다):
- `workflow_status <> 'RESOLVED'` — 수리가 끝난 자리에서 다시 탐지되면 같은 결함이
  아니라 **새 결함**이다. 옛 결함에 관측만 붙이면 "수리했는데 또 났다"는 사실이
  관측 횟수 뒤로 숨는다
- `FALSE_POSITIVE`·`REJECTED`는 **일부러 제외하지 않았다** — 오탐은 순찰마다 다시
  잡히므로 빼면 같은 오탐이 매번 새 결함으로 쌓여 목록이 잠긴다

**결함 코드**는 `INC-YYYYMMDD-NNNNNN`이고 날짜는 `Asia/Seoul` 기준이다.
`defect.incident_code_counter`에 `INSERT ... ON CONFLICT DO UPDATE ... WHERE
last_value < 999999 RETURNING`으로 일자별 시퀀스를 원자적으로 뽑는다.

**위험도**는 `DefectRiskAssessment.calculate(damageRatio, calculatedAt)`가 정하고
`risk_level`, `risk_score`, `risk_rule_version`, `risk_calculated_at`을 함께 저장한다
(룰 버전을 남기므로 나중에 기준이 바뀌어도 과거 판정을 설명할 수 있다).
`confidence`가 없거나 `< 0.7`이면 `review_required = true`다.

**SSE 발행 값은 payload가 아니라 저장된 행에서 다시 읽는다.** `risk_level`처럼 서버가
정하는 값이 있어 payload만으로는 실제 상태를 알 수 없기 때문이라고 주석에 근거가
있다. 그리고 `DefectRealtimeEventListener`가 `AFTER_COMMIT`에 받으므로 롤백되면
스트림으로 나가지 않는다.

**결함 행정 상태**(`defect/DefectWorkflow.java`):
`NEW`, `ACCEPTED`, `IN_PROGRESS`, `RESOLVED`, `FALSE_POSITIVE`, `REJECTED`.
권장 순서는 문서로 남기되 **단계를 강제하지 않는다** — 접수 없이 바로 고쳐 완료하는
건, 완료 후 재작업하는 건이 현장에 있어서 강제하면 담당자가 상태를 실제와 다르게
남기거나 아예 갱신하지 않게 된다는 것이 주석의 근거다. `NEW`로 되돌리는 것만 막는다
(시스템이 매기는 값이라 사람이 되돌리면 관측 이력과 어긋난다). `RESOLVED`는
`resolution` 필수다.

### 4.3 로봇·미션·명령 — `robot/`

| 하위 패키지 | 내용 |
|---|---|
| `api/` | 8개 컨트롤러 (조회/등록/명령/미션/순찰카탈로그/자격증명/텔레메트리 시계열·통계) |
| `application/` | `RobotQueryService`, `RobotRegistrationService`, **`RobotScopeService`** |
| `command/` | 생성·발행·결과수신·조회·타임아웃 (10개 파일) |
| `credential/` | MQTT 자격증명 발급·프로비저닝·레지스트리 드리프트 감시 (8개) |
| `mission/` | 미션 관리·진행·자동완료·이벤트 적재, 순찰 구역/경로 관리 (10개) |
| `status/`, `telemetry/`, `event/` | 상태·오프라인 판정, 텔레메트리 적재·시계열·통계 |
| `realtime/` | SSE |
| `data/` | 계약 DTO 55개 (`mqtt/v2/inbound`, `mqtt/v2/outbound`, `api/request`, `api/response`) |

**명령 상태**(`RobotCommandStatus`): `CREATED`, `SENT`, `ACCEPTED`, `COMPLETED`,
`FAILED`, `REJECTED`, `EXPIRED`, `PUBLISH_FAILED`. 앞 3개가 비종결이고
`isTerminal()`이 switch로 전수 열거돼 있어 상태를 추가하면 컴파일이 깨진다.
`PUBLISH_FAILED`를 따로 둔 것이 포인트다 — "로봇이 거부함(REJECTED)"과 "브로커에
못 보냄"은 운영상 완전히 다른 사건이다.

**명령 생성의 멱등성**(`RobotCommandCreationService`): `Idempotency-Key` 헤더를 받아
`request_control.idempotency_request`에
`(principal_key_hash, idempotency_key_hash)` unique로 `ON CONFLICT DO NOTHING`
클레임한다. 지문은 `method + path + commandType + parameters`의 SHA-256이므로 **같은
키로 다른 내용**을 보내면 걸러진다. lease 1분, 만료 24시간이다.

**미션 API**(`MissionController`): 목록/생성/조회/수정/삭제 +
`/{id}/trajectory`(GPS 궤적), `/{id}/map`, `/{id}/defects`, `/{id}/history`.
권한이 메서드마다 `@PreAuthorize`로 붙는다. 결함 조회는
`hasAuthority('MISSION_VIEW') and hasAuthority('DEFECT_STATUS_VIEW')`로 **둘 다**
요구한다.

**오프라인 판정**(`status/RobotOfflineDetectionService` + `RobotOfflineDetectionJob`):
state가 30초마다 오므로 3주기(90초) 동안 아무 메시지도 없으면 OFFLINE으로 본다
(`gilbom.robot.presence.offline-timeout: 90s`, 스캔 30초). 조회 SQL이
`FOR UPDATE OF state, session SKIP LOCKED`라서 여러 인스턴스가 동시에 돌아도 같은
로봇을 두 번 처리하지 않는다. 사유는 `HEARTBEAT_TIMEOUT`으로 남는다.

**자격증명 발급**(`credential/MqttCredentialIssuanceService`): MQTT 비밀번호를 발급하며
동시에 **이미지 업로드용 API Key도 함께 발급**한다
(`RobotImageApiKeyIssuanceService`). 발급·활성·폐기 전 과정이 감사 로그로 남는다
(`AUDIT_FIELDS` 12개 필드). `PasswordFileMqttCredentialProvisioner`가
`mosquitto_passwd`를 호출해 브로커 password 파일에 반영하고,
`MqttCredentialRegistryDriftJob`이 **DB와 브로커 파일이 어긋났는지** 주기적으로
확인한다.

### 4.4 보고서와 LLM — `report/`, `llm/`

**상태**: `DRAFT → IN_REVIEW → APPROVED / REJECTED` (`ReportStatus`).
API: 목록/생성/조회/수정/삭제, `submit`, `approve`, `reject`, `versions`,
`versions/{versionNo}`. 권한은 `REPORT_VIEW / CREATE / UPDATE / APPROVE`로 갈린다
— **작성자와 승인자를 권한 수준에서 분리**한 구조다.

**승인 시 PDF 생성**(`report/pdf/ApprovedReportPdfService`):
렌더링 → SHA-256 → 저장 키를
`reports/{reportId}/v{versionNo}/{sha256}.pdf`로 만든다. 즉 **버전과 내용 해시가 키에
박히므로** 승인된 문서가 나중에 바뀌면 다른 파일이 된다. 그리고
`registerRollbackCleanup()`으로 트랜잭션 롤백 시 방금 쓴 파일을 지운다 — DB에 없는
PDF가 남지 않게 한다. 한글 렌더러는 `KoreanApprovedReportPdfRenderer`다.

**LLM 파이프라인**(`report/generation/ReportLlmGenerationPipeline`)은 실패를
전부 **템플릿 폴백**으로 흡수한다. 폴백 사유가 enum으로 열거돼 있고
(`ReportGenerationFallbackReason`) DB에도 남는다
(`V34__persist_report_generation_fallback.sql`, `V45__record_llm_transport_status.sql`).

순서: 입력 준비 → `llmClient.isAvailable()` 확인(아니면 `POLICY_DISABLED`) →
요청 생성(실패 시 `IMAGE_EVIDENCE_INVALID`) → **동시성 permit 획득**(없으면
`CONCURRENCY_LIMIT`) → **quota 예약**(초과 시 `USER_DAILY_RATE_LIMIT` /
`DAILY_BUDGET_EXCEEDED`) → 호출 → 결과 검증(실패 시 `LLM_RESPONSE_INVALID`) →
quota 성공 확정 → 렌더링.

재시도는 최대 1회이고 두 가지 특수 처리가 있다:
- **이미지 payload가 거부되면** 이미지를 떼고 텍스트만으로 재시도
- 그 외 재시도 가능 실패는 `LlmRetryPolicy`가 판단

**정책 게이트**(`llm/LlmPolicyGate`)가 통과해야 실제 클라이언트가 만들어지고,
아니면 `DisabledLlmClient`가 주입된다. 검사 항목:
- provider가 `GMS`인지
- base URL이 정확히 `https://gms.ssafy.io/gmsapi/api.openai.com/v1`인지
  (scheme·host·port 443·userInfo/query/fragment 없음·path 일치까지 확인)
- 모델이 허용 목록(`gpt-5.4-mini`, `gpt-5.4`)인지
- prompt version이 허용 목록(`report-v1/v2/v3`)인지
- **학습 제외 승인 / 데이터 보존 승인 / 정책 승인 / 아웃바운드 allowlist 적용**
  플래그가 모두 true인지
- 일일 요청 한도·예산이 양수인지, timeout 상한(connect 10s / read 60s),
  출력 토큰 ≤ 1500, 요청 바이트 32KB~1MB, 동시 요청 1~20

**GMS 클라이언트**(`llm/GmsLlmClient`)의 방어:
- 응답을 **JSON Schema strict**로 강제한다
  (`resources/llm/report-structured-output.schema.json`, 스키마명 `gilbom_report_v1`)
- 요청이 `maxRequestBytes`를 넘으면 ① 이미지 1장을 **JPEG 재압축으로 맞춰보고**
  (`LlmJpegRequestFitter`) ② 그래도 크면 **이미지를 떼고** ③ 그래도 크면
  `REQUEST_TOO_LARGE`
- 응답 모델명이 요청 모델 또는 `모델-YYYY-MM-DD` 날짜 스냅샷인지 확인
- 출력 10만 자 초과 거부, JSON 객체 아니면 거부
- **모든 `toString()`이 `[REDACTED]`**다 — prompt·이미지·API Key·응답 본문이 로그나
  예외 메시지에 새지 않는다. 예외 cause도 보존하지 않는다는 주석이 붙어 있다
- `LlmOutboundInputGuard`가 전송 전 입력을 검사한다

### 4.5 이미지 저장 — `storage/`

**업로드**(`storage/api/ImageUploadController`, `POST /api/v1/images`)
- 인증은 세션이 아니라 `X-API-Key`. `RobotApiKeyAuthenticationFilter`가
  `shouldNotFilter`로 **이 경로 POST만** 걸러 처리한다
- 키는 SHA-256 hex로 조회한다. 서버가 만든 32바이트 난수라 사전 공격 대상이 아니므로
  SHA-256으로 충분하고, 비교는 unique index를 통해 DB가 한다는 근거가 주석에 있다
- **실패해도 응답을 쓰지 않고** 인증을 세우지 않은 채 넘긴다 → 체인의 entry point가
  401을 쓰므로 "키 없음"과 "키 틀림"이 같은 응답이 되어 키 존재 여부를 알아낼 수 없다
- 201(신규) / 200(멱등 재업로드)로 응답. 자연 키는 `(event_id, robot_id)`
- 크기 상한: 파일 500KB, 요청 1MB (`application.yml`의 multipart + Tomcat 양쪽)

**조회**(`storage/url/ImageUrlSigner`, `GET /api/v1/images/{id}/content?token=`)
- S3 presigned URL과 같은 역할을 로컬 디스크 단계에서 구현한 것이다
- 토큰은 `<만료 epoch초>.<base64url HMAC-SHA256>`이고 **서명 대상에 imageId를
  포함**하므로 다른 이미지의 토큰을 가져다 쓰면 실패한다
- 서명 키가 없으면 기동은 막지 않고(로컬/테스트) 임시 키 생성 + 경고를 남긴다.
  운영에서는 `GILBOM_STORAGE_URL_SIGNING_KEY_BASE64_FILE`을 Docker secret으로 준다
- 프론트는 저장소가 무엇이든 받은 URL을 `<img src>`에 그대로 넣기만 하면 된다

**전용 보안 체인**(`storage/auth/ImageApiSecurityConfiguration`)
이미지 API는 **관리자 체인과 완전히 다른 SecurityFilterChain**을 쓴다.
`securityMatcher("/api/v1/images/**")` + `@Order(HIGHEST_PRECEDENCE + 10)`으로 이
경로만 먼저 가져간다. 근거가 클래스 docstring에 있다: 관리자 체인은 세션 쿠키와
CSRF를 전제하지만 이미지 API는 로봇 API Key로 인증하고 CSRF 대상이 아니다.
**성질이 다른 두 인증을 한 체인에 넣으면 서로의 예외 조건이 되므로** 공용 설정을
고치는 대신 체인을 나눴다.

체인 구성:
- `csrf().disable()` — 로봇 클라이언트와 `<img>` 요청은 브라우저 폼이 아니다
- `anonymous().disable()`, `anyRequest().authenticated()`
- `SessionCreationPolicy.STATELESS` + `NullSecurityContextRepository` —
  인증을 요청마다 자격증명에서 새로 만들고 세션에 저장하지 않는다
- **`NullAuthenticatedSessionStrategy`** — 아래 참조
- 인증 필터 2개를 `UsernamePasswordAuthenticationFilter` 앞에 끼운다:
  `RobotApiKeyAuthenticationFilter`(업로드), `SignedImageUrlAuthenticationFilter`(조회)

> **세션 회전 차단 (커밋 `ee49989`, 2026-08-12)**
> Spring Security는 인증이 성립하면 세션 고정 공격 방어로 **세션 ID를 회전**시키는
> 후처리를 돌린다. 그런데 이미지 체인의 "인증"은 로그인이 아니라 서명 URL이나 API
> Key 검증이다. 브라우저 `<img>` 요청에는 관리자 세션 쿠키가 자동으로 실려 오므로,
> 그 후처리가 **관리자의 세션 ID를 회전시켜 버리는** 문제가 있었다.
> `sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy())`로 이
> 후처리를 꺼서, 이미지 인증이 기존 로그인 세션에 아무 영향도 주지 않게 했다.
>
> 회귀 테스트가 세 가지를 함께 검증한다
> (`ImageApiSecurityChainTest.signedImageAuthenticationDoesNotRotateAnExistingLoginSession`):
> ① 세션 ID가 그대로인가 ② 세션에 저장된 로그인 인증정보가 덮이지 않았는가
> ③ 응답에 `Set-Cookie`가 아예 없는가.

**추상화**: `ObjectStorageClient` 인터페이스 + `LocalFileObjectStorageClient` +
`ObjectStorageRegistry`. 지금은 로컬 볼륨이지만 S3로 갈 자리가 인터페이스로 열려 있다.

### 4.6 인증·인가·보안 — `auth/`, `security/`

**인증 상태 기계**(`auth/model/AuthenticationState`):
`ANONYMOUS → PASSWORD_CHANGE_REQUIRED → MFA_ENROLLMENT_REQUIRED → MFA_REQUIRED →
AUTHENTICATED`. "비밀번호 검증 성공은 인증 완료가 아니며 `AUTHENTICATED`만 보호 API에
접근할 수 있다"가 enum docstring에 명시돼 있다. 프론트 라우터가 같은 상태값으로
화면을 가른다.

**Spring Security 설정**(`security/config/SecurityConfig`)
- formLogin·httpBasic·logout·requestCache **전부 비활성화** (SPA + JSON API)
- CSRF: `HttpSessionCsrfTokenRepository` + 헤더 `X-CSRF-TOKEN` +
  `SafeXorCsrfTokenRequestHandler`(BREACH 완화 마스킹 유지)
- 인가는 **allowlist 방식**이다. health/csrf-token/session-state와 공개 인증 POST
  9개만 `permitAll`이고 `anyRequest().authenticated()`다 — "명시하지 않은 API는 새
  기능이 추가돼도 자동 공개되지 않는다"는 주석 그대로다. 공개 인증 API도 **CSRF
  검사는 통과해야** 한다
- 401을 내기 직전에 원인을 3값으로 로그에 남긴다: `sessionCookie`(쿠키를 아예 안
  보냄) / `cookieValid`(보냈는데 서버에 세션이 없음) / `liveSession`(세션은 있는데
  인증 컨텍스트가 없음). **세션 id는 남기지 않고** MDC `requestId`로 특정한다
- CSRF 실패와 권한 부족을 감사 로그에 각각 기록한다
  (`recordCsrfFailed` / `recordAccessDenied`)
- `AuthenticationSessionEnforcementFilter`를 `SecurityContextHolderFilter` 뒤에 끼워
  세션 수명 정책을 매 요청 검사한다
- CORS는 기본 **비활성**이다 (Nginx same-origin proxy가 정본). 켤 때만 정확한 Origin
  목록을 준다
- `UserDetailsService`를 일부러 던지는 구현으로 덮어 **Spring Boot가 임의의 기본
  사용자/비밀번호를 만들지 못하게** 막았다

**세션 정책**(`application.yml`): idle 30분, absolute 8시간, MFA challenge 5분,
**동시 세션 1개**, challenge 실패 5회. 저장은 Spring Session JDBC
(`session_store.spring_session`)이며 스키마는 Hibernate가 아니라 Flyway가 만든다
(`initialize-schema: never`). 정리 잡은 1분 간격 1000건 배치.

**MFA**: TOTP (`auth/mfa/TotpService`, `Base32Codec`) + 복구 코드
(`RecoveryCodeService`). **비밀키는 AES-GCM으로 암호화**해 저장하고
(`mfa/crypto/AesGcmTotpSecretEncryptionService`) 키는 버전 있는 keyring
(`"version=base64Key;version=base64Key"`)으로 주입한다 — 키 회전이 가능한 구조다.
관리자 MFA 초기화 경로가 별도로 있다(`AdminMfaResetService`).

**비밀번호**: Argon2id (`V5__enforce_argon2id_and_password_change_state.sql`,
`auth/password/PasswordHashingService`).

**Rate limit**(`auth/ratelimit/`, 21개 파일): DB 영속형이다
(`PersistentRateLimitRepository`) — 인스턴스가 재시작해도 카운터가 살아 있다.
**IP 원문을 저장하지 않고 HMAC으로 버킷을 만든다**(`RateLimitSubjectHasher`, 키 버전
있음). 기본값:

| 대상 | 한도 |
|---|---|
| CSRF 토큰 발급 | 60회 / 1분 (유효 세션 없는 요청만 소비) |
| 이메일 중복 확인 | 20회 / 1분 |
| 회원가입 | 5회 / 1시간 |
| IP 단위 인증 요청 | 20회 / 1분 |
| 비밀번호 실패 | 5회 / 15분 → 15분 잠금 |
| MFA 실패 | 5회 / 15분 |

**RBAC**: 역할·권한·사용자별 override·로봇 범위 배정까지 있다
(`V13__complete_rbac_scope_schema.sql`, `AdminController`의 `/roles`,
`/users/{id}/permission-overrides`, `/users/{id}/robot-assignments`).
`RobotScopeService`·`DefectScopeService`가 **행 단위 가시성**을 판정하고, SSE 발행도
`scopeService.canView(userId, robotId)`를 통과한 구독자에게만 보낸다.
**권한이 바뀌면 세션을 무효화**한다
(`V14__revoke_sessions_on_authorization_change.sql`,
`V43__audit_authorization_session_revocation.sql`).

**감사 로그**(`audit/`, 16개 파일): `AuditAction`/`AuditActorType`/`AuditTargetType`/
`AuditResult` + before/after 스냅샷(`AuditSnapshot`). `BestEffortAuditLogWriter`가
있어 **감사 기록 실패가 본 기능을 막지 않는다.** 감사 테이블은 append-only이고
검증 SQL이 따로 있다(`infra/postgres/verify-append-only.sql`).
조회 API는 `GET /api/v1/audit-logs`.

**공통 웹 방어**(`common/web/`): `RequestBodySizeLimitFilter`(1MB),
`RequestIdFilter`(X-Request-Id를 MDC와 응답 헤더에 넣어 Nginx 로그와 이어 붙인다),
`GlobalExceptionHandler`, `security/web/SecurityHeadersFilter`.
`common/concurrency/ExpectedVersionGuard`로 낙관적 잠금을 한다.

### 4.7 실시간 — SSE

세 스트림 모두 `/api/v1/stream` 아래에 있다.

| 엔드포인트 | 권한 | 필터 |
|---|---|---|
| `GET /api/v1/stream/robots` | `ROBOT_STATUS_VIEW` | — |
| `GET /api/v1/stream/defects` | `DEFECT_STATUS_VIEW` | `?patrolAreaId` |
| `GET /api/v1/stream/reports` | `REPORT_VIEW` | `?reportId` |

공통 규칙 (`robot/realtime/RobotSseService` 등):
- **세션이 없으면 스트림을 열지 않는다.** 구독 수명은 세션 수명을 넘지 못한다
- emitter timeout 30분, 클라이언트 재연결 힌트 3초
- 연결 즉시 `CONNECTED` 이벤트를 보낸다
- 발행 때마다 세션이 아직 유효한지 확인하고, 아니면 emitter를 닫는다
- 스코프 검사를 통과한 구독자에게만 보낸다
- 리스너가 `AFTER_COMMIT`에 받으므로 롤백된 변경은 나가지 않는다
- 보고서 스트림은 사용자당 3개 / 전체 200개 연결 상한이 있다
  (`gilbom.report.sse.max-connections-*`)

이벤트 타입(프론트 `useRobotRealtime.ts`가 화이트리스트로 검증):
`ROBOT_CONNECTIVITY_CHANGED`, `ROBOT_STATE_UPDATED`, `ROBOT_TELEMETRY_UPDATED`,
`COMMAND_STATUS_CHANGED`. 결함은 `defect.created` / `defect.updated`.

### 4.8 통계 — `statistics/`

`GET /api/v1/statistics/{by-status | by-defect-type | by-period | by-area}`,
권한 `DEFECT_STATUS_VIEW`. 특징 두 가지:
- `StatisticsQuery.requireOnlyParameters()`로 **허용되지 않은 쿼리 파라미터가 오면
  거부**한다 (오타를 조용히 무시하지 않는다)
- 컨트롤러 docstring에 "미구현 집계는 빈 성공 응답으로 노출하지 않는다"가 원칙으로
  적혀 있다

인덱스는 `V26__add_statistics_query_indexes.sql`로 따로 넣었다.
로봇 텔레메트리 통계는 별도 API다
(`/api/v1/robots/{id}/telemetry-statistics`, `/telemetry-series`,
계약 문서 `web/docs/robot-telemetry-statistics-contract.md`).

### 4.9 헬스체크와 스케줄러

**Health**(`health/`): `FlywayReadinessHealthIndicator`,
`MqttConnectionHealthIndicator`, `StorageReadinessHealthIndicator`.
그룹 설계가 명확하다 (`application.yml` 주석):
- `liveness`는 `livenessState`만 본다 — DB나 외부 장애로 정상 JVM을 재시작하는
  **restart storm을 만들지 않기 위해서**다
- `readiness`는 `readinessState, db, flywayReadiness, storageReadiness`.
  **LLM은 일부러 제외**한다(템플릿 폴백 대상이므로). **MQTT도 기본 제외**이고
  로봇 기능이 서비스 필수 조건인 배포에서만 환경변수로 붙인다
- `mqtt`는 별도 진단 그룹

`show-details: never`, `configprops.show-values: never`, `env.show-values: never` —
관리 엔드포인트로 설정값이 새지 않게 한다.

**주기 작업**: `RobotOfflineDetectionJob`, `RobotCommandTimeoutJob`,
`MissionAutoCompletionJob`, `AuthenticationSessionCleanupJob`, `RateLimitCleanupJob`,
`IdempotencyRequestCleanupJob`, `MqttCredentialRegistryDriftJob`.

**Secret 주입**(`config/SecretFileEnvironmentPostProcessor`): `*_FILE` 환경변수를
읽어 Spring property로 넣는다. 그래서 `.env`에 원문을 두지 않고 Docker secret 파일
경로만 준다.

---

## 5. 데이터베이스

**스키마 9개 + 이력 1개** (`README.md`의 DB dump 요약):
`audit`, `auth`, `defect`, `report`, `request_control`, `robot`, `session_store`,
`storage`, `telemetry`, `flyway_history`.
확장: `postgis`, `postgis_topology`, `postgis_tiger_geocoder`, `fuzzystrmatch`,
`pgcrypto`.

**역할 3개 분리** (`infra/postgres/init/20-create-application-roles.sh`,
`compose.yaml`):

| 역할 | 용도 |
|---|---|
| `gilbom_db_admin` | bootstrap·관리 전용. 애플리케이션에 전달하지 않는다 |
| `gilbom_flyway` | migration DDL·객체 소유 |
| `gilbom_app` | runtime DML만 (제한된 최소 권한) |

Flyway의 `placeholders.applicationRole`로 migration이 만든 객체에 runtime 계정의
최소 권한만 부여한다. `clean-disabled: true`, `validate-migration-naming: true`,
migration 이력은 업무 테이블과 분리된 `flyway_history` 스키마에 둔다.

**migration 45개의 성격**을 보면 무엇을 DB 차원에서 지켰는지가 보인다.
스키마 생성(`V1`,`V2`,`V11`,`V12`,`V22`) 외에 **불변식 강제**가 많다:
- `V9__enforce_session_lifecycle_policy`, `V10__enforce_authentication_defense_policy`
- `V17__enforce_mqtt_sequence_starts_at_one`
- `V25__enforce_single_active_robot_mission` — 로봇 하나에 활성 미션 하나
- `V27__validate_defect_and_command_constraints`, `V28__harden_incident_ingestion`
- `V30__harden_report_domain_invariants`
- `V29__limit_csrf_token_session_creation`
- `V38__soft_delete_terminal_missions`, `V35__allow_created_mission_deletion`

**검증 SQL**이 `infra/postgres/`에 10개 있다 — `verify-append-only.sql`,
`verify-rbac-scope.sql`, `verify-session-lifecycle.sql`,
`verify-authentication-defense.sql`, `verify-production-database.sql` 등. 배포 후
"정책이 실제로 걸려 있는지"를 SQL로 확인하는 자산이다.

산출물: `exec/3_DB_dump.sql`(PostgreSQL 16.9 plain dump, 41 테이블 / 16,336행),
`docs/길봄_ERD.sql`, `docs/길봄_DB_DDL.sql`, `docs/길봄_ERD_Image.png`.

---

## 6. Frontend — `web/frontend/gilbom`

React 19.2.8 + react-router 8.3.0 + recharts 3.10.1 + lucide-react.
**상태관리 라이브러리·데이터 페칭 라이브러리를 쓰지 않는다** — Context + hooks +
fetch 래퍼로 처리한다. TypeScript 6, Vite 8, ESLint 10.

### 6.1 구조

`src/features/{admin, audit, auth, command, defect, mission, platform, report, robot}`
로 **기능별 수직 분할**이다. 각 feature 안에 화면(`*Page.tsx`), 조각 컴포넌트,
`*Api.ts`, `*Types.ts`, 그리고 순수 로직 모듈(`*Policy.ts`, `*Presentation.ts`,
`*Eligibility.ts`)이 함께 있다. 순수 로직을 따로 뺀 이유는 그 파일들이 그대로
유닛 테스트 대상이기 때문이다(`test/` 22개 파일이 대부분 이 모듈들을 겨눈다).

### 6.2 라우팅과 권한 (`src/app/router.tsx`)

두 계층으로 가른다.
- **인증 진행 상태 가드** `RequireAuthenticationState` — `/login`은 `ANONYMOUS`만,
  `/auth/password-change`는 `PASSWORD_CHANGE_REQUIRED`만, `/auth/mfa`는
  `MFA_REQUIRED`만 들어간다. 백엔드 `AuthenticationState`와 값이 같다
- **권한 가드** `RequirePermission` — `/robots`는 `ROBOT_STATUS_VIEW`,
  `/missions`는 `MISSION_VIEW`, `/admin`은 `ADMIN_SETTINGS_MANAGE` …
  `/reports/:id/edit`는 `REPORT_VIEW`와 `REPORT_UPDATE`를 **중첩**해 요구한다

`features/auth/permissions.ts`가 권한 코드 16개를 상수로 두고
`ENFORCED_PERMISSION_CODES`로 관리한다. 주석에 근거가 있다: **서버 카탈로그에만 있고
실제 소비자가 없는 권한은 역할 편집 화면에 노출하지 않는다.**

### 6.3 API 클라이언트 (`src/shared/api/apiClient.ts`)

- HTTP status를 도메인 카테고리로 정규화한다:
  `NETWORK(0)`, `UNAUTHORIZED(401)`, `FORBIDDEN(403)`, `NOT_FOUND`, `CONFLICT(409)`,
  `VALIDATION(400/413/415/422)`, `UNAVAILABLE(429/502/503/504)`, `UNEXPECTED`
- `ApiError`가 `requestId`, `details`(필드 검증 오류), `retryAfterSeconds`,
  `csrfRejected`를 들고 다닌다 → 화면이 "왜 실패했는지"를 필드 단위로 표시할 수 있다
- POST/PUT/PATCH/DELETE에 `csrfTokenStore`의 토큰을 붙인다
- **CSRF 재시도 정책이 `never` 기본값**이다. 근거가 주석에 있다: CSRF 실패와 권한
  부족이 모두 403/`AUTH_FORBIDDEN`이므로 무조건 재시도하면 안 되고, **멱등 키나
  낙관적 잠금으로 중복 실행이 막힌 요청만** `replay-safe`로 opt-in한다
- 401은 `subscribeToUnauthorized`로 전역 리스너에 알려 로그인 화면으로 복구한다
  (`unauthorizedRecovery.ts`)

### 6.4 실시간 (`use*Realtime.ts` 4개)

`useRobotRealtime`, `useDefectRealtime`, `useMissionDefectRealtime`,
`useReportRealtime`. `EventSource('/api/v1/stream/...', {withCredentials: true})`로
붙고, **이벤트 타입 화이트리스트 + 필수 필드 타입 검사**를 통과한 payload만 받는다
(`parseRobotEvent`). SSE는 "다시 조회해라"는 신호로만 쓰고 실제 데이터는
`onSnapshotRequired` 콜백으로 REST 재조회한다 — 화면 상태가 이벤트 누락에
좌우되지 않는 구조다. 연결 상태(`connecting`/…)와 마지막 heartbeat 시각을 UI에
노출한다.

### 6.5 지도 (`src/lib/naverMaps.ts`, `*Map.tsx` 3종)

- Naver Maps 스크립트를 **앱 전체에서 한 번만** 주입한다(모듈 스코프 promise 캐시).
  주석에 실전 정보가 있다: 통합 콘솔에서 새로 발급된 Client ID는 `ncpClientId`가
  아니라 **`ncpKeyId`** 파라미터를 쓴다
- `RobotMap`(로봇 위치), `MissionMap`(궤적), `DefectMap`(결함 마커·클러스터),
  `PatrolAreaBoundaryEditor`(다각형 경계 편집), `PatrolAreaSelectionPreview`
- `VITE_NAVER_MAP_CLIENT_ID`는 빌드 시 번들에 들어가므로 바꾸면 프론트 이미지를
  다시 빌드해야 한다 (`README.md` 3.2)

### 6.6 보고서 화면의 안전 장치

`features/report/SafeReportText.ts`, `SafeReportDocument.ts` +
테스트 `safeReportText.test.ts`, `safeReportDocument.test.ts`.
**LLM이 생성한 텍스트를 화면에 넣기 전 정제하는 계층**을 따로 뒀다.

### 6.7 테스트

- 유닛 22개: `node --experimental-strip-types --test test/*.test.ts` — 별도 러너
  없이 Node 내장 테스트로 TS를 바로 돌린다
- E2E 18개(Playwright): 결함 목록/필터/검색/클러스터/편집/실시간지도/포커스줌,
  미션 시간·지도, 순찰구역 등록, 로봇 자격증명 관리
- **`*-live.spec.ts`는 별도 설정**(`playwright.live.config.ts`)이다 —
  `command-roundtrip-live`, `command-security-live`, `command-abnormal-live`,
  `mission-live`, `mission-time-live`. 실제 백엔드/브로커를 붙여 도는 시나리오를
  일반 CI와 분리했다

---

## 7. 인프라·운영 — `web/infra`, `web/compose.*.yaml`

### 7.1 Compose 8종

| 파일 | 용도 |
|---|---|
| `compose.yaml` | 로컬 (mqtt-init → mosquitto → db → backend → frontend) |
| `compose.build.yaml` | 이미지 빌드 전용 |
| `compose.prod.yaml` | 운영 기동 (빌드 없이 전달받은 tag 사용) |
| `compose.monitoring.yaml` | Prometheus·Alertmanager·Loki·Grafana·Alloy·Blackbox |
| `compose.jenkins.yaml` | CI 서버 |
| `compose.resilience.yaml` | 복원력 검증 |
| `compose.smoke.yaml` | 스모크 |
| `compose.rollback-drill.yaml` | 롤백 훈련 |

**컨테이너 하드닝이 전 서비스에 걸려 있다** (`compose.yaml`):
`read_only: true`, `cap_drop: [ALL]`, `security_opt: no-new-privileges:true`,
쓰기가 필요한 곳만 `tmpfs`(`noexec,nosuid,nodev` + size 제한),
`stop_grace_period`, 로그 로테이션(10MB × 3).

**기동 순서를 healthcheck로 강제**한다. 그리고 healthcheck가 "포트 열림"이 아니다:
- db: `pg_isready` **+ `SELECT PostGIS_Version()`** 까지 확인 (확장 초기화 확인)
- mosquitto: 실제 `mosquitto_sub`로 TLS·인증까지 통과해
  `$SYS/broker/version`을 1건 받아본다
- backend: `/actuator/health/readiness`
- frontend: `/healthz`

**Secret 9종은 전부 파일 주입**이다(`secrets:` 블록). `.env`에는 원문이 아니라
`*_SECRET_FILE` 경로만 쓴다: postgres admin/flyway/app, mqtt backend, LLM API key,
storage 서명 키, rate-limit HMAC 키, MFA TOTP keyring, 초기 관리자 비밀번호.

### 7.2 Nginx (`web/frontend/nginx/`)

파일이 목적별로 쪼개져 있다: `nginx.conf`, `server-common.conf`, `upstream.conf`,
`app-locations.conf`, `site-http.conf` / `site-https.conf` / `site-bootstrap.conf`,
`csp-http.conf` / `csp-https.conf`, `configure-runtime.sh`, `entrypoint.sh`,
`healthcheck.sh`.

`app-locations.conf`의 location 설계가 발표 포인트다.

**① 서명 이미지 경로에서 쿠키를 양방향으로 끊는다**
```nginx
location ~ "^/api/v1/images/[0-9a-fA-F-]{36}/content$" {
    proxy_set_header Cookie "";
    proxy_hide_header Set-Cookie;
```
주석의 근거: 브라우저 `<img>`는 same-origin 쿠키를 자동 첨부한다. Backend 이미지
체인이 STATELESS여도 Spring Session의 servlet filter는 SecurityFilterChain 바깥에
있어 **동시 이미지 응답이 새 session cookie를 내보낼 수 있고, 그게 관리자 쿠키를
덮으면 다음 API 요청이 401**이 된다. 그래서 edge에서 아예 끊는다.

이건 **두 겹 중 바깥쪽**이다. 안쪽은 Backend 이미지 체인의
`NullAuthenticatedSessionStrategy`로, 이미지 인증이 관리자 세션 ID를 회전시키지
못하게 막는다(§4.5). Nginx만 있으면 Backend 직접 접근 경로가 남고, Backend만 있으면
edge에서 새는 `Set-Cookie`를 못 막는다.

**② SSE 전용 location을 `/api/`보다 먼저 매칭**
```nginx
location ^~ /api/v1/stream/ {
    proxy_buffering off; proxy_request_buffering off; proxy_cache off;
    proxy_read_timeout 1h; proxy_send_timeout 1h;
    proxy_set_header Connection "";
```

**③ CSRF 토큰 발급에 `limit_req`** — burst 60 nodelay, 초과 시 429.
"공유 NAT의 정상적인 동시 새로고침은 burst로 수용하되 지속 flood는 거부"

**④ X-Forwarded-For를 이어붙이지 않는다** — `$remote_addr`로 덮어쓴다.
이 Nginx가 유일한 신뢰 edge이므로, 이어붙이면 **클라이언트가 IP rate-limit 버킷을
임의로 고를 수 있게** 된다는 근거가 주석에 있다.

**⑤ 보안 헤더는 edge에서 단일 값으로** — Backend가 보낸 CSP·X-Frame-Options 등을
`proxy_hide_header`로 지우고 Nginx 값을 쓴다. Backend 직접 접근 시에는 Backend 자체
헤더가 유지된다.

**⑥ 캐시 정책** — Vite content hash가 있는 `/assets/...-<hash>.<ext>`만 1년 캐시,
`/index.html`은 `expires -1`.

### 7.3 MQTT 브로커 (`web/infra/mosquitto/`)

프로파일이 3+1개다: `local/`(자체 CA 생성 + 인증서 + passwd),
`tls/`, `mtls/`, `test/`. 설명은 `PROFILES.md`, `README.md`에 있다.

`local/init-local-mqtt.sh`가 로컬 CA와 브로커 인증서를 만든다. 외부 로봇이 IP나
ngrok hostname으로 붙을 때를 위해 `MQTT_LAN_IP`, `MQTT_PUBLIC_HOSTNAME`을 **인증서
SAN에 넣는다.** `acl`로 topic 접근을 제한하고, `verify-credential-cutover.sh`로
자격증명 교체를 검증한다. 운영은 `run-production-mosquitto.sh`.

### 7.4 모니터링 (`web/infra/monitoring/`, `compose.monitoring.yaml`)

스택: Prometheus + Alertmanager + Loki + Grafana + Alloy(metrics/logs) + Blackbox.

`prometheus.yml`의 설계 근거:
- Backend는 포트를 publish하지 않고 Nginx도 `/actuator`를 프록시하지 않는다 →
  `backend:8080/actuator/prometheus`는 **internal app network 안에서만** 닿는다
- Host/container metric은 Alloy가 remote_write로 밀어 넣고, Prometheus는 수집기
  생존만 본다
- `blackbox-https`가 `https://i15d101.p.ssafy.io/healthz`를 **바깥에서 보듯** 찌른다
  → 인증서 만료일도 여기서 나온다

네트워크를 목적별로 쪼갰다: `monitoring`, `app`, `probe-egress`, `alert-egress`,
`loopback-publish`.

알림 규칙 4묶음: `rules/backend.yml`, `container.yml`, `host.yml`, `operations.yml`.
Grafana 대시보드 4개(`backend`, `containers`, `host`, `operations`)가
provisioning으로 자동 등록된다.

**백업 알림의 등급 설계**(`rules/operations.yml`, alert 10종). 백업 실패를 두 방향에서
잡는다 — 실행했는데 실패(`BackupFailed`)와 아예 실행되지 않음(`BackupStale`,
`BackupMissing`). 여기서 **TOTP keyring만 예외 처리**한 것이 설명할 지점이다.

- `BackupStale`(30시간, critical)의 대상에서 `backup="totp"`를 뺐다.
  keyring 반출은 timer가 없다 — **자동으로 돌리면 평문 키가 주기적으로 서버에
  나타나므로** 처음부터 사람이 손으로 반출하도록 설계했다
  (`infra/scripts/export-totp-keyring.sh`). 자동 백업과 같은 30시간 기준을 걸면
  손으로 안 돌린 이틀째마다 CRITICAL이 떠서 **진짜 실패가 그 소음에 묻힌다**
- 대신 `TotpKeyringExportStale`(7일, **warning**)을 새로 뒀다. 방치는 막되 등급은
  낮춘 이유: keyring을 잃으면 `auth.mfa_factor`의 암호문은 복구 불가이고, DB를
  되살려도 사용자 전원이 MFA를 다시 등록해야 한다. keyring은 **DB 백업에 들어가지
  않으므로 이 반출본이 유일한 사본**이다
- `BackupNotExportedOffhost`(7일, warning) — 서버 밖 반출 여부를 따로 본다

즉 "알림을 끄지 않고, 등급과 기준을 실제 운영 절차에 맞춘" 사례다. 자동 절차와 수동
절차에 같은 임계값을 쓰면 알림 피로가 생긴다는 판단이 규칙 주석에 그대로 적혀 있다.

Micrometer 설정(`application.yml`)에도 근거가 있다: histogram bucket 대신
**percentile 직접 계산**(0.95, 0.99)을 쓴다 — 인스턴스가 하나뿐이라 집계할 일이
없어 bucket이 시계열을 endpoint 수만큼 늘리는 비용을 치를 이유가 없다는 것.

### 7.5 CI/CD (`web/Jenkinsfile*` 5개)

`Jenkinsfile`(CI), `Jenkinsfile.deploy`, `Jenkinsfile.production-auto`,
`Jenkinsfile.rollback`, `Jenkinsfile.rollback-drill`.

CI 특징:
- `pollSCM('H/5 * * * *')`, `disableConcurrentBuilds(abortPrevious: true)`,
  timeout 45분
- **CI는 gitignore된 로컬 `.env`에 의존하지 않는다.** MFA TOTP 암호화 경계를
  environment에 명시적으로 강제한다
- Checkout 전에 mosquitto local 디렉터리 소유권을 컨테이너로 고쳐 준다
  (`--network none --read-only --cap-drop ALL` + 필요한 cap 3개만, symlink 거부)
- shallow checkout을 검사해 거부한다 (full SHA 승격에 필요)

배포 규칙(`README.md` 3.3): **Git Commit SHA를 이미지 tag로 고정**하고 빌드 서버에서
만든 것과 **동일 tag**를 운영에서 `--no-build`로 기동한다. 운영 서버에서 임의로 다시
빌드하지 않는다. Stage 15 정책은 운영 자동 배포가 "CI가 승격한 full SHA" + "root
preflight의 무마이그레이션 보안 gate"를 **둘 다** 통과해야 시작되는 구조다.

운영 스크립트(`web/infra/scripts/`): `deploy.sh`,
`gilbom-deploy-preflight.sh`, `gilbom-approve-migrations.sh`,
`generate-production-secrets.sh`, `backup-db.sh` / `backup-volumes.sh` /
`gilbom-backup.sh`, `export-totp-keyring.sh`,
`generate-database-content-manifest.sh`, `collect-host-metrics.sh`.
인증서는 `infra/certbot/certificate.sh issue`. 그 외 `infra/audit/gilbom.rules`
(auditd), `infra/cosign/`(이미지·blob 서명), `infra/systemd/`,
`infra/cloudwatch/agent-config.json`.

---

## 8. 문서 자산 (질문이 나왔을 때 열 파일)

**계약 정본** — `web/docs/`
`api-contract.md`, `openapi-v1.yaml`, `asyncapi-mqtt-v2.yaml`,
`final-mqtt-topic-schema-v2.md`, `mqtt-v2-contract-freeze.md`,
`common-api-response-and-errors.md`, `authorization-matrix.md`,
`defect-schema.md`, `report-status-contract.md`,
`robot-telemetry-statistics-contract.md`, `time-based-mission-contract.md`,
`statistics-api-contract.md`, `mqtt-device-db-mapping.md`,
`report-llm-result.schema.json`

**보안·정책** — `threat-model.md`, `http-security-baseline.md`,
`session-cookie-csrf.md`, `totp-secret-encryption.md`,
`admin-authentication-security.md`, `authentication-rate-limit-lock-audit.md`,
`audit-and-sensitive-data-policy.md`, `llm-client-policy.md`,
`gms-llm-api-key-policy/`, `security-tooling-policy.md`, `cosign-blob-signing.md`

**운영 런북** — `production-deployment-runbook.md`,
`production-conditional-rollback.md`, `production-rollback-drill.md`,
`day10-observability-backup-runbook.md`, `day10-stage6-recovery-runbook.md`,
`jenkins-pipeline.md`, `cd-automation.md`, `ci-artifact-promotion.md`,
`stage15-cicd-operations.md`, `database-migrations.md`,
`environment-configuration-strategy.md`, `runtime-versions.md`

**검증 증적** — `day4-security-acceptance.md`, `day7-*`(9종),
`day10-*`(JSON 증적 포함), `day10-test-evidence-matrix.md`,
`backend-test-baseline-2026-07-29.md`

**산출물** — `docs/` 루트: 발표자료(pptx/pdf), 기획서, 웹·임베디드 상세 명세서,
목업, ERD/DFD 이미지, DB DDL, API 명세서 PDF

**시연·배포** — `exec/`: `시연시나리오_화면안내 및 기기 세팅.docx`,
`2_외부_서비스_정보.md`, `3_DB_dump.sql`, `gitlab 소스 클론 이후 빌드 및 배포 정리
문서.md`, 모델 파일(`braille_defect.pt`, `segv2.pt`), MQTT CA 인증서

**루트 정리 문서** — `MQTT-송수신-동작-정리.md`, `웹_통신계약_로봇_수신_정리.md`,
`TROUBLESHOOTING/`, `CHANGLOGS/`, `1,2주차_한_일_정리/`(팀원별)

---

## 9. 예상 기술 질문과 답변

각 답변 끝에 근거 파일을 달았다.

**Q. 로봇이 갑자기 전원이 꺼지면 관제 화면은 어떻게 아나?**
두 겹이다. ① MQTT LWT — 로봇이 CONNECT할 때 `OFFLINE / UNGRACEFUL_DISCONNECT`
presence를 등록해 두므로 브로커가 대신 발행한다. ② 그마저 못 왔을 때는 서버가
90초(state 발행 주기 30초 × 3) 동안 아무 메시지도 없으면 OFFLINE으로 판정하고
`HEARTBEAT_TIMEOUT`을 남긴다.
→ `embedded/jetson_main/comms/client.py`, `robot/status/RobotOfflineDetectionService.java`

**Q. 같은 결함이 순찰마다 중복으로 쌓이지 않나?**
PostGIS `ST_DWithin` 2m 반경에서 같은 `defect_type`+`block_type`+구역의 기존 결함을
찾아 관측만 추가한다. 단, `RESOLVED`는 제외한다 — 수리한 자리에서 다시 나면 새
결함으로 세지 않으면 "고쳤는데 또 났다"가 관측 횟수 뒤로 숨는다. 반대로
`FALSE_POSITIVE`/`REJECTED`는 병합 대상으로 남긴다 — 빼면 같은 오탐이 매번 새 결함이
돼 목록이 잠긴다.
→ `defect/IncidentEventIngestionService.java`의 `findMergeCandidate` SQL 주석

**Q. MQTT 메시지를 재전송·위조하면?**
`(robot_id, boot_id, sequence)`를 DB에서 잠그고 클레임한다. sequence는 boot마다 1에서
시작해 단조 증가해야 하고, 같은 sequence에 다른 payload면 `MQTT_SEQUENCE_CONFLICT`,
같은 payload면 중복으로 보고 재처리하지 않는다(멱등). 추가로 topic의 `mqtt_id`와
payload의 `mqtt_id`가 다르면 거부하므로 다른 로봇을 사칭할 수 없고, topic별 신선도
게이트(telemetry 60s / state 300s / event는 payload TTL)로 오래된 메시지를 버린다.
→ `robot/mqtt/replay/MqttReplayProtectionService.java`,
`robot/mqtt/parser/MqttInboundFreshnessGuard.java`, `V17__...sql`

**Q. 로봇 사진이 인증 없이 열리지 않나?**
업로드는 `X-API-Key`(SHA-256 조회)로만 받고, 조회는 HMAC-SHA256 서명 URL이다.
토큰이 `<만료 epoch초>.<서명>`이고 **서명 대상에 imageId가 포함**되므로 다른 이미지의
토큰을 재사용할 수 없다. S3 presigned URL과 같은 계약을 로컬 디스크 단계에서 구현한
것이라, 나중에 S3로 바꿔도 프론트는 그대로다.
→ `storage/url/ImageUrlSigner.java`, `storage/auth/RobotApiKeyAuthenticationFilter.java`

**Q. 이미지 요청이 관리자 세션을 건드리지 않나?**
두 겹으로 막는다. ① **edge** — 서명 이미지 location에서 Nginx가
`proxy_set_header Cookie ""` / `proxy_hide_header Set-Cookie`로 쿠키를 양방향으로
끊는다. ② **Backend** — 이미지 API가 별도 SecurityFilterChain을 쓰고
(`securityMatcher("/api/v1/images/**")`), 그 체인에
`NullAuthenticatedSessionStrategy`를 걸어 **인증 성공 시 세션 ID를 회전시키는
후처리를 끈다.** 서명 URL·API Key 검증은 로그인이 아닌데, 브라우저 `<img>` 요청에는
관리자 세션 쿠키가 자동으로 실려 오기 때문에 그 후처리가 관리자 세션을 회전시키던
문제가 있었다. 회귀 테스트가 세션 ID·저장된 인증정보·`Set-Cookie` 부재를 함께
검증한다.
→ `web/frontend/nginx/app-locations.conf`,
`storage/auth/ImageApiSecurityConfiguration.java`,
`ImageApiSecurityChainTest.signedImageAuthenticationDoesNotRotateAnExistingLoginSession`
(커밋 `ee49989`)

**Q. 왜 이미지 API만 보안 체인을 따로 뒀나?**
관리자 체인은 세션 쿠키 + CSRF를 전제하고, 이미지 API는 로봇 API Key 인증에 CSRF
비대상이다. 한 체인에 넣으면 **서로가 서로의 예외 조건**이 되어 공용 설정이 특수
케이스로 오염된다. 그래서 `@Order(HIGHEST_PRECEDENCE + 10)` + `securityMatcher`로
이 경로만 먼저 가져가는 체인을 따로 뒀다. 위 세션 회전 문제도 체인이 분리돼 있었기에
그 체인 한 줄로 끝낼 수 있었다.
→ `storage/auth/ImageApiSecurityConfiguration.java` 클래스 docstring

**Q. TOTP keyring 백업은 왜 자동화하지 않았나?**
자동으로 돌리면 **평문 키가 주기적으로 서버에 나타난다.** 그래서 처음부터 사람이 손으로
반출하는 절차로 설계했다(`export-totp-keyring.sh`). 대신 방치를 막기 위해 알림을
분리했다 — 자동 백업 기준(30시간 critical)에서 totp를 빼고,
`TotpKeyringExportStale`을 7일 warning으로 뒀다. 같은 임계값을 쓰면 손으로 안 돌린
이틀째마다 CRITICAL이 떠서 진짜 실패가 소음에 묻히기 때문이다. keyring은 DB 백업에
들어가지 않아 이 반출본이 유일한 사본이고, 잃으면 사용자 전원이 MFA를 재등록해야 한다.
→ `web/infra/monitoring/prometheus/rules/operations.yml`,
`web/infra/scripts/export-totp-keyring.sh`

**Q. LLM 호출이 실패하거나 예산을 넘으면 보고서를 못 만드나?**
만든다. 모든 실패가 **템플릿 폴백**으로 흡수되고 사유가 enum + DB에 남는다
(`POLICY_DISABLED`, `CONCURRENCY_LIMIT`, `USER_DAILY_RATE_LIMIT`,
`DAILY_BUDGET_EXCEEDED`, `LLM_UNAVAILABLE`, `LLM_RESPONSE_INVALID`,
`LLM_REQUEST_TOO_LARGE`, `IMAGE_EVIDENCE_INVALID`, `QUOTA_GUARD_UNAVAILABLE`).
readiness health에 LLM을 넣지 않은 것도 같은 이유다.
→ `report/generation/ReportLlmGenerationPipeline.java`, `V34__...sql`, `V45__...sql`

**Q. LLM에 개인정보나 프롬프트가 로그로 새지 않나?**
클라이언트의 모든 `toString()`이 `[REDACTED]`이고, 전송 예외에 provider 응답 본문·
URL·prompt·Authorization을 cause로 보존하지 않는다는 주석이 명시돼 있다. 응답은
JSON Schema strict로 강제하고, 이미지 전송은 `image-input-enabled`와
`image-deidentification-approved`가 **둘 다** 승인돼야 켜진다.
→ `llm/GmsLlmClient.java`, `llm/LlmPolicyGate.java`, `application.yml`

**Q. LLM 요청이 너무 커지면?**
3단계다. ① 이미지 1장을 JPEG 재압축으로 한도에 맞춰본다 ② 그래도 크면 이미지를
떼고 텍스트만 보낸다 ③ 그래도 크면 `REQUEST_TOO_LARGE`. 게다가 provider가 이미지
payload를 거부한 경우에도 이미지를 떼고 1회 재시도한다.
→ `llm/GmsLlmClient.generate`, `llm/LlmJpegRequestFitter.java`

**Q. 명령을 두 번 누르면 로봇이 두 번 움직이나?**
아니다. `Idempotency-Key`를 `request_control.idempotency_request`에
`(principal_key_hash, idempotency_key_hash)` unique로 클레임한다. 지문이
`method+path+commandType+parameters` SHA-256이므로 같은 키로 다른 내용을 보내는 것도
걸린다.
→ `robot/command/RobotCommandCreationService.java`

**Q. 명령이 실패했을 때 원인을 구분할 수 있나?**
`RobotCommandStatus`가 8개로 갈린다. 특히 `REJECTED`(로봇이 거부)와
`PUBLISH_FAILED`(브로커에 못 보냄)와 `EXPIRED`(타임아웃)를 분리했다. `EXPIRED`는
`RobotCommandTimeoutJob`이 만든다.
→ `robot/command/RobotCommandStatus.java`

**Q. 권한 관리는 어디까지 되나?**
역할·권한 카탈로그 + 사용자별 permission override + **로봇 범위 배정**까지다.
`@PreAuthorize`로 엔드포인트를 막고, `RobotScopeService`/`DefectScopeService`가 행
단위 가시성을 판정한다. SSE 발행도 구독자별 스코프 검사를 통과해야 나간다.
권한이 바뀌면 세션을 무효화한다.
→ `auth/admin/AdminController.java`, `robot/application/RobotScopeService.java`,
`V13/V14/V43__...sql`

**Q. 인증 흐름이 왜 5단계인가?**
`ANONYMOUS → PASSWORD_CHANGE_REQUIRED → MFA_ENROLLMENT_REQUIRED → MFA_REQUIRED →
AUTHENTICATED`. 비밀번호 검증 성공이 인증 완료가 아니고 `AUTHENTICATED`만 보호 API에
접근한다. 초기 비밀번호 변경과 MFA 등록을 **상태로** 표현했기 때문에 프론트 라우터가
같은 값으로 화면을 강제할 수 있다.
→ `auth/model/AuthenticationState.java`, `src/app/router.tsx`

**Q. 무차별 로그인 시도는 어떻게 막나?**
IP 20회/1분, 비밀번호 실패 5회/15분 → 15분 잠금, MFA 실패 5회/15분, 회원가입
5회/1시간, CSRF 토큰 발급 60회/1분(Nginx에서도 `limit_req`). 카운터는 **DB 영속**이라
재시작해도 유지되고, **IP 원문 대신 버전 있는 HMAC 버킷**을 저장한다.
→ `auth/ratelimit/`, `application.yml`, `web/frontend/nginx/app-locations.conf`

**Q. 실시간이 SSE인 이유는? WebSocket은?**
방향이 서버→클라이언트 단방향이고(명령은 REST) 브라우저 재연결이 표준으로 붙는다.
구독 수명을 세션 수명에 묶고 스코프를 검사하는 인가 모델을 그대로 재사용할 수 있다.
SSE를 "다시 조회해라" 신호로만 쓰고 데이터는 REST로 다시 받으므로 이벤트 누락이
화면 상태를 깨지 않는다.
→ `*/realtime/*SseService.java`, `src/features/*/use*Realtime.ts`

**Q. 트랜잭션이 롤백됐는데 SSE로 나가거나 PDF가 남는 일은?**
없다. 실시간 리스너가 `AFTER_COMMIT`에 수신하고, 승인 PDF는
`TransactionSynchronization`으로 롤백 시 파일을 정리한다.
→ `defect/realtime/DefectRealtimeEventListener.java`,
`report/pdf/ApprovedReportPdfService.java`

**Q. DB 권한 분리는?**
3역할이다. `gilbom_db_admin`(bootstrap 전용, 앱에 안 줌),
`gilbom_flyway`(migration DDL·객체 소유), `gilbom_app`(runtime DML만).
Flyway placeholder로 새 객체에 runtime 최소 권한만 부여하고, `clean`은 비활성이다.
정책이 실제로 걸려 있는지는 `infra/postgres/verify-*.sql` 10개로 확인한다.
→ `compose.yaml`, `application.yml`, `infra/postgres/`

**Q. 운영 서버에서 이미지를 다시 빌드하면 안 되는 이유?**
빌드 서버가 Git Commit SHA를 tag로 고정해 만들고, 운영은 **같은 tag를 `--no-build`로
기동**한다. 운영에서 재빌드하면 검증한 산출물과 다른 것이 돌 수 있어 롤백 대상
digest도 어긋난다. 자동 배포는 CI가 승격한 full SHA + preflight 보안 gate를 둘 다
통과해야 시작된다.
→ `README.md` 3.3, `web/Jenkinsfile*`, `infra/scripts/gilbom-deploy-preflight.sh`

**Q. 로봇 실주행 테스트를 어떻게 안전하게 했나?**
실행 플래그가 계단식이다. 기본 dry-run(모의 장치·모의 인지) → `--live`(실물 구동,
모의 인지) → `--live --perception real`(실추론 + 실구동). `--comms-config`를 안 주면
통신 코드는 import조차 되지 않는다. `driving/` 패키지는 I/O·시계 접근이 금지된 순수
계산이라 하드웨어 없이 FSM 전체를 테스트로 돌린다. 브로커는 `fake_broker.py`로
대체한다.
→ `embedded/jetson_main/main.py` docstring, `driving/states.py`, `tests/`

**Q. 임베디드에서 IMU가 없어도 도나?**
돈다. 회피·회전·차도 이탈·막다른 길 재정향의 종료 판정을 yaw(IMU)에서 **시각(장애물
소멸·중심 재정렬) + 시간**으로 바꿨다. IMU 게이트가 남은 것은 SLOPE(경사는 pitch가
있어야 안다)와 U턴(방향을 되돌리는 것이 목적이라 "경로가 보인다"로 끝낼 수 없다)뿐이다.
IMU 배선이 해제된 상태에서 모든 장애물이 정지 대기로 떨어졌던 실외 관찰이 이 변경의
직접 원인이다.
→ `embedded/jetson_main/README.md` "0804 명세 반영", `driving/states.py`

**Q. 결함인데 GPS가 튀거나 순찰 구역을 못 찾으면 데이터를 버리나?**
버리지 않는다. `defect.incident_ingestion_issue`에 `INVALID_GPS` /
`PATROL_AREA_UNRESOLVED`로 남겨 검토 대상으로 격리한다. 이미지가 아직 안 올라온
경우도 결함 생성을 막지 않는다.
→ `defect/IncidentEventIngestionService.java`, `V42__accept_missing_incident_images.sql`

**Q. 테스트는 어느 정도 하나?**
Backend 158개(계약·보안·인가·MQTT·결함·보고서), Frontend 유닛 22개 + Playwright E2E
18개(그중 5개는 실서비스 연동용 별도 config), MQTT 계약 테스트 + fixture,
로봇 25개(가짜 브로커 E2E 포함), mock-robot 시뮬레이터. 검증 증적 문서가
`web/docs/day*`로 남아 있다.

**Q. 로그 한 건을 edge부터 백엔드까지 추적할 수 있나?**
`RequestIdFilter`가 `X-Request-Id`를 MDC에 넣고 응답 헤더로 노출한다. Nginx access
log와 Spring 로그 패턴(`%5p [%X{requestId:-}]`)이 같은 값을 쓴다. 에러 응답 body에도
`requestId`가 들어가고 프론트 `ApiError`가 그것을 들고 다닌다.
→ `common/web/RequestIdFilter.java`, `application.yml`, `src/shared/api/apiClient.ts`

---

## 10. 발표 시 주의 (`README.md` 5절)

- 시연 중 **로봇 Credential 재발급 버튼을 누르지 않는다**
- `scripts/drive.sh --live`는 주행 공간·카메라·비상 정지·조향 한계를 확인한 뒤에만
- GMS 조건이 안 맞으면 보고서 API는 살아 있고 템플릿 보고서로 대체된다 —
  실패가 아니라 설계된 폴백이라고 설명하면 된다
- 브라우저 배율 80~90%, MQTT 시뮬레이터·테스트 탐지 이벤트·녹화 영상을 대체 수단으로
  준비
