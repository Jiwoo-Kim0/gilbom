# Ingest Receipt와 Mission DB 정합성 통합 명세

- 작성 기준일: 2026-08-04
- 적용 범위: `web/tools/robot+comm`의 `comms/`, `communication/` 통신 계층
- 정본 계약:
  - `web/docs/final-mqtt-topic-schema-v2.md` §11~14
  - `web/docs/time-based-mission-contract.md`
  - `web/backend`의 현재 MQTT DTO·Mission 수신 코드
- 범위 제외: `driving/`, 모터 제어, 인지·주행 알고리즘 변경

## 1. 결론

Robot 통신 계층에는 Broker PUBACK과 Backend Receipt를 분리하는 구현이 반영됐다.
다만 운영 Backend가 DB Transaction 후 Receipt를 자동 발행하지 않으므로
`comms.yaml`의 `receipt_required` 기본값은 `false`다. 이 상태에서는 기존처럼 PUBACK을
성공 기준으로 사용하므로 Mission Event가 Backend 검증에서 거절되면 Backend Mission이
`READY`, `RUNNING` 또는 `PAUSED`에 남을 수 있다.

이 문제는 Receipt만 추가해서 끝나지 않는다. 다음 세 작업을 함께 통합해야 한다.

1. Mission 명령마다 대응하는 `events/mission` 발행: **Robot 반영 완료**
2. Receipt 구독·Outbox DB 확인 상태·재전송: **Robot 반영 완료, 기본 비활성**
3. Backend Event DB Commit 후 `ingest/receipt` 자동 발행: **미구현**

## 2. 2026-08-04 코드 대조 결과

### 2.1 발견한 불일치와 반영 상태

| 우선순위 | 통합 전 상태 | 정본 계약 | 발생 가능한 결과 | 반영 상태 |
| --- | --- | --- | --- | --- |
| P0 | `DriveCommandRunner`가 `EventPublisher`를 받지 않으며 START·PAUSE·RESUME·CANCEL 처리에서 Mission Event를 호출하지 않음 | 상태 전이마다 `MISSION_STARTED`, `MISSION_PAUSED`, `MISSION_RESUMED`, `MISSION_CANCELLED` 발행 | Command는 `COMPLETED`인데 Mission DB는 전이하지 않음 | Robot 완료 |
| P0 | `COMPLETE_MISSION`이 `REQUIRED_PARAMETERS`와 적용 대상에 없음 | Backend가 계획시간 종료와 관리자 완료에 `COMPLETE_MISSION {}` 발행 | 정상 종료 명령이 `INTERNAL_ERROR`로 거절되거나 Mission이 계속 `RUNNING` | Robot 완료 |
| P0 | CANCEL이 로봇 Mission 보관함과 `mission.json`을 먼저 비우고 Event를 발행하지 않음 | `MISSION_CANCELLED`를 Outbox에 기록한 뒤 보관함 정리 | 로봇은 활성 Mission이 없지만 DB는 `RUNNING`; 이후 명령은 `NO_ACTIVE_MISSION` | Robot 완료 |
| P0 | 긴급정지에서 Robot mode만 `SAFE_STOP`으로 바꾸고 Mission을 `PAUSED`로 전이시키는 Event가 없음 | 실행 중 긴급정지는 `SAFE_STOP + PAUSED`, `MISSION_PAUSED` 발행 | Robot 상태와 Mission DB 상태가 갈라짐 | Robot 완료 |
| P0 | 만료 Command Result가 계약에 없는 `COMMAND_EXPIRED` reason 사용 | `EXPIRED` 상태의 reason은 `OK` | Backend가 Result를 거절해 명령 이력이 Timeout으로 남음 | Robot 완료 |
| P0 | Outbox의 `sent_at`이 Broker PUBACK 시점에 기록되고 정리 기준이 됨 | Receipt 사용 시 DB 처리 결과까지 보존 | Backend 거절 Event가 재전송되지 않는 조용한 유실 | Robot 완료·설정 비활성 |
| P0 | 기존 문서가 topic을 `.../receipt`, 필드를 `status`, `reason_code`로 제안 | 실제 계약은 `.../ingest/receipt`, `ingest_status`, `incident_id` | 구독·역직렬화 실패 | 문서·Robot 완료 |
| P1 | MQTT Client가 `commands/request` 하나만 구독하고 단일 SUBACK 상태만 관리 | `commands/request`와 `ingest/receipt` 두 topic 구독 | Receipt를 발행해도 로봇이 받지 못함 | Robot 완료 |
| P1 | Backend Receipt 발행은 `local` Profile 수동 Controller에만 존재 | 실제 Event ingest Transaction과 자동 연결 | 운영 Backend는 저장 성공·거절 Receipt를 자동 발행하지 않음 | Backend 미완료 |

