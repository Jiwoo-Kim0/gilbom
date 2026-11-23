# MQTT 비-Outbox 통신 및 재연결 처리 계약

> 상태: 구현 전 합의안
> 대상: Backend, AGS1/Jetson Robot, MQTT Broker 운영
> 기준 계약: MQTT v2
> 기준일: 2026-08-06
> 관련 문서: `docs/mission-completion-synchronization.md`,
> `embedded/jetson_main/docs/INGEST-RECEIPT.md`,
> `web/docs/final-mqtt-topic-schema-v2.md`

## 1. 제품 정책

이번 제품에서는 **Application Outbox 서비스를 제공하지 않는다.**

- Robot은 통신 단절 중 발생한 Event를 목록으로 쌓지 않는다.
- Robot은 재연결 후 과거 Event 여러 건을 일괄 재전송하지 않는다.
- Backend도 Receipt 발행을 위한 별도 Outbox를 운영하지 않는다.
- 재연결 후에는 과거 Event 대신 현재 Presence·State·Telemetry Snapshot을 전송한다.
- `receipt_required`는 `false`를 유지한다.

현재 `embedded/jetson_main`에 구현된 SQLite Event Outbox와 재연결 시
`flush_outbox()` 동작은 이 제품 정책과 다르다. 실제 코드 반영 시 Event 발행 경로에서
Outbox 저장·재전송 기능을 제거해야 한다. 설정으로 다시 켜는 선택 기능도 제공하지
않는다.

여기서 제외하는 것은 Application 수준의 저장·재전송 서비스다. Backend가 잠시
끊겼지만 Robot과 Broker 연결은 유지된 경우 Broker가 영속 Session 안에서 QoS 1
메시지를 보관하는 것은 MQTT 전송 계층의 기능으로 허용한다. Robot 자체가 Broker와
끊긴 동안에는 Event를 보관하지 않는다.

## 2. 제공하는 보장과 제공하지 않는 보장

### 2.1 제공하는 보장

- 연결 중 Event는 MQTT QoS 1로 한 번 발행한다.
- Backend는 QoS 1 중복 전달에 대비해 `event_id` 기준 멱등 처리를 한다.
- Backend는 Event·Mission·History를 하나의 DB Transaction으로 처리한다.
- Backend는 영속 MQTT 구독 Session으로 Backend의 짧은 재접속 공백을 줄인다.
- 재연결 후 Robot의 최신 상태를 Server 상태와 대조한다.

### 2.2 제공하지 않는 보장

- Robot과 Broker의 연결이 끊긴 동안 발생한 Event의 사후 전달
- Robot 프로세스가 Event 발행 전에 종료된 경우의 복구
- PUBACK 이후 Backend DB 저장 성공을 Robot이 끝까지 확인하는 기능
- Receipt 유실 후 동일 Event를 다시 보내 최종 수렴하는 기능
- 5분 이상 단절된 Mission 완료·Incident·Safety·Battery Event 복구

따라서 PUBACK은 Broker가 메시지를 인수했다는 뜻일 뿐 Backend DB 저장 성공을 뜻하지
않는다. Outbox를 제공하지 않는 정책에서는 이 신뢰성 공백을 알려진 제한으로
수용한다.

## 3. 정상 통신 흐름

```text
Robot Event 생성
  -> 현재 MQTT 연결 여부 확인
  -> 연결 중이면 QoS 1로 즉시 발행
  -> 연결 중이 아니면 Event 폐기 및 유실 로그 기록
  -> Broker가 PUBACK 반환
  -> Backend가 Event 수신
  -> Backend DB Transaction commit
  -> 필요하면 Best-effort Receipt 발행
```

Receipt는 운영 진단용으로 발행할 수 있지만 Robot Outbox의 삭제나 재전송을 제어하지
않는다. Receipt 발행 실패도 별도 Queue에 저장하지 않는다.

## 4. Backend 책임과 패치 범위

### 4.1 영속 MQTT 구독 Session

