# 변경 로그: 로봇별 Telemetry 기간 통계

- 작성 시각: 2026-07-30 19:01 (Asia/Seoul)
- 작업 유형: 기능
- 상태: 완료

## 작업 요약

기존 결함·보고서 통계와 분리해 한 로봇의 MQTT v2 Telemetry 이력만 집계하는
`GET /api/v1/robots/{robotId}/telemetry-statistics`를 구현했다. 조회 기간은
`sampled_at` 기준 `[from,to)`이며 최대 31일이다. 배터리·Jetson 온도·속도·GPS
유효성·마지막 측정 시각을 반환하고, 로봇 상세 화면에서 최근 24시간을 기본으로
조회한다.

직전 작업 중 생성했던 Mission 통계 초안은 정식 변경으로 남기지 않고 제거했다.
이번 구현은 `telemetry.robot_telemetry`와 로봇 접근 범위만 사용하며
`defect.*`, `telemetry.robot_event`, `robot.mission`, 기존
`/api/v1/statistics/*` 구현을 변경하지 않았다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/backend/src/main/java/com/gilbom/robot/api/RobotTelemetryStatisticsController.java` | 추가 | `ROBOT_STATUS_VIEW` 권한과 인증 사용자 ID를 적용해 로봇별 Telemetry 기간 통계를 제공하는 GET endpoint를 추가했다. |
| `web/backend/src/main/java/com/gilbom/robot/telemetry/RobotTelemetryStatisticsService.java` | 추가 | `telemetry.robot_telemetry`를 로봇·`sampled_at` 기간으로 제한하고 표본 수, 배터리 평균·최저, Jetson 평균·최고, 속도 평균·최고, GPS 유효·무효·유효율, 마지막 측정 시각을 집계했다. `VISIBLE_ROBOT_PREDICATE`, 최대 31일 검증과 빈 데이터 의미를 적용했다. |
| `web/backend/src/main/java/com/gilbom/robot/data/api/response/RobotTelemetryStatisticsResponse.java` | 추가 | 로봇 identity, UTC 기간과 Telemetry 집계 지표의 응답 DTO를 추가했다. |
| `web/backend/src/test/java/com/gilbom/robot/controller/RobotTelemetryStatisticsControllerTest.java` | 추가 | 필요 권한, offset 시각의 UTC 정규화, 응답 직렬화, 누락 파라미터 400, 권한 부족 403, 잘못된 기간 422를 검증했다. |
| `web/backend/src/test/java/com/gilbom/robot/integration/RobotTelemetryStatisticsServiceIntegrationTest.java` | 추가 | PostGIS·Flyway 실제 스키마에서 `[from,to)`, 결측 표본 제외 평균, 빈 데이터, 사용자 로봇 범위, 31일 상한을 검증했다. |
| `web/frontend/gilbom/src/features/robot/RobotDetailPage.tsx` | 수정 | 로봇 상세 상태와 Command 사이에 Telemetry 통계 카드를 연결했다. |
| `web/frontend/gilbom/src/features/robot/RobotTelemetryStatisticsCard.tsx` | 추가 | 최근 24시간 자동 조회, 최대 31일 기간 입력, 로딩·오류·빈 데이터와 배터리·온도·속도·GPS 지표 UI를 구현했다. |
| `web/frontend/gilbom/src/features/robot/robot-telemetry-statistics.css` | 추가 | Desktop·Tablet·Mobile 통계 카드, 기간 필터와 지표 Grid 반응형 스타일을 추가했다. |
| `web/frontend/gilbom/src/features/robot/robotTelemetryStatistics.ts` | 추가 | 최근 24시간 기본 기간, 역전·31일 초과 검증, API UTC 변환과 nullable 수치 표시 함수를 추가했다. |
| `web/frontend/gilbom/src/features/robot/robotApi.ts` | 수정 | 기존 로봇 등록 Client 변경을 보존하면서 `getRobotTelemetryStatistics()` 조회 함수를 추가했다. |
| `web/frontend/gilbom/src/features/robot/robotTypes.ts` | 수정 | 기존 로봇 등록 Type 변경을 보존하면서 nullable Telemetry 지표를 포함하는 `RobotTelemetryStatistics`를 추가했다. |
| `web/frontend/gilbom/test/robotTelemetryStatistics.test.ts` | 추가 | 기본 24시간, 역전·31일 초과 차단, 숫자·결측값 표시를 검증했다. |
| `web/docs/robot-telemetry-statistics-contract.md` | 추가 | 책임 경계, 권한·객체 범위, 최대 기간, 집계 원천·지표와 빈 데이터 의미를 정본화했다. |
| `web/docs/openapi-v1.yaml` | 수정 | Telemetry 통계 operation, 파라미터, 권한, 응답 Schema와 오류 응답을 추가했다. |
| `web/docs/authorization-matrix.md` | 수정 | Telemetry 통계 endpoint의 `ROBOT_STATUS_VIEW`, `VIEWER+`, 최대 31일 객체 범위를 추가했다. |
| `web/docs/common-api-response-and-errors.md` | 수정 | Telemetry 통계 기간 역전·동일·31일 초과의 `STATISTICS_PERIOD_INVALID` 의미를 추가했다. |
| `web/docs/api-contract.md` | 수정 | 로봇 Telemetry 통계 계약 문서를 API 정본 목록에 연결했다. |
| `CHANGLOGS/2026-07-30_1901_robot-telemetry-statistics.md` | 추가 | 구현 범위, 검증 결과, 영향과 후속 작업을 기록했다. |

## 상세 내용

### Backend 집계

- `sampleCount`: 기간 내 Telemetry 행 수
- `validBatterySampleCount`: `battery_valid = TRUE` 표본 수
- `averageBatteryPercent`, `minimumBatteryPercent`
- `averageJetsonTemperatureC`, `maximumJetsonTemperatureC`
- `averageSpeedMps`, `maximumSpeedMps`
- `validGpsSampleCount`, `invalidGpsSampleCount`
- `gpsValidRatePercent`: 유효 GPS 표본 / 전체 표본
- `lastSampledAt`: 기간 내 가장 늦은 로봇 측정 시각

평균과 비율은 소수점 둘째 자리로 반환한다. 집계 행이 없으면 건수와 GPS 유효율은
0이고 평균·최저·최고·마지막 시각은 null이다. 사용자에게 배정되지 않은 로봇과
존재하지 않는 로봇은 동일하게 `ROBOT_NOT_FOUND`를 반환한다.

### Frontend

- 로봇 상세 진입 시 최근 24시간을 자동 조회한다.
- 시작·종료 `datetime-local` 입력으로 기간을 변경할 수 있다.
- Client와 Backend 모두 역전·동일 기간과 31일 초과를 차단한다.
- Telemetry가 없으면 MQTT 발행 상태를 확인하라는 빈 데이터 안내를 표시한다.

### 책임 경계

- 결함 Controller·Service·Repository·DTO·테스트는 변경하지 않았다.
- 기존 공용 통계 Controller·Repository·보고서 통계 Client도 변경하지 않았다.
- DB Migration은 추가하지 않았다. 기존
  `ix_robot_telemetry_robot_sampled (robot_id, sampled_at DESC)`를 사용한다.

## 검증

- 실행한 명령:
  - `.\gradlew.bat test --tests "com.gilbom.robot.controller.RobotTelemetryStatisticsControllerTest" --tests "com.gilbom.robot.integration.RobotTelemetryStatisticsServiceIntegrationTest"`
  - `.\gradlew.bat test --tests "com.gilbom.robot.controller.RobotTelemetryStatisticsControllerTest" --tests "com.gilbom.robot.integration.RobotTelemetryStatisticsServiceIntegrationTest" --tests "com.gilbom.robot.integration.TelemetryIngestionIntegrationTest" --tests "com.gilbom.robot.integration.RobotQueryScopeIntegrationTest"`
  - `npm test`
  - `npm run lint -- --no-cache`
  - `npm run build`
  - PyYAML로 `openapi-v1.yaml` 파싱 후 신규 path·Schema·`additionalProperties: false` 확인
  - 대상 파일 `git diff --check`
- 결과:
  - Backend 집중 테스트 7건 통과, 실패·Skip 0
  - 기존 Telemetry 수집·로봇 범위를 포함한 Backend 회귀 18건 통과, 실패·Skip 0
  - Frontend Node 테스트 29건 통과, 실패·Skip 0
  - Frontend ESLint 통과
  - TypeScript·Vite Production Build 통과, 1,918 Module 변환
  - OpenAPI YAML 파싱 및 신규 path·Schema 확인
  - 대상 파일 whitespace 오류 없음
- 미실행 항목과 사유:
  - Backend 전체 Test Suite는 실행하지 않고 변경과 직접 연관된 Controller·PostGIS
    집계·기존 Telemetry 수집·객체 범위 18건을 실행했다.
  - 실제 Jetson/EC2 연속 Telemetry를 사용한 장시간 조회와 Browser 실기 확인은
    외부 기기·운영 데이터가 필요해 실행하지 않았다.
  - 공유 Docker 개발 DB에는 현재 시연 로봇·Telemetry 데이터가 없어 영속 데이터를
    임의 생성하는 Runtime Smoke Test를 실행하지 않았다.

## 영향 및 주의사항

- 기간 기준은 Backend 수신 시각 `received_at`이 아니라 로봇 측정 시각
  `sampled_at`이다.
- 배터리 평균·최저는 유효 배터리 표본만 사용하고, nullable 온도·속도는 값이 있는
  표본만 평균낸다.
- 최대 31일 제한은 고빈도 Telemetry의 무제한 집계를 막기 위한 API 계약이다.
- Frontend 반영에는 개발 서버 HMR 또는 Docker Frontend Image 재빌드가 필요하다.
- 이 작업은 기존 작업이 남아 있는 dirty worktree에서 수행했으며 관련 없는 변경을
  정리하거나 덮어쓰지 않았다.

## 미완료 및 후속 작업

1. 실제 Mock Robot 또는 Jetson이 Telemetry를 발행한 뒤 로봇 상세의 최근 24시간
   집계와 DB 원본 값을 대조한다.
2. 시계열 차트가 필요하면 현재 Summary API를 확장하지 말고 granularity가 명시된
   별도 endpoint를 계약부터 추가한다.
3. 운영 데이터량을 기준으로 31일 최악 구간의 `EXPLAIN (ANALYZE, BUFFERS)`를
   확인하고 필요할 때만 covering index를 후속 Migration으로 추가한다.
