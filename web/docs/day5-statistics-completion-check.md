# Day 5 통계 완료 체크

- 판정 기준일: 2026-07-29
- 통계 API 범위: **완료**
- Day 5 전체 범위: **Jetson 보안 후속 작업이 남아 있어 부분 완료**
- API 계약: [statistics-api-contract.md](statistics-api-contract.md)
- Fixture·실행계획:
  [patrol-mission-statistics-fixture.md](patrol-mission-statistics-fixture.md)

통계 완료와 Day 5 전체 완료를 분리한다. 아래 통계 기준은 모두 충족하지만,
Jetson 보안 잔여 작업을 통계 작업에 포함해 완료로 오인하지 않는다.

## 1. 통계 완료 기준

| # | 완료 기준 | 구현·검증 근거 | 판정 |
|---:|---|---|---|
| 1 | 네 통계 endpoint의 Fixture 예상값 일치 | `PatrolMissionSchemaIntegrationTest`의 `statusStatisticsApplyScopeExcludeMergedAndZeroFillEnums`, `defectTypeStatisticsApplyScopeExcludeMergedAndZeroFillEnums`, `areaStatisticsAggregateSourcesBeforeJoiningAndApplyScope`, `periodStatisticsUseUtcHalfOpenBucketsScopeAndZeroFill`가 같은 SQL Fixture의 정확한 상태·유형·A-11/B-22·UTC 일별 값을 비교한다. | 완료 |
| 2 | UTC `[from,to)` 경계 | `StatisticsPeriodTest`가 UTC 정규화와 최대 366일을 검증하고, 기간 통합 테스트가 `from`과 같은 시각 포함, `to`와 같은 시각 제외 및 부분 날짜를 검증한다. | 완료 |
| 3 | 빈 데이터 `200`/0 응답 | 상태·유형은 전체 enum을 0으로, 기존 Area는 0 지표 한 건으로, 기간은 빈 UTC 날짜 bucket을 0으로 반환한다. Controller 성공 응답 계약은 모두 `200`이다. | 완료 |
| 4 | 잘못된 기간·UUID·페이지 크기 차단 | `StatisticsPeriodTest`, `StatisticsQueryTest`, `StatisticsControllerTest.areaStatisticsRejectMalformedAreaUuid`, `areaStatisticsRejectInvalidPageAndSize`가 `400`/`422`를 구분해 검증한다. 없는 정상 UUID는 `404 PATROL_AREA_NOT_FOUND`다. | 완료 |
| 5 | 인증 없음 `401`, 권한 없음 `403` | `StatisticsControllerTest`가 네 endpoint 각각의 미인증 요청과 `DEFECT_STATUS_VIEW`, `MISSION_VIEW AND DEFECT_STATUS_VIEW` 조합을 검증한다. | 완료 |
| 6 | 객체 범위 밖 로봇 데이터 미집계 | 네 통합 테스트가 Robot 001 VIEWER에게 Robot 002의 결함과 Mission·Incident·Observation이 집계되지 않는지 확인한다. 범위 조건은 원천 CTE 또는 결함 원천 행에서 먼저 적용한다. | 완료 |
| 7 | SQL Injection 형태 입력 차단 | `defectType=DAMAGED; DROP TABLE...`와 `granularity=DAY; DROP TABLE...`가 SQL 실행 전에 `400 COMMON_INVALID_REQUEST`로 거부된다. SQL 값은 `NamedParameterJdbcTemplate`로 바인딩하고 identifier 선택은 enum switch만 사용한다. | 완료 |
| 8 | 동일 요청의 반복 결과 동일 | 네 통합 테스트가 동일 사용자·기간·필터 요청의 응답 객체가 같은지 재호출 비교한다. Fixture 자체도 두 번 적용한 뒤 원천·집계값 불변을 확인한다. | 완료 |
| 9 | 실제 API Query `EXPLAIN` 검토 | `actualStatisticsRepositoryQueriesHaveReviewableExecutionPlans`가 Repository가 실제 실행한 SQL과 바인딩 값을 캡처해 네 Query 모두 `EXPLAIN (ANALYZE, BUFFERS)`를 실행한다. V26 인덱스 호환성은 `enable_seqscan=off` 계획으로 별도 검증한다. | 완료 |
| 10 | OpenAPI·권한·오류·계약 테스트 동시 갱신 | `openapi-v1.yaml`, `authorization-matrix.md`, `common-api-response-and-errors.md`, 백엔드 `ErrorCode`, `verify-statistics-contract.sh`가 네 endpoint와 오류를 함께 검증한다. | 완료 |
| 11 | JDK 21 + Docker 전체·통합 테스트 무 skip | 아래 최종 실행 기록으로 판정한다. `disabledWithoutDocker=true` 통합 테스트가 실행됐는지는 전체 결과의 `skipped=0`과 통합 테스트 XML의 실행 건수로 확인한다. | 완료 |

