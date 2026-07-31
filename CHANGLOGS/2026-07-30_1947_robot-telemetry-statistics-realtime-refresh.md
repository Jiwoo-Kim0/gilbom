# 변경 로그: 로봇 Telemetry 통계 SSE 기반 자동 갱신

- 작성 시각: 2026-07-30 19:47 (Asia/Seoul)
- 작업 유형: 기능
- 상태: 완료

## 작업 요약

로봇 상세 화면의 Telemetry 통계를 최초 진입 시 DB에서 조회한 뒤, 같은 로봇의
`ROBOT_TELEMETRY_UPDATED` SSE를 받으면 5초 단위로 합쳐 최근 24시간 DB 통계를
다시 조회하도록 연결했다. SSE Payload를 Browser 통계에 직접 누적하지 않고
Backend 통계 API와 `telemetry.robot_telemetry`를 계속 정본으로 사용한다.

사용자가 시작·종료 시각을 직접 입력하면 고정 기간 조회로 전환해 SSE 자동
재조회를 중단한다. `24시간 자동 갱신` 버튼으로 현재 시각까지의 최근 24시간
Rolling 조회를 즉시 재개할 수 있다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/frontend/gilbom/src/features/robot/RobotDetailPage.tsx` | 수정 | 현재 상세 Robot과 일치하는 `ROBOT_TELEMETRY_UPDATED` 또는 SSE 재연결 Snapshot 신호를 별도 Revision으로 만들고 Telemetry 통계 카드에 전달했다. |
| `web/frontend/gilbom/src/features/robot/RobotTelemetryStatisticsCard.tsx` | 수정 | 첫 SSE 신호 뒤 5초 타이머 하나만 유지하는 자동 재조회, 최근 24시간 Rolling 기간, 수동 기간 조회 시 자동 갱신 중지, 자동 모드 재개, Robot 변경·화면 이탈 시 타이머 정리를 구현했다. |
| `web/frontend/gilbom/src/features/robot/robotTelemetryStatistics.ts` | 수정 | 분 단위 입력 표시와 별도로 현재 초·밀리초까지 포함하는 정확한 최근 24시간 API 기간을 생성하는 `createRollingTelemetryStatisticsPeriod()`를 추가했다. |
| `web/frontend/gilbom/src/features/robot/robot-telemetry-statistics.css` | 수정 | 자동·고정 조회 상태 안내와 추가 자동 갱신 버튼을 위한 상태 색상 및 반응형 Filter 줄바꿈을 추가했다. |
| `web/frontend/gilbom/test/robotTelemetryStatistics.test.ts` | 수정 | SSE 처리 기준 시각까지의 Rolling 기간이 밀리초 단위로 정확히 24시간인지 검증하는 단위 테스트를 추가했다. |
| `web/docs/robot-telemetry-statistics-contract.md` | 수정 | SSE는 통계 Payload가 아닌 무효화 신호이고, 5초 요청 병합·고정 기간 모드·Route 이탈 정리·DB 저장 비의존 규칙을 Frontend 계약으로 기록했다. |
| `CHANGLOGS/2026-07-30_1947_robot-telemetry-statistics-realtime-refresh.md` | 추가 | 이번 자동 갱신 구현 범위, 검증 결과, 영향과 후속 작업을 기록했다. |

## 상세 내용

- 상세 화면 진입 시 기존 통계 API를 즉시 호출해 최근 24시간 DB Snapshot을
  표시한다.
- 현재 Robot의 `ROBOT_TELEMETRY_UPDATED`만 통계 Revision을 증가시키며 다른
  Robot의 이벤트는 무시한다.
- 첫 갱신 신호에서 5초 Timer를 예약하고 그동안 들어오는 후속 이벤트는 같은
  조회로 합친다. Timer를 이벤트마다 뒤로 미루지 않아 Telemetry가 연속 유입돼도
  통계 조회가 무기한 지연되지 않는다.
- 자동 조회마다 `to`를 실행 시각으로, `from`을 정확히 24시간 전으로 다시 계산해
  새 Telemetry가 고정된 초기 종료 시각 뒤에 누락되지 않도록 했다.
- 사용자가 기간 입력을 편집하거나 조회하면 예약 Timer를 취소하고 고정 기간
  모드로 전환한다.
- 상세 Route가 unmount되거나 Robot ID가 바뀌면 예약 Timer를 정리한다. 기존
  `useRobotRealtime`의 EventSource cleanup도 유지된다.
- MQTT 수신, Telemetry 이력 INSERT, 최신 상태 UPSERT, Backend 통계 SQL과 DB
  Schema는 변경하지 않았다.

## 검증

- 실행한 명령:
  - `npm test`
  - `npx eslint src/features/robot/RobotDetailPage.tsx src/features/robot/RobotTelemetryStatisticsCard.tsx src/features/robot/robotTelemetryStatistics.ts test/robotTelemetryStatistics.test.ts`
  - `npm run lint -- --no-cache`
  - `npm run build`
  - `git diff --check -- <이번 작업 대상 파일>`
- 결과:
  - Frontend Node 단위 테스트 30건 통과, 실패·건너뜀 0건
  - 대상 ESLint와 Frontend 전체 ESLint 통과
  - TypeScript·Vite Production Build 통과, 1,918 Module 변환
  - Rolling 최근 24시간의 초·밀리초 경계 테스트 통과
  - 대상 파일 whitespace 오류 없음
  - Build의 기존 500kB 초과 JavaScript Chunk 경고만 있으며 실패는 없음
- 미실행 항목과 사유:
  - 실제 Mock Robot/Jetson MQTT → Backend DB 저장 → SSE → Browser 자동 재조회
    종단 간 검증은 실행 중인 인증 Session과 실제 Telemetry 발행 환경이 필요해
    이번 로컬 정적 검증에서는 실행하지 않았다.
  - Backend와 DB 코드는 변경하지 않아 Backend Test Suite와 Flyway Migration은
    실행하지 않았다.

## 영향 및 주의사항

- 통계 API 호출은 SSE Event마다 발생하지 않고 연속 이벤트를 최대 5초에 한 번
  합친다.
- 현재 상태 카드는 기존처럼 SSE 신호 뒤 Robot REST Snapshot을 갱신하고, 통계는
  별도의 5초 주기로 DB 집계 결과를 읽는다.
- 수동으로 지정한 과거 기간은 새 Telemetry에 의해 변하지 않으므로 자동 갱신하지
  않는다.
- 이 작업은 아직 Git에 반영되지 않은 로봇 Telemetry 통계 기능 위에서 수행했다.
  해당 기능과 함께 Stage·Commit해야 누락 없이 동작한다.
- Frontend Docker 환경 반영에는 Frontend Image 재빌드가 필요하다.

## 미완료 및 후속 작업

1. Mock Robot 또는 실제 Jetson의 연속 Telemetry로 Browser에서 통계 표본 수와
   마지막 측정 시각이 약 5초 단위로 증가하는지 확인한다.
2. SSE 연결을 끊었다가 복구한 뒤 DB에 누적된 Telemetry가 재연결 Snapshot
   보정으로 반영되는지 확인한다.
3. Browser 개발자 도구에서 연속 Telemetry 중 통계 API 호출 빈도가 5초당 최대
   1회인지 확인한다.