`commands/result.status=COMPLETED`는 명령 실행 결과일 뿐 Mission 완료가 아니다.
Backend Mission 상태는 `events/mission`을 수신해 DB에 반영했을 때만 바뀐다.

### 2.2 이미 구현되어 유지할 부분

- Mission을 포함한 Event 4개 topic은 SQLite Outbox에 발행 전 저장된다.
- `event_id`와 `occurred_at`은 재전송해도 유지된다.
- `mission.json`은 원자적 교체로 저장되고 Mission ID, 상태, 구역, 시작시각,
  탐지 집계를 보존한다.
- 정상 종료는 활성 Mission을 `MISSION_FAILED/GRACEFUL_SHUTDOWN`으로 닫으려 한다.
- 비정상 종료 후 다음 기동은 `mission.json`을 읽고
  `MISSION_FAILED/PROCESS_RESTARTED`를 만든다.
- 새 boot의 Outbox Event를 State·Telemetry보다 먼저 보내도록 접속 순서를 제어한다.
- Mosquitto ACL에는 Robot의 `ingest/receipt` read와 Backend의 write 권한이 이미 있다.

이 장치들은 유지해야 한다. 다만 Receipt가 없으면 Mission 종료 Event가 Backend에서
거절된 사실을 로봇이 알 수 없으므로 좀비 Mission을 완전히 막지는 못한다.

## 3. 정식 Receipt 계약

### 3.1 Topic

```text
tbs/v2/robots/{mqtt_id}/ingest/receipt
```

| 항목 | 값 |
| --- | --- |
| 방향 | Backend → Robot |
| QoS | 1 |
| Retain | `false` |

`tbs/v2/robots/{mqtt_id}/receipt`는 잘못된 경로이므로 사용하지 않는다.

### 3.2 Payload

```json
{
  "schema_version": "2.0",
  "event_id": "0f2c71ad-cce5-4f34-a6f8-7b41520e5663",
  "ingest_status": "STORED",
  "incident_id": null,
  "received_at": "2026-08-04T01:10:23.456Z"
}
```

| 필드 | 필수 | 설명 |
| --- | :---: | --- |
| `schema_version` | O | 반드시 `"2.0"` |
| `event_id` | O | Robot이 발행한 Event UUID를 그대로 사용 |
| `ingest_status` | O | `STORED`, `MERGED`, `DUPLICATE`, `REJECTED` |
| `incident_id` | O | Incident 결과만 코드 사용, Mission 등 다른 Event는 `null` |
| `received_at` | O | Backend 처리 시각 |

현재 DTO에는 `mqtt_id`, `status`, `reason_code`가 없다. Backend ObjectMapper는 알 수
없는 필드를 거부하므로 임의로 추가하지 않는다. 거절 원인은 현재 Backend 로그와
`gilbom.mqtt.inbound.rejected` Metric에서 확인한다. Receipt에 거절 원인이 필요하면
계약·JSON Schema·Backend DTO를 함께 버전 변경해야 한다.

### 3.3 발행 시점

- `STORED`, `MERGED`, `DUPLICATE`: 관련 DB Transaction Commit 후 발행한다.
- `REJECTED`: 원본 JSON에서 유효한 `event_id`를 추출할 수 있을 때만 발행한다.
- JSON 파싱 실패, `event_id` 누락처럼 응답 대상을 식별할 수 없는 거절은 로그와
  Metric으로만 남긴다.
- Transaction 안에서 Commit 전에 Receipt를 발행하면 안 된다.

현재 `MqttV2PublishController`의 `/ingest/receipt`는 `@Profile("local")` 수동 시험용이다.
운영 자동 Receipt로 간주하지 않는다.

## 4. Mission 명령과 Event의 필수 연결

통합은 `communication/commands.py`, `communication/runtime.py`, `comms/` 안에서만
수행한다. `driving/` 코드는 변경하지 않는다.