## 2. 성능 판정 범위

PostGIS 16 Testcontainer의 작은 Fixture에서는 원천 크기에 따라 자연
Sequential Scan과 V26 Index Only Scan이 함께 선택됐다. 실제 Repository SQL 네
개의 자연 실행계획 모두 Planning/Execution Time을 남겼으며, 상태·유형·지역·기간
Query에서 사용자 객체 범위 조건까지 포함된 계획을 검토했다.

이 결과는 Query 형태와 인덱스 적용 가능성을 확인하는 개발 게이트다. 운영 성능의
최종 판정은 실제 Cardinality와 데이터 분포를 가진 스테이징 DB에서 자연
`EXPLAIN (ANALYZE, BUFFERS)`, p95 응답시간, 인덱스 크기 및 쓰기 비용을 다시
측정해야 한다.

## 3. Jetson 보안 분리 판정

다음 항목은 현재 구현돼 있으며 통계 API 완료 판정을 막지 않는다.

| 구현됨 | 근거 |
|---|---|
| Credential hash·버전·만료와 robot ID 결합 | `robot.robot_credential` 제약 및 `MqttCredentialIssuanceService` |
| MQTT client별 Topic ACL | `infra/mosquitto/acl` |
| `boot_id`·`seq` 중복/Replay 방지 | Telemetry·Event UNIQUE 제약과 각 Ingestion Service |
| Command ID 멱등성·TTL 상태 | `RobotCommandCreationService`, `RobotCommandResultIngestionService`, `RobotCommandTimeoutService` |
| Command Request Retain 금지 | `MqttTopicType.COMMAND_REQUEST`의 `retained=false`와 `MqttV2Publisher` |

다음은 **Jetson 담당자의 별도 후속 작업**으로 남긴다.

1. Credential 회전·폐기 API와 DB Registry/Mosquitto 인증 저장소의 원자적 또는
   보상 가능한 동기화
2. 처음 수신한 `(boot_id, seq)`라도 허용 시간보다 오래된 MQTT 메시지를
   `sent_at`/`occurred_at` 기준 TTL로 차단
3. 이미지 업로드에서 실제 JPEG magic byte와 decode 검증, 500KB 상한,
   기기별 quota 및 rate limit

따라서 **통계 작업은 완료**지만, Jetson 연동 보안까지 포함한 **Day 5 전체를 완료로
표시하려면 위 세 후속 작업의 구현·시험이 추가로 필요**하다.

## 4. 최종 실행 기록

검증은 저장소의 `scripts/backend-test.sh`와 동일한 JDK 21·Docker socket·
Testcontainers host 설정을 사용한다.

```text
실행일: 2026-07-29
JDK image: eclipse-temurin:21-jdk

통계 집중 테스트:
tests=34, failures=0, skipped=0
- StatisticsControllerTest: 23
- PatrolMissionSchemaIntegrationTest: 11

전체 백엔드 테스트:
Backend test summary: tests=486, failures=0, skipped=0
BUILD SUCCESSFUL in 1m 49s

계약 동기화:
Statistics API contract synchronization: PASS
```

전체 테스트는 `--rerun-tasks --no-daemon`으로 다시 실행했다. 통합 테스트 클래스의
`@Testcontainers(disabledWithoutDocker = true)`가 조용히 건너뛰어졌다면
`skipped > 0`이 되어 Gradle의 무-skip 게이트가 빌드를 실패시키므로, 위
`skipped=0`은 Testcontainers가 실제 실행됐다는 판정 근거다.