Backend 연결 옵션은 다음을 모두 만족해야 한다.

- `cleanStart = false`
- `SessionExpiryInterval = 3600`
- 재기동 후에도 동일한 Backend MQTT `clientId` 사용
- Event 구독 QoS 1 유지
- Broker의 영속 Session 지원과 설정 확인
- 여러 Backend 인스턴스가 동일 `clientId`로 접속하지 않도록 보장

이는 Robot Outbox가 아니다. Robot이 이미 Broker에 발행한 QoS 1 메시지를 Backend의
짧은 중단 동안 Broker가 보관하는 전송 계층 보완책이다.

### 4.2 Event 멱등 처리

Outbox가 없어도 MQTT QoS 1 자체에서 중복 전달될 수 있으므로 Backend 멱등 처리는
유지한다.

- 동일 `event_id`와 동일 불변 Payload는 업무 데이터를 중복 반영하지 않는다.
- 같은 `event_id`지만 불변 Payload가 다르면 `MQTT_EVENT_CONFLICT`로 거부한다.
- 동일 `(boot_id, seq)`가 다른 메시지에 사용되면 Sequence 충돌로 거부한다.
- Event·Mission·History 변경은 한 Transaction에서 commit한다.

### 4.3 Best-effort Receipt

Receipt 기능을 유지한다면 Event Transaction이 commit된 후 즉시 한 번 발행한다.
Backend Receipt Outbox와 발행 재시도 Worker는 만들지 않는다.

| 처리 결과 | Receipt | `incident_id` |
| --- | --- | --- |
| 신규 Mission·Safety·Battery Event 저장 | `STORED` | `null` |
| 신규 결함 생성 및 최초 관측 저장 | `STORED` | `INC-YYYYMMDD-NNNNNN` |
| 기존 결함에 관측 병합 | `MERGED` | 기존 Incident 업무 코드 |
| 이미 처리한 동일 `event_id` | `DUPLICATE` | 연결된 코드 또는 `null` |
| 최종 거부 | `REJECTED` | 보통 `null` |

`incident_id`는 Defect UUID가 아니라 업무 코드를 사용한다. Defect 업무 처리는
변경하지 않고 Receipt 전용 조회만 허용한다.

Robot은 Receipt를 수신하더라도 운영 로그와 상태 관측에만 사용한다. Receipt Timeout을
이유로 Event를 재전송하지 않는다.

### 4.4 재연결 상태 조정

Robot 재연결 시 Backend는 새 Presence·State·Telemetry Snapshot을 기준으로 Server
상태와 비교한다.

| Server 상태 | Robot Snapshot | Backend 처리 |
| --- | --- | --- |
| Mission `RUNNING` | 같은 Mission `RUNNING` | 정상 계속 수행 |
| Mission `RUNNING` | 같은 Mission `PAUSED` | `PAUSED` 불일치 표시 또는 정책에 따라 반영 |
| Mission `RUNNING` | Mission 없음 | `ROBOT_MISSION_STATE_LOST` 확인 필요 |
| Mission `RUNNING` | 다른 Mission | 심각 불일치, 자동 명령 금지 |
| Mission `COMPLETED` | Mission 없음 또는 대기 | 정상 종료 상태 유지 |

누락된 `MISSION_COMPLETED` Event를 추측해 Mission을 자동 완료하지 않는다. 현재 State의
`mission = null`만으로는 완료, 취소, 실패, 로컬 상태 손실을 구분할 수 없기 때문이다.

### 4.5 이전 Boot 메시지

Robot Outbox 재전송은 없지만 Backend 영속 Session에 남아 있던 QoS 1 Event가 Robot
재부팅 후 늦게 전달될 수 있다. 따라서 TTL 안의 EVENT에 한해서만 이전 Boot 메시지를
제한적으로 허용하는 계약은 유지할 수 있다.

