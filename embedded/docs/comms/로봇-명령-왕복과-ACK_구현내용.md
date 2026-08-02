# Robot Command 발행·ACK·Timeout 구현 내용

> 담당: 팀원 B — Robot 제어
> 작업일: 2026-07-28 ~ 2026-07-30
> 연결 Jira: `S15P11D101-377`, `S15P11D101-484`, `DAY6-B-02`, `DAY6-B-03`
> 최종 상태: **Backend·Frontend·정상/비정상/보안 Broker E2E DONE**

이 문서는 관리자 요청이 REST·DB·MQTT·Robot Result를 거쳐 다시 관리 화면으로
돌아오는 **Command 왕복 흐름만** 묶는다. Patrol Area 생성, Mission 시간
진행률과 Robot 일반 Telemetry 관제는 별도 작업 문서다.

관련 정본:
[MQTT v2 계약](../../final-mqtt-topic-schema-v2.md),
[OpenAPI](../../openapi-v1.yaml),
[권한 매트릭스](../../authorization-matrix.md)

---

## 0. 3분 요약

Command Backend와 관리자 제어 화면, 정상·비정상·보안 Broker 왕복은
구현·검증됐다.

```text
관리자 POST
→ Idempotency 검증
→ robot_command CREATED
→ MQTT Publish
→ SENT 또는 PUBLISH_FAILED
→ Robot ACCEPTED/COMPLETED/FAILED/REJECTED Result
→ robot_command_result INSERT
→ Command 상태 전이
→ COMMAND_STATUS_CHANGED SSE
```

정상뿐 아니라 Publish 실패, 업무 실패, 안전 거절, 만료, 늦은 Result, 중복
REST, 중복 MQTT Result, Robot Scope와 감사 Rollback을 자동 테스트했다.

`frontend/src/features/command/`에는 상태 기반 여섯 제어 버튼, 확인 Modal,
최근 Command 목록과 상세 Drawer가 있다. ACK와 최종 Result, 실패·거절·Timeout을
구분하고 `COMMAND_STATUS_CHANGED`와 SSE 재연결 뒤 REST 정본을 다시 조회한다.

정상과 비정상 E2E는 실제 TLS Mosquitto와 Python Mock Robot을 연결해
REST→DB→TLS Broker→Mock→Result→DB→SSE→React를 통과했다. Broker 발행 실패,
업무 실패, 안전 거절, Timeout, REST/MQTT 중복과 늦은 Result까지 확인했으며
권한·Robot Scope·CSRF·감사도 실제 로그인 세션과 Chromium에서 검증했다.
로컬 자동 검증 이후 남은 범위는 실제 Jetson·EC2 Broker 왕복이다.

---

## 1. 요구사항과 수용 기준

### 1.1 제공 Command

| Command | Backend | Frontend | 파라미터 핵심 |
| --- | :---: | :---: | --- |
| `START_MISSION` | O | O | `mission_id`, `zone_id` |
| `PAUSE_MISSION` | O | O | `reason` |
| `RESUME_MISSION` | O | O | 없음 또는 계약 기본값 |
| `COMPLETE_MISSION` | O | O | 없음(정상 완료) |
| `CANCEL_MISSION` | O | O | `reason` |
| `EMERGENCY_STOP` | O | O | `reason` |
| `CLEAR_EMERGENCY_STOP` | O | O | `reason` |
| `RETURN_TO_CHARGE` | X | X | 현재 범위 제외 |

실행 중 긴급정지는 Mission ID를 보존하고 `MISSION_PAUSED`를 발행한다. 긴급정지
해제는 활성 Mission을 `PAUSED`에 두며, 관리자가 별도 `RESUME_MISSION`을 실행해야
`MISSION_RESUMED`와 함께 주행을 재개한다.

### 1.2 REST

| Method | Path | Permission |
| --- | --- | --- |
| POST | `/api/v1/robots/{robotId}/commands` | `ROBOT_COMMAND_EXECUTE` |
| GET | `/api/v1/robots/{robotId}/commands` | `ROBOT_COMMAND_VIEW` |
| GET | `/api/v1/commands/{commandId}` | `ROBOT_COMMAND_VIEW` |
| GET | `/api/v1/commands/{commandId}/results` | `ROBOT_COMMAND_VIEW` |

POST는 CSRF와 Robot `OPERATOR+` Scope, UUID `Idempotency-Key`를 요구한다.
범위 밖 Robot·Command는 존재 여부를 노출하지 않도록 404다.

### 1.3 상태

```text
CREATED
SENT
ACCEPTED
COMPLETED
FAILED
REJECTED
EXPIRED
PUBLISH_FAILED
```

허용되지 않는 역전이는 거부한다. 특히:

- `EXPIRED → COMPLETED` 금지
- 종결 상태에서 다른 종결 상태로 변경 금지
- 늦은 Result는 기록 정책에 따라 보존해도 현재 상태를 되돌리지 않음

