# 변경 로그: MQTT Event TTL·Replay TLS Broker→DB E2E

- 작성 시각: 2026-07-30 09:29 (Asia/Seoul)
- 작업 유형: 수정
- 상태: 완료

## 작업 요약

오늘 DailyLog의 첫 작업인 `DAY6-B-00-1`과 Jira `S15P11D101-466`을 수행했다.
Embedded가 발행하는
MQTT v2 Wire Payload를 정본의 `mqtt_id` 규칙에 맞추고, 네 Event 유형의 공통
Envelope에 `ttl_sec` 기본값과 `60~300` 범위 검증을 추가했다. 실제 로컬 TLS
Mosquitto와 PostgreSQL/PostGIS Testcontainer를 통과하는 Broker E2E를 추가해
만료·미래시각 Event의 업무 DB 무부작용, 정확한 재전송의 멱등성, 같은
`(boot_id,seq)`를 다른 Event가 재사용하는 충돌 거부를 검증했다.

`web/docs/final-mqtt-topic-schema-v2.md`는 이미 Payload 식별자를 `mqtt_id`로,
Event `ttl_sec`를 필수 `60~300` 정수로 정의하고 있어 수정하지 않았다. 구현과
예시 Payload를 해당 정본에 맞췄다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `embedded/comms/contract.py` | 수정 | Event TTL 최소값 60초, 최대값·기본값 300초 상수를 추가해 발행 계약의 단일 기준으로 사용하게 했다. |
| `embedded/comms/factory.py` | 수정 | `MessageFactory`에 Event TTL 설정과 정수·범위 검증을 추가하고 Event Envelope에 `ttl_sec`를 넣었다. Presence·State·Telemetry·Event·Command Result의 MQTT Wire 식별자 키를 정본의 `mqtt_id`로 통일했다. |
| `embedded/test/test_factory.py` | 수정 | Wire `mqtt_id`, Event 기본 TTL, 60·300 경계 허용, 범위 밖·실수·Boolean 거부, Mission Event 필드 순서를 검증했다. |
| `embedded/test/test_events.py` | 수정 | Outbox 재전송에서 기존 `ttl_sec`가 유지되고 `sent_at`만 변경 가능한지 검증했다. |
| `embedded/test/test_broker_e2e.py` | 수정 | 실제 Embedded Broker Presence Payload의 식별자 기대값을 `mqtt_id`로 보정했다. |
| `embedded/test/test_client.py` | 수정 | Online·Last Will·Graceful Shutdown Presence Payload의 `mqtt_id` 계약을 검증하게 했다. |
| `embedded/test/test_commands.py` | 수정 | Command Result Wire Payload의 식별자 키와 값을 `mqtt_id`로 검증하게 했다. |
| `embedded/test/test_robot_state.py` | 수정 | State Wire Payload의 식별자 키와 값을 `mqtt_id`로 검증하게 했다. |
| `embedded/test/test_telemetry.py` | 수정 | Telemetry Wire Payload의 식별자 키와 값을 `mqtt_id`로 검증하게 했다. |
| `web/backend/src/test/java/com/gilbom/robot/mqtt/client/MqttInboundBrokerE2E.java` | 추가 | 실제 Paho MQTT v5 Robot·Backend Client, TLS Mosquitto, 실제 Parser·Freshness·Dispatcher·Replay·업무 Handler, PostGIS Testcontainer를 연결했다. 만료 Mission Event, 미래 Incident Event, 정상 Mission Event의 정확한 재전송, 교차 Event 순번 충돌과 Metric·DB 상태를 검증한다. |
| `web/backend/build.gradle` | 수정 | `mqttBrokerE2E` 설명에 기존 발행 Message Expiry뿐 아니라 수신 Event→DB E2E도 포함된다는 점을 반영했다. |
| `web/infra/mosquitto/README.md` | 수정 | Telemetry 수동 발행 예시의 Wire 식별자 키를 `robot_id`에서 정본의 `mqtt_id`로 수정했다. |
| `TROUBLESHOOTING/3_MqttTtlAndMessageExpiry.md` | 수정 | 2026-07-30 Event 수신 TTL Broker E2E 결과와 새 상세 문서 링크를 추가했다. |
| `TROUBLESHOOTING/4_MqttReplayProtection.md` | 수정 | 로컬 TLS Broker에서 정확한 Event 재전송 멱등성 검증을 완료한 상태와 실기 후속 범위를 기록했다. |
| `TROUBLESHOOTING/8_MqttInboundEventBrokerE2E.md` | 추가 | 정본·Embedded 불일치, 선택적 Spring Context Bean 누락, DTO `isXxx()` 과잉 직렬화의 증상·원인·수정·진단 순서와 최종 E2E 결과를 기록했다. |
| `DailyLog/2026-07-30_JIRA_Day6_작업.md` | 수정 | `TODAY-B-00-7`과 `DAY6-B-00-1`을 완료로 보정하고 실제 실행 순서에 완료 상태를 표시했다. |
| `DailyLog/2026-07-30_Day6_상세_구현.md` | 수정 | Event TTL·만료·미래·재전송·순번 충돌과 Backend 전체 회귀·TLS Broker E2E 체크리스트를 완료 처리했다. |
| `web/docs/2026-07-28-story-subtask-status.md` | 수정 | Replay 정책을 Backend·Mock 완료로 보정하고 로컬 TLS Broker Event TTL·Replay·순번 충돌 E2E 근거와 Jetson·EC2 후속 범위를 분리했다. |
| `CHANGLOGS/2026-07-30_0929_mqtt-inbound-event-broker-e2e.md` | 추가 | 이번 구현, 변경 파일, 검증 결과, 영향과 후속 실기 검증 범위를 기록했다. |