| Command | 성공 전제 | 통신 계층에서 반드시 생성할 Event | Backend Mission 결과 |
| --- | --- | --- | --- |
| `START_MISSION` | 실제 주행 시작 상태 적용 | `MISSION_STARTED` | `READY → RUNNING` |
| `PAUSE_MISSION` | 실제 정지 상태 적용 | `MISSION_PAUSED` | `RUNNING → PAUSED` |
| `RESUME_MISSION` | 실제 재개 상태 적용 | `MISSION_RESUMED` | `PAUSED → RUNNING` |
| `COMPLETE_MISSION` | Mission `RUNNING`, 안전정지 아님, 정지 적용 | `MISSION_COMPLETED` | `RUNNING → COMPLETED` |
| `CANCEL_MISSION` | 활성 Mission 존재, 정지 적용 | `MISSION_CANCELLED` | `READY/RUNNING/PAUSED → CANCELLED` |
| `EMERGENCY_STOP` | 물리 정지 적용 | RUNNING Mission이면 `MISSION_PAUSED` | `RUNNING → PAUSED` |
| `CLEAR_EMERGENCY_STOP` | 원격 안전정지 해제 | Mission Event 없음, Mission은 `PAUSED` 유지 | 별도 RESUME 전까지 `PAUSED` |

순서는 다음과 같이 고정한다.

```text
명령 수신
  → 전제조건 확인
  → 기존 주행 FSM 요청 및 적용 확인
  → RobotState + mission.json 갱신
  → 대응 Mission Event를 Outbox에 먼저 기록
  → commands/result 최종 상태 발행
```

Mission Event 발행 함수가 존재한다는 것만으로는 충분하지 않다. Command 처리기가
`EventPublisher`를 주입받고 위 표의 각 성공 경로에서 실제로 호출해야 한다.

## 5. 로봇에 보존해야 하는 상태정보

### 5.1 활성 Mission 사본 (`mission.json`)

최소 보존 필드는 다음과 같다.

```json
{
  "mission_id": "MIS-20260803-001",
  "mission_state": "RUNNING",
  "zone_id": "A-01",
  "started_at": "2026-08-03T10:45:46.158Z",
  "incident_count": 1,
  "normal_detection_count": 4
}
```

- `mission_id`: Backend가 START 명령으로 준 `mission_code` 문자열을 그대로 사용한다.
- `mission_state`: 활성 상태인 `RUNNING` 또는 `PAUSED`를 보존한다.
- `zone_id`: Backend가 준 `area_code` 문자열을 그대로 사용한다.
- `started_at`: 프로세스 재시작 뒤 수행시간 복원에 사용한다.
- 두 count: 최종 Mission Event의 `result` 정합성에 사용한다.
- 파일은 Outbox와 같은 영속 디렉터리에 두고 원자적으로 교체한다.
- `kill -9`와 전원 차단은 종료 Handler를 지나지 않으므로 다음 기동 복구가 필수다.

Terminal Event를 Outbox에 기록하기 전에 이 파일을 지우면 안 된다. Outbox 기록이
성공한 뒤 메모리의 활성 Mission을 비울 수 있으며, Receipt가 오기 전까지는 Outbox가
동일 `event_id`의 복구 근거가 된다.

### 5.2 Event Outbox에 추가로 보존할 상태

현재 `sent_at` 하나로 Broker 전송과 DB 저장을 함께 표현하지 않는다.

| 필드 | 의미 |
| --- | --- |
| `event_id`, `kind`, `payload`, `created_at` | 기존 Event 원본 |
| `attempted_at`, `attempts` | 발행 시도 이력 |
| `broker_acked_at` | Mosquitto PUBACK 시각; 기존 `sent_at`을 호환 목적으로 사용할 수 있음 |
| `receipt_at` | Backend Receipt 수신 시각 |
| `ingest_status` | `STORED`, `MERGED`, `DUPLICATE`, `REJECTED` |
| `incident_id` | Incident 저장·병합 결과, Mission Event는 null |

처리 규칙은 다음과 같다.

- `STORED`, `MERGED`, `DUPLICATE`: DB 처리 완료로 확정한다.
- `REJECTED`: 같은 잘못된 Payload를 무한 재전송하지 않고 행과 로그를 보존해
  운영자가 원인을 확인하게 한다.
- PUBACK 후 Receipt Timeout: 정본 계약에 따라 같은 `event_id`와 원본 Payload로
  재전송한다. `sent_at`만 현재 시각으로 갱신한다.