- 같은 Robot에 속했던 `boot_id`인지 확인한다.
- 기존 `event_id` 또는 `(boot_id, seq)` 충돌을 검사한다.
- 현재 `boot_id`와 `last_seen_seq`를 변경하지 않는다.
- STATE, TELEMETRY, COMMAND RESULT에는 적용하지 않는다.

### 4.6 운영 Robot 등록

운영 배포 전 다음을 확인한다.

- `robot.robot`에 실제 `mqtt_id`가 등록돼 있는지 확인
- Robot 활성 상태 확인
- MQTT Credential 발급 여부 확인
- Broker publish·subscribe ACL 확인
- Backend Broker 계정의 Robot Event 구독 권한 확인

## 5. Robot 책임과 패치 범위

### 5.1 Event 발행

- Event 발생 시 현재 연결 상태를 확인한다.
- 연결 중이면 MQTT QoS 1로 즉시 발행한다.
- 연결 중이 아니면 SQLite Outbox에 넣지 않는다.
- 발행하지 못한 Event는 유실 사실과 원인을 로그·Metric에 남긴다.
- 재연결 후 유실 Event를 다시 생성하거나 일괄 전송하지 않는다.
- 재연결 직후 `Presence -> State -> Telemetry` 순서로 최신 Snapshot만 보낸다.

Event 유실 로그에는 비민감 식별 정보와 다음 항목을 남긴다.

- Event 종류
- `event_id`
- `mission_id`가 있으면 Mission 식별자
- 발생 시각
- 유실 사유: `MQTT_DISCONNECTED`, `PUBLISH_FAILED`, `PROCESS_STOPPING` 등

### 5.2 기존 Outbox 제거

현재 `embedded/jetson_main`의 Event 발행은 SQLite Outbox를 항상 거친다. 제품 정책을
적용할 때 다음 동작과 설정을 운영 코드에서 제거한다.

- Event 발행 전 `outbox.enqueue()`
- 재연결 후 `_drain_outbox()`
- 주기적인 `flush_outbox()`
- Receipt를 기다리는 `receipt_required` 분기
- Outbox 적체를 Storage Health로 판단하는 로직

Mission 활성 상태와 처리한 `command_id`의 영속화는 안전 복구와 중복 명령 방지를 위한
상태 저장이므로 Event Outbox와 구분해 유지한다.

### 5.3 Receipt 파서

Receipt 구독을 진단 목적으로 유지한다면 전체 필드 집합의 완전 일치를 요구하지 않는다.

```python
required_fields.issubset(receipt.keys())
```

필수 필드는 다음과 같다.

- `schema_version`
- `event_id`
- `ingest_status`
- `incident_id`
- `received_at`

필수 필드가 없으면 거부하고 Server가 추가한 모르는 필드는 무시한다. Receipt는 로그와
관측에만 사용하고 로컬 재전송을 시작하지 않는다.

### 5.4 타임스탬프 공통 파서

Python 3.10의 `datetime.fromisoformat()`에 직접 의존하지 않고 UTC Instant 공통
파서를 사용한다.

- 소수부 없음, 3자리, 6자리, 9자리 지원
- `Z`와 명시적 UTC Offset 지원
- 7~9자리 소수부는 6자리로 명시적으로 절삭
- Timezone이 없는 시각은 거부

적용 대상은 Receipt `received_at`, Command `issued_at`·`expires_at`, 영속 Mission
`started_at`, Event `occurred_at`이다.

### 5.5 `zone_id`

`zone_id`는 형식을 해석하지 않는 불투명 문자열이다.

- `^[A-Z]-[0-9]{2}$` 정규식과 경고 제거
- 비어 있지 않은 문자열인지 확인
- `START_MISSION`으로 받은 값을 변경하지 않고 저장·echo
- 대소문자 변환과 별도 정규화 금지

### 5.6 NTP

Jetson에는 chrony를 설치·활성화한다.