### 1.4 완료 기준

- DB 저장과 필수 감사가 같은 Transaction이다.
- MQTT Publish는 DB 저장 뒤 실행한다.
- Publish 결과에 따라 `SENT` 또는 `PUBLISH_FAILED`가 된다.
- Command Request는 QoS 1, Retain `false`, Message Expiry를 사용한다.
- 같은 REST Idempotency Key는 같은 결과를 반환하고 재발행하지 않는다.
- Robot은 같은 `command_id`를 다시 받아도 동작을 다시 실행하지 않는다.
- Scheduler가 만료된 미종결 Command를 `EXPIRED`로 바꾼다.
- 화면은 ACK와 최종 Result를 구분하고 재연결 뒤 REST로 복구한다.

---

## 2. 무엇을 만들었나

### 2.1 DB

Migration V21:

```text
robot.robot_command
robot.robot_command_result
```

V23에서 Command의 Mission·Area FK를 실제 정본에 연결했고 V25에서 Robot별 활성
Mission 단일화로 mission_id 없는 PAUSE·RESUME·CANCEL 대상 결정성을 보강했다.

### 2.2 Backend

```text
RobotCommandController
RobotCommandCreationService
RobotCommandLifecycleService
RobotCommandQueryService
RobotCommandResultIngestionService
RobotCommandTimeoutService
RobotCommandTimeoutJob
RobotCommandRepository
MqttV2Publisher
CommandResultMqttMessageHandler
```

### 2.3 MQTT

발행:

```text
tbs/v2/robots/{mqtt_id}/commands/request
```

수신:

```text
tbs/v2/robots/{mqtt_id}/commands/result
```

JSON 만료시각과 MQTT 5 Message Expiry는 같은 남은 TTL을 기준으로 계산한다.

Broker 연결이 끊기면 Paho 자동 재연결과 Backend Watchdog가 연결 복구를 시도한다.
재시도 최대 간격은 기본 5초이며 Spring Duration 형식으로 바꿀 수 있다.

```dotenv
MQTT_RECONNECT_MAX_DELAY=5s
```

예를 들어 `3s`, `5000ms`, `1m`처럼 지정한다. 간격은 1초부터 지수적으로
증가하되 설정값을 넘지 않고, Watchdog도 같은 간격으로 단절 상태를 확인한다.

### 2.4 SSE

Command 상태가 바뀌면 `COMMAND_STATUS_CHANGED`를 발행한다. Frontend는 Event의
부분 Payload만으로 최종 화면을 만들지 않고 Command·Result REST를 다시 읽어야 한다.

---

## 3. 설계 판단과 그 이유

### 3.1 DB 저장 후 Publish

Publish부터 하고 DB 저장이 실패하면 Robot은 실행했는데 서버에는 Command가 없는
상태가 된다. 먼저 정본을 저장하고 Publish 결과를 상태로 남긴다.

### 3.2 Publish 실패도 행을 보존한다

실패 시 행을 삭제하면 사용자는 무엇을 요청했는지, 재시도해도 되는지 알 수 없다.
`PUBLISH_FAILED`로 보존해 감사·장애 분석·안전한 재시도를 지원한다.

### 3.3 REST와 Robot 양쪽에 멱등성을 둔다

브라우저 재시도와 MQTT QoS 1 재전달은 서로 다른 중복 원인이다.

- REST: `Idempotency-Key`
- MQTT/Robot: `command_id`

한쪽만 막으면 다른 구간에서 중복 동작이 생긴다.

### 3.4 ACK와 최종 Result를 분리했다

`ACCEPTED`는 Robot이 받았다는 뜻이지 실행 완료가 아니다. 화면에서 둘을 모두
“성공”으로 표시하면 실제 실행 실패를 놓친다.

### 3.5 Frontend 버튼 정책은 최종 보안이 아니다

READY·RUNNING·PAUSED·SAFE_STOP·Offline에 따라 버튼을 숨기거나 비활성화하지만,
동시에 상태가 바뀔 수 있으므로 Backend가 업무 규칙과 Scope를 다시 검사한다.

---

## 4. 검증과 재현 명령

Backend 집중 검증:

```powershell
cd web/backend
.\gradlew.bat test --rerun-tasks `
  --tests "com.gilbom.robot.controller.RobotCommandControllerTest" `
  --tests "com.gilbom.robot.integration.RobotCommandLifecycleIntegrationTest"
```

검증 범위:

- 정상 `CREATED→SENT→ACCEPTED→COMPLETED`
- Publish 실패
- FAILED·REJECTED
- Result 없음 Timeout
- 만료 뒤 늦은 Result
- 같은 Idempotency Key
- 같은 Result 중복
- 범위 밖 Robot·Command
- CSRF·Permission
- 감사 실패 Rollback

과거 완료 기록:

