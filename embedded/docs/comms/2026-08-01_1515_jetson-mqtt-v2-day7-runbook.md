# 변경 로그: Jetson MQTT v2 Day 7 통합 가이드

- 작성 시각: 2026-08-01 15:15 (Asia/Seoul)
- 작업 유형: 문서
- 상태: 완료

## 작업 요약

MQTT v2 Wire 계약을 변경하지 않고, 현재 Embedded 코드와 EC2 운영
Broker를 실제 Jetson에 연결하기 위한 Day 7 실행·검증 가이드를 작성했다.
Mock·로컬 통과와 실제 Jetson 통과를 분리하고, TLS·Credential·ACL,
Presence·State·Telemetry, Mission·6개 Command, 단절·재연결·Replay·Expiry,
DB·SSE·React 대조를 Day 7 완료 게이트로 정리했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/docs/final-mqtt-topic-schema-v2.md` | 수정 | 동결된 Wire 구조는 유지하고 참고 자료 목록에 Jetson Day 7 통합 가이드 링크를 추가 |
| `web/docs/팀원B/V2/jetson-mqtt-v2-day7-integration-runbook.md` | 추가 | MQTT v2 정본 연결, 실제 Embedded 환경변수·TLS 설정, Topic·Payload 경계, Day 7 단계별 검증·증거 양식·실패 분류를 문서화 |
| `CHANGLOGS/2026-08-01_1515_jetson-mqtt-v2-day7-runbook.md` | 추가 | 작업 범위, 검증 결과, 실제 Jetson 미실행 상태와 후속 작업을 기록 |

## 상세 내용

- `final-mqtt-topic-schema-v2.md`를 Wire 정본으로 유지하고 운영 접속 절차를
  새 문서로 분리했다. 정본의 참고 자료 목록에만 새 가이드 링크를
  추가했으며 Topic·Payload·Schema·enum은 변경하지 않았다.
- 기존 Credential 전달 문서의 Embedded TODO 상태가 현재와 다름을 명시했다.
- 현재 코드는 `MQTT_HOST`와 `MQTT_PASSWORD`를 읽으며, `ca_cert`와
  `require_tls` 값은 `embedded/config.yaml`에서 설정해야 함을 반영했다.
- 운영 Broker `i15d101.p.ssafy.io:8883`과 현재 서버 TLS + Robot별
  username/password 인증 기준을 기록했다.
- v2에서 제거된 `position.accuracy_m`과 계약에 없는 CPU·GPU·모터 온도를
  실기 Payload에 임의로 추가하지 않도록 경계를 명시했다.
- 충전 자동화와 팀원 C의 Incident 내용 처리를 Day 7 팀원 B 완료
  판정에서 제외했다.

## 검증

- 실행한 명령:
  - `python -B -m unittest discover -s test/mqtt_contract -p 'test_*.py' -v`
  - `rg`와 `Get-Content`로 MQTT v2 계약, Telemetry Schema, Embedded 설정 Loader,
    Mosquitto ACL, Day 7 계획, EC2 운영 로그 대조
  - 새 문서의 상대 링크 대상·줄 끝 공백·Git diff 검사
- 결과:
  - MQTT v2 계약 테스트 9개 통과, 실패 0개
  - 실제 코드의 환경변수·TLS·Topic·Command 지원 범위와 문서를 대조함
  - 문서는 실제 Jetson 미검증 상태를 `WAITING`/`NOT DONE`으로 표현함
- 미실행 항목과 사유:
  - 실제 Jetson→EC2 연결, Robot Credential·ACL, 실데이터·Command 왕복은
    장비·운영 Credential·현장 안전 조건이 필요하여 실행하지 않음
  - 제품 코드를 변경하지 않아 Backend·Frontend·Embedded 전체 테스트는
    재실행하지 않음

## 영향 및 주의사항

- MQTT v2 Topic·Payload·Schema·제품 코드·운영 설정은 변경하지 않았다.
- 이 문서는 실행 안내이며 Jetson 통합 완료 증거가 아니다.
- 운영 Credential 원문을 문서·로그·캡처·Git에 추가하지 않는다.
- 실제 Payload 차이가 발생해도 동결된 v2를 즉시 수정하지 말고
  장비 설정·센서 미제공·계약 변경을 먼저 분류한다.

## 미완료 및 후속 작업

1. 실제 Robot을 등록하고 Robot별 MQTT Credential을 발급한다.
2. Jetson에 Broker Host, CA, `mqtt_id`, Client ID, Password를 안전하게 주입한다.
3. 가이드의 TLS→Telemetry→Command→단절·복구 순서로 실기 검증한다.
4. PASS·FAIL·WAITING 결과와 Secret 없는 증거를 새 CHANGLOG에 남긴다.