- 통신 시작 전 동기화 여부 또는 Offset 확인
- 현재 Offset과 마지막 동기화 시각 기록
- Server보다 5초 이상 미래인 Event가 발생하지 않도록 감시
- 시간 미동기화 상태를 Readiness와 운영 경고에 반영

## 6. Mission 완료에 미치는 영향

현재 최종 완료 조건은 Backend가 `MISSION_COMPLETED` Event를 DB에 저장하는 것이다.
Outbox가 없으면 다음 장애에서 Server Mission이 `RUNNING`에 남을 수 있다.

```text
Robot이 실제 주행을 종료하고 IDLE 도달
  -> Robot/Broker 연결 단절
  -> MISSION_COMPLETED 발행 실패 및 폐기
  -> 재연결 후 Robot은 mission = null Snapshot 전송
  -> Backend는 완료인지 상태 손실인지 구분할 수 없음
  -> Mission을 자동 완료하지 않고 확인 필요로 유지
```

따라서 이 제품 정책에서는 “통신 단절 중 완료된 Mission도 자동으로 최종 수렴한다”는
보장을 제공하지 않는다. 운영 화면에는 다음 상태가 필요하다.

- 완료 Event 확인 대기
- Robot Mission 상태 소실
- Server·Robot Mission 불일치
- 관리자 확인 필요

Outbox 없이 자동 완료 복구까지 요구한다면 별도 계약 변경이 필요하다. 가능한 선택지는
다음 두 가지지만 이번 합의에는 포함하지 않는다.

1. Command Result `COMPLETED`를 Mission 최종 완료 근거로 승격한다.
2. State Snapshot에 마지막 Terminal Mission의 ID·결과·완료 시각·원인 Command ID를
   추가하고 Server가 이를 Reconciliation 근거로 사용한다.

## 7. Incident·Safety·Battery Event에 미치는 영향

- Robot이 Broker와 연결된 동안 전달된 Event만 Server DB와 Web에 반영된다.
- 단절 중 발생한 결함 관측은 재연결 후 결함 Marker로 복구되지 않는다.
- 단절 중 발생한 Safety·Battery Event도 이력으로 복구되지 않는다.
- 현재 State·Telemetry에 남아 있는 최신 안전·배터리 상태만 재연결 후 반영된다.
- Defect 업무 로직은 변경하지 않는다.

이 결과는 Outbox를 제공하지 않는 제품 정책의 명시적인 데이터 유실 범위다.

## 8. TTL 정책

과거 Event를 Robot이 재전송하지 않으므로 Event TTL은 현재의 60~300초 범위를
유지한다. MQTT 5 Event 발행 시 남은 TTL을 `MessageExpiryInterval`로 설정해 Broker가
만료 Event를 늦게 전달하지 않도록 한다.

Backend는 계속 `occurred_at + ttl_sec`를 기준으로 만료를 검사한다. 재전송으로
`occurred_at`이나 TTL을 갱신하지 않는다.

## 9. Robot 팀 전달사항

1. 제품에서 Event Outbox와 재연결 후 과거 Event 일괄 재전송을 제공하지 않는다.
2. 기존 SQLite Outbox는 Event 발행 경로에서 제거하며 다시 켜는 설정을 제공하지 않는다.
3. 연결 중인 Event만 QoS 1로 즉시 발행한다.
4. 연결되지 않았거나 발행에 실패한 Event는 유실 로그·Metric을 남기고 폐기한다.
5. 재연결 후에는 `Presence -> State -> Telemetry` 최신 Snapshot만 발행한다.
6. `receipt_required`는 `false`로 유지한다.
7. Receipt는 사용하더라도 진단용이며 Timeout에 따른 재전송은 하지 않는다.
8. `zone_id` 패턴 검사를 제거한다.
9. Receipt 추가 필드와 9자리 Timestamp를 처리한다.
10. chrony 동기화 상태를 기기 운영 조건에 포함한다.
11. Mission 상태와 `command_id` 영속화는 Outbox가 아니므로 유지한다.
12. 실제 배포 대상 통신 트리는 `embedded/jetson_main`으로 한다.

