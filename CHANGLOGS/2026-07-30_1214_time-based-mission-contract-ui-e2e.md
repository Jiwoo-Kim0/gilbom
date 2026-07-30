# 변경 로그: 시간 기반 Mission 계약·화면·Browser E2E

- 작성 시각: 2026-07-30 12:14 (Asia/Seoul)
- 작업 유형: 기능
- 상태: 부분 완료

## 작업 요약

Mission 진행률을 계획 Route의 GPS 투영 방식에서 기존 MQTT Telemetry
`mission.elapsed_sec`와 신규 계획 수행시간의 비율로 변경했다. 기존 DB·API FK
호환을 위해 Area별 `AUTONOMOUS_DEFAULT`를 내부 적용하되 사용자 화면과 진행률
계산에서는 Route를 제거했다.

계획·경과·남은 시간, ETA, 기준 시각과 Stale을 Mission 화면에 표시하고 전용
반응형 CSS를 추가했다. 실제 Chromium Browser에서 Route 비노출, 내부 Dummy ID,
계획시간 Payload와 생성 결과를 검증했다. Browser REST는 결정적 Fixture이므로
실제 로그인 세션과 실행 Backend를 하나로 잇는 운영형 Browser E2E가 남아
작업 상태는 부분 완료, 완료 기준 진행률은 90%로 기록한다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/backend/src/main/resources/db/migration/V29__add_time_based_mission_contract.sql` | 추가 | `planned_duration_sec` 양수 제약과 기존 Mission 3,600초 Backfill, 기존 활성 Area별 `AUTONOMOUS_DEFAULT` 내부 Route 생성을 추가했다. |
| `web/backend/src/main/java/com/gilbom/robot/data/api/response/MissionDtos.java` | 수정 | 생성·수정 요청의 계획 수행시간 검증과 응답의 계획·경과·잔여·기준시각·Stale 필드를 추가했다. |
| `web/backend/src/main/java/com/gilbom/robot/mission/MissionManagementService.java` | 수정 | 계획시간 저장·수정·감사 Snapshot과 선택 Area 소속 활성 `AUTONOMOUS_DEFAULT` 검증을 연결했다. |
| `web/backend/src/main/java/com/gilbom/robot/mission/MissionProgressService.java` | 수정 | PostGIS Route 투영·30m 조건을 제거하고 최신 `elapsed_sec / planned_duration_sec`, 단조 증가, 완료 전 99.99% 상한으로 변경했다. |
| `web/backend/src/main/java/com/gilbom/robot/mission/MissionQueryService.java` | 수정 | 가장 큰 유효 누적시간, 잔여시간, 기준시각, 15초 Stale과 최신 표본 기준 ETA를 Mission 조회에 추가했다. |
| `web/backend/src/main/java/com/gilbom/robot/mission/PatrolAreaManagementService.java` | 수정 | 신규 Area의 내부 Dummy 자동 생성과 Area 경계·수명주기 변경 시 Dummy Geometry·상태 동기화를 추가했다. |
| `web/backend/src/main/java/com/gilbom/robot/telemetry/TelemetryIngestionService.java` | 수정 | GPS 유효 여부와 분리해 Mission Telemetry의 `elapsed_sec`를 진행률 계산에 전달했다. |
| `web/backend/src/test/java/com/gilbom/robot/controller/MissionControllerTest.java` | 수정 | 계획시간 응답과 0초 생성 요청 거부를 검증했다. |
| `web/backend/src/test/java/com/gilbom/robot/integration/MissionManagementIntegrationTest.java` | 수정 | V29, Dummy 생성·공간 포함, 계획시간 생성·수정, 경과·잔여·ETA·Stale 조회와 내부 Route 정책을 실제 PostGIS에서 검증했다. |
| `web/backend/src/test/java/com/gilbom/robot/integration/RobotEventMissionDefectIntegrationTest.java` | 수정 | 8초 계획 Mission의 2초=25%, PAUSED 3초=37.5%, RESUME 뒤 완료 Event 100%를 검증했다. |
| `web/docs/openapi-v1.yaml` | 수정 | 생성·수정·응답의 계획시간과 경과·잔여·기준시각·Stale·ETA, 내부 Route 비노출 의미를 계약에 반영했다. |
| `web/docs/final-mqtt-topic-schema-v2.md` | 수정 | Payload 구조는 유지하고 `mission.elapsed_sec`를 PAUSED 포함 벽시계 누적시간과 서버 진행률 분자로 명확히 했다. |
| `web/docs/robot-patrol-mission-schema-workplan.md` | 수정 | 유효 GPS는 궤적에만 사용하고 진행률은 누적시간 비율로 계산하도록 기존 Route 진행률 설명을 정정했다. |
| `web/docs/time-based-mission-contract.md` | 추가 | 사용자 입력, 내부 Dummy, 시간 산식, 조회 필드, 검증 경계와 운영형 E2E 잔여 범위를 정본 문서로 작성했다. |
| `web/frontend/gilbom/src/features/mission/missionTypes.ts` | 수정 | 시간 기반 Mission 응답·생성 Type을 추가하고 사용자 Route 필터 Type을 제거했다. |
| `web/frontend/gilbom/src/features/mission/missionApi.ts` | 수정 | 사용자 Route 목록 필터 Query를 제거하고 내부 Dummy 조회만 유지했다. |
| `web/frontend/gilbom/src/features/mission/MissionManagePage.tsx` | 수정 | 계획시간 입력, 내부 Dummy 자동 선택, 계획·경과·잔여·ETA·Stale 표시와 Route UI 비노출을 구현했다. |
| `web/frontend/gilbom/src/features/mission/mission.css` | 추가 | Mission 생성·필터·Master/Detail·시간 Grid·상태·이력·Stale과 반응형 전용 CSS를 구현했다. |
| `web/frontend/gilbom/package.json` | 수정 | Playwright dev dependency와 `test:e2e:mission` Script를 추가했다. |
| `web/frontend/gilbom/package-lock.json` | 수정 | Playwright 1.62.0 설치 결과를 잠갔다. |
| `web/frontend/gilbom/playwright.config.ts` | 추가 | Chromium Project와 Vite E2E Web Server 설정을 추가했다. |
| `web/frontend/gilbom/e2e/mission-time.spec.ts` | 추가 | 실제 Chromium에서 시간 표시, Route 비노출, 내부 Dummy·계획시간·CSRF 생성 요청을 검증했다. |
| `DailyLog/2026-07-30_JIRA_Day6_작업.md` | 수정 | `DAY6-B-00-6`을 구현 100%·완료 기준 90% `IN PROGRESS`로 갱신하고 통과·잔여 증거를 기록했다. |
| `DailyLog/2026-07-30_Day6_상세_구현.md` | 수정 | Day 5 진행률을 75%로 갱신하고 시간 Mission 완료 항목과 운영형 Browser E2E 잔여 범위를 기록했다. |
| `TROUBLESHOOTING/10_TimeBasedMission.md` | 추가 | 계약 판단부터 전체 변경, 각 실패의 현상·원인·해결, 검증 수치와 재현 순서를 기록했다. |
| `CHANGLOGS/2026-07-30_1214_time-based-mission-contract-ui-e2e.md` | 추가 | 이번 작업의 변경 파일, 검증, 영향과 후속 범위를 기록했다. |

## 상세 내용

### DB·Backend

- 기존 Mission은 V29 적용 시 3,600초 계획시간을 받는다.
- 신규 Area와 기존 활성 Area 모두 Area별 내부 Dummy를 확보한다.
- Mission 생성은 내부 Dummy만 허용하지만 API FK 필드는 호환을 위해 유지한다.
- 진행률은 `RUNNING`, `PAUSED`에서 누적시간 비율로만 갱신된다.
- 오래된 표본과 누적시간 역행은 진행률을 되돌리지 않는다.
- 조회는 가장 큰 누적시간을 사용해 경과·잔여·ETA 표시도 역행하지 않게 한다.
- `MISSION_COMPLETED` 전에는 최대 99.99%, Event 처리 뒤에만 100%다.

### Frontend·Browser

- 사용자는 Robot·Area·계획시간만 입력한다.
- Area 선택 시 활성 `AUTONOMOUS_DEFAULT`가 없으면 생성 버튼을 비활성화하고
  설정 오류를 표시한다.
- 목록·상세·필터에서 Route를 제거하고 시간 진행 상태를 표시한다.
- Browser E2E는 실제 Chromium을 실행하지만 API는 결정적 Fixture로 격리해 UI와
  요청 Payload를 검증한다.

### 문제 원인과 해결

- 일반 Route 경계 회귀 Fixture가 내부 Dummy로 바뀌어 예외가 사라진 문제는
  Fixture를 일반 Route로 분리해 해결했다.
- DB가 `PAUSED -> COMPLETED`를 거부한 문제는 정본 전이대로 RESUME을 추가했다.
- Playwright 일반 Chromium 설치 후 headless shell이 빠진 문제는 전용 Runtime을
  별도 설치했다.
- 중복 Heading과 Form 접근성 이름 Locator 문제는 Heading Level과 고유 DOM ID로
  안정화했다.
- Mission timestamp CHECK와 JDBC `Instant` 추론 문제는 정합 시각과 UTC
  `OffsetDateTime`을 사용해 해결했다.
- 전체 회귀 종료 시 Spring Session Cleanup의 Connection refused는 닫힌
  Testcontainer를 비동기 Thread가 조회한 종료 로그이며 Test 실패가 아님을
  최종 Summary와 XML로 구분했다.

## 검증

- 실행한 명령:
  - `.\gradlew.bat test --tests "com.gilbom.robot.controller.MissionControllerTest" --tests "com.gilbom.robot.integration.MissionManagementIntegrationTest" --tests "com.gilbom.robot.integration.RobotEventMissionDefectIntegrationTest"`
  - `.\gradlew.bat test`
  - `npm run lint`
  - `npm run build`
  - `npm run test:e2e:mission`
  - Python PyYAML OpenAPI Parse와 시간 필드 Assertion
  - `git diff --check`
- 결과:
  - Mission 집중 Backend 29개, 실패 0, Skip 0
  - Backend 전체 544개, 실패 0, Skip 0
  - Frontend ESLint 통과
  - TypeScript·Vite production build 통과, 1,894 Module 변환
  - 실제 Chromium Mission E2E 1개 통과
  - OpenAPI YAML Parse와 시간 계약 Assertion 통과
  - `git diff --check` 통과
- 미실행 항목과 사유:
  - 실제 로그인 세션·실행 Backend·영속 DB를 같은 Browser 흐름으로 통과하는
    운영형 E2E는 테스트 계정·MFA Fixture 수명주기와 정리 절차를 함께 구성해야
    하므로 이번 결정적 Browser 회귀와 분리했다.
  - Mission 지도와 MQTT→Command→완료 전체 E2E는 각각
    `DAY6-B-00-7`, `DAY6-B-00-8`의 별도 범위다.
  - 실제 Jetson은 Day 7 통합 범위이므로 실행하지 않았다.

## 영향 및 주의사항

- `MissionCreateRequest.plannedDurationSec`가 필수가 되어 기존 API Client는 양수
  계획시간을 보내야 한다.
- `patrolRouteId`는 당분간 API에 남지만 반드시 선택 Area의 활성
  `AUTONOMOUS_DEFAULT`여야 한다.
- MQTT JSON Shape는 바뀌지 않았다. Embedded는 기존 `mission.elapsed_sec`를
  PAUSED를 포함한 벽시계 누적시간으로 단조 증가시켜야 한다.
- 기존 Mission의 3,600초 Backfill은 업무별 원래 계획시간을 복원한 값이 아니라
  호환 기본값이다. 운영 데이터에 실제 계획시간이 있으면 V29 적용 뒤 보정해야 한다.
- Playwright 신규 환경은 `npx playwright install chromium-headless-shell`로
  Browser Runtime을 준비해야 한다.
- 작업 트리에 동시에 존재한 Mission 지도와 기타 사용자 변경은 되돌리거나
  이번 완료 범위로 주장하지 않았다. 전체 회귀는 현재 합쳐진 작업 트리 상태에서
  통과했다.

## 미완료 및 후속 작업

- 실제 로그인 Backend Browser 단일 체인을 추가하고 임시 계정·MFA·Robot
  Assignment가 0건으로 정리되는지 확인한다.
- 합의된 계획시간 상한이 필요하면 API·UI·OpenAPI에 같은 값으로 별도 확정한다.
- `DAY6-B-00-7` Mission 지도와 영역 이탈 경고를 완료한다.
- `DAY6-B-00-8` MQTT→DB→REST/SSE→React 전체 Mock E2E를 완료한다.