- 성공 Receipt가 없는 Mission Terminal Event는 자동 정리하지 않는다.
- Receipt 기능을 Backend보다 먼저 켜면 모든 Event가 미확인으로 쌓인다. 양쪽 배포를
  동시에 할 수 없다면 `receipt_required=false`로 시작하고 Backend 자동 발행 확인 후
  활성화한다.

### 5.3 서버 대조에 필요한 현재 상태

Robot State에는 아래 정보가 함께 있어야 한다.

```json
{
  "boot_id": "473721e9-d779-40b1-b30e-1733a1432a9e",
  "seq": 1,
  "updated_at": "2026-08-04T01:12:00.000Z",
  "mode": "READY",
  "reason_code": "NONE",
  "mission": null
}
```

활성 Mission이면 `mission`은 다음과 같다.

```json
{
  "mission_id": "MIS-20260803-001",
  "state": "RUNNING"
}
```

Backend의 `robot_current_state`는 현재 `mission_status`만 저장하고 State Payload의
`mission_id`는 현재 상태 컬럼에 보존하지 않는다. 자동 정합성 감지를 구현하려면
최소한 아래 값을 한 묶음으로 비교·기록해야 한다.

- Robot 보고: `mqtt_id`, `boot_id`, `seq`, `updated_at`, `mode`, `reason_code`,
  `mission.mission_id`, `mission.state`
- Backend 정본: 내부 `mission_id`, `mission_code`, `status`, `started_at`, `ended_at`,
  마지막 `source_event_id`
- 연결 상태: `connectivity_status`, boot session 상태, `offline_reason`,
  `last_received_at`

새 `boot_id`의 State가 `mission=null`인데 Backend에 `RUNNING` 또는 `PAUSED` Mission이
있으면 즉시 자동 완료로 바꾸지 않는다. 접속 순서상 Outbox의 Terminal Event가 State보다
먼저 도착해야 하므로 짧은 유예 후 다음을 판정한다.

1. Terminal Event Receipt가 성공했으면 정상 종료로 확정한다.
2. Terminal Event가 `REJECTED`이거나 Receipt Timeout이면 명령을 제한하고 경고한다.
3. Robot과 Backend가 모두 Mission ID를 보고하지만 값이 다르면 새 START를 차단한다.
4. 운영자 강제 `FAILED` 처리는 마지막 복구 수단이며 자동 정상 완료로 위장하지 않는다.

## 6. 재시작·강제 종료 시나리오

### 6.1 정상 종료

```text
활성 Mission 확인
  → MISSION_FAILED / GRACEFUL_SHUTDOWN Outbox 기록
  → SHUTTING_DOWN State
  → OFFLINE / GRACEFUL_SHUTDOWN Presence
  → Process 종료
```

### 6.2 `kill -9` 또는 전원 차단

```text
mission.json과 Outbox가 디스크에 남음
  → 새 Process, 새 boot_id
  → mission.json 복구
  → 주행은 재개하지 않음
  → MISSION_FAILED / PROCESS_RESTARTED Outbox 기록
  → MQTT 접속
  → 밀린 Event를 State·Telemetry보다 먼저 발행
  → Backend Commit Receipt 확인
```

`var/outbox.sqlite3`와 `var/mission.json`이 배포 교체나 임시 디렉터리 정리로 삭제되면
복구할 수 없다. Jetson 서비스 배포에서 두 파일의 절대 경로와 영속 마운트를 확인한다.

## 7. Backend 통합 작업

1. 각 Event handler의 DB 결과를 `STORED`, `MERGED`, `DUPLICATE`, `REJECTED`로 매핑한다.
2. Commit 후 `MqttV2Publisher.publishIngestReceipt()`를 호출한다.
3. 동일 `event_id` 재수신도 `DUPLICATE` Receipt를 돌려 Robot Outbox를 닫는다.
4. 파싱 가능한 거절은 `REJECTED`, 파싱 불가능한 거절은 로그·Metric으로 남긴다.
5. Receipt 발행 실패를 재시도할 Backend Outbox 또는 동등한 내구성 수단을 둔다.
6. 새 boot State와 활성 Mission 정본의 불일치를 감지하고 경고·명령 제한한다.
7. Receipt 자동 발행이 운영 Profile에서 동작하는지 확인한다. local 수동 Controller만으로
   완료 처리하지 않는다.

## 8. Robot 통신 계층 통합 결과

1. `[완료] communication/commands.py`
   - `EventPublisher` 주입
   - `COMPLETE_MISSION` 지원
   - 모든 Mission 전이 성공 경로에서 대응 Event 발행
   - 긴급정지 시 RUNNING Mission을 PAUSED로 전이·발행
