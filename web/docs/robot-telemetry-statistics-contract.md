# 로봇 Telemetry 통계·시계열 API 계약

- 문서 상태: 확정
- 적용 API:
  - `GET /api/v1/robots/{robotId}/telemetry-statistics`
  - `GET /api/v1/robots/{robotId}/telemetry-series`
- 기계 판독 계약: [`openapi-v1.yaml`](./openapi-v1.yaml)
- 권한 정본: [`authorization-matrix.md`](./authorization-matrix.md)

## 1. 목적과 책임 경계

이 API는 한 로봇이 MQTT v2로 전송해
`telemetry.robot_telemetry`에 저장된 측정 이력만 기간 집계한다.

다음 원천은 조회하거나 변경하지 않는다.

- `defect.defect`
- `defect.defect_observation`
- `telemetry.robot_event`
- `robot.mission`
- 기존 `/api/v1/statistics/*`

## 2. 요청과 권한

- 권한: `ROBOT_STATUS_VIEW`
- 객체 범위: 현재 사용자에게 유효한 `auth.user_robot_assignment`가 있는 로봇
- 시스템 `ADMIN`은 전체 로봇 범위를 사용하되 기능 권한 검사는 유지한다.
- 범위 밖 또는 존재하지 않는 로봇은 모두 `404 ROBOT_NOT_FOUND`다.
- `from`, `to`는 필수 ISO-8601 offset date-time이다.
- 집계 구간은 `sampled_at >= from AND sampled_at < to`인 `[from,to)`다.
- `from < to`이며 두 Instant의 차이는 최대 31일이다.
- 날짜 형식 오류·파라미터 누락은 `400 COMMON_INVALID_REQUEST`다.
- 역전·동일 기간 또는 31일 초과는 `422 STATISTICS_PERIOD_INVALID`다.

## 3. 집계 지표

| 응답 필드 | 집계 기준 |
|---|---|
| `sampleCount` | 기간 내 고유 Telemetry 행 수 |
| `validBatterySampleCount` | `battery_valid = TRUE` 표본 수 |
| `averageBatteryPercent` | 유효 배터리 표본의 `battery_percent` 평균 |
| `minimumBatteryPercent` | 유효 배터리 표본의 최저 `battery_percent` |
| `averageJetsonTemperatureC` | 값이 존재하는 `jetson_temp_c` 평균 |
| `maximumJetsonTemperatureC` | 값이 존재하는 `jetson_temp_c` 최고 |
| `averageSpeedMps` | 값이 존재하는 `speed_mps` 평균 |
| `maximumSpeedMps` | 값이 존재하는 `speed_mps` 최고 |
| `validGpsSampleCount` | `position_valid = TRUE` 표본 수 |
| `invalidGpsSampleCount` | `position_valid = FALSE` 표본 수 |
| `gpsValidRatePercent` | `validGpsSampleCount / sampleCount * 100` |
| `lastSampledAt` | 기간 내 가장 늦은 `sampled_at` |

평균·비율은 소수점 둘째 자리 `HALF_UP`으로 반환한다.
표본이 없으면 건수와 GPS 유효율은 0이며 평균·최저·최고·마지막 측정 시각은
`null`이다.

## 4. 시계열 요청과 집계

시계열 API는 통계 API와 같은 `from`, `to`, 권한, 객체 범위와 `[from,to)` 규칙을
사용한다. `interval`의 기본값은 `5m`이며 다음 값만 허용한다.

| `interval` | 구간 길이 |
|---|---:|
| `1m` | 1분 |
| `5m` | 5분 |
| `15m` | 15분 |
| `1h` | 1시간 |

요청 기간은 최대 31일이고 `ceil((to-from)/interval)`은 최대 5,000구간이다.
지원하지 않는 `interval`은 `400 COMMON_INVALID_REQUEST`, 구간 수 초과는
`422 STATISTICS_PERIOD_INVALID`다.

구간은 UTC Unix Epoch를 원점으로 PostgreSQL `date_bin`을 사용해 정렬한다.
첫 구간은 요청 `from`보다 먼저 시작할 수 있지만 집계에 포함되는 원본 행은 항상
`[from,to)` 내부로 제한한다. 원본 표본이 없는 구간은 가짜 0 지점을 만들지 않고
응답 `points`에서 생략한다.

각 지점은 다음 값을 반환한다.

- `bucketAt`, `sampleCount`
- 유효 배터리 표본 수, 배터리 평균·최저
- Jetson 온도 평균·최고
- 속도 평균·최고
- GPS 유효·무효 표본 수와 유효율

## 5. Frontend

로봇 상세 화면은 최근 24시간을 기본 기간으로 조회한다. 사용자는 시작·종료 시각을
직접 선택할 수 있으며 Client와 Backend가 모두 최대 31일을 검증한다.

기본 최근 24시간 모드에서는 상세 화면이 같은 로봇의
`ROBOT_TELEMETRY_UPDATED` SSE를 갱신 신호로 사용한다. SSE Payload에서 통계를
직접 누적하지 않고, 최초 이벤트 뒤 5초 동안 후속 이벤트를 한 요청으로 합쳐
`GET /api/v1/robots/{robotId}/telemetry-statistics`를 다시 호출한다. 연속으로
이벤트가 들어와도 타이머를 계속 뒤로 미루지 않으므로 최대 5초마다 DB 정본을
재조회한다.

사용자가 기간을 직접 입력해 조회하면 고정 기간 모드로 전환해 SSE 자동 재조회를
중단한다. `24시간 자동 갱신`을 선택하면 조회 종료 시각을 현재 시각으로 이동시키고
최근 24시간 자동 갱신을 재개한다. 상세 Route가 unmount되면 EventSource와 예약된
통계 재조회 타이머를 모두 해제하며, MQTT Telemetry의 Backend 수신·DB 저장에는
영향을 주지 않는다.

최근 24시간 그래프는 `5m` 시계열을 기본으로 사용하고 Recharts로 배터리 평균·최저,
Jetson 온도 평균·최고, 속도 평균·최고를 표시한다. 요약 통계와 시계열은 같은
조회 기간을 사용하며 같은 5초 SSE 갱신 주기로 DB 정본을 다시 조회한다.