현장 기기에 `embedded/gseok` 기반 프로세스가 남아 있다면 전환 기간에는 사용 중인
트리를 먼저 식별한다. 전환 완료 후에는 `embedded/jetson_main`만 운영 기준으로
유지한다.

## 10. 배포 순서

1. Application Outbox 미제공과 데이터 유실 범위를 양팀 계약에 명시한다.
2. `embedded/jetson_main`을 기준 통신 트리로 선언한다.
3. Backend에 영속 MQTT Session을 적용한다.
4. Backend Event 멱등성과 이전 Boot의 Broker 지연 Event 처리 규칙을 검증한다.
5. Robot Event 발행 경로에서 SQLite Outbox와 재연결 Drain을 제거한다.
6. Robot에 `zone_id`, Timestamp, NTP 호환 패치를 적용한다.
7. 운영 Robot 등록, MQTT Credential·ACL을 확인한다.
8. 재연결 Snapshot과 Mission 불일치 표시를 검증한다.
9. 필요하면 Best-effort Receipt를 진단 기능으로 별도 활성화한다.

## 11. 필수 검증 시나리오

- [ ] Backend 중단 중 Broker가 QoS 1 Event를 보관하고 동일 Backend `clientId` 재접속
      후 TTL 안에 전달하는지 확인
- [ ] Robot과 Broker 연결 단절 중 Event가 Outbox에 저장되지 않는지 확인
- [ ] Robot 재연결 후 과거 Event가 발행되지 않는지 확인
- [ ] Robot 재연결 후 Presence·State·Telemetry만 발행되는지 확인
- [ ] QoS 1 중복 Event가 업무 데이터를 중복 반영하지 않는지 확인
- [ ] 동일 `event_id`와 다른 불변 Payload가 `MQTT_EVENT_CONFLICT`로 거부되는지 확인
- [ ] 소수부 0·3·6·9자리 Timestamp 처리 확인
- [ ] `A-01` 형식이 아닌 `zone_id`가 변경 없이 왕복하는지 확인
- [ ] Mission 완료 Event 유실 시 Server가 임의 완료하지 않고 확인 필요로 남기는지 확인
- [ ] 단절 중 Incident가 재연결 후 Marker로 생성되지 않는 정책을 확인
- [ ] 미등록 Robot Event가 `ROBOT_NOT_FOUND`로 거부되는지 확인

## 12. 수정한 클래스와 수정해야 할 클래스

이 절의 “수정한 클래스”는 현재 작업 트리에 실제 변경이 있는 운영 코드를 뜻한다.
“수정해야 할 클래스”는 본 문서의 Outbox 미제공 정책을 코드에 적용하기 위해 아직
변경해야 하는 Robot 항목이다. Backend 항목은 2026-08-06에 반영했으며 테스트와
문서만 바꾼 파일은 별도 표로 분리한다.

### 12.1 이미 수정한 Backend 클래스

아래 변경은 Mission 완료 명령과 Server·Robot 상태 동기화 1차 구현에 반영돼 있다.

