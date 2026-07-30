# 변경 로그: MQTT v2 계약 동결

- 작성 시각: 2026-07-30 11:45 (Asia/Seoul)
- 작업 유형: 문서
- 상태: 완료

## 작업 요약

MQTT v2의 Topic·방향·QoS·Retain, Payload 구조, 처리 의미와 정상·오류
시나리오를 AsyncAPI 3.1.0, JSON Schema Draft 07, Fixture Manifest로 동결했다.
자동 계약 검증 8개를 추가하고 현재 Embedded 전체 479개 및 Backend MQTT 집중
18개 테스트로 구현 정합성을 확인했다. 과거 v1 MQTT 문서는 보관하되
Deprecated / Superseded by v2 상태를 명시했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `2026-07-29_JIRA_오늘_작업.md` | 수정 | TODAY-B-03-2 MQTT v2 계약 동결 작업을 DONE으로 갱신했다. |
| `DAY5_작업_및_밀린_일.md` | 수정 | AsyncAPI·Schema·Fixture·자동 검증 산출물의 완료 상태와 경로를 기록했다. |
| `web/docs/api-contract.md` | 수정 | 현행 MQTT 정본 링크를 v2 동결 가이드로 바꾸고 v1 문서를 Deprecated 보관본으로 분리했다. |
| `web/docs/final-mqtt-topic-schema-v1.md` | 수정 | 문서 상단에 Deprecated / Superseded by v2 경고와 현행 정본 링크를 추가했다. |
| `web/docs/final-mqtt-topic-schema-v2.md` | 수정 | FROZEN 상태·일자·동결 묶음·변경 정책을 추가하고 Command Result reason을 기계 판독 계약과 일치시켰다. |
| `web/docs/mqtt-device-db-mapping.md` | 수정 | `tbs/v1` 기준 과거 매핑임을 명시하고 v2 정본으로 연결했다. |
| `web/docs/asyncapi-mqtt-v2.yaml` | 추가 | Backend 관점의 10개 Channel·Operation, 방향, MQTT QoS·Retain·Expiry와 외부 Payload Schema 참조를 정의했다. |
| `web/docs/mqtt-v2-contract-freeze.md` | 추가 | 동결 범위, 제외 환경값, 검증 명령, Fixture 예상 결과, Major 변경 절차를 문서화했다. |
| `web/docs/mqtt-v2/schemas/common.schema.json` | 추가 | MQTT ID·UUID·UTC 시각·공통 enum·GPS·Battery·Health 정의를 Draft 07로 고정했다. |
| `web/docs/mqtt-v2/schemas/event-envelope.schema.json` | 추가 | Event ID·발생·전송 시각·TTL·Source의 공통 Envelope를 고정했다. |
| `web/docs/mqtt-v2/schemas/presence.schema.json` | 추가 | ONLINE·OFFLINE별 필수·금지 필드와 종료 이유를 검증한다. |
| `web/docs/mqtt-v2/schemas/state.schema.json` | 추가 | Robot Mode·Reason·Mission·Battery·Health Snapshot의 필수 필드와 조건을 검증한다. |
| `web/docs/mqtt-v2/schemas/telemetry.schema.json` | 추가 | GPS·Battery·온도·속도 Telemetry의 타입·범위·Validity 일관성을 검증한다. |
| `web/docs/mqtt-v2/schemas/incident-event.schema.json` | 추가 | 결함 유형·신뢰도·위치·이미지·정규화 bbox Evidence를 검증한다. |
| `web/docs/mqtt-v2/schemas/battery-event.schema.json` | 추가 | Battery 상태 전이 Event와 전압·잔량을 검증한다. |
| `web/docs/mqtt-v2/schemas/safety-event.schema.json` | 추가 | 안전 Event의 유형·상태·Severity·위치를 검증한다. |
| `web/docs/mqtt-v2/schemas/mission-event.schema.json` | 추가 | Mission 상태 전이와 종료 상태의 Result 필수 조건을 검증한다. |
| `web/docs/mqtt-v2/schemas/command-request.schema.json` | 추가 | Command 종류, 발행·만료 시각과 종류별 Parameters를 검증한다. |
| `web/docs/mqtt-v2/schemas/command-result.schema.json` | 추가 | Command 접수·실행 상태, Reason과 완료 시각 조건을 검증한다. |
| `web/docs/mqtt-v2/schemas/ingest-receipt.schema.json` | 추가 | Server의 Event 저장·중복·거부 Receipt를 검증한다. |
| `test/fixtures/mqtt/v2/manifest.json` | 추가 | 모든 Fixture의 Topic·QoS·수신 시각·Schema·처리·DB·Current State·Command 실행 예상 결과를 고정했다. |
| `test/fixtures/mqtt/v2/valid/presence-online.json` | 추가 | 정상 ONLINE Presence 기준 Payload를 추가했다. |
| `test/fixtures/mqtt/v2/valid/presence-offline.json` | 추가 | 정상 OFFLINE Presence 기준 Payload를 추가했다. |
| `test/fixtures/mqtt/v2/valid/state-ready.json` | 추가 | READY Robot State 기준 Payload를 추가했다. |
| `test/fixtures/mqtt/v2/valid/state-patrolling.json` | 추가 | PATROLLING Mission State 기준 Payload를 추가했다. |
| `test/fixtures/mqtt/v2/valid/telemetry.json` | 추가 | 모든 주요 센서 값이 유효한 Telemetry를 추가했다. |
| `test/fixtures/mqtt/v2/valid/telemetry-gps-invalid.json` | 추가 | GPS Invalid와 위치 Null의 정상 조합을 추가했다. |
| `test/fixtures/mqtt/v2/valid/telemetry-battery-invalid.json` | 추가 | Battery Invalid와 수치 Null의 정상 조합을 추가했다. |
| `test/fixtures/mqtt/v2/valid/incident-event.json` | 추가 | 결함 위치·이미지·bbox를 포함한 정상 Incident Event를 추가했다. |
| `test/fixtures/mqtt/v2/valid/battery-low-event.json` | 추가 | LOW Battery 상태 전이 Event를 추가했다. |
| `test/fixtures/mqtt/v2/valid/safety-stopped-event.json` | 추가 | STOPPED Safety Event를 추가했다. |
| `test/fixtures/mqtt/v2/valid/mission-completed-event.json` | 추가 | Result를 포함한 COMPLETED Mission Event를 추가했다. |
| `test/fixtures/mqtt/v2/valid/command-start-mission.json` | 추가 | Mission ID를 포함한 START_MISSION Command를 추가했다. |
| `test/fixtures/mqtt/v2/valid/command-emergency-stop.json` | 추가 | Parameters가 없는 EMERGENCY_STOP Command를 추가했다. |
| `test/fixtures/mqtt/v2/valid/command-result-accepted.json` | 추가 | Command ACCEPTED 중간 결과를 추가했다. |
| `test/fixtures/mqtt/v2/valid/command-result-completed.json` | 추가 | 완료 시각을 포함한 Command COMPLETED 결과를 추가했다. |
| `test/fixtures/mqtt/v2/valid/ingest-receipt-stored.json` | 추가 | Event STORED Receipt를 추가했다. |
| `test/fixtures/mqtt/v2/invalid/telemetry-missing-seq.json` | 추가 | 필수 Sequence가 누락된 Schema 거부 입력을 추가했다. |
| `test/fixtures/mqtt/v2/invalid/telemetry-unsupported-version.json` | 추가 | 지원하지 않는 `schema_version` 거부 입력을 추가했다. |
| `test/fixtures/mqtt/v2/invalid/state-invalid-enum.json` | 추가 | 허용되지 않은 State enum 거부 입력을 추가했다. |
| `test/fixtures/mqtt/v2/invalid/telemetry-latitude-out-of-range.json` | 추가 | 위도 범위를 초과한 거부 입력을 추가했다. |
| `test/fixtures/mqtt/v2/invalid/telemetry-invalid-uuid.json` | 추가 | 잘못된 Boot UUID 거부 입력을 추가했다. |
| `test/fixtures/mqtt/v2/invalid/telemetry-timezone-missing.json` | 추가 | UTC `Z`가 없는 시각 거부 입력을 추가했다. |
| `test/fixtures/mqtt/v2/invalid/state-battery-inconsistent.json` | 추가 | Battery Validity와 값이 불일치하는 거부 입력을 추가했다. |
| `test/fixtures/mqtt/v2/invalid/telemetry-position-inconsistent.json` | 추가 | GPS Validity와 좌표가 불일치하는 거부 입력을 추가했다. |
| `test/fixtures/mqtt/v2/invalid/incident-ttl-out-of-range.json` | 추가 | Event TTL 허용 범위를 벗어난 거부 입력을 추가했다. |
| `test/fixtures/mqtt/v2/invalid/mission-terminal-result-missing.json` | 추가 | 종료 Mission Event에서 Result가 누락된 거부 입력을 추가했다. |
| `test/fixtures/mqtt/v2/invalid/command-start-parameters-invalid.json` | 추가 | START_MISSION Parameters가 잘못된 거부 입력을 추가했다. |
| `test/fixtures/mqtt/v2/invalid/command-expiry-before-issued.json` | 추가 | 만료 시각이 발행 시각보다 앞선 의미 오류 입력을 추가했다. |
| `test/fixtures/mqtt/v2/invalid/incident-bbox-ordering-invalid.json` | 추가 | bbox 최소·최대 순서가 뒤집힌 의미 오류 입력을 추가했다. |
| `test/fixtures/mqtt/v2/invalid/incident-retry.json` | 추가 | `sent_at`만 바뀐 동일 Event 재전송 멱등 시나리오를 추가했다. |
| `test/fixtures/mqtt/v2/invalid/battery-sequence-conflict.json` | 추가 | 같은 Boot·Sequence를 다른 Event가 사용한 충돌 시나리오를 추가했다. |
| `test/fixtures/mqtt/v2/invalid/telemetry-previous-boot.json` | 추가 | 교체된 Boot의 지연 Telemetry Replay 시나리오를 추가했다. |
| `test/mqtt_contract/test_mqtt_v2_contract.py` | 추가 | Schema·AsyncAPI·Fixture·Semantic 처리·Secret·Embedded 상수와 발행 정책을 검증하는 8개 Test를 추가했다. |
| `TROUBLESHOOTING/9_MqttV2ContractFreeze.md` | 추가 | 사전 확인부터 전체 작업, 실패 시도, 원인·해결, 검증과 실제 환경 후속 범위를 기록했다. |
| `CHANGLOGS/2026-07-30_1145_mqtt-v2-contract-freeze.md` | 추가 | 이번 작업의 모든 변경 파일과 검증 결과를 기록했다. |

