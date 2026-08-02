# 변경 로그: 임무 목록 범위 드롭다운

- 작성 시각: 2026-08-02 18:56 (Asia/Seoul)
- 작업 유형: 기능
- 상태: 완료

## 작업 요약

임무 실행 이력은 보존하면서 관리 화면에서 진행 중·대기 임무와 종료된 임무를 나눠 볼 수 있도록 목록 범위 드롭다운을 추가했다. 기본 범위는 `ACTIVE`이며, `TERMINAL`과 `ALL`을 선택할 수 있다. 상세 상태 드롭다운은 선택한 범위에 포함되는 상태만 표시한다. 완료·취소·실패 Mission의 삭제 버튼은 화면에서 제거하고 실행 이력이 없는 `CREATED` Mission만 삭제할 수 있도록 정리했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/backend/src/main/java/com/gilbom/robot/data/api/response/MissionDtos.java` | 수정 | 목록 조회 범위를 표현하는 `MissionListScope`(`ACTIVE`, `TERMINAL`, `ALL`)를 추가했다. |
| `web/backend/src/main/java/com/gilbom/robot/api/MissionController.java` | 수정 | Mission 목록 API가 `scope` 쿼리 파라미터를 받고 조회 서비스로 전달하도록 변경했다. 기존 호출 호환성을 위해 API 기본값은 `ALL`로 유지했다. |
| `web/backend/src/main/java/com/gilbom/robot/mission/MissionQueryService.java` | 수정 | 정확한 `status`가 없을 때 `scope`별 상태 집합으로 DB에서 필터링해 페이지네이션 결과가 화면 범위와 일치하도록 했다. 정확한 상태가 있으면 해당 조건을 우선한다. 삭제된 Mission은 목록·상세·지도 조회에서 제외한다. |
| `web/backend/src/test/java/com/gilbom/robot/controller/MissionControllerTest.java` | 수정 | 기본 `ALL` 범위와 명시적 `TERMINAL` 범위가 조회 서비스로 전달되는지 검증했다. |
| `web/backend/src/test/java/com/gilbom/robot/integration/MissionManagementIntegrationTest.java` | 수정 | 완료 Mission이 `TERMINAL`에는 포함되고 `ACTIVE`에는 제외되는지, 삭제 후 조회에서 제외되면서 실행 이력은 보존되는지 검증했다. |
| `web/docs/openapi-v1.yaml` | 수정 | Mission 목록의 `scope` 파라미터 값, 기본값, 상태 집합 및 `status` 우선 규칙을 API 계약에 기록했다. |
| `web/frontend/gilbom/src/features/mission/missionTypes.ts` | 수정 | 프론트 Mission 필터 타입에 `scope`를 추가했다. |
| `web/frontend/gilbom/src/features/mission/missionApi.ts` | 수정 | Mission 목록 요청에 선택한 `scope`를 쿼리 파라미터로 전송하도록 변경했다. |
| `web/frontend/gilbom/src/features/mission/missionEligibility.ts` | 추가 | Robot 재배정 가능 여부와 `CREATED` Mission 삭제 가능 여부를 공통 판정 함수로 분리했다. |
| `web/frontend/gilbom/src/features/mission/MissionManagePage.tsx` | 수정 | `진행 중·대기 임무`, `종료된 임무`, `전체 임무` 범위 드롭다운과 범위 연동 상세 상태 드롭다운을 추가하고, 종료 Mission 삭제 동작을 UI에서 제거했다. |
| `web/frontend/gilbom/src/features/command/CommandControlPage.tsx` | 수정 | 제어 화면의 현재 Mission 탐색은 기존 의미를 유지하도록 `ALL` 범위를 명시했다. |
| `web/frontend/gilbom/test/missionEligibility.test.ts` | 추가 | `CREATED`만 삭제 가능하고 종료 상태는 이력으로 보존되는 판정 규칙을 검증했다. |
| `web/frontend/gilbom/test/missionApi.test.ts` | 수정 | Mission 목록 범위와 상세 상태가 API 쿼리에 함께 전달되는지 검증했다. |
| `web/frontend/gilbom/e2e/mission-time.spec.ts` | 수정 | 관리 화면 기본 범위가 `ACTIVE`이고 `TERMINAL` 및 재선택한 `ACTIVE` 요청이 실제 전송되는지 브라우저에서 검증했다. |
| `CHANGLOGS/2026-08-02_1856_mission-list-scope-dropdown.md` | 추가 | 이번 기능 변경과 검증 결과를 기록했다. |

## 상세 내용

- `ACTIVE`: `CREATED`, `READY`, `RUNNING`, `PAUSED`
- `TERMINAL`: `COMPLETED`, `CANCELLED`, `FAILED`
- `ALL`: 삭제되지 않은 모든 Mission
- 관리 화면은 `ACTIVE`를 기본 선택해 현재 관리할 임무에 집중하고, 과거 실행 결과는 `TERMINAL`에서 조회한다.
- 목록 범위가 바뀌면 범위 밖의 상세 상태가 남지 않도록 상세 상태 선택을 초기화한다.
- 정확한 상태 필터가 전달되면 서버는 범위 조건 대신 정확한 상태를 사용한다.
- 종료 Mission은 운영 이력으로 보존하며 화면에서 삭제 기능을 제공하지 않는다. 실행 전 `CREATED` Mission만 삭제할 수 있다.
- DB 스키마와 Flyway Migration은 변경하지 않았다.

## 검증

- 실행한 명령:
  - `web/backend`에서 `./gradlew.bat test --no-daemon --rerun-tasks --tests com.gilbom.robot.controller.MissionControllerTest --tests com.gilbom.robot.integration.MissionManagementIntegrationTest`
  - `web/frontend/gilbom`에서 `npm test -- --run`
  - `web/frontend/gilbom`에서 `npm run lint`
  - `web/frontend/gilbom`에서 `npm run build`
  - `web/frontend/gilbom`에서 `npm run test:e2e:mission`
  - `docker compose -f web/compose.yaml build backend frontend`
  - `docker compose -f web/compose.yaml up -d --no-build --wait --force-recreate backend frontend`
  - `docker compose -f web/compose.yaml ps`
  - `Invoke-WebRequest http://localhost:3000`
  - `Invoke-WebRequest http://localhost:8080/actuator/health`
  - 대상 파일 `git diff --check`
