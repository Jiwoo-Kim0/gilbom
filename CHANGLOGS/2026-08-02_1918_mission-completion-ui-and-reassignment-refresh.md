# 변경 로그: 임무 완료 안내와 재배정 후보 갱신

- 작성 시각: 2026-08-02 19:18 (Asia/Seoul)
- 작업 유형: 수정
- 상태: 완료

## 작업 요약

임무 완료 확인창이 같은 안전 문구를 두 번 표시하고, `PAUSED` Mission에 남아 있던 오래된 화면에서 완료를 요청하면 `MISSION_NOT_FOUND`로 안내하던 문제를 수정했다. 완료 확인 문구는 아직 정지하지 않은 Robot을 이미 정지한 것처럼 표현하지 않도록 명령 이후의 안전 정지·완료 Event 확정 흐름으로 바로잡았다. Mission이 종료된 뒤 새 임무 생성 폼의 Robot 후보가 최초 조회 결과에 남아 재배정 가능한 Robot이 보이지 않던 캐시 문제도 함께 수정했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/backend/src/main/java/com/gilbom/robot/command/RobotCommandCreationService.java` | 수정 | Mission 명령 대상 상태가 전제조건과 다르면 현재 활성 Mission 상태를 확인하고 `MISSION_STATE_CONFLICT`와 명령별 안전 메시지를 반환하도록 변경했다. `PAUSED`에서 완료 요청 시 404 대신 먼저 재개하라는 409 응답을 제공한다. |
| `web/backend/src/test/java/com/gilbom/robot/integration/RobotCommandLifecycleIntegrationTest.java` | 수정 | `PAUSED` Mission 완료 요청이 `MISSION_STATE_CONFLICT`이며 메시지에 현재 상태와 재개 안내를 포함하는지 검증했다. |
| `web/frontend/gilbom/src/features/command/CommandConfirmModal.tsx` | 수정 | 안전 확인 체크박스가 있는 명령에서는 같은 `safetyMessage`를 별도 안내 상자에 중복 렌더링하지 않도록 변경했다. |
| `web/frontend/gilbom/src/features/command/commandPolicy.ts` | 수정 | 완료 안내를 `명령 전송 → Robot 안전 정지 → MISSION_COMPLETED Event → COMPLETED 확정` 순서로 표현하고 안전 확인 문구를 미래 절차에 맞게 수정했다. |
| `web/frontend/gilbom/src/features/mission/MissionManagePage.tsx` | 수정 | 새 임무 생성 영역을 열 때, 수동 새로고침 때, 생성 영역이 열린 상태에서 Robot 상태·Command 상태 SSE를 받을 때 Robot·Area 후보 카탈로그를 다시 조회하도록 변경했다. |
| `web/frontend/gilbom/test/commandPolicy.test.ts` | 수정 | 완료 확인 문구가 안전 정지를 이미 끝낸 것처럼 표현하지 않고 완료 Event 확정 흐름을 안내하는지 검증했다. |
| `web/frontend/gilbom/e2e/mission-time.spec.ts` | 수정 | 완료 확인 안전 문구가 Modal에 한 번만 표시되고 새 임무 생성 영역을 열 때 Robot 후보 API를 다시 조회하는지 Chromium에서 검증했다. |
| `web/docs/openapi-v1.yaml` | 수정 | Mission 명령 상태 전제조건 불일치는 `MISSION_STATE_CONFLICT`이며 `COMPLETE_MISSION`은 `RUNNING`에서만 허용된다는 계약을 명시했다. |
| `CHANGLOGS/2026-08-02_1918_mission-completion-ui-and-reassignment-refresh.md` | 추가 | 오류 원인, 변경 내용, 자동 검증과 로컬 Runtime 확인 결과를 기록했다. |

## 상세 내용

- 완료 확인창의 동일 문구 2회 표시는 요청 중복이 아니라 안내 상자와 확인 체크박스가 같은 값을 각각 렌더링한 UI 문제였다.
- 문제 확인 당시 DB에는 `COMPLETE_MISSION` Command Result가 `COMPLETED`였지만 Mission 자체는 `PAUSED`였다. 이는 명령 처리 완료와 Mission 결과 확정이 서로 다른 상태임을 보여준다.
- Mission은 Robot의 `MISSION_COMPLETED` Event 수신 후에만 `COMPLETED`로 확정한다.
- 오래된 화면에서 `PAUSED` Mission에 완료를 요청하는 Race가 발생하면 Backend는 `MISSION_STATE_CONFLICT`와 `PAUSED 상태라면 먼저 재개한 뒤 완료` 메시지를 반환한다.
- 종료 Mission이 `COMPLETED`, `CANCELLED`, `FAILED`가 되면 활성 Mission이 아니므로 `ACTIVE` Robot은 새 Mission 후보가 된다.
- 서버의 `missionAssignable` 계산은 이미 정상이고, 원인은 Frontend가 Robot 후보 목록을 최초 한 번만 조회한 것이었다. 생성 폼 진입 및 관련 실시간 상태 변경 시 후보를 갱신하도록 고쳤다.
- `일시정지`는 정상 운영용 `PAUSED → RESUME` 흐름이고, `긴급정지`는 `SAFE_STOP → CLEAR → RESUME` 안전 잠금 흐름이므로 두 제어는 유지한다.
- DB 스키마와 Flyway Migration은 변경하지 않았다.

## 검증

- 실행한 명령:
  - `web/backend`에서 `./gradlew.bat test --no-daemon --rerun-tasks --tests com.gilbom.robot.integration.RobotCommandLifecycleIntegrationTest`
  - `web/frontend/gilbom`에서 `npm test -- --run`
  - `web/frontend/gilbom`에서 `npm run lint`
  - `web/frontend/gilbom`에서 `npm run build`
  - `web/frontend/gilbom`에서 `npx playwright test e2e/mission-time.spec.ts --project=chromium`
  - `web/frontend/gilbom`에서 `npm run test:e2e:mission`
  - 대상 파일 `git diff --check`
  - `docker compose -f web/compose.yaml build backend frontend`
  - `docker compose -f web/compose.yaml up -d --no-build --wait --force-recreate backend frontend`
  - `docker compose -f web/compose.yaml ps`
  - Frontend와 Backend health HTTP 조회
  - PostgreSQL에서 Mission, Robot Current State, 재배정 조건 조회
- 결과:
  - Backend Command 통합 테스트 12건 통과, 실패·Skip 0건.
  - Docker 이미지 빌드 안의 Backend 전체 테스트 784건 중 실패 0건, 환경 의존 테스트 165건은 기존 빌드 옵션에 따라 skipped.
  - Frontend 단위 테스트 44건 통과, 실패·Skip 0건.
  - ESLint와 TypeScript/Vite production build 통과. 기존 bundle size 경고만 존재한다.
  - 완료 문구·후보 재조회 Chromium E2E 1건 통과, 전체 Mission E2E 3건 통과.
  - 병렬 검증 첫 시도에서 ESLint가 Playwright의 `test-results` 정리와 경합해 `ENOENT`로 한 번 실패했으나, 단독 재실행은 성공했다. 소스 린트 오류는 없었다.
  - Backend·Frontend·PostgreSQL·Mosquitto 컨테이너 모두 healthy, Frontend HTTP 200, Backend health HTTP 200.
  - Mock Robot PID 9848 실행 중.
  - 사용자 생성 후 최신 Mission은 `RUNNING`, Robot은 `PATROLLING/RUNNING`으로 일치하며 이전 Mission은 `COMPLETED`로 보존됨을 확인했다.
  - `git diff --check` 오류 없음. Windows 작업 트리의 줄바꿈 변환 안내만 출력됐다.
- 미실행 항목과 사유:
  - 실제 하드웨어 Robot의 물리 정지 검증은 장비 조작 범위가 아니므로 실행하지 않았다. Mock Robot과 MQTT/DB 상태까지 확인했다.

## 영향 및 주의사항

- Command 상태 `COMPLETED`는 해당 명령 처리가 끝났다는 뜻이고 Mission 상태 `COMPLETED`와 동일하지 않다. Mission 종료 결과는 Mission Event를 정본으로 삼는다.
- `PAUSED` Mission은 먼저 `RESUME_MISSION`으로 `RUNNING`에 진입한 뒤 `COMPLETE_MISSION`을 실행해야 한다.
- `SAFE_STOP`은 `CLEAR_EMERGENCY_STOP` 후에도 `PAUSED`를 유지하므로 별도 `RESUME_MISSION`이 필요하다.
- 후보 카탈로그 재조회는 Telemetry 5초 주기마다 실행하지 않고 생성 폼 진입, 수동 새로고침, Robot State·Command 상태 변경에만 실행한다.
- 새 DB Migration은 없다. 이번 로컬 반영은 Backend·Frontend 코드 이미지 재배포다.

## 미완료 및 후속 작업

- 실제 Robot 통합 시 `COMPLETE_MISSION` Command Result와 `MISSION_COMPLETED` Event가 모두 도착하고 State가 `READY/mission=null`로 바뀌는 순서를 물리 정지와 함께 확인한다.
