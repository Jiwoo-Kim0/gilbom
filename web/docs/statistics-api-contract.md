# 통계 API 계약

- 문서 상태: 확정
- 적용 API: `/api/v1/statistics/*`
- 기계 판독 계약: [`openapi-v1.yaml`](./openapi-v1.yaml)
- 권한 정본: [`authorization-matrix.md`](./authorization-matrix.md)
- 검증 Fixture: [`patrol-mission-statistics-fixture.md`](./patrol-mission-statistics-fixture.md)

## 1. Endpoint와 권한

| Endpoint | 집계 | 필요 권한 |
|---|---|---|
| `GET /api/v1/statistics/by-status` | 결함 처리 상태별 현재 결함 수 | `DEFECT_STATUS_VIEW` |
| `GET /api/v1/statistics/by-defect-type` | 결함 유형별 현재 결함 수 | `DEFECT_STATUS_VIEW` |
| `GET /api/v1/statistics/by-area` | 지역별 Mission·Incident·결함 | `MISSION_VIEW` AND `DEFECT_STATUS_VIEW` |
| `GET /api/v1/statistics/by-period` | UTC 날짜별 Mission·Incident·결함 | `MISSION_VIEW` AND `DEFECT_STATUS_VIEW` |

두 권한이 표시된 API는 둘 다 보유해야 한다. 조회 가능한 원천 행은 현재 사용자에게
유효한 `auth.user_robot_assignment`를 SQL 조건으로 적용한 후 집계한다. 시스템
`ADMIN`은 전체 로봇 범위를 사용하지만 기능 권한 검사를 생략하지 않는다.

## 2. 공통 요청과 기간

- `from`, `to`는 필수 ISO-8601 offset date-time이다.
- 서버 요청 DTO는 두 값을 `Instant`로 받고 응답에서는 UTC `Z`로 정규화한다.
- 모든 원천은 반열림 구간 `[from,to)`로 집계한다.
- `from < to`여야 한다.
- 두 Instant의 차이는 `Duration.ofDays(366)` 이하여야 한다.
- 누락, 날짜 파싱 실패, UUID 파싱 실패는 `400 COMMON_INVALID_REQUEST`다.
- `from >= to` 또는 366일 초과는 `422 STATISTICS_PERIOD_INVALID`다.

기간 상한을 Instant 차이로 정의한 이유는 offset이 다른 요청도 같은 절대 구간이면
항상 같은 판정을 내리기 위해서다.

## 3. 원천과 집계 기준

| 지표 | 원천 | 기간 칼럼 | 집계 규칙 |
|---|---|---|---|
| Mission | `robot.mission` | `started_at` | 구간 내 시작한 Mission 수와 최종 상태 수 |
| 완료율 | `robot.mission` | `started_at` | `COMPLETED / 전체 Mission * 100`, 전체 0이면 0, 소수점 둘째 자리 `HALF_UP` |
| 수행시간 | `robot.mission` | `started_at` | `result_duration_sec` 합계, null은 0 |
| Incident | `telemetry.robot_event` | `occurred_at` | `event_type = 'INCIDENT'` Event 수 |
| Defect | `defect.defect` | `last_seen_at` | `merged_into_defect_id IS NULL`인 현재 결함 수 |

Mission의 `result_incident_count`는 기기 결과 요약이므로 Incident 통계의 정본으로
사용하지 않는다. 실제 수신 Event를 세는 `incidentEventCount`와 이름도 구분한다.
병합된 원본 결함은 현재 결함 수에서 제외해 target과 source가 이중 집계되지 않게
한다. 지역별 Incident는 `robot_event.mission_id`로 연결한 Mission의
`patrol_area_id`에 귀속하며 Mission과 연결되지 않은 Incident는 지역별 집계에서는
제외하고 기간별 집계에는 포함한다.

## 4. Endpoint별 반환 규칙

### 4.1 처리 상태·결함 유형

- `by-status`는 `DefectWorkflowStatus` 6개를 enum 선언 순서로 항상 반환한다.
- `by-defect-type`은 `DefectType` 7개를 enum 선언 순서로 항상 반환한다.
- 해당 기간에 데이터가 없어도 모든 항목을 반환하고 `defectCount`는 0이다.
- `totalDefectCount`는 항목별 `defectCount`의 합과 같아야 한다.

