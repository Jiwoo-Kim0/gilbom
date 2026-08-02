# 변경 로그: Mission 정상 종료·자동 명령·이력 숨김

- 작성 시각: 2026-08-02 17:13 (Asia/Seoul)
- 작업 유형: 수정
- 상태: 완료

## 작업 요약

기존 화면의 `임무 종료`가 실제로는 `CANCEL_MISSION`을 보내 Mission을
`CANCELLED`로 만들던 계약 불일치를 수정했다. 정상 종료용
`COMPLETE_MISSION`을 REST·DB·MQTT·Frontend·Embedded·Mock Robot에 추가했고,
`임무 취소`와 `임무 종료`를 화면에서 분리했다.

계획시간이 끝난 RUNNING Mission은 Backend DB만 임의로 COMPLETED로 바꾸지
않는다. 5초 간격 Scheduler가 `SYSTEM/COMPLETE_MISSION`을 MQTT로 발행하고,
로봇이 모터를 정지한 뒤 `MISSION_COMPLETED` Event를 보내야 Mission이
`COMPLETED·100%`로 확정된다. PAUSED는 자동 종료 대상에서 제외해 재개
가능성을 보존했다.

실행 전 `CREATED`는 기존처럼 물리 삭제하고, `COMPLETED`·`CANCELLED`·`FAILED`
종료 Mission은 Telemetry·Event·Command·상태 이력 FK 증적을 보존한 채 일반
목록에서 숨기는 논리 삭제로 분리했다. 활성 Mission이 없으면 ACTIVE
Robot을 새 Mission에 재배정할 수 있게 했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/backend/src/main/resources/db/migration/V37__soft_delete_terminal_missions.sql` | 추가 | Mission `deleted_at/deleted_by`, 종료 상태 제약·인덱스, `COMPLETE_MISSION` Command CHECK, Mission별 SYSTEM 종료 명령 중복 방지 인덱스 추가 |
| `web/backend/src/main/java/com/gilbom/robot/mission/MissionAutoCompletionJob.java` | 추가 | 5초 기본 간격으로 기한 도래 Mission 종료 Command 발행 작업 실행 |
| `web/backend/src/main/java/com/gilbom/robot/mission/MissionCompletionCommandCreationService.java` | 추가 | 기한이 도래한 RUNNING Mission을 잠그고 `SYSTEM/COMPLETE_MISSION` Command를 멱등 생성, PAUSED·기존 종료 명령 제외 |
| `web/backend/src/main/java/com/gilbom/robot/mission/MissionCompletionCommandDispatcher.java` | 추가 | SYSTEM MQTT Payload 발행과 SENT·PUBLISH_FAILED Lifecycle 기록 |
| `web/backend/src/main/java/com/gilbom/robot/data/mqtt/v2/outbound/CommandRequestPayload.java` | 수정 | `COMPLETE_MISSION` enum과 빈 parameters 검증 추가 |
| `web/backend/src/main/java/com/gilbom/robot/data/api/request/RobotCommandCreateRequest.java` | 수정 | 수동 종료 REST 요청의 빈 parameters 허용 |
| `web/backend/src/main/java/com/gilbom/robot/command/RobotCommandCreationService.java` | 수정 | RUNNING·PAUSED Mission에 COMPLETE Target을 연결하고 TTL 30초 적용, 숨긴 Mission 제외 |
| `web/backend/src/main/java/com/gilbom/robot/mission/MissionManagementService.java` | 수정 | CREATED 물리 삭제와 종료 Mission 논리 삭제 분리, 활성 Mission 삭제 거절 |
| `web/backend/src/main/java/com/gilbom/robot/mission/MissionQueryService.java` | 수정 | Mission 목록·상세·지도에서 숨긴 Mission 제외 |
| `web/backend/src/main/java/com/gilbom/robot/mission/MissionEventIngestionService.java` | 수정 | 숨긴 Mission에 뒤늦은 Robot Event가 상태를 다시 쓰지 못하게 차단 |
| `web/backend/src/main/java/com/gilbom/robot/application/RobotQueryService.java` | 수정 | 활성 Mission 없음을 `missionAssignable`로 응답하고, 관리 Mission 상태를 오래된 Telemetry Mission 상태보다 우선 |
| `web/backend/src/main/java/com/gilbom/robot/data/api/response/RobotListItemResponse.java` | 수정 | `missionAssignable` 필드 추가 |
| `web/backend/src/main/java/com/gilbom/robot/telemetry/TelemetryIngestionService.java` | 수정 | 숨긴 Mission 참조 제외 |
| `web/backend/src/main/java/com/gilbom/robot/event/RobotEventIngestionService.java` | 수정 | 숨긴 Mission 참조 제외 |
| `web/backend/src/main/java/com/gilbom/defect/IncidentEventIngestionService.java` | 수정 | 숨긴 Mission 참조 제외 |
| `web/backend/src/main/java/com/gilbom/statistics/StatisticsRepository.java` | 수정 | 숨긴 Mission을 운영 통계에서 제외 |
| `web/backend/src/main/java/com/gilbom/report/generation/ReportGenerationDataRepository.java` | 수정 | 숨긴 Mission을 보고서 생성 원천에서 제외 |
| `web/backend/src/test/java/com/gilbom/robot/integration/MissionManagementIntegrationTest.java` | 수정 | 종료 Mission 숨김·이력 보존·조회 제외·감사 로그 검증 |
| `web/backend/src/test/java/com/gilbom/robot/integration/RobotCommandLifecycleIntegrationTest.java` | 수정 | 수동 COMPLETE Target 연결, 계획시간 SYSTEM Command 발행, Event 전 Mission RUNNING 유지, PAUSED 제외 검증 |
| `web/backend/src/test/java/com/gilbom/robot/integration/RobotQueryScopeIntegrationTest.java` | 수정 | 오래된 Telemetry RUNNING보다 COMPLETED Mission 정본을 우선하고 숨김 후에는 노출하지 않음을 검증 |
| `web/backend/src/test/java/com/gilbom/robot/controller/RobotQueryControllerTest.java` | 수정 | `missionAssignable` REST JSON 계약 검증 |
| `web/frontend/gilbom/src/features/command/commandTypes.ts` | 수정 | Frontend `COMPLETE_MISSION` 타입과 빈 parameters Request 타입 추가 |
| `web/frontend/gilbom/src/features/command/commandPolicy.ts` | 수정 | `임무 종료`(COMPLETE)와 `임무 취소`(CANCEL) 정의·허용 상태·확인 메시지 분리 |
| `web/frontend/gilbom/src/features/command/commandPresentation.ts` | 수정 | Command 이력의 종료·취소 한글 라벨 분리 |
| `web/frontend/gilbom/src/features/command/CommandControlPage.tsx` | 수정 | COMPLETE·CANCEL 아이콘 분리, Mission Catalog 정본으로 오래된 Robot 상태 보정 |
| `web/frontend/gilbom/src/features/mission/MissionCommandActions.tsx` | 수정 | Mission 상세에 `임무 종료` COMPLETE 버튼 추가하고 CANCEL을 `임무 취소`로 표시 |
| `web/frontend/gilbom/src/features/mission/MissionManagePage.tsx` | 수정 | Backend `missionAssignable`로 Robot 후보 판정, CREATED `삭제`와 종료 Mission `목록에서 숨기기` UI 분리 |
| `web/frontend/gilbom/src/features/mission/missionEligibility.ts` | 추가 | 삭제·숨김 가능 Mission 상태와 Robot 재배정 후보 판정 함수 추가 |
| `web/frontend/gilbom/src/features/mission/missionTypes.ts` | 수정 | Robot Option `missionAssignable` 타입 추가 |
| `web/frontend/gilbom/test/commandPolicy.test.ts` | 수정 | RUNNING·PAUSED COMPLETE 허용과 빈 parameters Request 검증 |
| `web/frontend/gilbom/test/missionEligibility.test.ts` | 추가 | 종료 Mission 재배정, 삭제·숨김 가능 상태 단위 검증 |
| `web/frontend/gilbom/e2e/mission-time.spec.ts` | 수정 | Mission 생성 Robot Fixture에 `missionAssignable` 계약 반영 |
| `web/frontend/gilbom/e2e/patrol-area-registration.spec.ts` | 수정 | Robot Fixture의 임무 재배정 가능 여부 반영 |
| `web/frontend/gilbom/e2e/robot-credential-management.spec.ts` | 수정 | Robot 목록 Fixture에 `missionAssignable` 필드 반영 |
| `embedded/commands.py` | 수정 | `COMPLETE_MISSION` 검증·핸들러를 추가하고 모터 정지 → `MISSION_COMPLETED` → Mission 보관함 정리 순서 구현 |
| `embedded/test/test_commands.py` | 수정 | COMPLETE 정지 순서·Event·READY·활성 Mission 필수 검증 추가 |
| `web/tools/mock-robot/mock_robot.py` | 수정 | COMPLETE Command를 `MISSION_COMPLETED/READY`로 처리하고 CANCEL Event와 분리 |
| `web/tools/mock-robot/tests/test_mock_robot_mission.py` | 수정 | Mock COMPLETE Event·Mission ID·완료 사유·보관함 정리 검증 |
| `web/docs/mqtt-v2/schemas/command-request.schema.json` | 수정 | MQTT JSON Schema에 COMPLETE enum과 빈 parameters 분기 추가 |
| `web/docs/openapi-v1.yaml` | 수정 | COMPLETE Command, `missionAssignable`, 종료 Mission 숨김 DELETE 계약 반영 |
| `web/docs/final-mqtt-topic-schema-v2.md` | 수정 | 명령 7종 COMPLETE TTL·parameters 계약 추가 |
| `web/docs/mqtt-device-db-mapping.md` | 수정 | COMPLETE 정상 종료와 CANCEL 취소 Mapping 분리 |
| `web/docs/time-based-mission-contract.md` | 수정 | 계획시간 도래 시 DB 직행이 아닌 SYSTEM COMPLETE 명령·Robot Event 확정 계약 명시 |
| `web/docs/database-migrations.md` | 수정 | V37 논리 삭제·COMPLETE Command DB 변경과 V1~V37 이력 반영 |
| `web/docs/팀원B/작업내용/순찰구역과-시간기반-Mission_구현내용.md` | 수정 | 계획시간 SYSTEM COMPLETE·PAUSED 제외·Event 확정 사이클 반영 |
| `web/docs/팀원B/작업내용/로봇-명령-왕복과-ACK_구현내용.md` | 수정 | COMPLETE Command 지원표에 추가 |
| `embedded/docs/comms/통신임베디드가 알아야할점.md` | 수정 | 일시정지·긴급정지·해제·재개·정상 종료·취소·실패·자동 COMPLETE 상태 사이클 정리 |
| `embedded/docs/comms/웹_통신계약_로봇_수신_정리.md` | 수정 | Embedded COMPLETE parameters·TTL·정지·Event 처리 계약 추가 |
| `embedded/docs/comms/MQTT-송수신-동작-정리.md` | 수정 | 명령 7종과 MISSION_COMPLETED 호출자, 종료·취소 의미 분리 |
| `embedded/docs/comms/MQTT-v2-임베디드-대조와-수정_구현내용.md` | 수정 | Command 지원 수를 7종으로 갱신 |
| `embedded/docs/comms/로봇-명령-왕복과-ACK_구현내용.md` | 수정 | COMPLETE Command 지원 표 추가 |
| `embedded/docs/comms/순찰구역과-시간기반-Mission_구현내용.md` | 수정 | 계획시간 COMPLETE 명령 수신과 Event 전 DB 상태 유지 규칙 반영 |
| `web/Jenkinsfile` | 수정 | Migration Gate·산출물 Key를 V1~V37로 갱신 |
| `web/infra/postgres/verify-empty-database.sql` | 수정 | 빈 DB Migration 검증 상한을 V37로 갱신 |
| `web/infra/postgres/verify-production-database.sql` | 수정 | 운영 DB Migration 검증 상한을 V37로 갱신 |
| `web/scripts/generate-build-metadata.py` | 수정 | 빌드 Metadata 필수 Migration 키를 V37로 갱신 |
| `web/scripts/verify-ci-release-contract.sh` | 수정 | CI 배포 계약의 V37 범위 검증 |
| `web/scripts/verify-day8-infrastructure.sh` | 수정 | 인프라 완료 검증 기준을 V37로 갱신 |
| `web/scripts/verify-production-runtime.sh` | 수정 | 운영 Runtime Flyway 상한 V37 검증 |
| `web/scripts/verify-release-artifacts.sh` | 수정 | Release 산출물 Migration Key를 V37로 갱신 |
| `web/docs/day8-completion-runbook.md` | 수정 | V37 Migration 운영 검증 명령 반영 |
| `web/docs/jenkins-pipeline.md` | 수정 | Jenkins Migration Gate V37 기준 반영 |
| `CHANGLOGS/2026-08-02_1713_mission-completion-and-archive.md` | 추가 | 이번 작업의 변경·검증·주의사항 기록 |

## 상세 내용

### Mission 종료·취소 사이클

- `RUNNING | PAUSED --COMPLETE_MISSION--> MISSION_COMPLETED --> COMPLETED`
- `READY | RUNNING | PAUSED --CANCEL_MISSION(reason)--> MISSION_CANCELLED --> CANCELLED`
- COMPLETE는 로봇 모터를 정지한 뒤 정상 종료 Event를 발행한다.
- 수동 COMPLETE는 ONLINE·ACTIVE Robot의 RUNNING·PAUSED Mission에서 사용한다.
- 시간 만료 COMPLETE는 SYSTEM Command로 발행하며 Robot Event 전에는 Mission을
  COMPLETED로 바꾸지 않는다.

### 일시정지·긴급정지 차이

- 일시정지: `PAUSE_MISSION`, `PAUSED/REMOTE_PAUSE`, 안전 조건이 맞으면
  `RESUME_MISSION`으로 바로 재개한다.
- 긴급정지: `EMERGENCY_STOP`, 모터 정지를 먼저 실행하고
  `SAFE_STOP/REMOTE_ESTOP`에 안전 Lock을 유지한다. 직접 RESUME은 거절하고
  `CLEAR_EMERGENCY_STOP` 후 Mission을 PAUSED로 보존한 뒤 별도 RESUME를 받는다.

### Mission 목록·Robot 재배정

- CREATED는 실행 증적이 없어 물리 삭제한다.
- COMPLETED·CANCELLED·FAILED는 `deleted_at/deleted_by`를 써서 일반 조회와
  통계·보고서에서 숨기고 FK 연결 증적은 보존한다.
- CREATED·READY·RUNNING·PAUSED Mission이 없고 Robot이 ACTIVE면 새 Mission에
  재배정할 수 있다.
- 현재 Frontend의 상태 필터로 종료 이력을 구분하고, 상세의
  `목록에서 숨기기`로 종료 이력을 기본 목록에서 제외한다.

## 검증

- 실행한 명령:
  - `web/backend/gradlew.bat test --no-daemon`
  - `npm test -- --runInBand`
  - `npm run build`
  - `python -m py_compile embedded/commands.py web/tools/mock-robot/mock_robot.py web/tools/mock-robot/tests/test_mock_robot_mission.py`
  - `python web/tools/mock-robot/tests/test_mock_robot_mission.py`
  - `npx playwright test e2e/mission-time.spec.ts --project=chromium`
  - `docker compose up --build --detach backend frontend`
  - `docker compose ps`
  - `psql ... SELECT version, success FROM flyway_history.flyway_schema_history ...`
  - `git diff --check`
- 결과:
  - Backend 전체: 782 tests, failures 0, skipped 0, BUILD SUCCESSFUL.
  - Frontend: 40 tests, failures 0; TypeScript·Vite production build 성공.
  - Mock Robot: 9 tests 성공; Embedded·Mock Python 문법 검증 성공.
  - Playwright Mission 생성: Chromium 1 test 성공.
  - Docker image build 내 Backend: 782 tests, failures 0, `allowSkippedTests` 정책에 따라
    Testcontainers 164 skipped; Frontend test·lint·build 성공.
  - Local Runtime: backend·frontend·db·mosquitto 모두 healthy, Backend actuator UP,
    Frontend health HTTP 200.
  - Flyway: V37 `success=true`, Backend Startup에서 37개 Migration 검증·V36→V37 적용 성공.
  - `git diff --check`: whitespace error 없음. Windows 작업트리의 LF→CRLF 예고만 있음.
- 미실행 항목과 사유:
  - `embedded/test/test_commands.py` pytest Suite는 현재 System Python에 `pytest`가 없어
    실행하지 못했다(`No module named pytest`). 대신 문법 검증과 동일 계약을
    사용하는 Mock Robot 종료 테스트 9개를 실행했다.
  - 현재 로컬 PAUSED Mission을 실제로 종료하는 API·UI 조작은 사용자 실행
    데이터를 변경하므로 임의로 수행하지 않았다.

## 영향 및 주의사항

- 임베디드가 `COMPLETE_MISSION`을 배포하지 않은 예전 버전이면 정상 종료요청을
  거절하거나 Timeout할 수 있다. `embedded/commands.py`와 운영 Robot을 함께
  배포해야 한다.
- PAUSED Mission은 계획시간이 지났어도 즉시 완료하지 않는다. 재개하면
  다음 5초 Scan에서 SYSTEM COMPLETE를 발행한다.
- Broker가 메시지를 접수했어도 Robot Event가 오지 않으면 Mission은 RUNNING을
  유지한다. Command 이력의 EXPIRED·FAILED·REJECTED를 확인해야 한다.
- 종료 Mission 숨김은 현재 복구 API가 없는 논리 삭제다. DB 증적은 남지만
  운영자가 일반 UI에서 복구하는 기능은 후속 범위다.
- 기존 업무·Telemetry·Command FK를 물리 삭제하지 않으므로 감사·추적
  증적은 유지된다.

## 미완료 및 후속 작업

- 운영 Embedded/Jetson에 `COMPLETE_MISSION` 호환 코드를 배포하고 실제 모터 정지·
  `MISSION_COMPLETED`·ACK를 실기기에서 1회 통합 검증해야 한다.
- 필요하면 종료 Mission 복구·보관함 필터 API를 별도 후속 기능으로 추가한다.
