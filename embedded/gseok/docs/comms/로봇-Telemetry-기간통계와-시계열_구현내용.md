# Robot Telemetry 기간 통계·시계열 구현 내용

> 담당: 팀원 B — Robot Telemetry·관제
> 작업일: 2026-07-30
> 연결 Jira: `S15P11D101-368`, Day 8 누락·지연 처리, Day 9 관제 UX
> 최종 상태: **DONE (LOCAL)** — API·단위·통합·Frontend 검증 완료, 운영 데이터 성능 측정 대기

이 문서는 MQTT로 저장된 한 Robot의 Telemetry 이력을 기간 단위로 집계해
요약·시계열·React 차트로 표시하는 **조회 흐름만** 묶는다. MQTT 수신, Current
State UPSERT와 일반 실시간 관제는
[로봇 상태수집과 실시간 관제](로봇-상태수집과-실시간-관제_구현내용.md)에서
관리한다.

관련 정본:
[Telemetry 통계 계약](../../robot-telemetry-statistics-contract.md),
[OpenAPI](../../openapi-v1.yaml),
[권한 매트릭스](../../authorization-matrix.md)

---

## 0. 3분 요약

Robot 상세 화면에서 최근 24시간의 배터리·Jetson 온도·속도·GPS 품질을 요약하고
시간대별 그래프로 볼 수 있다. 조회 정본은 `telemetry.robot_telemetry`이고
기간은 Robot 측정 시각인 `sampled_at`의 `[from,to)`를 사용한다.

```text
Robot 상세 진입
→ 기간 통계·시계열 REST 조회
→ PostgreSQL 기간·Robot Scope 집계
→ 요약 지표와 Recharts 그래프 표시
→ ROBOT_TELEMETRY_UPDATED SSE
→ 5초 단위로 병합해 REST 정본 재조회
```

요약 API와 시계열 API는 기존 `ROBOT_STATUS_VIEW` 및 사용자 Robot 객체 범위를
그대로 적용한다. 최대 기간은 31일이며 시계열은 `1m`, `5m`, `15m`, `1h`
구간만 허용하고 최대 5,000개 구간으로 제한한다.

---

## 1. 요구사항과 수용 기준

### 1.1 REST

| Method | Path | 용도 |
| --- | --- | --- |
| GET | `/api/v1/robots/{robotId}/telemetry-statistics?from&to` | 기간 전체 요약 |
| GET | `/api/v1/robots/{robotId}/telemetry-series?from&to&interval` | 고정 시간 구간 시계열 |

- Permission: `ROBOT_STATUS_VIEW`
- 사용자에게 배정되지 않은 Robot과 존재하지 않는 Robot은 동일한 404
- 시간 기준: `sampled_at >= from AND sampled_at < to`
- 최대 기간: 31일
- 허용 시계열 간격: `1m`, `5m`, `15m`, `1h`
- 반환 구간: 최대 5,000개

### 1.2 지표

- 전체·유효 배터리 표본 수
- 배터리 평균·최저
- Jetson 온도 평균·최고
- 속도 평균·최고
- GPS 유효·무효 표본 수와 유효율
- 마지막 Robot 측정 시각
- 시계열 구간별 동일 지표

빈 기간은 404가 아니라 Robot identity와 0건·nullable 지표·빈 `points`를
반환한다.

### 1.3 Frontend

- 기본 최근 24시간 자동 조회
- 최대 31일의 사용자 지정 기간 조회
- 배터리·온도·속도 동기화 그래프
- 로딩·오류·빈 데이터 상태 분리
- 수동 기간 조회 시 자동 갱신 중지
- `24시간 자동 갱신`으로 Rolling 조회 재개
- 연속 SSE는 최대 5초에 한 번의 REST 재조회로 병합

---

## 2. 무엇을 만들었나

### 2.1 Backend

```text
RobotTelemetryStatisticsController
RobotTelemetryStatisticsService
RobotTelemetryStatisticsResponse
RobotTelemetrySeriesController
RobotTelemetrySeriesService
RobotTelemetrySeriesResponse
RobotTelemetrySeriesPointResponse
```

`RobotTelemetryStatisticsService`는 기간 전체를 한 행으로 집계하고,
`RobotTelemetrySeriesService`는 PostgreSQL `date_bin`으로 구간별 집계를
생성한다. 두 Service 모두 기존 Robot 가시성 Predicate를 SQL에 포함한다.

DB Migration은 추가하지 않았다. 기존
`ix_robot_telemetry_robot_sampled (robot_id, sampled_at DESC)`를 사용한다.

### 2.2 Frontend

```text
features/robot/RobotTelemetryStatisticsCard.tsx
features/robot/RobotTelemetryCharts.tsx
features/robot/robotTelemetryStatistics.ts
features/robot/robot-telemetry-statistics.css
features/robot/robotApi.ts
features/robot/robotTypes.ts
```

`RobotTelemetryCharts`는 상세 통계가 실제 표시될 때만 동적으로 불러 Recharts
전용 Chunk를 분리한다. 배터리·온도·속도 그래프는 같은 시간축을 사용하고,
결측 구간은 직선으로 이어 붙이지 않는다.

### 2.3 실시간 갱신

SSE Payload를 Browser에서 직접 합산하지 않는다. 현재 Robot의
`ROBOT_TELEMETRY_UPDATED`를 무효화 신호로 사용하고, 첫 신호 뒤 5초 Timer 하나만
예약해 DB 집계 API를 다시 읽는다. Route 이탈·Robot 변경 시 Timer를 정리한다.

