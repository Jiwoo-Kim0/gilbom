# MQTT v2 · AsyncAPI · Schema · Fixture 동결 작업 및 트러블슈팅

## 1. 한 줄 요약

현재 Backend와 Embedded의 MQTT 통신 형식이 `tbs/v2`,
`schema_version: "2.0"`, `mqtt_id`, Event `ttl_sec` 기준으로 정합한 것을
확인하고, 사람이 읽는 계약·AsyncAPI·JSON Schema·Fixture·자동 검증을 하나의
FROZEN 묶음으로 고정했다. 실제 구현 불일치는 Embedded Python에 있었으며 해당
수정 내역은 `8_MqttInboundEventBrokerE2E.md`를 정본으로 참조한다.

## 2. 작업 범위와 결론

### 2.1 구현 수정과 계약 동결의 구분

- 이번 작업에서 Backend Java와 Embedded Python 구현은 수정하지 않았다.
- Backend는 기존 v2 구현을 MQTT 집중 테스트로 확인했다.
- Embedded는 기존에 반영된 `robot_id` → `mqtt_id`, Event `ttl_sec` 추가,
  `tbs/v2`·`"2.0"` 통일 상태를 전체 테스트와 계약 상수 비교로 확인했다.
- 동결 작업은 양쪽 구현이 따라야 할 정본을 기계 판독 가능한 형태로 추가한 것이다.
- 따라서 현재 코드 불일치의 수정 대상은 Embedded였지만, 회귀 방지를 위한 동결
  대상은 Backend·Embedded·AsyncAPI·Schema·Fixture 전체 경계다.

### 2.2 동결 대상

- 10개 Topic의 경로와 Backend 관점 `receive`·`send` 방향
- Topic별 QoS, Retain, MQTT 5 Message Expiry
- Payload 필수·선택 필드, 타입, enum, UUID, UTC `Z`, 숫자 범위
- `schema_version`, `mqtt_id`, `boot_id`, `seq`와 Event `ttl_sec`
- GPS·배터리 `valid=false` 시 값의 Null 일관성
- Topic/Payload `mqtt_id`, TTL, 미래 시각, 중복, Replay, Sequence 처리
- DB INSERT, Current State 갱신, Command 실행 여부와 오류 코드
- 정상·형식 오류·처리 시나리오별 예상 결과

### 2.3 동결하지 않은 환경값

- Broker 실제 Host·Port와 배포 환경 이름
- MQTT Password, 인증서, Private Key, Secret 경로
- 실제 Robot의 `mqtt_id`, `mqtt_client_id`
- 운영 DB 주소와 사용자별 Credential

## 3. 전체 수행 작업

### 3.1 사전 확인

작업 전에 다음 자료를 확인했다.

- `docs/길봄_MQTT 통신_SWAGGER REST SSE_API 명세서.pdf`
- `web/docs/final-mqtt-topic-schema-v2.md`
- `web/docs/mqtt-device-db-mapping.md`
- `web/docs/robot-patrol-mission-schema-workplan.md`
- `web/docs/팀원B/V2/로봇 MQTT 환경변수 및 Credential 전달.md`
- `CHANGLOGS/2026-07-30_0624_robot-troubleshooting-guides.md`
- `CHANGLOGS/2026-07-29_1931_mqtt-registry-cutover-commit.md`
- `CHANGLOGS/2026-07-29_1908_mqtt-credential-registry-cutover.md`
- `CHANGLOGS/2026-07-29_1859_mqtt-publisher-broker-e2e.md`
- `CHANGLOGS/2026-07-29_1718_mqtt-client-topic-acl.md`
- `TROUBLESHOOTING/8_MqttInboundEventBrokerE2E.md`

로그는 참고 자료로만 사용하고 실제 파일과 테스트 결과를 최종 근거로 삼았다.

### 3.2 AsyncAPI 추가

`web/docs/asyncapi-mqtt-v2.yaml`을 추가했다.

- AsyncAPI 문서 형식: `3.1.0`
- Payload 계약 버전: `2.0.0`
- 계약 상태와 일자: `FROZEN`, `2026-07-30`
- Backend 기준 8개 수신 Operation과 2개 송신 Operation
- 10개 Channel과 각 Message의 외부 JSON Schema 참조
- MQTT Binding `0.2.0`, QoS, Retain, Message Expiry
- 실제 Broker Secret 대신 로컬 예시와 환경 주입 원칙만 명시

