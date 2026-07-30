# 변경 로그: 시간 기반 Mission MQTT 계약 묶음 검증

- 작성 시각: 2026-07-30 12:24 (Asia/Seoul)
- 작업 유형: 문서
- 상태: 완료

## 작업 요약

`CHANGLOGS/2026-07-30_1214_time-based-mission-contract-ui-e2e.md` 작성 후 MQTT
v2 동결 묶음을 다시 대조하는 과정에서 사람용 계약에 과거 Route·GPS 기반 진행률
설명이 두 군데 남은 것을 확인했다. 두 문장을 시간 기반 계약으로 정정하고 JSON
Schema 설명, 동결 가이드, 자동 계약 테스트와 트러블슈팅 원인 기록을 함께 맞췄다.
MQTT Payload의 필드·타입·필수 여부·Topic은 변경하지 않았다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/docs/final-mqtt-topic-schema-v2.md` | 수정 | 상단 핵심 결정과 Mission Event 후속 설명에 남아 있던 Route·GPS 진행률 문장을 `mission.elapsed_sec / planned_duration_sec` 시간 계산으로 정정했다. |
| `web/docs/mqtt-v2/schemas/telemetry.schema.json` | 수정 | 기존 `mission.elapsed_sec`에 PAUSED 포함 벽시계·단조 증가 의미를 설명으로 추가했다. Wire Shape와 검증 제약은 유지했다. |
| `web/docs/mqtt-v2-contract-freeze.md` | 수정 | 동결 대상에 `mission.elapsed_sec` 시간 의미와 Route·GPS 비의존성을 명시했다. |
| `test/mqtt_contract/test_mqtt_v2_contract.py` | 수정 | JSON Schema와 사람용 계약의 벽시계·PAUSED·단조 증가·시간 산식 정합성 및 과거 Route 문구 부재를 검증하는 회귀 테스트를 추가했다. |
| `TROUBLESHOOTING/10_TimeBasedMission.md` | 수정 | 잔존 모순의 현상·원인·해결·재발 방지와 MQTT 계약 테스트 9건 결과·실행 명령을 추가했다. |
| `CHANGLOGS/2026-07-30_1224_time-mission-contract-bundle-verification.md` | 추가 | 12:14 작업 로그의 동결 계약 묶음 보완 사항과 검증 결과를 별도 기록했다. |

## 상세 내용

`mission.elapsed_sec` 상세 절은 이미 시간 기반으로 바뀌었지만 문서 상단의
`v2 핵심 결정`과 Mission Event 절 아래에는 계획 Route·유효 GPS로 진행률을
계산한다는 과거 설명이 남아 있었다. 원인은 상세 절만 중심으로 수정하고 동일
개념의 전체 문서 검색과 동결 묶음 정합성 검사를 작업 로그 작성 전에 수행하지
않은 것이다.

사람용 계약의 두 잔존 문구를 시간 기반 설명으로 교체하고, 기계 판독 Schema에도
동일 의미를 추가했다. 자동 테스트는 필드 구조뿐 아니라 의미를 나타내는 핵심
문구와 산식을 확인하므로 이후 일부 문서만 되돌아가는 회귀를 탐지한다. 설명
정정만 수행했으므로 AsyncAPI 참조, Fixture Payload와 Manifest 예상 결과는 변경할
필요가 없었다.

## 검증

- 실행한 명령:
  - `$env:PYTHONIOENCODING='utf-8'; python -m unittest discover -s test/mqtt_contract -p 'test_*.py' -v`
  - `git diff --check`
- 결과:
  - MQTT v2 계약 테스트 9건 통과, 실패 0건
  - JSON Schema Draft 07 유효성, AsyncAPI 참조, Fixture·Manifest, Embedded 상수,
    사람용 계약과 `elapsed_sec` 의미 정합성 모두 통과
  - `git diff --check` 오류 없음. 기존 작업 파일의 LF→CRLF 경고만 출력됨
- 미실행 항목과 사유:
  - Backend·Frontend 전체 테스트는 이 보완에서 실행 코드를 바꾸지 않아 다시
    실행하지 않았다. 직전 12:14 작업에서 Backend 544건, Frontend lint·build,
    Playwright Chromium 1건이 모두 통과했다.

## 영향 및 주의사항

- MQTT JSON Payload Shape와 버전은 그대로 `2.0`이며 Embedded에 새 필드를
  요구하지 않는다.
- Embedded는 기존 `mission.elapsed_sec`를 Mission 시작 후 벽시계 누적 초로
  발행해야 하며 PAUSED 중에도 증가시켜야 한다.
- 서버 진행률은 최신 유효 누적시간과 Mission 계획시간으로 계산하고 GPS 유효
  여부나 내부 Dummy Route Geometry에는 의존하지 않는다.
- 이 보완 로그의 상태는 완료지만 `DAY6-B-00-6` 전체 완료 기준은 실제 로그인
  세션·실행 Backend·영속 DB Browser 단일 체인이 남아 있어 계속 90%
  `IN PROGRESS`다.

## 미완료 및 후속 작업

- `DAY6-B-00-6`의 운영형 Browser E2E를 테스트 계정·세션·DB 정리 절차와 함께
  추가한다.
- 동결 계약 의미를 변경해야 한다면 v2 설명만 수정하지 말고 새 Namespace와
  `schema_version`으로 Major 변경 절차를 수행한다.
