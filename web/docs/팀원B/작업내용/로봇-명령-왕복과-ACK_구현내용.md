# Robot Command 발행·ACK·Timeout Backend 구현 내용

> 담당: 팀원 B — Robot 제어
> 작업일: 2026-07-28 ~ 2026-07-30
> 연결 Jira: `S15P11D101-377`, `DAY6-B-02`, `DAY6-B-03`
> 최종 상태: **Backend DONE / Frontend·전체 E2E IN PROGRESS**

이 문서는 관리자 요청이 REST·DB·MQTT·Robot Result를 거쳐 다시 관리 화면으로
돌아오는 **Command 왕복 흐름만** 묶는다. Patrol Area 생성, Mission 시간
진행률과 Robot 일반 Telemetry 관제는 별도 작업 문서다.

관련 정본:
[MQTT v2 계약](../../final-mqtt-topic-schema-v2.md),
[OpenAPI](../../openapi-v1.yaml),
[권한 매트릭스](../../authorization-matrix.md)

---

## 0. 3분 요약

Command Backend는 구현됐다.

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

하지만 사용자가 실제로 누를 Command 화면은 아직 없다.
`frontend/src/features/command/` 폴더가 없으며 버튼·확인 Modal·ACK·Timeout
표시도 없다. Mock Robot의 Command Handler는 존재하지만
REST→TLS Broker→Mock→DB→SSE→React 전체 증거도 아직 없다.

---

## 1. 요구사항과 수용 기준

### 1.1 제공 Command

| Command | Backend | Frontend 계획 | 파라미터 핵심 |
| --- | :---: | :---: | --- |
| `START_MISSION` | O | O | `mission_id`, `zone_id` |
| `PAUSE_MISSION` | O | O | `reason` |
| `RESUME_MISSION` | O | O | 없음 또는 계약 기본값 |
| `CANCEL_MISSION` | O | O | `reason` |
| `EMERGENCY_STOP` | O | O | `reason` |
| `CLEAR_EMERGENCY_STOP` | O | O | 없음 |
| `RETURN_TO_CHARGE` | X | X | 현재 범위 제외 |

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

전체 Browser E2E는 아직 미실행이다.

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

1. `features/command/` Type·API Client
2. Permission·메뉴·Route
3. 상태 기반 버튼
4. 중요 명령 확인 Modal
5. Command·Result·Timeout 화면
6. SSE 뒤 REST 정본 갱신
7. 정상·실패·거절·만료·중복 Browser E2E
8. 실제 TLS Broker 전체 왕복

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
- [팀원 B Story 현황](../../2026-07-28-story-subtask-status.md)
