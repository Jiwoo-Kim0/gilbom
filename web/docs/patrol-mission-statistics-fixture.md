# 순찰·Mission 통계 SQL Fixture와 예상 결과

## 1. 용도와 실행 범위

이 Fixture는 기간·지역별 통계 API와 Mission·Event·Telemetry·Defect 관계를
검증하기 위한 **로컬/통합 테스트 전용 데이터**다. 운영 데이터나 Flyway Migration이
아니다.

- Fixture:
  `backend/src/test/resources/fixtures/patrol-mission-statistics.sql`
- 자동 검증:
  `PatrolMissionSchemaIntegrationTest`
- 집계 기간:
  UTC 반열림 구간
  `[2026-07-01T00:00:00Z, 2026-07-05T00:00:00Z)`
- 식별자:
  고정 UUID, 고정 UTC 시각, SRID 4326 가상 좌표
- 재실행:
  모든 INSERT가 `ON CONFLICT DO NOTHING`을 사용하며 테스트가 같은 SQL을 두 번
  실행한 뒤 집계값 불변을 검증한다.

운영 DB에는 실행하지 않는다. 실제 이미지 파일이나 Presigned URL도 포함하지 않고
`storage.robot_image`의 가상 private Object Key 메타데이터만 한 건 사용한다.

## 2. Fixture 구성

| 데이터 | 수 | 구성 |
| --- | ---: | --- |
| Robot | 2 | `RBT-STATS-001`, `RBT-STATS-002` |
| Patrol Area | 2 | `A-11`, `B-22` |
| Patrol Route | 3 | `A-MAIN`, `A-WEST`, `B-MAIN` |
| Mission | 4 | 완료 2, 취소 1, 실패 1 |
| Robot Event | 10 | Mission 종료 Event 4, Incident Event 6 |
| Mission Status History | 4 | Mission별 마지막 상태 전이 |
| Telemetry | 8 | Mission별 위치 궤적 2점 |
| Robot Image | 1 | 첫 Incident의 가상 JPEG 메타데이터 |
| Defect | 4 | Area별 2건 |
| Defect Observation | 6 | Incident Event와 일대일 원본 연결 |

## 3. Mission 원본과 예상값

| Mission | Robot | Area | Route | 상태 | 시작 | 종료 | 수행시간(초) | Incident |
| --- | --- | --- | --- | --- | --- | --- | ---: | ---: |
| `MIS-STATS-20260701-001` | 001 | A-11 | A-MAIN | COMPLETED | 07-01 00:00Z | 07-01 01:00Z | 3,600 | 2 |
| `MIS-STATS-20260702-001` | 001 | A-11 | A-WEST | CANCELLED | 07-02 00:00Z | 07-02 00:30Z | 1,800 | 1 |
| `MIS-STATS-20260703-001` | 002 | B-22 | B-MAIN | FAILED | 07-03 00:00Z | 07-03 00:20Z | 1,200 | 0 |
| `MIS-STATS-20260704-001` | 002 | B-22 | B-MAIN | COMPLETED | 07-04 00:00Z | 07-04 01:30Z | 5,400 | 3 |

### 전체 예상 집계

| 항목 | 예상값 |
| --- | ---: |
| Mission 수 | 4 |
| 완료 | 2 |
| 취소 | 1 |
| 실패 | 1 |
| 완료율 | 50% |
| 결과 수행시간 합계 | 12,000초 |
| Mission 결과 Incident 합계 | 6 |
| 실제 Incident Event 수 | 6 |
| Telemetry 궤적 점 수 | 8 |
| Defect 수 | 4 |
| Defect Observation 수 | 6 |

### Area별 예상 집계

| Area | Mission | 완료 | 취소 | 실패 | 수행시간(초) | Incident Event | Defect | Observation |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| A-11 | 2 | 1 | 1 | 0 | 5,400 | 3 | 2 | 3 |
| B-22 | 2 | 1 | 0 | 1 | 6,600 | 3 | 2 | 3 |

### Route별 예상 Mission

| Route | Mission | 수행시간(초) |
| --- | ---: | ---: |
| A-MAIN | 1 | 3,600 |
| A-WEST | 1 | 1,800 |
| B-MAIN | 2 | 6,600 |

## 4. 대표 집계 Query

기간은 `BETWEEN`이 아니라 `[from,to)` 조건을 사용해 다음 날짜 자정의 중복 집계를
막는다.