`receive`는 Backend가 Robot/Broker로부터 받는 방향이고, `send`는 Backend가
Robot으로 발행하는 방향으로 고정했다.

### 3.3 JSON Schema 추가

`web/docs/mqtt-v2/schemas/`에 Draft 07 Schema 12개를 추가했다.

| 파일 | 검증 대상 |
|---|---|
| `common.schema.json` | 공통 ID·시각·enum·좌표·배터리·상태 정의 |
| `event-envelope.schema.json` | Event 공통 Envelope와 TTL |
| `presence.schema.json` | ONLINE·OFFLINE 조건부 필드 |
| `state.schema.json` | Robot 상태 Snapshot |
| `telemetry.schema.json` | GPS·배터리·온도·속도 Telemetry |
| `incident-event.schema.json` | 결함 관측, Evidence, bbox |
| `battery-event.schema.json` | 배터리 상태 전이 Event |
| `safety-event.schema.json` | 안전 사건 확정·해제 Event |
| `mission-event.schema.json` | Mission 상태 전이와 종료 결과 |
| `command-request.schema.json` | Command 종류별 Parameters와 만료 |
| `command-result.schema.json` | Command 접수·완료·실패 결과 |
| `ingest-receipt.schema.json` | Server 처리 Receipt |

Schema는 `additionalProperties: false`를 기본으로 사용한다. 알 수 없는 선택
필드도 거부하는 현재 소비자 정책에 맞춰 필드 추가 역시 Major 변경으로 취급한다.

### 3.4 Fixture와 Manifest 추가

`test/fixtures/mqtt/v2/`에 Payload Fixture 32개와 Manifest 1개를 추가했다.

정상 Fixture 16개:

- Presence ONLINE·OFFLINE
- State READY·PATROLLING
- 정상 Telemetry
- GPS Invalid와 Battery Invalid Telemetry
- Incident·Battery·Safety·Mission Event
- START_MISSION·EMERGENCY_STOP Command
- ACCEPTED·COMPLETED Command Result
- STORED Ingest Receipt

오류·처리 시나리오 Fixture 16개:

- 필수 `seq` 누락, 지원하지 않는 Version, 잘못된 enum
- 위도 범위 초과, UUID 오류, UTC timezone 누락
- Battery·Position Validity 불일치
- Event TTL 범위 초과, Mission 종료 결과 누락
- Command Parameters 오류, 만료시각 선후 오류
- Incident bbox 순서 오류
- 동일 Event 재전송, Sequence 충돌, 이전 Boot Replay

`manifest.json`에는 각 입력에 대해 다음 결과를 고정했다.

- `schema_valid`
- `processing`
- `error_code`
- `db_insert`
- `current_state_update`
- `row_delta`
- `execute_command`

JSON Schema로 표현할 수 없는 시각 선후, bbox 원소 순서, Topic/Payload ID,
수신 QoS, Freshness, 중복·Replay·Command 멱등성은 `semantic_check`로 분리했다.

### 3.5 자동 계약 검증 추가

`test/mqtt_contract/test_mqtt_v2_contract.py`를 추가했다.

검증 항목:

1. 12개 JSON Schema의 Draft 07 Meta Schema 적합성
2. 중복 JSON Key 거부
3. AsyncAPI의 상태·10개 Topic·10개 Operation·방향·QoS·Retain
4. AsyncAPI Message와 외부 Schema의 완전한 연결
5. 모든 Fixture의 Manifest 등록과 ID 중복 방지
6. 정상 Fixture 통과와 형식 오류 Fixture 거부
7. 10종 Semantic 시나리오의 예상 처리
8. Fixture 내 Password·Secret·API Key·Private Key 부재
9. Embedded의 Namespace·Version·TTL·enum·발행 QoS/Retain과 Schema 정합

### 3.6 계약 문서와 작업 상태 갱신

- `web/docs/final-mqtt-topic-schema-v2.md`
  - 상태를 FROZEN으로 지정했다.
  - AsyncAPI·Schema·Fixture·검증 가이드 연결을 추가했다.
  - Command Result reason과 Major 변경 정책을 명시했다.
