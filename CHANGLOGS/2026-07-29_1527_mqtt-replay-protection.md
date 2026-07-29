# 변경 로그: MQTT boot_id·seq Replay 차단

- 작성 시각: 2026-07-29 15:27 (Asia/Seoul)
- 작업 유형: 기능
- 상태: 완료

## 작업 요약

`S15P11D101-432`의 State·Telemetry·Event 공용 `seq` 정책을 Backend 수신 경로에
통합했다. 로봇별 Current State 행 잠금으로 세 Topic의 동시 수신을 직렬화하고,
정확히 같은 재전송만 멱등 처리한다. 새 Boot의 `seq=1`, 증가 순번, 교차 Topic
충돌, 종료·이전 Boot Replay와 교체된 Boot의 Retained Presence를 업무 처리 전에
차단한다.

별도 Replay Table이나 DB Migration은 추가하지 않고 기존 Boot Session,
Current State와 State·Telemetry·Event 이력을 사용했다. 팀원 C 소유
`IncidentEventIngestionService`와 Defect 구현은 변경하지 않았다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/backend/src/main/java/com/gilbom/robot/mqtt/replay/MqttReplayProtectionService.java` | 추가 | Current State `FOR UPDATE` 잠금, 세 History Table 공용 순번 조회, 정확한 재전송 멱등 판정, Boot 생성·교체·종료·증가 순번 검증과 제한 Metric을 구현했다. |
| `web/backend/src/main/java/com/gilbom/common/error/ErrorCode.java` | 수정 | `MQTT_REPLAY_REJECTED`, `MQTT_SEQUENCE_CONFLICT` 422 오류 코드를 추가했다. |
| `web/backend/src/main/java/com/gilbom/robot/status/RobotStatusIngestionService.java` | 수정 | State 수신을 공통 Replay Claim에 연결하고 교체된 이전 Boot의 Retained ONLINE은 무시하며, Timeout·비정상 단절 Boot만 동일 Boot 재연결을 허용했다. |
| `web/backend/src/main/java/com/gilbom/robot/telemetry/TelemetryIngestionService.java` | 수정 | Telemetry History·Mission Progress·Current State 처리 전에 공통 Replay Claim을 수행하고 정확한 재전송만 무부작용으로 반환하도록 변경했다. |
| `web/backend/src/main/java/com/gilbom/robot/event/RobotEventIngestionService.java` | 수정 | Incident·Battery·Safety·Mission Event를 실제 Topic으로 구분해 공통 Replay Claim에 연결하고 교차 Topic 순번 충돌을 차단했다. |
| `web/backend/src/test/java/com/gilbom/robot/integration/RobotStatusIngestionIntegrationTest.java` | 수정 | State·Telemetry 동시 동일 순번 Claim, 이전 Boot State, 교체된 Boot Retained ONLINE과 Boot Session 결과를 검증했다. |
| `web/backend/src/test/java/com/gilbom/robot/integration/TelemetryIngestionIntegrationTest.java` | 수정 | 정확한 재전송, 동일 순번 다른 Telemetry, 이전 Boot, 새 Boot `seq!=1`과 무부작용을 검증했다. |
| `web/backend/src/test/java/com/gilbom/robot/integration/RobotEventMissionDefectIntegrationTest.java` | 수정 | Event·Telemetry 교차 Topic 충돌, 이전 Boot Event, 새 Boot `seq!=1`, 오류 Metric과 Mission·Event 무부작용을 검증하고 공통 B 서비스 생성자를 연결했다. |
| `web/backend/src/test/java/com/gilbom/robot/integration/RobotQueryScopeIntegrationTest.java` | 수정 | Spring 통합 테스트 Context에 공통 Replay Service를 포함했다. |
| `web/docs/final-mqtt-topic-schema-v2.md` | 수정 | 공용 순번, 순번 누락 허용, 재전송 식별, Boot 교체·복구 가능 Presence와 Replay 오류·Metric 계약을 정본에 명시했다. |
| `web/docs/mqtt-device-db-mapping.md` | 수정 | 기존 Table 기반 잠금·조회 방식, Transaction 경계, Topic별 중복 판정과 Boot별 Current State 초기화를 기록했다. |
| `web/docs/common-api-response-and-errors.md` | 수정 | Replay·순번 충돌 오류 코드와 422 의미를 동기화했다. |
| `web/docs/openapi-v1.yaml` | 수정 | 공통 `ErrorCode` Enum에 Replay·순번 충돌 오류를 추가했다. |
| `web/docs/2026-07-28-story-subtask-status.md` | 수정 | `S15P11D101-432` Backend 완료 근거와 Embedded 구현 확인 후속 범위를 분리해 기록했다. |
| `DAY5_작업_및_밀린_일.md` | 수정 | 합의된 Boot 생성·재연결·공용 순번·Event 재전송 규칙의 문서 반영 상태를 완료로 변경했다. |
| `2026-07-29_JIRA_오늘_작업.md` | 수정 | `TODAY-B-00-2`를 `S15P11D101-432` 완료 이력으로 옮기고 활성 P0·전체 하위 작업 수와 우선순위를 갱신했다. 동시 작업의 Credential·Image 항목은 유지했다. |
| `2026-07-29_오늘_상세_구현.md` | 수정 | Replay를 활성 실행 순서에서 제거하고 완료 근거, 잔여 활성 P0 7개와 최종 전체 테스트 수를 반영했다. 동시 작업의 구현 범위는 유지했다. |
| `CHANGLOGS/2026-07-29_1527_mqtt-replay-protection.md` | 추가 | 구현 범위, 변경 파일, 검증 결과, 책임 경계와 후속 작업을 기록했다. |

## 상세 내용

### 공용 Replay Claim

- State·Telemetry·Event 4종은 `(robot_id, boot_id, seq)`를 하나의 순번 공간으로
  공유한다.
- 새 Boot의 첫 순번은 `1`이어야 한다. QoS 0 Telemetry 유실을 고려해 순번 누락은
  허용하지만 새 Claim은 `last_seen_seq`보다 커야 한다.
- State는 같은 JSON, Telemetry는 같은 `message_id`와 JSON, Event는 같은
  `event_id`와 불변 사건 식별 내용일 때만 멱등 재전송으로 처리한다.
- 같은 순번을 다른 Topic이나 메시지가 사용하면 `MQTT_SEQUENCE_CONFLICT`,
  종료·이전 Boot, 새 Boot의 잘못된 시작 순번과 비증가 순번은
  `MQTT_REPLAY_REJECTED`로 거부한다.
- Replay 판정과 Boot 순번 갱신은 호출 서비스의 History·Mission·Event 처리와 같은
  Transaction에서 수행하므로 후속 처리 실패 시 함께 Rollback된다.

### Boot·Presence 정책

- 새 Boot 승인 시 이전 Boot를 `REPLACED_BY_NEW_BOOT`로 종료하고 Boot별 State와
  Telemetry Current 측정값을 초기화한다.
- Presence에는 `seq`가 없다. `UNGRACEFUL_DISCONNECT`와 `HEARTBEAT_TIMEOUT`은 같은
  Boot의 ONLINE 복구를 허용하지만 교체된 Boot의 Retained ONLINE은 무시한다.
- 이미 정상 처리된 과거 Boot의 정확한 재전송은 멱등 반환하고, 과거 Boot에서 처음
  보는 메시지는 업무 Side Effect 전에 거부한다.

### 책임 경계

- B 소유 공통 `RobotEventIngestionService`까지만 Replay Guard를 연결했다.
- C 소유 `IncidentEventIngestionService`, Defect 병합·관측·이미지 로직은 수정하지
  않았다. Incident Event Replay는 C 처리 진입 전에 공통 Event 단계에서 차단된다.

## 검증

- 실행한 명령:
  - `.\gradlew.bat test --rerun-tasks --tests "com.gilbom.robot.integration.TelemetryIngestionIntegrationTest" --tests "com.gilbom.robot.integration.RobotStatusIngestionIntegrationTest" --tests "com.gilbom.robot.integration.RobotEventMissionDefectIntegrationTest" --tests "com.gilbom.robot.integration.RobotQueryScopeIntegrationTest"`
  - `.\gradlew.bat test --rerun-tasks`
  - PowerShell로 `build/test-results/test/TEST-*.xml` Suite·Test·실패·오류·생략 합계 대조
  - Replay 변경 경로 `git diff --check`와 신규 Service 후행 공백 검색
  - `IncidentEventIngestionService`·`defect` 경로 변경 여부 확인
- 결과:
  - 집중 검증 최종 결과: 4 Suite, 30 Test, 실패 0, 오류 0, 생략 0
  - 전체 Backend 최종 결과: 98 Suite, 514 Test, 실패 0, 오류 0, 생략 0
  - Gradle 결과: `BUILD SUCCESSFUL in 2m 9s`
  - 개발 중 첫 집중 실행은 29 Test 중 6건이 실패했다. 새 Boot 전환 시 기존
    Current 측정 순번 초기화, 기대 오류 코드와 테스트 간 ONLINE Session 격리를
    수정한 뒤 29건 전부 통과했고, 동시성 회귀 Test 추가 후 30건 전부 통과했다.
  - 전체 테스트 종료 과정에서 종료된 Testcontainers DB를 Spring Session 정리
    Scheduler가 재조회한 경고 Stack Trace가 남았으나 Test 실패·오류는 0이고
    Gradle은 성공 종료했다.
  - Replay 변경 파일의 공백 오류가 없고 C 소유 Service·Defect 파일 변경이 없다.
- 미실행 항목과 사유:
  - 실제 Robot·Mock Publisher와 TLS Mosquitto를 연결한 Broker E2E는
    `TODAY-B-00-3`, `TODAY-B-00-7` 후속 작업 범위라 실행하지 않았다.
  - Jira 서버의 실제 이슈 상태 변경은 연결 도구가 없어 수행하지 않았고 로컬 작업
    현황 문서에 `S15P11D101-432` 완료 근거를 반영했다.

## 영향 및 주의사항

- 새 DB Table·Migration 없이 기존 Current State 행 잠금과 History Unique 제약을
  함께 사용한다.
- `last_seen_seq`는 이제 State·Telemetry·Event 공용 최댓값이 아니라 마지막으로
  승인한 증가 순번이다. 각 Topic이 독립 순번을 발행하면 교차 Topic 충돌로 거부된다.
- 새 Boot로 전환하면 이전 Boot의 Current 측정값을 의도적으로 비워 서로 다른 Boot의
  상태와 Telemetry가 섞이지 않게 한다.
- Replay 거부 Metric은 `gilbom.mqtt.inbound.rejected`의 제한된 `topic`, `reason`
  Tag만 사용하며 UUID·Payload·시각 원문을 기록하지 않는다.
- 작업 트리의 Mission·Robot Frontend와 Credential·Image 문서 변경은 다른 작업자의
  변경이므로 수정하거나 되돌리지 않았다.

## 미완료 및 후속 작업

- 실제 Robot·Mock Publisher가 통신 프로세스 시작 시 UUIDv4 `boot_id`를 생성하고
  State·Telemetry·Event 공용 `seq`를 발행하는지 Broker E2E에서 확인한다.
- 현재 우선순위 문서의 다음 작업은 `TODAY-B-00-5` Credential 회전·조회·폐기
  API이며, MQTT 후속은 Client별 ACL과 Publisher·Broker TTL E2E다.
- Jira `S15P11D101-432`의 실제 상태·Comment·Commit 연결은 저장소 Commit 시 함께
  반영한다.
