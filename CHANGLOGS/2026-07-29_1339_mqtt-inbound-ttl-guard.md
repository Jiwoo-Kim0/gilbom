# 변경 로그: MQTT 수신 메시지 TTL 차단

- 작성 시각: 2026-07-29 13:39 (Asia/Seoul)
- 작업 유형: 기능
- 상태: 완료

## 작업 요약

Jira `S15P11D101-431`의 오래된 MQTT 메시지 TTL 차단을 구현했다. Telemetry는 60초, State는 300초, Command Result는 60초를 최대 지연으로 적용하고, Event는 발행자가 지정하는 `ttl_sec`를 60~300초 범위에서 필수로 받는다. 모든 수신 시각은 서버 수신 시각을 기준으로 비교하며 미래 시각 허용 오차는 5초다.

오래되거나 허용 범위를 넘는 미래 시각의 메시지는 Handler와 DB 쓰기 전에 거부한다. 거부 결과는 유한한 `topic`, `reason` 태그를 사용하는 `gilbom.mqtt.inbound.rejected` Metric과 공통 오류 코드로 관측할 수 있다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/backend/src/main/java/com/gilbom/robot/mqtt/config/MqttInboundFreshnessProperties.java` | 추가 | Topic별 최대 지연과 미래 시각 허용 오차를 바인딩하는 설정 클래스를 추가했다. |
| `web/backend/src/main/java/com/gilbom/robot/mqtt/parser/MqttInboundFreshnessGuard.java` | 추가 | Topic별 기준 시각 선택, 엄격한 TTL 경계 판정, 미래 시각 검증, Command 만료 검증과 거부 Metric 기록을 구현했다. |
| `web/backend/src/main/java/com/gilbom/robot/mqtt/parser/MqttMessageParser.java` | 수정 | Schema·Payload 검증 직후, Dispatcher/Handler 전달 전에 Freshness Guard를 실행하도록 연결했다. |
| `web/backend/src/main/java/com/gilbom/robot/data/mqtt/v2/common/MqttEventPayload.java` | 수정 | 모든 Event Payload가 공통으로 `ttlSec`를 제공하도록 계약을 확장했다. |
| `web/backend/src/main/java/com/gilbom/robot/data/mqtt/v2/inbound/BatteryEventPayload.java` | 수정 | 필수 `ttl_sec`와 60~300초 Bean Validation을 추가했다. |
| `web/backend/src/main/java/com/gilbom/robot/data/mqtt/v2/inbound/IncidentEventPayload.java` | 수정 | 필수 `ttl_sec`와 60~300초 Bean Validation을 추가했다. |
| `web/backend/src/main/java/com/gilbom/robot/data/mqtt/v2/inbound/MissionEventPayload.java` | 수정 | 필수 `ttl_sec`와 60~300초 Bean Validation을 추가했다. |
| `web/backend/src/main/java/com/gilbom/robot/data/mqtt/v2/inbound/SafetyEventPayload.java` | 수정 | 필수 `ttl_sec`와 60~300초 Bean Validation을 추가했다. |
| `web/backend/src/main/java/com/gilbom/common/error/ErrorCode.java` | 수정 | `MQTT_MESSAGE_EXPIRED`, `MQTT_TIMESTAMP_IN_FUTURE` 오류 코드를 추가했다. |
| `web/backend/src/main/java/com/gilbom/robot/command/RobotCommandResultIngestionService.java` | 수정 | Result INSERT와 Command 상태 변경 전에 메시지 지연 및 원 Command `expires_at`을 검증하도록 변경했다. |
| `web/backend/src/main/resources/application.yml` | 수정 | Telemetry·State·Command Result 최대 지연 및 미래 시각 허용 오차 환경변수를 추가했다. |
| `web/backend/src/test/java/com/gilbom/robot/mqtt/parser/MqttMessageParserTest.java` | 수정 | Topic별 만료, 미래 시각, Event TTL 누락·범위·경계와 Metric 태그를 검증했다. |
| `web/backend/src/test/java/com/gilbom/robot/mqtt/handler/MqttInboundMessageDispatcherTest.java` | 수정 | 만료 메시지가 Handler에 도달하지 않는 것을 검증했다. |
| `web/backend/src/test/java/com/gilbom/robot/integration/RobotCommandLifecycleIntegrationTest.java` | 수정 | 늦은 Result가 INSERT 및 Command 상태 변경을 일으키지 않으며 Timeout Scanner가 만료를 확정하는 것을 검증했다. 제한형 테스트 컨텍스트에 Guard 빈도 등록했다. |
| `web/backend/src/test/java/com/gilbom/robot/integration/RobotEventMissionDefectIntegrationTest.java` | 수정 | Event 계약 변경에 맞춰 테스트 Payload에 `ttl_sec`를 추가했다. |
| `web/docs/final-mqtt-topic-schema-v2.md` | 수정 | Topic별 기준 시각·TTL·미래 허용 오차, Event 재전송 규칙과 JSON 예시의 `ttl_sec`를 동기화했다. |
| `web/docs/mqtt-device-db-mapping.md` | 수정 | Handler 전 차단 순서, DB 무부작용, Metric 및 Presence 제외 근거를 문서화했다. |
| `web/docs/environment-configuration-strategy.md` | 수정 | 네 개의 MQTT Freshness 환경변수와 기본값을 추가했다. |
| `web/docs/common-api-response-and-errors.md` | 수정 | 두 MQTT 수신 거부 오류의 의미를 공통 오류 문서에 추가했다. |
| `web/docs/openapi-v1.yaml` | 수정 | 공통 Error Code Enum에 두 MQTT 수신 거부 코드를 추가했다. 동일 파일의 Patrol Route 변경은 이번 작업 범위가 아니다. |
| `2026-07-29_JIRA_오늘_작업.md` | 수정 | `TODAY-B-00-1`을 `S15P11D101-431`로 연결하고 완료 상태와 검증 근거를 반영했다. |
| `2026-07-29_오늘_상세_구현.md` | 수정 | TTL 세부 항목을 완료 처리하고 최신 Backend 기준선 98 Suite/508 Test를 반영했다. |
| `DAY5_작업_및_밀린_일.md` | 수정 | Event `ttl_sec` 미결정 설명을 구현된 60~300초 정책으로 교체했다. |
| `web/docs/2026-07-28-story-subtask-status.md` | 수정 | 기존 TTL 미사용 상태를 현재 구현 계약으로 갱신했다. |
| `web/참조용.agents.md` | 수정 | MQTT 계약 참조 자료의 Event TTL 설명을 현재 정책과 일치시켰다. |
| `CHANGLOGS/2026-07-29_1339_mqtt-inbound-ttl-guard.md` | 추가 | 이번 구현 범위, 검증 결과, 영향 및 후속 작업을 기록했다. |

## 상세 내용

- Freshness 기준은 서버의 `receivedAt`과 Payload 기준 시각의 차이다.
  - Telemetry: `sampled_at`, 60초
  - State: `updated_at`, 300초
  - Event: `occurred_at`, Payload의 `ttl_sec` 60~300초
  - Command Result: 최종 상태는 `completed_at`, `ACCEPTED`는 `received_at`, 60초
- 메시지 나이가 최대 지연과 같거나 크면 만료로 판정한다. 미래 기준 시각이 서버 수신 시각보다 5초를 초과해 앞서면 거부한다.
- Command Result는 메시지 Freshness와 별도로 원 Command의 정확한 `expires_at`도 넘지 않아야 한다.
- Event 재전송 시 최초 `occurred_at`과 `ttl_sec`를 유지해야 하므로 재전송으로 만료 시간이 연장되지 않는다.
- Presence는 Retained/LWT 특성과 공통 이벤트 시각 부재 때문에 이번 TTL 대상에서 제외했다. Presence의 재생 공격 방지는 후속 `boot_id`·`seq` Replay 차단 정책에서 처리한다.
- 거부 Metric의 태그 값은 Topic 유형과 고정 Reason만 사용해 Cardinality를 제한했다.

## 검증

- 실행한 명령:
  - `.\gradlew.bat test --tests "com.gilbom.robot.mqtt.parser.MqttMessageParserTest" --rerun-tasks`
  - `.\gradlew.bat test --tests "com.gilbom.robot.mqtt.parser.MqttMessageParserTest" --tests "com.gilbom.robot.integration.RobotCommandLifecycleIntegrationTest" --tests "com.gilbom.robot.integration.RobotEventMissionDefectIntegrationTest" --rerun-tasks`
  - `.\gradlew.bat test --tests "com.gilbom.robot.integration.RobotCommandLifecycleIntegrationTest" --tests "com.gilbom.robot.mqtt.handler.MqttInboundMessageDispatcherTest" --rerun-tasks`
  - `.\gradlew.bat test --rerun-tasks`
  - OpenAPI YAML Parse, 내부 Schema `$ref`, 신규 Error Code 존재 검증
  - MQTT 계약 문서 내 JSON Code Block 전체 Parse 및 `sent_at` Event의 `ttl_sec` 존재 검증
  - `git diff --check`
- 결과:
  - Parser 단위 검증: 11 Test, 실패 0
  - Parser·Command·Event 집중 검증: 27 Test, 실패 0
  - Command·Dispatcher 집중 검증: 14 Test, 실패 0
  - 최종 전체 Backend 검증: 98 Suite, 508 Test, 실패 0, 오류 0, 생략 0
  - OpenAPI Parse·내부 참조·오류 코드 검증 성공
  - MQTT JSON 예시 31개 Parse 및 Event TTL 검증 성공
  - 공백 오류 없음
  - 중간 전체 검증에서 제한형 Command 테스트 컨텍스트의 Guard 빈 누락으로 9개가 실패했으나, 테스트 설정을 수정한 뒤 집중 및 전체 검증을 모두 다시 통과했다.
- 미실행 항목과 사유:
  - 실제 Mosquitto·로봇 Publisher를 연결한 E2E는 실행하지 않았다. Parser/Dispatcher/DB 통합 테스트로 서버의 선행 차단과 무부작용을 검증했으며 실제 장비 계약 반영은 Publisher의 `ttl_sec` 적용 후 통합 환경에서 확인해야 한다.

## 영향 및 주의사항

- 임베디드 Event Publisher는 모든 Event 메시지에 정수 `ttl_sec`를 60~300 범위로 보내야 한다. 누락하거나 범위를 벗어나면 Payload 검증 단계에서 거부된다.
- Telemetry 기본값 60초는 최신성이 중요한 데이터용이고, State 기본값 300초는 터널·엘리베이터 등 일시 통신 단절 후 복구 메시지를 수용하기 위한 값이다.
- 만료된 Command Result는 결과 이력으로 저장하지 않는다. Command 만료 상태는 기존 Timeout Scanner가 확정한다.
- 설정값은 환경변수로 조정할 수 있지만, Event 허용 범위 60~300초는 Payload 계약과 Bean Validation에도 고정되어 있으므로 범위 변경 시 코드·계약·Publisher를 함께 변경해야 한다.

## 미완료 및 후속 작업

- 다음 우선 작업은 `TODAY-B-00-2`의 `boot_id`·`seq` 기반 Replay 차단이다.
- 실제 로봇/Mock Publisher의 Event Payload에 `ttl_sec`를 반영하고 Mosquitto 통합 환경에서 만료·재전송 시나리오를 확인해야 한다.
- Presence 재생 방지와 재시작 이후 Sequence 정책은 Replay 차단 작업에서 확정한다.