| 클래스 | 파일 | 반영 내용 |
| --- | --- | --- |
| `MissionCompletionCommandCreationService` | `web/backend/src/main/java/com/gilbom/robot/mission/MissionCompletionCommandCreationService.java` | 자동 완료 전 Online·Telemetry freshness·Mission·안전 상태와 직전 명령 검사 |
| `MissionCompletionCommandDispatcher` | `web/backend/src/main/java/com/gilbom/robot/mission/MissionCompletionCommandDispatcher.java` | 자동 완료 Payload에 Mission 코드와 `PLANNED_DURATION_REACHED` 포함 |
| `RobotCommandCreationService` | `web/backend/src/main/java/com/gilbom/robot/command/RobotCommandCreationService.java` | 수동 완료 전 상태 검사와 `OPERATOR_COMPLETED` 지정 |
| `RobotCommandService` | `web/backend/src/main/java/com/gilbom/robot/command/RobotCommandService.java` | 저장된 COMPLETE 파라미터를 MQTT Payload로 전달 |
| `RobotCommandCreateRequest` | `web/backend/src/main/java/com/gilbom/robot/data/api/request/RobotCommandCreateRequest.java` | REST 요청과 Server 내부 COMPLETE 파라미터 호환 |
| `CommandRequestPayload` | `web/backend/src/main/java/com/gilbom/robot/data/mqtt/v2/outbound/CommandRequestPayload.java` | COMPLETE용 `missionId`, `completionReason` 계약 추가 |
| `MissionEventPayload` | `web/backend/src/main/java/com/gilbom/robot/data/mqtt/v2/inbound/MissionEventPayload.java` | 선택적 `causationCommandId` 수신 허용 |
| `RobotEventIngestionService` | `web/backend/src/main/java/com/gilbom/robot/event/RobotEventIngestionService.java` | 원인 Command의 Robot·Mission 일치 검증과 Event 저장 |
| `RobotStatusIngestionService` | `web/backend/src/main/java/com/gilbom/robot/status/RobotStatusIngestionService.java` | State의 Mission 코드를 내부 Mission ID로 현재 상태에 저장 |
| `TelemetryIngestionService` | `web/backend/src/main/java/com/gilbom/robot/telemetry/TelemetryIngestionService.java` | Telemetry의 Mission 코드를 내부 Mission ID로 저장 |
| `MissionProgressService` | `web/backend/src/main/java/com/gilbom/robot/mission/MissionProgressService.java` | Robot Online일 때만 진행률 갱신 |

DB에는 `V43__synchronize_mission_completion_state.sql`이 추가돼 있다. 이 파일은 Java
클래스는 아니지만 현재 상태의 `mission_id`, Mission Event의
`causation_command_id`, 완료 재시도용 인덱스를 담당한다.

### 12.2 이미 수정한 Robot 클래스

| 클래스 | 파일 | 반영 내용 |
| --- | --- | --- |
| `MessageFactory` | `embedded/jetson_main/comms/factory.py` | Mission Event에 선택적 `causation_command_id` 직렬화 |
| `DriveCommandRunner` | `embedded/jetson_main/communication/commands.py` | COMPLETE 대상 Mission 검사, `MISSION_MISMATCH`, 완료 사유와 원인 Command ID 전달 |
| `EventPublisher` | `embedded/jetson_main/communication/events.py` | `MISSION_COMPLETED`에 완료 사유와 `causation_command_id` 포함 |

이 세 클래스의 현재 변경은 Mission 완료 계약용이다. Event Outbox 제거는 아직
반영하지 않았다.

### 12.3 2026-08-06에 반영한 Backend 신뢰성 클래스

