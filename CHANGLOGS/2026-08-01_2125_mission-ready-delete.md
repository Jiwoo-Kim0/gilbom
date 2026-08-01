# 변경 로그: Mission 준비 완료 및 생성 임무 삭제

- 작성 시각: 2026-08-01 21:25 (Asia/Seoul)
- 작업 유형: 기능
- 상태: 완료

## 작업 요약

관리자가 생성한 Mission을 `CREATED`에서 `READY`로 전환한 뒤 기존 Robot 제어
화면에서 `START_MISSION`을 발행할 수 있도록 Mission 관리 화면을 연결했다. 실행
이력이 없는 `CREATED` Mission은 확인 후 삭제할 수 있도록 Backend DELETE API,
DB 강제 조건, 감사 로그와 Frontend 동작을 추가했다. `READY` 이후 Mission은 이력
보존을 위해 삭제하지 않고 기존 취소 상태 전이를 사용한다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/backend/src/main/java/com/gilbom/audit/AuditAction.java` | 수정 | Mission 삭제 성공을 별도 감사 행위로 남기기 위한 `MISSION_DELETED` 값을 추가했다. |
| `web/backend/src/main/java/com/gilbom/robot/api/MissionController.java` | 수정 | `MISSION_MANAGE`, CSRF, Robot OWNER 범위를 적용하는 `DELETE /api/v1/missions/{missionId}` Endpoint를 추가했다. |
| `web/backend/src/main/java/com/gilbom/robot/mission/MissionManagementService.java` | 수정 | Mission을 행 잠금한 뒤 OWNER와 `CREATED` 상태를 검증하고 초기 상태 이력·Mission을 원자적으로 삭제하며 삭제 전 Snapshot을 감사 로그에 기록하는 `delete`를 추가했다. |
| `web/backend/src/main/resources/db/migration/V35__allow_created_mission_deletion.sql` | 추가 | 시작·종료·진행 이력이 없는 `CREATED` 행만 삭제하도록 Mission Trigger를 추가하고, 단일 초기 `CREATED` 상태 이력만 함께 삭제할 수 있도록 append-only Trigger를 제한적으로 확장한 뒤 Runtime Role에 두 테이블의 DELETE 권한을 부여했다. |
| `web/backend/src/test/java/com/gilbom/robot/controller/MissionControllerTest.java` | 수정 | Mission DELETE의 204 응답, 권한·CSRF 전달과 CSRF 누락 거부를 검증했다. |
| `web/backend/src/test/java/com/gilbom/robot/integration/MissionManagementIntegrationTest.java` | 수정 | `CREATED` Mission·초기 이력 삭제와 삭제 감사 Snapshot, `READY` 전환 후 삭제 거부를 실제 PostGIS/Flyway 환경에서 검증했다. |
| `web/frontend/gilbom/src/features/mission/missionTypes.ts` | 수정 | Mission PATCH 요청에 사용할 선택 필드와 관리자 상태 값의 `MissionUpdateInput`을 추가했다. |
| `web/frontend/gilbom/src/features/mission/missionApi.ts` | 수정 | 기존 Mission PATCH 계약을 호출하는 `updateMission`과 204 DELETE를 처리하는 `deleteMission`을 추가했다. |
| `web/frontend/gilbom/src/features/mission/MissionManagePage.tsx` | 수정 | `MISSION_MANAGE` 사용자에게 CREATED 상세의 `준비 완료`·`삭제` 동작, 삭제 확인, 처리 중 중복 조작 차단, 성공·오류 안내와 READY 후 Robot 제어 화면 이동을 추가했다. |
| `web/frontend/gilbom/src/features/mission/mission.css` | 수정 | Mission 상세 작업 버튼과 모바일 배치, 삭제 비활성화 및 오류 여백 스타일을 추가했다. |
| `web/frontend/gilbom/test/missionApi.test.ts` | 추가 | PATCH READY와 DELETE가 계약 URL·Method·CSRF·Body로 호출되는지 검증했다. |
| `web/docs/openapi-v1.yaml` | 수정 | CREATED-only Mission DELETE의 권한, CSRF, 응답 코드와 이력 보존 정책을 OpenAPI 계약에 추가했다. |
| `web/docs/authorization-matrix.md` | 수정 | Mission 단건 Endpoint의 DELETE 권한, OWNER Scope, CSRF와 감사 범위를 반영했다. |
| `CHANGLOGS/2026-08-01_2125_mission-ready-delete.md` | 추가 | 이번 구현 범위, 변경 파일, 검증 결과와 배포 주의사항을 기록했다. |

## 상세 내용

- Mission 생성은 계속 `CREATED`로 끝나며 자동 시작하지 않는다.
- Mission 상세의 `준비 완료`는 기존 PATCH API에 `{ "status": "READY" }`를
  보내고, 성공 후 목록·상세를 다시 조회한다.
- READY 상세은 연결된 Robot 상세로 이동할 수 있으며 기존 제어 정책이 Robot
  ONLINE·ACTIVE, READY Mission, `ROBOT_COMMAND_EXECUTE` 권한을 확인한 뒤
  `START_MISSION` 버튼을 활성화한다.
- 삭제는 `MISSION_MANAGE`와 대상 Robot OWNER를 모두 요구한다. Service와 DB
  Trigger가 이중으로 `CREATED`, 시작·종료 없음, 진행률 0을 강제한다.
- 초기 상태 이력 외에 다른 상태 이력이 있거나 Telemetry·Event·Command가 연결된
  Mission은 FK 또는 상태 검증으로 삭제되지 않는다.
- 삭제 후 Mission 상태 이력은 제거되지만 삭제 전 Mission Snapshot과 작업자·요청
  문맥은 `MISSION_DELETED` 감사 로그에 남는다.

## 검증

- 실행한 명령:
  - `cd web/backend && .\\gradlew.bat test --tests com.gilbom.robot.controller.MissionControllerTest --tests com.gilbom.robot.integration.MissionManagementIntegrationTest`
  - `cd web/backend && .\\gradlew.bat test`
  - `cd web/frontend/gilbom && npm test`
  - `cd web/frontend/gilbom && npm run lint`
  - `cd web/frontend/gilbom && npm run build`
  - `python -B -c "import yaml, pathlib; ... yaml.safe_load(...)"`
  - `git diff --check`
  - Flyway Migration 파일 Version 중복 검사
- 결과:
  - Mission Controller 8개, Mission PostGIS 통합 15개: 실패 0, 스킵 0
  - Backend 전체 774개: 실패 0, 스킵 0
  - Flyway V35 실제 적용 및 CREATED 삭제·READY 삭제 거부 Testcase 실행 확인
  - Frontend 전체 38개: 실패 0, 스킵 0
  - Frontend ESLint 성공, TypeScript·Vite Production Build 성공
  - OpenAPI YAML 파싱 성공, `deleteMission` Operation 확인
  - Migration Version 중복 없음, whitespace 오류 없음
- 미실행 항목과 사유:
  - 실제 Browser 클릭과 EC2 배포 후 Mission→READY→START MQTT 왕복은 운영 배포와
    실제 Robot ONLINE 조건이 필요하므로 이번 Local 코드 작업에서는 실행하지 않았다.

## 영향 및 주의사항

- V35가 적용되어야 Backend DELETE가 Runtime DB Role로 동작하므로 Backend Image와
  Migration을 Frontend Image와 같은 배포 단위로 반영해야 한다.
- READY·RUNNING·PAUSED·종료 Mission은 삭제할 수 없고 409
  `MISSION_STATE_CONFLICT`가 반환된다. READY를 없애려면 기존 CANCELLED 전이를 쓴다.
- 회원가입 여부와 별개로 화면 변경 동작에는 `MISSION_MANAGE`가 필요하다. 기존
  `새 임무` 버튼으로 생성할 수 있는 계정이면 같은 권한으로 새 버튼이 보인다.
- Frontend Build의 500KB 초과 Chunk 경고와 Backend Test 종료 시 Testcontainer가
  닫힌 뒤 Scheduled Session 작업이 남기는 Hikari 경고는 있었지만 Test 실패는 없다.
- 작업 시작 전부터 존재하던 Root 문서 삭제·Untracked 파일은 변경하지 않았다.

## 미완료 및 후속 작업

1. Jenkins에서 Backend·Frontend Commit SHA Image를 함께 Build한다.
2. EC2 Day 8 `/opt/gilbom/deploy/image.env`를 갱신하고 Compose로 두 Service를
   재생성해 V35 적용을 확인한다.
3. `MISSION_MANAGE` 계정으로 CREATED 삭제와 READY 전환을 확인한다.
4. 실제 Robot을 ONLINE으로 연결한 뒤 Robot 제어 화면에서 START를 발행하고
   Jetson ACK·State·Telemetry·Dashboard 반영을 왕복 검증한다.