2. `[완료] communication/runtime.py`
   - 하나의 `EventPublisher`를 Command Runner와 종료 복구 경로가 공유
3. `[완료] comms/topics.py`
   - `INGEST_RECEIPT = "ingest/receipt"` 구독 topic 추가
4. `[완료] comms/client.py`
   - Command와 Receipt를 함께 구독
   - topic별 SUBACK 상태 추적
   - Receipt 검증 후 Outbox에 전달
5. `[완료] comms/outbox.py`
   - Broker ACK와 Backend Receipt 상태 분리
   - Schema migration은 기존 SQLite 파일을 보존하는 `ALTER TABLE` 방식 사용
   - Receipt Timeout 재전송과 REJECTED 보존
6. `[완료] comms/config.py, comms.yaml`
   - Backend 자동 Receipt 전에는 `receipt_required=false`
   - 서버 배포 확인 후에만 `true`로 전환

이 목록은 통신 계층 변경만 요구하며 `driving/`의 상태 전이표나 주행 알고리즘을
수정하지 않는다.

## 9. 통합 완료 조건

Robot 로컬 자동 테스트에서 아래 통신 동작을 확인했다.

- START: Command 최종 `COMPLETED`와 `MISSION_STARTED` 생성
- PAUSE/RESUME: 각 Mission Event가 같은 순서로 생성
- COMPLETE: 정지 적용 후 `MISSION_COMPLETED` 생성과 Mission 보관함 정리
- CANCEL: `MISSION_CANCELLED` 생성 후 Mission 보관함 정리
- ESTOP: Robot `SAFE_STOP`, Mission `PAUSED`, `MISSION_PAUSED` 생성
- Receipt 필수 모드에서 PUBACK 후에도 Outbox가 성공으로 정리되지 않음
- `STORED`, `DUPLICATE`, `REJECTED` Receipt 상태 처리
- 기존 SQLite Outbox 행을 지우지 않는 Schema Migration
- Command·Receipt topic 동시 구독

2026-08-04 Robot 전체 테스트는 186건 통과했다. 다음 항목은 Backend 작업 후 EC2
통합 환경에서 추가 검증한다.

- START/PAUSE/RESUME/COMPLETE/CANCEL Event에 따른 실제 Mission DB 상태 전이
- Command Result `COMPLETED`만으로 Mission DB가 바뀌지 않는 경계
- Backend 자동 Receipt와 동일 `event_id`의 `DUPLICATE` 왕복
- 실제 Broker의 Receipt topic ACL, SUBACK, QoS 1, retain false
- `kill -9` 후 `mission.json` 복구와 `PROCESS_RESTARTED` 실패 Event 저장
- MQTT 재접속 시 Event가 새 State·Telemetry보다 먼저 발행
- 영속 경로에서 Process 재시작 후 `outbox.sqlite3`, `mission.json` 유지

## 10. 운영 진단 체크리스트

Mission이 화면에서 `RUNNING`에 남으면 다음 순서로 확인한다.

1. `robot.mission_status_history`에 마지막 MQTT 전이가 있는지 확인한다.
2. `telemetry.robot_event`에 Terminal Mission Event가 저장됐는지 확인한다.
3. Backend 로그에서 `MQTT inbound message rejected`와 error code를 확인한다.
4. Robot Outbox에서 해당 `event_id`의 Broker ACK와 Receipt 상태를 각각 확인한다.
5. Robot의 `mission.json`, 현재 `boot_id`, State의 `mission` 값을 확인한다.
6. `telemetry.robot_boot_session`의 `session_status`, `offline_reason`, `last_seen_seq`를
   확인한다.
7. Backend 활성 Mission과 Robot 보고 Mission ID가 다르면 새 Mission 시작을 막고
   먼저 불일치를 해소한다.

2026-08-03 실서버 조사에서는 Mission DB가 `RUNNING`인 상태에서 Terminal Mission
Event가 없었고, 이후 새 boot들은 `UNGRACEFUL_DISCONNECT` 또는
`HEARTBEAT_TIMEOUT`으로 끝났다. 이 증상은 위 P0 항목들과 일치한다. 다만 현재
`robot+comm` 폴더는 Git 기준 미추적 상태이므로, 당시 Jetson에 실제 배포된 코드와
동일한지는 배포 artifact 또는 commit hash로 별도 확인해야 한다.