- 결과:
  - 백엔드 집중 테스트 26건 통과, 실패 0건.
  - Docker 이미지 빌드 안의 백엔드 전체 테스트 784건 중 실패 0건, 환경 의존 테스트 165건은 기존 빌드 옵션에 따라 skipped.
  - 프론트 단위 테스트 43건 통과, 실패 0건.
  - ESLint 및 TypeScript/Vite 프로덕션 빌드 통과. 번들 크기 경고만 존재한다.
  - Mission Playwright E2E 3건 통과.
  - 백엔드와 프론트 컨테이너 모두 healthy, 프론트 HTTP 200, 백엔드 health HTTP 200/UP.
  - Mock Robot 프로세스(PID 9848)가 재배포 후에도 실행 중임을 확인했다.
  - `git diff --check` 오류 없음. 작업 트리의 줄바꿈 변환 안내만 출력됐다.
- 미실행 항목과 사유:
  - 실제 하드웨어 Robot 연동 테스트는 장비 조작 범위가 아니어서 실행하지 않았다. Mock Robot 프로세스 생존과 로컬 통신 인프라 상태까지만 확인했다.

## 영향 및 주의사항

- API 기본 범위는 기존 클라이언트 호환을 위해 `ALL`이고, 관리자 Mission 화면은 요청에 `ACTIVE`를 명시한다.
- 기존에 이미 soft-delete된 Mission은 어느 범위에서도 보이지 않는다.
- 종료 Mission 삭제 API는 기존 호출 호환성을 위해 유지하지만 관리자 UI에는 노출하지 않는다.
- 새 DB Migration이 없으므로 이번 로컬 반영은 코드 이미지 재배포이며 DB 마이그레이션 배포가 아니다.

## 미완료 및 후속 작업

- 실제 Robot과의 통합 환경에서 종료 Mission 누적량 및 운영자가 선호하는 기본 정렬을 확인할 수 있다.
- 사용자에게 노출된 Robot Credential은 실기 검증이 끝난 뒤 회전하는 것이 안전하다.