- Command Backend 전체 생명주기 통합 테스트 통과
- 실제 TLS Broker에서 2초 Command는 Offline Queue에서 만료되어 미전달
- 30초 Command는 재연결 뒤 1건 전달
- QoS 1, Retain `false`, Session Present 확인

정상 Broker·Browser E2E:

```powershell
cd web
python scripts/command-roundtrip-e2e.py
```

- 현재 Backend 소스로 이미지를 빌드하고 임시 PostgreSQL DB·Backend를 기동한다.
- 실제 TLS Mosquitto에 Mock Robot을 연결하고 Chromium에서 여섯 명령을 실행한다.
- 각 명령의 고유 `commandId`, `COMPLETED`, `ACCEPTED`·`COMPLETED` Result를
  화면·REST·DB에서 대조한다.
- 최종 Mission `CANCELLED`, Robot mode `READY`, Mission 분리 상태를 검증한다.
- 완료 뒤 임시 Backend·DB·Vite·Mock을 정리하고 정상 Compose Backend를 복구한다.

비정상 Broker·Browser E2E:

```powershell
cd web
python scripts/command-abnormal-e2e.py
```

- Broker를 실제 중지해 `PUBLISH_FAILED`와 발행 시도·오류 보존을 검증한다.
- Backend를 재시작하지 않고 MQTT 연결이 자동 복구되는지 검증한다.
- Mock Override로 `FAILED`, `REJECTED`와 `reasonCode`의 DB·화면 표시를 검증한다.
- Result를 31초 지연해 `EXPIRED` 화면과 늦은 Result의 DB 무부작용을 확인한다.
- 같은 REST Idempotency Key가 Command·Publish를 늘리지 않는지 확인한다.
- 같은 MQTT `command_id`가 동작을 반복하지 않고 기존 `result_id`를 재발행하며
  Backend가 중복 Result를 저장하지 않는지 확인한다.

보안 Broker·Browser E2E:

```powershell
cd web
python scripts/command-security-e2e.py
```

- 익명 Command 조회가 `401 AUTH_UNAUTHENTICATED`인지 확인한다.
- `ROBOT_COMMAND_EXECUTE`가 제거된 사용자는 화면이 조회 전용이고 직접 발행도
  `403 AUTH_FORBIDDEN`인지 확인한다.
- 전역 실행 권한이 있어도 대상 Robot 배정이 `VIEWER`면 직접 발행이
  `404 ROBOT_NOT_FOUND`인지 확인해 객체 존재 여부를 숨긴다.
- `OPERATOR` 배정 사용자의 CSRF 누락·불일치 발행이 각각 거절되는지 확인한다.
- 정상 화면 발행과 같은 Idempotency Key 재요청 뒤 Command, Publish,
  `ROBOT_COMMAND_ISSUED` 감사가 각각 한 건만 남는지 DB와 대조한다.
- `AUTH_CSRF_FAILED`, `AUTH_ACCESS_DENIED` 거절 감사도 행위 사용자와 요청
  경로가 일치하는지 확인한다.

---

## 5. 작업 중 겪은 함정

### JSON `expires_at`만 넣으면 Broker Queue를 막지 못한다

Broker는 Payload를 해석하지 않는다. MQTT 5 Message Expiry가 없으면 만료된
Command가 Robot 재연결 뒤 도착할 수 있다.

### 늦은 Result가 만료 상태를 되돌릴 수 있다

Result Handler가 최종 상태를 조건 없이 UPDATE하면 `EXPIRED`가 `COMPLETED`로
바뀐다. 상태 전이 조건과 원 Command의 정확한 만료시각을 함께 검사한다.

### Publish 실패 시 Transaction 전체 Rollback은 위험하다

Command 행까지 없애면 요청 흔적과 감사가 사라진다. 업무 Transaction과 외부
Publish 결과를 구분해 실패 상태를 보존한다.

### 같은 Command를 Mock가 두 번 실행할 수 있다

Backend의 중복 발행 방지만으로 충분하지 않다. QoS 1과 재연결 때문에 Robot
자체의 처리 완료 `command_id` Cache가 필요하다.

---

## 6. 미완 항목

### 내 몫 — P0

1. 실제 Jetson·EC2 Broker 왕복

### 팀원 A

- 공통 Router·RBAC·CSRF와 Command 화면 연결 검토
- 운영 Nginx SSE Buffering
- EC2 Broker·Secret

### Embedded팀

- 실제 Command 실행
- `command_id` 중복 실행 방지
- ACCEPTED와 최종 Result 시점
- 안전 조건 거절 Reason Code

---

## 7. 관련 문서와 기록

- `DailyLog/2026-07-30_Day6_상세_구현.md` 6~8절
- `TROUBLESHOOTING/3_MqttTtlAndMessageExpiry.md`
- `TROUBLESHOOTING/14_CommandNormalRoundtrip.md`
- [팀원 B Story 현황](../../2026-07-28-story-subtask-status.md)
