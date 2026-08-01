# 변경 로그: Mission 직접 제어 및 주기형 Mock Robot

- 작성 시각: 2026-08-01 22:21 (Asia/Seoul)
- 작업 유형: 기능
- 상태: 완료

## 작업 요약

Mission 관리 상세에서 Robot 상세 화면으로 이동하지 않고 현재 Mission을 직접
시작·일시정지·재개·종료할 수 있도록 기존 Robot Command 계약을 연결했다. 로컬
Mock Robot은 일회성 표본이 아니라 Telemetry·State와 Safety·Battery·Incident
Event를 독립 주기로 계속 발행하도록 확장하고, RUNNING Mission에서는 위치·속도,
Battery와 Jetson 온도가 변하는 표본을 MQTT로 보내도록 했다. Frontend는 이 값을
직접 만들지 않고 Backend가 DB에 저장한 Snapshot을 REST·SSE로 다시 조회한다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/frontend/gilbom/src/features/mission/MissionCommandActions.tsx` | 추가 | Mission 상세용 직접 제어 컴포넌트를 추가했다. 기존 Command 정책·확인 Modal·Idempotency Key·권한·Robot 온라인 상태·처리 중 명령 검사를 재사용해 START, PAUSE, RESUME, CANCEL을 발행한다. |
| `web/frontend/gilbom/src/features/mission/MissionManagePage.tsx` | 수정 | 기존 READY 상세의 Robot 제어 이동 링크를 제거하고 활성 Mission에 직접 제어 컴포넌트를 표시했다. Robot SSE 수신 시 명령 상태와 Mission Snapshot을 다시 읽도록 Revision을 연결했으며, 작업 시작 전에 존재하던 준비 완료·CREATED 삭제 변경은 보존했다. |
| `web/frontend/gilbom/src/features/mission/mission.css` | 수정 | Mission 상세 안의 직접 제어 패널, 연결 상태, 2열 Command 버튼과 모바일 1열 배치를 추가했으며 기존 준비 완료·삭제 스타일을 보존했다. |
| `web/tools/mock-robot/mock_robot.py` | 수정 | State·Safety·Battery·Incident 독립 주기 옵션과 Event Payload 순환 발행을 추가했다. Battery·온도 변화와 RUNNING 중 GPS·속도 변화를 제한 범위에서 생성하고 기존 Mission Command/Event·Telemetry 흐름에 연결했다. |
| `web/tools/mock-robot/tests/test_mock_robot_mission.py` | 수정 | 새 주기 인자를 Test Fixture에 반영하고 Safety 4종, Battery 5종, Incident Payload 및 RUNNING 중 Telemetry 값 변화·속도를 검증했다. 기존 Fixture에서 빠져 있던 Command Override 기본값도 보완했다. |
| `web/tools/mock-robot/README.md` | 수정 | 모든 발행 Topic, 독립 주기 옵션, 동적 Telemetry 실행 예제와 Mock→MQTT→Backend→DB→REST/SSE→Frontend 데이터 흐름 및 테스트 결함 누적 주의사항을 문서화했다. |
| `CHANGLOGS/2026-08-01_2221_mission-direct-control-periodic-mock.md` | 추가 | 변경 범위, 검증 결과, 실행 중인 로컬 Test Bot과 주의사항을 기록했다. |

## 상세 내용

- Mission 직접 제어는 별도 상태 전이를 Frontend에 만들지 않는다. 기존
  `POST /api/v1/robots/{robotId}/commands`를 호출하고 MQTT Result와 Mission Event를
  정본으로 사용한다.
- START는 선택 Mission의 동적 `missionCode`와 `patrolAreaCode`를 사용하므로 Mission
  ID를 하드코딩하지 않는다.
- 활성 Mission에는 START·PAUSE·RESUME·CANCEL 네 버튼을 모두 표시하되, 현재
  Mission/Robot 상태에서 허용되지 않는 명령은 기존 정책 사유와 함께 비활성화한다.
- Mock 기본 Telemetry는 5초, State는 30초이며 실행 시 Safety 45초, Battery 60초,
  Incident 75초를 지정했다. Presence는 연결 생명주기 Retained 메시지이고 Mission
  Event는 실제 명령 및 완료 시점에만 발생하므로 임의 주기 발행하지 않는다.
- Frontend 숫자를 Timer로 조작하지 않았다. 변경 표본은 Mock Robot이 MQTT로
  발행하고 Backend Ingestion을 거쳐 `telemetry.robot_telemetry`와
  `telemetry.robot_current_state`에 저장된 뒤 화면이 API로 조회한다.
- 로컬 Test Bot은 PID와 로그 경로를 `%TEMP%/gilbom-mock-RBT-001.runtime`에 기록하고
  비밀번호는 프로세스 환경으로만 전달했다. Credential 원문은 코드·로그·이 변경
  로그에 기록하지 않았다.

## 검증

- 실행한 명령:
  - `python -m unittest web/tools/mock-robot/tests/test_mock_robot_mission.py`
  - `python -m py_compile web/tools/mock-robot/mock_robot.py`
  - `npm test`
  - `npm run lint`
  - `docker compose build frontend`
  - `docker compose up -d --no-deps frontend`
  - `Invoke-WebRequest http://127.0.0.1:3000/`
  - `docker compose exec -T db psql ...`로 RBT-001 현재 상태, 최근 Telemetry와 Event 유형 조회
  - `git diff --check` 대상 파일 검사