| 클래스 | 파일 | 반영 내용 |
| --- | --- | --- |
| `MqttConnectionConfig` | `web/backend/src/main/java/com/gilbom/robot/mqtt/config/MqttConnectionConfig.java` | `cleanStart(false)`와 `SessionExpiryInterval=3600` 적용. `robot-test` 기본 발행 토픽을 제거해 동적 `MqttHeaders.TOPIC` 누락 시 실패하도록 변경 |
| `MqttProperties` | `web/backend/src/main/java/com/gilbom/robot/mqtt/config/MqttProperties.java` | Session Expiry 설정 필드·기본값·범위 검증 추가, 사용하지 않는 `publishTopic` 제거 |
| `MqttReplayProtectionService` | `web/backend/src/main/java/com/gilbom/robot/mqtt/replay/MqttReplayProtectionService.java` | Broker가 늦게 전달한 freshness 유효 이전 Boot EVENT만 현재 Boot·Sequence를 변경하지 않고 제한 수용 |
| `MissionEventMqttMessageHandler` | `web/backend/src/main/java/com/gilbom/robot/mqtt/handler/MissionEventMqttMessageHandler.java` | commit 이후 Best-effort `STORED`·`DUPLICATE` Receipt 연계 |
| `SafetyEventMqttMessageHandler` | `web/backend/src/main/java/com/gilbom/robot/mqtt/handler/SafetyEventMqttMessageHandler.java` | commit 이후 Best-effort `STORED`·`DUPLICATE` Receipt 연계 |
| `BatteryEventMqttMessageHandler` | `web/backend/src/main/java/com/gilbom/robot/mqtt/handler/BatteryEventMqttMessageHandler.java` | commit 이후 Best-effort `STORED`·`DUPLICATE` Receipt 연계 |
| `IncidentEventMqttMessageHandler` | `web/backend/src/main/java/com/gilbom/robot/mqtt/handler/IncidentEventMqttMessageHandler.java` | commit 이후 Best-effort `STORED`·`MERGED`·`DUPLICATE` Receipt 연계 |
| `IngestReceiptCoordinator` | `web/backend/src/main/java/com/gilbom/robot/mqtt/receipt/IngestReceiptCoordinator.java` | 처리 결과를 Receipt로 변환해 한 번 발행하고, 실패는 DB 처리와 분리해 로그·Metric만 기록 |
| `IncidentReceiptProjectionReader` | `web/backend/src/main/java/com/gilbom/robot/mqtt/receipt/IncidentReceiptProjectionReader.java` | Defect 코드를 수정하지 않고 `event_id`로 `incident_code`와 대표 관측 여부만 조회 |

`IngestReceiptCoordinator`는 다음만 담당한다.

- Transaction commit 이후 `MqttV2Publisher.publishIngestReceipt()` 한 번 호출
- Event 처리 결과를 Receipt 상태로 변환
- `event_id`로 Incident 업무 코드를 읽는 전용 조회
- 발행 실패 로그와 Metric 기록

`MqttV2Publisher`와 `IngestReceiptPayload`에는 필요한 발행 API와 Payload가 이미 있어
재사용할 수 있다. Backend Receipt Outbox, Queue, 재시도 Worker 클래스는 새로 만들지
않는다.

Defect 업무 클래스인 `IncidentEventIngestionService`의 병합·생성 로직은 수정하지
않았다. Incident Receipt는 별도 조회 클래스에서 기존 DB 결과를 읽기만 한다.

### 12.4 수정·삭제해야 할 Robot 클래스

| 우선순위 | 클래스·모듈 | 파일 | 필요한 변경 |
| --- | --- | --- | --- |
| P0 | `MqttClient` | `embedded/jetson_main/comms/client.py` | `Outbox` 주입·enqueue·재전송 Thread·`flush_outbox()`·`_drain_outbox()`·Receipt 대기 분기 제거. 연결 중 Event만 직접 QoS 1 발행하고 실패 시 유실 로그 기록 |
| P0 | `Outbox`, `OutboxRecord` | `embedded/jetson_main/comms/outbox.py` | 제품 기능에서 완전히 삭제. 비활성 설정이나 대체 구현을 남기지 않음 |
| P0 | `MqttConfig` | `embedded/jetson_main/comms/config.py` | `outbox_path`, `receipt_required` 필드와 설정 파싱 제거 |
| P0 | `CommunicationRuntime` | `embedded/jetson_main/communication/runtime.py` | Outbox 경로 계산·주입 제거. 재연결 및 재기동 시 과거 Event를 만들거나 Drain하지 않고 최신 Snapshot만 발행 |
| P0 | `RobotState` | `embedded/jetson_main/communication/robot_state.py` | `_outbox` 의존성과 `pending_count()` 기반 Storage Health 제거. Mission 상태 파일용 저장 경로는 별도 설정으로 유지 |
| P0 | `EventPublisher` | `embedded/jetson_main/communication/events.py` | 직접 발행 실패 반환값을 확인해 Event 유실 로그·Metric 기록. Outbox 저장을 전제로 한 주석과 종료 순서 설명 제거 |
| P1 | `MessageFactory` | `embedded/jetson_main/comms/factory.py` | `sent_at` 갱신 주체를 Outbox로 설명한 주석 제거. Payload 계약 자체는 유지 |
| P1 | `DriveCommandRunner` | `embedded/jetson_main/communication/commands.py` | COMPLETE Event 발행 실패가 가능한 비-Outbox 정책을 로그·결과 처리에 반영 |