이 규칙은 데이터 유무에 따라 차트 범례와 응답 배열의 형태가 달라지는 일을 막는다.

### 4.2 지역별

- `patrolAreaId`는 선택 query UUID다.
- 미지정 시 모든 순찰 구역을 `areaCode ASC, patrolAreaId ASC`로 정렬해 반환한다.
- 지정한 구역이 존재하지만 기간 내 데이터가 없으면 200과 0 집계 한 건을 반환한다.
- UUID 형식은 유효하지만 구역이 없으면 `404 PATROL_AREA_NOT_FOUND`다.
- 지역 통계에만 `page=0`, `size=20` 기본값을 적용하며 `size` 최대값은 100이다.
- 음수 page, 1 미만 또는 100 초과 size는 `400 COMMON_INVALID_REQUEST`다.
- 페이지 메타데이터는 `items`, `page`, `size`, `totalElements`, `totalPages`다.

구역 목록을 기준 테이블로 두고 각 집계를 LEFT JOIN하는 의미를 계약으로 삼기 때문에
데이터 없는 기존 구역도 사라지지 않는다.

### 4.3 기간별

- v1의 `granularity`는 `DAY` 하나이며 생략 시 `DAY`다.
- 응답의 `date`는 UTC 날짜이고 오름차순이다.
- 각 날짜 D의 실제 집계 구간은
  `[max(from, D 00:00Z), min(to, D+1 00:00Z))`다.
- 요청 구간과 겹치는 UTC 날짜는 데이터가 없어도 모두 포함하고 지표를 0으로 채운다.

첫날과 마지막 날을 요청 경계에서 자르면 임의 offset 요청도 `[from,to)` 밖의 데이터를
섞지 않으면서 UTC 일별 시계열을 유지할 수 있다.

## 5. 결정적 결과와 구현 제약

- 같은 DB snapshot, 인증 주체, 정규화한 `from`, `to`, 필터에는 같은 집계와 순서를
  반환한다.
- 사용자 입력을 SQL 조각에 연결하지 않는다. `NamedParameterJdbcTemplate`,
  Criteria/JPA parameter 등 bind parameter를 사용한다.
- enum 정렬은 공개 계약의 allow-list를 사용하며 요청 문자열로 정렬식을 만들지 않는다.
- 각 원천을 먼저 동일 차원으로 집계한 뒤 결합해 Mission×Incident×Defect join
  곱집합으로 count가 부풀지 않게 한다.
- 구현 테스트는 Fixture의 전체·지역·날짜·상태·유형 결과, `[from,to)` 경계,
  빈 데이터, 권한 범위, 잘못된 요청과 반복 실행 결과를 검증한다.

## 6. 계약 변경 게이트

통계 구현을 시작하기 전에 다음 정본이 같은 변경에서 일치해야 한다.

1. OpenAPI 네 path, query parameter, 성공 DTO와 400/401/403/404/422 응답
2. 권한 매트릭스의 단일 권한 또는 AND 권한과 객체 범위
3. 공통 오류 문서, 백엔드 `ErrorCode`, 오류 코드 테스트
4. `scripts/verify-statistics-contract.sh` 계약 동기화 검사

필드나 의미를 변경해야 하면 구현과 문서를 다시 같은 변경으로 갱신한다.

## 7. 공통 구현 기준

공통 기반은 `com.gilbom.statistics`에 다음 책임으로 분리한다.

| 클래스 | 책임 |
|---|---|
| `StatisticsController` | `/api/v1/statistics` base path. 각 GET mapping은 해당 집계 구현과 함께 추가 |
| `StatisticsQueryService` | 기간·페이지·granularity 검증 순서와 지역 존재 확인 조정 |
| `StatisticsDtos` | OpenAPI 성공 응답 DTO와 공개 enum |
| `StatisticsPeriod` | 필수 경계, UTC Instant, `[from,to)`, 최대 366일 |
| `StatisticsQuery` | 지역 페이지 offset과 기간 granularity의 검증된 query 값 |
| `StatisticsRepository` | `NamedParameterJdbcTemplate` 기반 지역 존재 확인과 후속 집계 SQL |

공통 기반만 존재하는 동안에는 네 GET endpoint를 빈 200 응답이나
`UnsupportedOperationException`으로 노출하지 않는다. 각 mapping은 실제 집계,
권한, 오류 및 계약 테스트가 준비되는 작업에서 함께 활성화한다.