- 결과:
  - Mock Robot 단위 테스트 5개 성공, Python 구문 검사 성공
  - Frontend 단위 테스트 38개 성공, ESLint 성공, TypeScript·Vite Production Build 성공
  - 최신 Frontend Image로 컨테이너 교체 후 `healthy`, HTTP 200, Image ID 일치
  - Test Bot 프로세스 생존 및 TLS Broker 연결 확인
  - 실제 START_MISSION 수신 후 RBT-001 `ONLINE/PATROLLING/RUNNING` 확인
  - 최근 DB 표본에서 5초 간격 Battery·Jetson 온도·GPS 좌표 변화와
    `speed_mps=0.8`, `moving=true` 확인
  - Safety·Battery·Incident Event가 `telemetry.robot_event`에 유형별 저장됨을 확인
  - 대상 파일 whitespace 오류 없음
- 미실행 항목과 사유:
  - 실제 Jetson 센서·모터 주행 검증은 현재 Local Mock Robot 작업이며 실기 연결이
    아니므로 실행하지 않았다.
  - Browser 자동 클릭 E2E는 실행하지 않았으나 실제 화면에서 발행된 START Command가
    Mock 수신·Mission Event·DB RUNNING 상태까지 왕복한 것을 확인했다.

## 영향 및 주의사항

- 표시값은 DB에 저장된 실제 수신 표본이지만 원천은 Test Bot이므로 현실 센서 측정값은
  아니다. 실제 시연에서는 Mock을 종료하고 Jetson 측정값을 사용해야 한다.
- Incident 주기 발행은 로컬 DB에 결함 관측과 결함 데이터를 계속 추가한다.
- Safety·Battery Event 순환은 장애·복구 UI 검증용 테스트 데이터이며 운영 Broker에서
  사용하지 않는다.
- READY에서는 정지 상태가 정상이므로 GPS와 속도는 변하지 않는다. RUNNING에서만
  위치와 `moving/speed_mps`가 변한다.
- 작업 시작 전부터 존재한 Mission 준비 완료·CREATED 삭제 변경과 다른 Dirty
  Worktree 파일은 되돌리거나 덮어쓰지 않았다.

## 미완료 및 후속 작업

1. 실제 Jetson 연결 전 Local Mock Robot 프로세스를 종료한다.
2. 대화에 노출된 MQTT Credential은 테스트 종료 뒤 회전한다.
3. 실제 장비에서는 Battery·온도·GPS·속도를 센서/추정 원천에서 생성하고 같은 MQTT
   계약으로 발행한다.
