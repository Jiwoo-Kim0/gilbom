# 변경 로그: 로봇 Telemetry Recharts 시계열 그래프

- 작성 시각: 2026-07-30 20:10 (Asia/Seoul)
- 작업 유형: 기능
- 상태: 완료

## 작업 요약

`telemetry.robot_telemetry`만 사용하는 로봇별 시계열 집계 API를 추가하고 로봇
상세 화면에 Recharts 기반 배터리·Jetson 온도·속도 그래프를 연결했다.
시계열 원본과 사용자 Robot 범위는 Backend가 통제하며 Browser가 원본 표본을
직접 집계하지 않는다.

기본 최근 24시간은 5분, 1일 초과 7일 이하는 15분, 7일 초과는 1시간 구간을
사용한다. 기존 `ROBOT_TELEMETRY_UPDATED` SSE 5초 병합 신호로 요약 통계와
시계열을 같은 기간에 다시 조회한다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/backend/src/main/java/com/gilbom/robot/api/RobotTelemetrySeriesController.java` | 추가 | `ROBOT_STATUS_VIEW` 권한으로 `GET /api/v1/robots/{robotId}/telemetry-series`를 제공하고 `from`, `to`, 기본 `interval=5m`을 Service에 전달한다. |
| `web/backend/src/main/java/com/gilbom/robot/telemetry/RobotTelemetrySeriesService.java` | 추가 | `VISIBLE_ROBOT_PREDICATE`로 객체 범위를 검사하고 PostgreSQL `date_bin`으로 `[from,to)` Telemetry를 구간 집계한다. `1m·5m·15m·1h`, 최대 31일·5,000구간 검증과 빈 배열 의미를 구현했다. |
| `web/backend/src/main/java/com/gilbom/robot/data/api/response/RobotTelemetrySeriesResponse.java` | 추가 | Robot identity, UTC 기간, 정규화된 interval과 시간순 point 목록 응답을 정의했다. |
| `web/backend/src/main/java/com/gilbom/robot/data/api/response/RobotTelemetrySeriesPointResponse.java` | 추가 | 구간 시각·표본 수, 배터리 평균·최저, Jetson 평균·최고, 속도 평균·최고, GPS 유효·무효·유효율을 정의했다. |
| `web/backend/src/test/java/com/gilbom/robot/controller/RobotTelemetrySeriesControllerTest.java` | 추가 | 기본 5분 구간과 Offset 시각 정규화, 직렬화, 권한 부족 403, 필수 기간 누락과 지원하지 않는 interval 400을 검증했다. |
| `web/backend/src/test/java/com/gilbom/robot/integration/RobotTelemetryStatisticsServiceIntegrationTest.java` | 수정 | 실제 PostGIS·Flyway Schema에서 `date_bin` 구간 순서·집계값, 빈 시계열, Robot 객체 범위, interval 정규화, 5,000구간 상한을 검증하고 Series Service를 Test Context에 추가했다. |
| `web/frontend/gilbom/package.json` | 수정 | 외부 Runtime API 호출 없이 번들에서 차트를 그리는 `recharts` 3.10.1을 정확한 버전으로 추가했다. 기존에 Stage된 Script 변경은 보존했다. |
| `web/frontend/gilbom/package-lock.json` | 수정 | Recharts 3.10.1과 전이 의존성의 버전·무결성 정보를 npm 11 Lockfile에 반영했다. |
| `web/frontend/gilbom/src/features/robot/robotTypes.ts` | 수정 | 시계열 interval, point와 전체 응답 Type을 추가하고 기존 Robot 등록·통계 Type을 보존했다. |
| `web/frontend/gilbom/src/features/robot/robotApi.ts` | 수정 | 기간과 interval을 URLSearchParams로 전송하는 `getRobotTelemetrySeries()` Client를 추가하고 기존 Robot 등록·통계 Client를 보존했다. |
| `web/frontend/gilbom/src/features/robot/robotTelemetryStatistics.ts` | 수정 | 조회 기간에 따라 5분·15분·1시간 구간을 선택하는 `selectTelemetrySeriesInterval()`을 추가했다. |
| `web/frontend/gilbom/src/features/robot/RobotTelemetryStatisticsCard.tsx` | 수정 | 요약 통계와 시계열 API를 같은 기간으로 병렬 조회하고 기존 SSE 5초 자동 갱신에 함께 연결했다. Recharts Chunk는 상세 통계가 표시될 때만 동적 import한다. |
| `web/frontend/gilbom/src/features/robot/RobotTelemetryCharts.tsx` | 추가 | 배터리 평균·최저, Jetson 평균·최고, 속도 평균·최고를 세 개의 동기화된 Recharts 선 그래프로 표시한다. 시간축을 실제 Timestamp 비율로 표시하고 누락 구간에는 null 절단점을 넣어 통신 공백을 연속선으로 오인하지 않게 했다. |
| `web/frontend/gilbom/src/features/robot/robot-telemetry-statistics.css` | 수정 | Desktop 2열·Tablet/Mobile 1열 그래프, 축·범례·Grid 색상과 카드 스타일을 추가했다. |
| `web/frontend/gilbom/test/robotTelemetryStatistics.test.ts` | 수정 | 24시간·7일·장기 조회의 시계열 interval 선택을 검증했다. |
| `web/docs/robot-telemetry-statistics-contract.md` | 수정 | 시계열 endpoint, interval·5,000구간 제한, UTC 구간 원점, 빈 구간, 반환 지표와 Recharts·SSE 동작을 정본화했다. |
| `web/docs/openapi-v1.yaml` | 수정 | Telemetry Series Operation, Query Parameter와 `additionalProperties: false` 응답·Point Schema를 추가했다. |
| `web/docs/authorization-matrix.md` | 수정 | Telemetry Series의 `ROBOT_STATUS_VIEW`, `VIEWER+`, 최대 기간·구간 객체 범위를 추가했다. |
| `web/docs/common-api-response-and-errors.md` | 수정 | 시계열 기간·5,000구간 초과 422와 지원하지 않는 interval 400 의미를 추가했다. |
| `CHANGLOGS/2026-07-30_2010_robot-telemetry-recharts-series.md` | 추가 | 이번 시계열 API·Recharts 구현, 검증 결과, 영향과 후속 작업을 기록했다. |

## 상세 내용

### Backend 시계열

- Endpoint:
  `GET /api/v1/robots/{robotId}/telemetry-series?from=...&to=...&interval=5m`
- 허용 interval: `1m`, `5m`, `15m`, `1h`
- 기간: `sampled_at >= from AND sampled_at < to`, 최대 31일
- 응답 상한: `ceil((to-from)/interval) <= 5,000`
- 지원하지 않는 interval:
  `400 COMMON_INVALID_REQUEST`
- 역전·동일·31일 또는 5,000구간 초과:
  `422 STATISTICS_PERIOD_INVALID`
- 범위 밖·존재하지 않는 Robot:
  `404 ROBOT_NOT_FOUND`
- 원본 표본이 없으면 Robot identity와 `points: []`를 반환한다.
- 기존 `ix_robot_telemetry_robot_sampled (robot_id, sampled_at DESC)`를 사용해
  별도 Migration을 추가하지 않았다.

### Frontend 그래프

- 배터리: 유효 표본 평균·최저, 0~100% 축
- Jetson 온도: 평균·최고
- 속도: 평균·최고, 0 이상 축
- Tooltip·Legend와 세 그래프의 동기화된 Hover를 제공한다.
- 빈 시계열은 그래프 영역 대신 빈 데이터 안내를 표시한다.
- 실제 구간 시각을 Number Time Axis로 사용해 시간 간격을 보존한다.
- 누락된 인접 구간 사이에 값이 null인 절단점을 넣고 `connectNulls=false`를
  적용해 데이터 공백을 선으로 연결하지 않는다.
- Recharts는 동적 import해 `RobotTelemetryCharts` 전용 Chunk로 분리했다.
- Recharts는 npm 번들 의존성이며 외부 Chart API로 Telemetry를 전송하지 않는다.

## 검증

- 실행한 명령:
  - `npm install recharts --save-exact`
  - `.\gradlew.bat test -PisolatedBuildDirectory=build-codex-telemetry-series --tests "com.gilbom.robot.controller.RobotTelemetrySeriesControllerTest" --tests "com.gilbom.robot.integration.RobotTelemetryStatisticsServiceIntegrationTest"`
  - `.\gradlew.bat test -PisolatedBuildDirectory=build-codex-telemetry-series-regression --tests "com.gilbom.robot.controller.RobotTelemetrySeriesControllerTest" --tests "com.gilbom.robot.controller.RobotTelemetryStatisticsControllerTest" --tests "com.gilbom.robot.integration.RobotTelemetryStatisticsServiceIntegrationTest" --tests "com.gilbom.robot.integration.TelemetryIngestionIntegrationTest" --tests "com.gilbom.robot.integration.RobotQueryScopeIntegrationTest"`
  - `npm test`
  - `npm run lint -- --no-cache`
  - `npm run build`
  - PyYAML로 `openapi-v1.yaml` 파싱 후 신규 Operation·Permission·기본 interval·두 Schema의 `additionalProperties: false` 확인
  - 대상 파일 Trailing Whitespace 검사
  - `npm ls recharts --depth=0`
- 결과:
  - Backend 집중 Controller·PostGIS 집계 10건 통과, 실패·건너뜀 0건
  - 기존 통계·Telemetry 수집·Robot Scope를 포함한 Backend 회귀 25건 통과,
    실패·건너뜀 0건
  - Frontend Node 단위 테스트 31건 통과, 실패·건너뜀 0건
  - Frontend 전체 ESLint 통과
  - TypeScript·Vite Production Build 통과, 2,495 Module 변환
  - Recharts 전용 Lazy Chunk 368.80kB, Main JS 520.10kB로 분리
  - 기존 Main Chunk 500kB 경고는 남지만 Build 실패는 없음
  - OpenAPI YAML·Permission·Schema 검증 통과
  - Recharts 3.10.1 설치 확인, npm Audit 대상 198 Package 취약점 0건
  - 대상 파일 Trailing Whitespace 없음
- 미실행 항목과 사유:
  - 실제 Mock Robot/Jetson MQTT → DB → SSE → Browser 그래프 자동 갱신 E2E는
    인증 Session과 연속 Telemetry Runtime을 이번 작업에서 기동하지 않아
    실행하지 않았다.
  - Backend 전체 Test Suite는 실행하지 않고 변경과 직접 관련된 5개 Suite
    25건을 회귀 Gate로 실행했다.
  - 운영 데이터량 기반 `EXPLAIN (ANALYZE, BUFFERS)`는 실제 장기 Telemetry가
    없어 실행하지 않았다.

## 영향 및 주의사항

- 결함 Controller·Service·Repository·Table과 기존 `/api/v1/statistics/*`는
  변경하거나 조회하지 않는다.
- DB Schema·Flyway Migration 변경은 없다.
- 요약 통계와 시계열 중 하나라도 실패하면 통계 카드의 공통 오류 상태로 표시한다.
- Recharts 설치로 Frontend 의존성과 Lockfile이 증가했다. Chart Chunk는 Robot
  상세 통계가 실제 렌더링될 때만 내려받는다.
- `package.json`에는 이번 작업 전부터 Stage된 사용자의 Script 변경이 있으며
  이번 작업은 이를 되돌리지 않고 Recharts 항목만 추가했다.
- 이 작업은 아직 Git에 반영되지 않은 로봇 Telemetry 요약 통계·SSE 자동 갱신
  기능 위에서 수행했다. 이전 통계 파일과 이번 시계열 파일을 함께
  Stage·Commit해야 화면이 정상 동작한다.
- Docker Frontend·Backend 반영에는 두 Image를 모두 재빌드해야 한다.

## 미완료 및 후속 작업

1. Mock Robot 연속 Telemetry로 Browser 그래프가 약 5초 단위로 갱신되는지
   확인한다.
2. 인위적으로 Telemetry 발행을 중단해 시간축의 공백이 끊어진 선으로 표시되는지
   확인한다.
3. 운영 31일 데이터에서 1시간 집계 SQL 실행 계획과 응답 시간을 측정한다.
4. GPS 유효율 또는 수신 표본 수 그래프가 시연에 필요하면 현재 응답 필드를
   재사용해 별도 Series를 추가한다.
