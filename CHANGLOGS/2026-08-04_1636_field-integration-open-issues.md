# 변경 로그: 실차 통합 미해결 이슈 정리

- 작성 시각: 2026-08-04 16:36 (Asia/Seoul)
- 작업 유형: 문서
- 상태: 완료

## 작업 요약

실차 통합 과정에서 확인된 Mission, 이미지, 탐지 계약, 주행·주차 문제를 우선순위별로 정리했다. 온라인 상태에서도 취소·완료되지 않는 좀비 Mission을 P0 최우선으로 지정하고, 상태 불일치 조사 지점, 수집할 식별자, 목표 동작, 회귀 시나리오와 완료 기준을 구체화했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/docs/field-integration-open-issues.md` | 추가 | 좀비 Mission과 계획시간 자동 종료를 P0로 지정하고, 이미지 송수신 정합, 점자블록 미설치 통신 계약, 파손 이미지 패치 검증, 통신 통합 후 주행·주차 회귀의 현상·확인 항목·완료 기준을 기록 |
| `CHANGLOGS/2026-08-04_1636_field-integration-open-issues.md` | 추가 | 문서 작성 근거, 변경 범위, 검증 결과와 후속 작업 기록 |

## 상세 내용

- 사용자가 최우선으로 지정한 좀비 Mission 문제를 `P0-1`로 배치했다.
- 관리자 REST 인증 실패와 실제 Mission 상태 불일치를 구분하도록 `401 AUTH_UNAUTHENTICATED` 분기 기준을 기록했다.
- Mission DB, 현재 Robot 상태, Command, Mission Event, Robot 로컬 상태를 동일 식별자와 UTC 시각으로 대조하도록 증거 표를 추가했다.
- 계획시간 자동 종료는 안전한 Robot 정지와 `MISSION_COMPLETED` Event를 완료 확정 조건으로 유지하면서, Event 유실 시 무기한 활성 상태를 방지할 관리자 복구 경로를 요구사항에 포함했다.
- 이미지 문제는 `event_id`, `image_id`, 파일 크기, SHA-256로 송신 로그부터 Docker Volume과 관리자 조회까지 연결하도록 정리했다.
- 미설치 결함은 현재 예약값인 `INSTALLATION_INVALID`의 정식 계약화가 필요함을 기록했다.
- 실제 Robot이 사용 중이면 Mock Robot으로 대체하되 동일 MQTT ID 동시 접속을 금지했다.

## 검증

- 실행한 명령:
  - `Get-Content`로 시간 기반 Mission 계약과 최신 Mission 통신 변경 로그 확인
  - `rg`로 MQTT v2의 Mission 완료·취소, Incident 이미지, 미설치 예약값 계약 대조
  - 작성 문서의 P0 우선순위, 필수 이슈, 관련 정본 경로 검색
  - `git diff --check`로 Markdown 공백·형식 오류 확인
- 결과:
  - 좀비 Mission이 P0 최우선으로 표시되고 사용자 제공 여섯 이슈가 모두 문서에 포함됨
  - 현재 정본의 Mission 완료·취소 및 이미지 Incident 계약과 모순되는 표현이 없는지 대조함
- 미실행 항목과 사유:
  - 문서 작성 작업이므로 Backend, Frontend, Robot 자동 테스트와 실차 검증은 실행하지 않음

## 영향 및 주의사항

- 실행 코드, 설정, DB Migration 및 API 계약은 변경하지 않았다.
- 문서의 원인 후보는 진단 전 가설이며, 실제 수정 근거는 한 Mission의 Backend·MQTT·Robot 타임라인으로 확정해야 한다.
- 계획시간 도달만으로 Robot 정지 확인 없이 완료 처리하면 실제 주행 상태와 DB가 어긋날 수 있어, 완료 확정과 장애 복구 경로를 분리했다.

## 미완료 및 후속 작업

- 실제 Robot 또는 Mock Robot으로 P0-1 좀비 Mission을 먼저 재현하고 전체 상태 타임라인을 수집한다.
- 재현 결과에 따라 Backend 상태 전이, Robot Mission Event 발행, Frontend 명령 정책 중 최초 불일치 지점을 수정한다.
- P0 수정 후 계획시간 자동 종료 및 Robot 재할당 회귀 테스트를 수행한다.