```sql
SELECT
    m.status,
    COUNT(*) AS mission_count,
    COALESCE(SUM(m.result_duration_sec), 0) AS duration_sec
FROM robot.mission m
WHERE m.patrol_area_id =
      '30000000-0000-4000-8000-000000000001'
  AND m.started_at >= TIMESTAMPTZ '2026-07-01T00:00:00Z'
  AND m.started_at <  TIMESTAMPTZ '2026-07-05T00:00:00Z'
GROUP BY m.status
ORDER BY m.status;
```

예상 결과:

| status | mission_count | duration_sec |
| --- | ---: | ---: |
| CANCELLED | 1 | 1,800 |
| COMPLETED | 1 | 3,600 |

## 5. EXPLAIN 결과

실행 환경:

- PostgreSQL 16.9 / PostGIS 3.5 Testcontainer
- Flyway V1~V23 적용
- Fixture `ANALYZE` 실행 후
- 2026-07-28 로컬 통합 테스트

### 자연 실행계획

Fixture는 Mission이 4건뿐이어서 PostgreSQL 비용 기반 Planner가 정상적으로 작은
테이블의 Sequential Scan을 선택했다.

```text
GroupAggregate  (cost=1.10..1.12 rows=1 width=25)
  (actual time=0.035..0.036 rows=2 loops=1)
  Group Key: status
  Buffers: shared hit=4
  ->  Sort
        Sort Key: status
        Sort Method: quicksort  Memory: 25kB
        ->  Seq Scan on mission m
              (actual time=0.007..0.008 rows=2 loops=1)
              Filter: patrol_area_id + started_at UTC range
              Rows Removed by Filter: 3
              Buffers: shared hit=1
Planning Time: 0.321 ms
Execution Time: 0.059 ms
```

작은 Fixture의 Seq Scan은 인덱스 누락 증거가 아니다. 같은 세션에서
`enable_seqscan=off`로 비용 선택만 제한해 Query와 복합 인덱스의 호환성을 별도로
확인했다.

### 인덱스 사용 가능성 증거

```text
GroupAggregate  (cost=8.16..8.19 rows=1 width=25)
  (actual time=0.036..0.037 rows=2 loops=1)
  Group Key: status
  Buffers: shared hit=5
  ->  Sort
        Sort Key: status
        Sort Method: quicksort  Memory: 25kB
        ->  Index Scan using ix_mission_area_started on mission m
              (actual time=0.008..0.009 rows=2 loops=1)
              Index Cond: patrol_area_id + started_at UTC range
              Buffers: shared hit=2
Planning Time: 0.322 ms
Execution Time: 0.063 ms
```

통합 테스트는 다음 대표 조건도 `enable_seqscan=off`에서 해당 인덱스로 서비스할 수
있는지 실제 `EXPLAIN (ANALYZE, BUFFERS)`로 확인한다.

| 대표 조회 | 확인 인덱스 |
| --- | --- |
| Mission Robot·기간 | `ix_mission_robot_started` |
| Mission Area·기간 | `ix_mission_area_started` |
| Mission Route·기간 | `ix_mission_route_started` |
| Mission Status·기간 | `ix_mission_status_started` |
| Event Robot·기간 | `ix_robot_event_robot_occurred` |
| Event Mission·기간 | `ix_robot_event_mission_occurred` |
| Telemetry Mission·기간 | `ix_robot_telemetry_mission_sampled` |
| Patrol Area Bounding Box | `ix_patrol_area_boundary` |

운영 성능 판정은 실제 Cardinality와 분포를 반영한 스테이징 데이터에서 자연
`EXPLAIN (ANALYZE, BUFFERS)`를 다시 수집해야 한다. 강제로 확인한 인덱스 계획은
인덱스가 Query 조건을 지원한다는 증거이지 운영 Planner 선택을 보장하지 않는다.

## 6. 자동 검증 결과

실행 명령:

```powershell
.\gradlew.bat test --rerun-tasks `
  --tests com.gilbom.robot.integration.PatrolMissionSchemaIntegrationTest
```

최종 결과:

```text
tests=4
skipped=0
failures=0
errors=0
BUILD SUCCESSFUL
```

검증 내용:

- Fixture 최초 적용과 두 번째 적용의 집계값 동일
- runtime application role로 Mission·Event·Telemetry·Defect 집계 SELECT 성공
- 전체·Area·Route별 예상값 일치
- 자연 실행계획과 인덱스 사용 가능성 실행계획 생성
- 요구된 B-tree 7개와 GiST 1개가 대표 조회를 지원