## 상세 내용

AsyncAPI의 문서 버전과 Payload 계약 버전을 분리하고, 10개 MQTT Topic을 Backend
관점의 8개 `receive`와 2개 `send` Operation으로 표현했다. Payload는 Draft 07
Schema로 엄격히 검증하며, JSON Schema로 표현하기 어려운 시각 선후·Topic/Payload
ID·bbox 순서·중복·Replay는 Manifest Semantic 시나리오와 Test로 보완했다.

현재 구현 불일치는 Embedded Python에 있었고 관련 수정은 선행 작업에서
`mqtt_id`, Event `ttl_sec`, `tbs/v2`, `"2.0"` 기준으로 반영됐다. 이번 작업은
Embedded나 Backend Source를 추가로 변경하지 않고 계약 상수·발행 정책 및 양쪽
테스트로 정합성을 확인했다.

과거 `tbs/v1` 문서는 삭제하지 않았다. v2가 현행 정본임을 상단에 명시해 자료
보존과 신규 구현의 오용 방지를 함께 만족시켰다.

## 검증

- 실행한 명령:
  - `$env:PYTHONIOENCODING='utf-8'; python -m unittest discover -s test/mqtt_contract -p 'test_*.py' -v`
  - System Python에 로컬 uv Cache의 `pytest 9.1.1` 경로를 추가한 뒤 Embedded에서 전체 `pytest -q`
  - `.\gradlew.bat test --rerun-tasks --tests "com.gilbom.robot.mqtt.parser.MqttMessageParserTest" --tests "com.gilbom.robot.mqtt.topic.MqttTopicParserTest" --tests "com.gilbom.robot.mqtt.client.MqttV2PublisherTest" --tests "com.gilbom.robot.mqtt.handler.MqttTopicMessageHandlerRegistrationTest"`
  - `git diff --check`
  - 변경 로그 표의 파일 경로와 실제 작업 파일을 비교하는 Read-only Python 점검