---

## 3. 설계 판단과 그 이유

### 3.1 Summary와 Series를 분리했다

요약 응답에 수천 개의 시계열 점을 섞으면 작은 상태 카드도 큰 Payload를 항상
받는다. 요약과 그래프를 독립 Endpoint로 두어 필요한 화면만 시계열을 읽는다.

### 3.2 `received_at`이 아니라 `sampled_at`을 사용했다

네트워크 지연 때문에 늦게 도착한 표본도 실제 Robot 측정 시각의 구간에 들어가야
한다. Backend 수신 시각으로 집계하면 통신 장애가 센서 추세처럼 보일 수 있다.

### 3.3 SSE를 통계 정본으로 쓰지 않았다

SSE는 유실·재연결이 가능하고 부분 Payload다. 화면은 Event를 “다시 읽어야 한다”는
신호로만 사용하고 PostgreSQL 집계 결과를 정본으로 유지한다.

### 3.4 기간·구간 상한을 함께 뒀다

31일 제한만으로는 1분 구간 요청이 과도해질 수 있다. 기간 상한과 5,000구간 상한을
동시에 적용해 예측 가능한 응답 크기를 유지한다.

### 3.5 결측 구간을 0으로 만들지 않았다

수신되지 않은 배터리·온도·속도를 0으로 그리면 실제 급락으로 오해한다. 구간의
결측값은 null로 유지하고 그래프 선을 끊는다.

---

## 4. 검증과 재현 명령

Backend:

```powershell
cd web/backend
.\gradlew.bat test `
  -PisolatedBuildDirectory=build-codex-telemetry-commit `
  --tests "com.gilbom.robot.controller.RobotTelemetrySeriesControllerTest" `
  --tests "com.gilbom.robot.controller.RobotTelemetryStatisticsControllerTest" `
  --tests "com.gilbom.robot.integration.RobotTelemetryStatisticsServiceIntegrationTest" `
  --tests "com.gilbom.robot.integration.TelemetryIngestionIntegrationTest" `
  --tests "com.gilbom.robot.integration.RobotQueryScopeIntegrationTest"
```

Frontend:

```powershell
cd web/frontend/gilbom
npm test
npm run lint -- --no-cache
npm run build
```

2026-07-30 최종 커밋 전 결과:

- Backend 집중 회귀 25건 통과, 실패·오류·Skip 0
- Frontend 단위 테스트 37건 통과
- 전체 ESLint 통과
- TypeScript·Vite Production Build 통과
- Recharts 전용 Lazy Chunk 분리 확인
- OpenAPI 두 경로와 응답 Schema의 YAML Parse·참조 검증 통과
- 대상 파일 `git diff --check` 통과

실제 Mock Robot·Jetson 연속 Telemetry를 사용한 Browser 자동 갱신 E2E와 운영
31일 데이터의 `EXPLAIN (ANALYZE, BUFFERS)`는 실행하지 않았다.

---

## 5. 작업 중 겪은 함정

### 유효 배터리와 전체 표본 수를 섞으면 평균이 틀린다

배터리 평균·최저는 `battery_valid = TRUE` 표본만 사용한다. GPS 유효율의 분모는
기간 전체 표본이므로 지표마다 유효성 조건을 명시해야 한다.

### 고정된 최초 종료 시각은 새 Telemetry를 누락한다

자동 모드에서 `to`를 최초 진입 시각으로 유지하면 SSE가 와도 새 표본이 기간 밖에
남는다. 재조회할 때마다 현재 시각과 정확히 24시간 전을 다시 계산한다.

### Event마다 집계하면 수신 주기만큼 SQL이 실행된다

5초 Telemetry를 그대로 REST 호출로 연결하면 Robot·사용자 수에 비례해 집계
부하가 증가한다. 첫 Event 기준 5초 병합 Timer를 사용하고 Event마다 Timer를
뒤로 미루지 않는다.

### 그래프 의존성이 Main Bundle을 키운다

Recharts를 정적 import하면 일반 Robot 화면도 차트 코드를 받는다. 상세 통계
영역에서만 Lazy import해 전용 Chunk로 분리했다.

---

## 6. 미완 항목

### 내 몫

- 실제 Mock Robot 또는 Jetson 연속 Telemetry에서 5초 병합 재조회 확인
- 인위적으로 발행을 중단해 시계열 결측 구간이 끊어진 선으로 보이는지 확인
- 운영 31일 데이터의 SQL 실행 계획과 응답시간 측정
- 필요할 때만 covering index를 별도 Migration으로 검토

### 팀원 A

- 운영 Nginx SSE Buffering과 다중 Backend 배포 여부 확인
- 운영 데이터 보존 기간·모니터링 기준 협의

### Embedded팀

- 실제 센서 주기·결측·Invalid 값의 의미 확인
- 장시간 운행 중 NTP 보정과 `sampled_at` 연속성 확인

---

## 7. 관련 문서와 커밋

- `CHANGLOGS/2026-07-30_1901_robot-telemetry-statistics.md`
- `CHANGLOGS/2026-07-30_1947_robot-telemetry-statistics-realtime-refresh.md`
- `CHANGLOGS/2026-07-30_2010_robot-telemetry-recharts-series.md`
- Commit `609083f` — `feat: 로봇 Telemetry 통계·실시간 차트`
- [Robot Telemetry 통계 계약](../../robot-telemetry-statistics-contract.md)
- [팀원 B Story 현황](../../2026-07-28-story-subtask-status.md)