## 상세 내용

Embedded 내부 설정과 이미지 Upload REST의 `robot_id` 명칭은 그대로 유지했다.
이번 변경은 MQTT Wire Payload에만 적용되며, Topic의 `{mqtt_id}`, Broker username,
Payload의 `mqtt_id`를 같은 외부 식별자로 맞춘다.

Event TTL은 `occurred_at + ttl_sec`를 기준으로 한다. 기본값은 정본 허용 범위의
상한인 300초이며, Outbox 재전송 시 `event_id`, `boot_id`, `seq`,
`occurred_at`, `ttl_sec`는 바뀌지 않고 `sent_at`만 갱신된다.

Broker E2E의 최소 Spring Context는 운영과 같은 Parser, Freshness Guard,
Dispatcher, Replay Service, Mission·Incident Handler와 수집 Service를 사용한다.
첫 실행에서는 `MqttInboundFreshnessProperties` 누락으로 Context가 기동되지
않았고, 다음 실행에서는 테스트 DTO의 검증용 `isXxx()` 메서드가 JSON에 섞여
`MQTT_PAYLOAD_MALFORMED`가 발생했다. 설정 Bean을 Import하고 Wire Mapper의
`AUTO_DETECT_IS_GETTERS`를 비활성화한 뒤 최종 시나리오가 통과했다.

## 검증

- 실행한 명령:
  - `embedded/`에서 `uv run --with pytest --with paho-mqtt==2.1.0 --with PyYAML==6.0.2 --with requests pytest -q test/test_factory.py test/test_events.py test/test_broker_e2e.py test/test_client.py test/test_commands.py test/test_robot_state.py test/test_telemetry.py`
  - `embedded/`에서 `uv run --with pytest --with paho-mqtt==2.1.0 --with PyYAML==6.0.2 --with requests pytest -q`
  - `web/backend/`에서 `.\gradlew.bat testClasses --rerun-tasks`
  - `web/backend/`에서 `.\gradlew.bat test --rerun-tasks --tests "com.gilbom.robot.mqtt.parser.MqttMessageParserTest" --tests "com.gilbom.robot.integration.RobotEventMissionDefectIntegrationTest"`
  - `web/backend/`에서 `.\gradlew.bat test --rerun-tasks`
  - `web/backend/`에서 로컬 `.env`의 계정과 CA 경로를 환경변수로 주입한 뒤 `.\gradlew.bat mqttBrokerE2E --rerun-tasks`
  - `git diff --check`
- 결과:
  - Embedded MQTT 집중 회귀: `373 passed`
  - Embedded 전체 회귀: `479 passed`
  - Backend `testClasses`: 성공
  - Backend Parser·Event 집중 회귀: `22 tests`, 실패 0, 생략 0
  - Backend 전체 회귀: `542 tests`, 실패 0, 생략 0
  - 실제 TLS Broker E2E: `2 tests`, 실패 0, 생략 0
  - 최종 E2E에서 기존 Command Message Expiry와 신규 Event 만료·미래시각·정확한 재전송·순번 충돌 Broker→DB 시나리오 모두 통과
  - `git diff --check`: 공백 오류 없음. Windows 작업 트리의 기존 LF→CRLF 경고만 출력
  - 시스템 Python 직접 실행은 `pytest` 미설치로 테스트를 시작하지 못했고, 같은 테스트를 `uv --with pytest` 격리 환경에서 실행해 통과했다.
  - Backend 전체 회귀 종료 중 Spring Session 정리와 Testcontainer 종료 순서 관련 로그가 출력됐으나 Gradle 결과와 테스트 집계는 실패 0이었다.
- 미실행 항목과 사유:
  - 실제 Jetson Publisher와 EC2 TLS Broker 검증은 장비·운영 환경이 필요한 Day 7 통합 범위이므로 실행하지 않았다.
  - Frontend는 이번 작업에서 변경하지 않아 lint·build를 실행하지 않았다.

## 영향 및 주의사항

- 이전 Embedded MQTT Payload의 `robot_id`를 소비하던 비정본 Consumer가 있다면
  Embedded 배포와 함께 `mqtt_id`로 갱신해야 한다. 현재 Backend v2 Parser와
  정본은 이미 `mqtt_id`를 요구한다.
- 이미지 Upload REST의 multipart `robot_id`는 별도 계약이므로 이번 변경 대상이
  아니다.
- Event TTL은 전송시각이 아니라 발생시각 기준이다. 재전송 시
  `occurred_at`이나 `ttl_sec`를 바꿔 만료를 연장하면 안 된다.
- E2E용 Broker 계정·비밀번호·인증서 내용은 로그와 문서에 기록하지 않았다.

## 미완료 및 후속 작업

- Day 7에 실제 Jetson이 UUIDv4 `boot_id`, State·Telemetry·Event 공용 `seq`,
  `mqtt_id`, Event `ttl_sec` 계약을 따르는지 확인한다.
- EC2 Broker에서 실제 Credential·ACL·인증서로 같은 Event E2E를 재실행한다.
- Embedded NTP 오차, 발행 주기, MQTT Client ID 동시 접속 정책은 협업 확인을
  계속한다.