`CommunicationRuntime`이 현재 Mission 상태 파일의 저장 디렉터리를
`outbox_path`의 부모 디렉터리에서 가져오므로, Outbox를 삭제할 때 Mission 상태 저장까지
함께 사라지지 않도록 `state_storage_dir` 같은 독립 설정으로 분리해야 한다.

### 12.5 함께 수정·삭제해야 할 Robot 파일

다음은 클래스는 아니지만 Outbox 제거를 완료하려면 함께 변경해야 한다.

| 파일 | 필요한 변경 |
| --- | --- |
| `embedded/jetson_main/comms/__init__.py` | `Outbox`, `OutboxRecord` 공개 Export와 lazy import 제거 |
| `embedded/jetson_main/comms/topics.py` | Event 종류를 Outbox 대상으로 설명한 주석 제거 |
| `embedded/jetson_main/comms.yaml` | `outbox_path`, `receipt_required` 설정 제거 및 독립 Mission 상태 저장 경로 추가 |
| `embedded/jetson_main/main.py` | Event 실패를 Outbox가 복구한다고 설명한 주석과 가정 제거 |
| `embedded/jetson_main/communication/evidence.py` | Evidence 정리 정책의 Outbox 상수·주기 참조 제거 |

### 12.6 수정해야 할 테스트

| 테스트 | 필요한 변경 |
| --- | --- |
| `embedded/jetson_main/tests/test_client.py` | Outbox 저장·재전송·재기동 복구 Test 삭제. Offline Event 폐기와 연결 중 직접 QoS 1 발행 Test 추가 |
| `embedded/jetson_main/tests/test_broker_e2e.py` | Broker 장애 중 Robot Outbox 보존 Test를 “과거 Event 미전송·재연결 Snapshot만 전송” Test로 교체 |
| `embedded/jetson_main/tests/test_communication_integration.py` | `receipt_required`, Outbox Migration Test 제거. Runtime에 Outbox가 생성되지 않는지 검증 |
| Backend MQTT 설정 Test | `cleanStart=false`, Session Expiry 3600초와 설정 Override 검증 반영. 동일 `clientId` Broker 재접속 E2E는 추가 확인 필요 |
| Backend Broker E2E | Backend 중단 중 Broker에 보관된 QoS 1 Event가 TTL 안에 전달되는지 검증 |

### 12.7 계약·운영 문서

- `web/docs/final-mqtt-topic-schema-v2.md`
- `web/docs/mqtt-device-db-mapping.md`
- `embedded/jetson_main/docs/INGEST-RECEIPT.md`
- `docs/mission-completion-synchronization.md`
- Jetson systemd·chrony 배포 설정

### 12.8 상대 팀 전달 묶음

현재 실제로 변경된 운영 클래스와 검증 파일의 전체 Snapshot은 저장소 루트의
`robot-team-handoff-2026-08-06/`에 원래 디렉터리 구조대로 모았다.

- Robot: `MessageFactory`, `DriveCommandRunner`, `EventPublisher`와 관련 Test
- Backend: 12.1의 Java 클래스 전체, Application 설정, V43 DB Migration과 통합 Test
- Frontend: `CommandControlPage`, Command 타입·스타일과 관련 E2E
- Mock Robot: 완료 명령 왕복 변경본과 Test

묶음의 `README.md`에 원래 적용 위치와 병합 시 주의사항을 기록했다. 이 묶음은 현재
작업 트리에서 이미 변경된 파일의 Snapshot이며, 아직 구현하지 않은 Outbox 제거와
Backend 영속 Session 패치는 포함하지 않는다.