- `web/docs/mqtt-v2-contract-freeze.md`
  - 동결 묶음, 검증 명령, 환경값 제외 범위, 예상 결과, 변경 절차를 작성했다.
- `DAY5_작업_및_밀린_일.md`
  - MQTT v2 계약 산출물 동결 항목을 완료로 갱신했다.
- `2026-07-29_JIRA_오늘_작업.md`
  - TODAY-B-03-2 계약 동결 작업을 DONE으로 갱신했다.

### 3.7 v1 문서 상태 정리

과거 계약을 삭제하지 않고 다음 문서에 `DEPRECATED / SUPERSEDED`를 표시했다.

- `web/docs/final-mqtt-topic-schema-v1.md`
- `web/docs/mqtt-device-db-mapping.md`

`web/docs/api-contract.md`의 현행 MQTT 정본 링크는 v2 동결 가이드로 교체하고,
v1은 보관본임을 명시했다. REST API 자체의 v1 버전과 MQTT 계약 v2는 서로 다른
버전 축이므로 REST `/api/v1` 표기는 변경하지 않았다.

## 4. 발생한 문제와 해결

### 4.1 PDF 추출 출력의 Windows Encoding 오류

#### 현상

PDF 내용을 Python으로 추출한 첫 시도에서 다음 오류가 발생했다.

```text
UnicodeEncodeError: 'cp949' codec can't encode character
```

#### 원인

PDF 추출 자체가 아니라 Windows Console 기본 출력 Encoding인 CP949가 문서의
일부 Unicode 문자를 표현하지 못했다.

#### 해결

`PYTHONIOENCODING=utf-8`을 지정해 다시 추출했다. 문서 확인은 완료했으며 원본
PDF는 변경하지 않았다.

### 4.2 Mission Schema의 중복 `allOf` 작성

#### 현상

초기 작성 중 `mission-event.schema.json`에 같은 수준의 `allOf` Key가 두 번
포함됐다.

#### 원인

종료 상태별 조건부 규칙을 Patch로 합치는 과정에서 기존 조건 블록을 확장하지 않고
새 Key를 추가했다.

#### 해결

중복 블록을 즉시 하나로 합쳤다. 이후 모든 JSON을 중복 Key 거부 Loader로 읽고
Draft 07 Meta Schema 검증을 수행하도록 계약 테스트를 보강했다.

### 4.3 Embedded Test Runner 부재

#### 현상

`embedded`에서 다음 명령이 실패했다.

```text
python -m pytest -q
No module named pytest
```

#### 원인

System Python에는 프로젝트 Runtime 의존성인 `paho-mqtt`, `PyYAML`,
`requests`가 설치돼 있었지만 `pytest`가 없었다.

#### 해결

네트워크 설치 없이 로컬 uv Cache의 `pytest 9.1.1`을 확인하고 System Python의
Module Search Path에 해당 Cache만 추가해 테스트를 실행했다. Runtime 의존성은
기존 System Python 설치본을 그대로 사용했다.

### 4.4 uvx 격리 환경의 Runtime 의존성 누락

#### 현상

`uvx --offline pytest -q`는 Test 수집 단계에서 `paho`, `requests`, `yaml`
Import 오류 8건으로 실패했다.

#### 원인

uvx의 격리 환경에는 pytest만 있고 프로젝트 Runtime 의존성이 포함되지 않았다.

#### 해결

격리 환경 대신 위 4.3의 System Python + 로컬 pytest Cache 조합을 사용했다.
그 결과 Embedded 전체 479개 Test가 통과했다.

### 4.5 Offline uv Cache의 PyYAML Version 불일치

#### 현상

다음 Offline 보강 시도가 실패했다.

```text
uvx --offline --with paho-mqtt==2.1.0 --with PyYAML==6.0.3 --with requests pytest -q
```

`PyYAML 6.0.3`과 추가로 시도한 `6.0.2` Distribution을 uv Cache에서 찾지
못했다.

#### 원인

Offline 모드에서는 Cache에 없는 Distribution을 내려받을 수 없다.

#### 해결

네트워크 설치나 환경 변경을 수행하지 않고 System Python의 기존 PyYAML을
재사용했다.

### 4.6 Markdown Trailing Whitespace

#### 현상

