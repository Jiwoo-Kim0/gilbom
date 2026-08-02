# 변경 로그: 임베디드 긴급정지 복구와 통신 인계

- 작성 시각: 2026-08-02 14:54 (Asia/Seoul)
- 작업 유형: 수정 | 테스트 | 문서
- 상태: 부분 완료

## 작업 요약

Mock Robot뿐 아니라 실제 `embedded/` Command 처리도 실행 중 긴급정지 시 Mission을
`PAUSED`로 보존하고 `MISSION_PAUSED`를 발행하도록 수정했다. 긴급정지 해제는 자동
주행하지 않고 `PAUSED`에 머물며, 별도 `RESUME_MISSION` 뒤에만 `RUNNING`으로
복귀한다. 임베디드팀이 MQTT v2 구현·실기를 준비할 때 필요한 `zone_id`, 식별자,
Topic, QoS, TLS, 시간, Outbox, Command, Mission Event 규칙을 새 통합 인계 문서로
정리했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `embedded/commands.py` | 수정 | `_emergency_stop()`이 모터와 Robot mode를 먼저 정지한 뒤 RUNNING Mission을 `PAUSED`로 전환하고 `MISSION_PAUSED`를 발행한다. 이미 PAUSED인 Mission에는 중복 Event를 만들지 않는다. 기존 `zone_id` 정규식 제거 변경은 보존했다. |
| `embedded/test/test_commands.py` | 수정 | 긴급정지 시 Mission ID 보존·PAUSED Event를 검증하고, 해제 후 PAUSED 대기와 명시적 재개 뒤 RUNNING·MISSION_RESUMED를 검증하도록 기존 기대값을 수정했다. 기존 불투명 `zone_id` 테스트 변경은 보존했다. |
| `embedded/docs/comms/통신임베디드가 알아야할점.md` | 추가 | MQTT v2 정본, zone_id 무제약 규칙, 긴급정지 복구 FSM, 식별자, Topic/QoS/Retain, TLS, 시간·재전송, State·Telemetry, Command·Event, 실기 체크리스트를 통합했다. |
| `embedded/docs/comms/MQTT-송수신-동작-정리.md` | 수정 | Command 표와 안전 정지 해제 설명, 긴급정지 처리 순서를 PAUSED 보존·명시적 재개 계약으로 동기화했다. 기존 zone_id 문서 변경은 보존했다. |
| `embedded/docs/comms/웹_통신계약_로봇_수신_정리.md` | 수정 | 긴급정지·해제 Command 동작과 Mission Event 발행, 자동 주행 금지 규칙을 현재 구현과 맞췄다. 기존 zone_id 문서 변경은 보존했다. |
| `embedded/docs/comms/로봇-명령-왕복과-ACK_구현내용.md` | 수정 | `CLEAR_EMERGENCY_STOP`의 `reason` 파라미터를 정정하고 긴급정지 해제 후 별도 재개 흐름을 추가했다. |
| `CHANGLOGS/2026-08-02_1454_embedded-communication-handoff.md` | 추가 | 실제 임베디드 수정, 통합 인계 문서, 검증과 미실행 사유를 기록했다. |

## 상세 내용

- 이전 실제 임베디드 구현은 긴급정지 때 Mission을 RUNNING으로 두고 해제 시
  `release_safe_stop()`이 곧바로 PATROLLING으로 복귀했다.
- 수정 후 `EMERGENCY_STOP`은 모터 정지와 SAFE_STOP 전이 뒤 실행 중 Mission을
  PAUSED로 만들고 Mission Event를 발행한다.
- `CLEAR_EMERGENCY_STOP`은 PAUSED Mission을 읽어 Robot mode도 PAUSED로 전환한다.
- SAFE_STOP 중 직접 RESUME 거절 정책은 유지하며, 해제 후 RESUME만 RUNNING으로
  복귀시킨다.
- Patrol Area `areaCode`/MQTT `zone_id`는 형식 제약 없는 불투명 문자열이다. 기존
  사용자 변경으로 임베디드 정규식, Backend DTO 패턴, JSON Schema 패턴이 제거됐고
  `V36__relax_zone_id_format.sql`이 DB CHECK 제약 제거를 준비한다. 이번 작업에서는
  해당 zone_id 구현·Migration을 수정하지 않았다.

## 검증

- 실행한 명령:
  - 실제 `CommandRunner`, `RobotState`, `EventPublisher`, `MessageFactory`를 조립한
    Python 긴급정지 복구 스모크
  - `commands.py`, `test/test_commands.py` 내장 `compile()` 구문 검사
  - 대상 코드·문서 `git diff --check`
  - 인계 문서의 정본·코드·Runbook·V36 참조 경로 `Test-Path`
  - 과거 긴급정지 기대 함수명·RUNNING 즉시 복귀 문구 전역 검색
  - `python -m pytest -q -p no:cacheprovider` 전체 임베디드 테스트 시도
- 결과:
  - `START_MISSION → EMERGENCY_STOP → CLEAR_EMERGENCY_STOP → RESUME_MISSION`
    스모크 통과.
  - 임의 한글 `zone_id`가 START부터 재개까지 그대로 보존됨을 함께 확인.
  - 상태는 `PATROLLING/RUNNING → SAFE_STOP/PAUSED → PAUSED/PAUSED →
    PATROLLING/RUNNING` 순서로 확인.
  - Mission Event는 `MISSION_STARTED → MISSION_PAUSED → MISSION_RESUMED` 순서로 확인.
  - Python 구문 검사 통과.
  - 대상 파일 `git diff --check` 통과.
  - 인계 문서 참조 경로 8개 모두 존재 확인.
  - 과거 긴급정지 즉시 복귀 Test 이름·단정은 남지 않음.
  - 전체 pytest는 현재 Python 환경에 `pytest` 모듈이 없어 Test 수집 전에 중단.
- 미실행 항목과 사유:
  - 임베디드 전체 pytest 회귀는 `pytest` 미설치로 미실행했다. 제품 Test 실패가 아니라
    Runner 의존성 부재다.
  - 실제 모터·Jetson·Broker·Backend 왕복은 로컬 DB·Mosquitto와 외부 장비가 준비되지
    않아 미실행했다.

## 영향 및 주의사항

- 긴급정지 처리 중 `MISSION_PAUSED`는 Command Result와 다른 Topic으로 발행되므로
  Topic 간 도착 순서에 의존하지 않는다. 각 메시지의 ID와 상태로 처리한다.
- 모터 정지는 기존대로 MQTT ACK보다 먼저 수행한다.
- 긴급정지 해제는 자동 재개가 아니다. 운영자는 주변 안전 확인 뒤 별도 재개 명령을
  실행해야 한다.
- `zone_id` 관련 기존 변경은 다른 작업자의 작업으로 간주해 되돌리거나 재작성하지
  않았다. 배포 시 Flyway V36 적용 여부는 별도로 확인한다.

## 미완료 및 후속 작업

- pytest가 준비된 환경에서 `cd embedded && pytest -q` 전체 회귀를 실행한다.
- 실제 Jetson에서 모터 정지→SAFE_STOP/PAUSED→MISSION_PAUSED→해제 후 PAUSED→
  명시적 재개의 순서를 확인한다.
- 운영 DB에 Flyway V36이 적용됐는지 확인하고 임의 `areaCode`의 Incident 저장까지
  종단 간 검증한다.