- 결과:
  - MQTT v2 계약 Test: 8개 통과
  - Embedded 전체 Test: 479개 통과
  - Backend MQTT 집중 Test: 18개 통과, 실패 0, Skip 0, BUILD SUCCESSFUL
  - `git diff --check`: 오류 없음
  - 변경 로그 파일 대조: 기록 56개, 예상 56개, 누락·초과·실제 파일 누락 0개
- 미실행 항목과 사유:
  - AsyncAPI 공식 CLI 검증: 로컬에 CLI가 없어 구조·참조·Binding은 자동 계약 Test로 검증했다.
  - 실제 Jetson 배포: 장비 접근과 운영 Credential이 필요한 외부 환경 후속 작업이다.
  - EC2 Mosquitto E2E: 운영 TLS Endpoint와 Credential이 필요한 외부 환경 후속 작업이다.
  - 이번 작업의 Broker E2E 재실행: 구현 Source를 변경하지 않았으며 최신 `TROUBLESHOOTING/8_MqttInboundEventBrokerE2E.md`의 TLS Broker 2개 통과 기록을 확인했다.

## 영향 및 주의사항

- v2 계약 묶음 중 한 파일만 독립적으로 변경하면 안 된다. 사람용 계약,
  AsyncAPI, Schema, Fixture, Test를 함께 검토해야 한다.
- `additionalProperties: false` 정책 때문에 선택 필드 추가도 현재 계약에서는
  호환 변경이 아니다. Wire 변경은 새 Major Namespace와 `schema_version`으로
  관리한다.
- REST API `/api/v1`과 MQTT `tbs/v2`는 별도 Version 축이다. REST 문서의 v1
  표기는 MQTT v2 전환 때문에 바꾸지 않는다.
- 작업 시작 전부터 존재한 다른 Mission·Frontend·OpenAPI 변경은 이번 작업에서
  수정하거나 되돌리지 않았다.
- Fixture에는 실제 Secret과 Credential을 포함하지 않는다.

## 미완료 및 후속 작업

- 실제 Jetson에 현재 Embedded Python을 배포하고 Process 재시작을 확인한다.
- Jetson Credential·ACL로 EC2 Mosquitto의 `tbs/v2` Publish/Subscribe를 검증한다.
- 실제 환경에서 LWT, Retain, QoS, MQTT 5 Message Expiry, 시간 동기화와 Replay를
  검증한다.
- 위 항목은 로컬 계약 동결과 별개의 배포 검증이며 현재 로컬 작업 상태는 완료다.