`git diff --check`에서 v2 계약 문서의 상태·동결일자 두 줄에 Trailing
Whitespace가 보고됐다.

#### 원인

Markdown 강제 줄바꿈을 위해 넣은 공백 두 칸이 Git Whitespace 검사에 걸렸다.

#### 해결

공백을 제거하고 문서 의미는 그대로 유지했다. 최종 `git diff --check`로 다시
확인한다.

### 4.7 변경 로그 파일 목록 대조 Script의 PowerShell Quoting 오류

#### 현상

변경 로그를 다시 읽은 뒤 표의 파일 경로 56개와 실제 파일을 대조하는 첫 Python
One-liner가 정규식 Compile 단계에서 실패했다.

```text
re.error: unterminated character set at position 5
```

#### 원인

PowerShell Double-quoted Command 안의 Markdown Backtick을 포함한 정규식이
PowerShell Escape 처리의 영향을 받아 Python에 원형대로 전달되지 않았다.

#### 해결

정규식을 없애고 Python에서 `chr(96)`으로 Backtick 구분자를 만든 뒤 표의 첫
Column을 분리했다. 재실행 결과는 다음과 같다.

```text
recorded=56
expected=56
missing=[]
extra=[]
missing_files=[]
```

## 5. 검증 결과

### 5.1 계약 자동 검증

```powershell
$env:PYTHONIOENCODING='utf-8'
python -m unittest discover -s test/mqtt_contract -p 'test_*.py' -v
```

결과:

```text
Ran 8 tests
OK
```

### 5.2 Embedded 전체 회귀

System Python에 로컬 uv Cache의 pytest 경로만 추가하여 실행했다.

```text
479 passed in 13.24s
```

### 5.3 Backend MQTT 집중 회귀

```powershell
.\gradlew.bat test --rerun-tasks `
  --tests "com.gilbom.robot.mqtt.parser.MqttMessageParserTest" `
  --tests "com.gilbom.robot.mqtt.topic.MqttTopicParserTest" `
  --tests "com.gilbom.robot.mqtt.client.MqttV2PublisherTest" `
  --tests "com.gilbom.robot.mqtt.handler.MqttTopicMessageHandlerRegistrationTest"
```

결과:

```text
Backend test summary: tests=18, failures=0, skipped=0
BUILD SUCCESSFUL
```

### 5.4 이전 실제 Broker E2E 확인

이번 작업에서는 Broker E2E를 다시 실행하지 않았다. 최신 관련 기록인
`TROUBLESHOOTING/8_MqttInboundEventBrokerE2E.md`에서 TLS Broker 기준 2개
E2E와 Backend 전체 542개 통과를 확인했다. 이번 작업의 기계 판독 계약은 해당
Wire 규칙과 현재 구현을 대상으로 검증했다.

## 6. 남은 외부 환경 검증

로컬 계약 동결은 완료했다. 다음 항목은 코드·문서 정합성과 별개의 배포 검증이다.

- 수정된 Embedded Python의 실제 Jetson 배포와 Process 재시작
- 실제 Jetson Credential·ACL로 `tbs/v2` Publish/Subscribe 확인
- EC2 Mosquitto TLS Endpoint에서 QoS·Retain·LWT·Message Expiry 확인
- Jetson → EC2 Broker → Backend → DB의 실제 시간 동기화와 Replay 확인

이 항목들은 실제 장비와 운영 Credential이 필요한 후속 작업이며, 미수행을 로컬
계약 검증 성공으로 표현하지 않는다.

## 7. 재현·진단 순서

1. `web/docs/mqtt-v2-contract-freeze.md`에서 동결 묶음을 확인한다.
2. 계약 자동 Test 8개를 실행한다.
3. 실패 시 Schema → AsyncAPI → Fixture Manifest → Embedded 상수 순으로
   불일치 위치를 확인한다.
4. Payload Parser 문제가 의심되면 Backend MQTT 집중 Test 18개를 실행한다.
5. Robot 발행 문제가 의심되면 Embedded 전체 Test를 실행한다.
6. 로컬 통과 후 실제 Jetson과 EC2에서 TLS Broker E2E를 수행한다.
7. 변경이 Wire 형식에 영향을 주면 v2를 직접 확장하지 말고 새 Major Namespace와
   `schema_version`을 정의한다.
