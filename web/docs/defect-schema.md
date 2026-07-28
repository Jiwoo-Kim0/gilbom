# 결함(defect) 스키마

## 1. 범위와 원칙

이 문서는 `docs/길봄_DB.pdf`(길봄 DB Specification v1.0) 7장에 정의된 `defect` 스키마 중
V12 migration(`V12__create_defect_schema.sql`)으로 실제 반영한 부분을 설명한다. PDF는
41개 테이블·9개 스키마 전체를 다루지만, 이 문서와 migration은 그중 **defect 스키마 4개
테이블만** 다룬다.

- 점자블록 이상은 AI가 보낸 개별 관측(observation)과, 관리자가 실제로 처리하는 결함
  (defect)을 분리해서 저장한다. 같은 위치의 결함을 여러 번 관측해도 관리 대상은 하나다.
- 위험도(`risk_level`/`risk_score`)는 서버가 계산한 값이며, AI가 보낸 `confidence`와는
  다른 값이다. 둘을 같은 컬럼에 섞지 않는다.
- 관리자 처리 상태(`workflow_status`)와 그 변경 이력은 `defect_status_history`에 별도로
  남기며, 이 이력 테이블은 감사 로그·보고서 버전과 동일하게 append-only로 보호한다.
- `robot`, `telemetry`, `storage` 스키마는 아직 이 백엔드에 없다. PDF 설계상 그 스키마를
  참조하는 컬럼은 FK 없이 UUID 값만 보관하고, 해당 스키마가 추가되는 시점에 FK를 이어
  붙인다. 아래 3절에 그 목록을 정리한다.

## 2. 테이블 구조

### 2.1 `defect.defect` — 서버가 관리하는 결함

서로 다른 `event_id`의 공간적 중복을 병합해서 만드는 실제 관리 대상이다. 관측이 아니라
"처리해야 할 건" 단위이므로 위험도·행정 처리상태·위치 대표값을 가진다.

| 컬럼 | 규칙 |
|---|---|
| `incident_code` | 외부 표시 코드. UNIQUE. 예: `INC-20260723-000031` |
| `risk_level` / `risk_score` | 서버가 계산하는 `LOW`/`MEDIUM`/`HIGH`/`CRITICAL`과 점수. `risk_rule_version`으로 계산 규칙 버전을 함께 남긴다 |
| `workflow_status` | `NEW → ACCEPTED → IN_PROGRESS → RESOLVED`, 또는 `FALSE_POSITIVE`/`REJECTED`. 관리자 REST 처리로만 전이된다 |
| `resolution` | `RESOLVED` 등으로 종결할 때의 처리 결과. `REPAIRED`, `CLEANED`, `DUPLICATE`, `REINSPECTION_REQUIRED`, `NOT_APPLICABLE` |
| `location` | 대표 위치(`GEOMETRY(POINT,4326)`). 개별 관측 위치가 아니라 결함의 대표 좌표 |
| `zone_id` | `A-01` 형식 구역 코드. 정규식 `^[A-Z]-[0-9]{2}$`로 형식만 검증하고, `robot.patrol_area` FK는 아직 걸지 않는다 |
| `observation_count` | 이 결함으로 병합된 관측 수 캐시. 최소 1 |
| `merged_into_defect_id` | 다른 결함에 흡수 병합된 경우의 대표 결함. 자기 자신을 가리킬 수 없다 |

### 2.2 `defect.defect_observation` — 불변 관측 사실

`events/incident` MQTT 메시지 하나가 만드는 불변 사실이다. AI 모델·이미지·BBox·파손
비율처럼 "그 순간 로봇이 무엇을 봤는가"를 기록하고, 이후 값이 바뀌지 않는다. `defect`
테이블과 N:1로 연결된다.

| 컬럼 | 규칙 |
|---|---|
| `event_id` | PK. `telemetry.robot_event.event_id`를 참조할 예정이지만 그 테이블이 없어 FK는 없다 |
| `robot_id` | 관측을 발행한 로봇. `robot.robot` FK, `ON DELETE RESTRICT`. V19에서 추가했다 |
| `defect_id` | 병합 대상 결함. `defect.defect` FK, `ON DELETE RESTRICT` |
| `confidence` | AI 신뢰도, 0~1 |
| `damage_ratio` | 파손 면적 비율, 0~1 (nullable) |
| `bbox_norm` | `[x_min,y_min,x_max,y_max]` 정규화 좌표 JSON 배열. `JSONB_TYPEOF = 'array'`로 형식 검증 |
| `image_id` | `storage.robot_image.image_id`를 참조할 예정이지만 storage 스키마가 없어 FK는 없다 |
| `is_representative` | 결함 카드에 보여줄 대표 관측 여부. 결함당 최대 1개(partial UNIQUE index) |

### 2.3 `defect.defect_status_history` — 처리 이력 (append-only)

관리자 처리상태·유형·위험도·위치·처리 결과가 바뀔 때마다 전후 값과 사유를 남긴다.
`report.report_version`, `audit.audit_log`와 동일한 패턴으로 append-only trigger를 걸어
`UPDATE`/`DELETE`를 DB 레벨에서 차단한다.

| 컬럼 | 규칙 |
|---|---|
| `change_type` | `STATUS_CHANGED`, `TYPE_CHANGED`, `RISK_CHANGED`, `LOCATION_CHANGED`, `RESOLUTION_CHANGED`, `MERGED`, `UNMERGED` |
| `before_values` / `after_values` | 변경 전후 snapshot. JSON object만 허용 |
| `changed_by` | 변경한 관리자. 서버가 자동으로 위험도를 재계산한 경우 등은 NULL 허용 |

### 2.4 `defect.defect_merge_history` — 공간 병합 이력

서로 다른 `event_id`가 사실은 같은 결함일 때 병합하거나, 병합을 취소한 과정을 추적한다.

| 컬럼 | 규칙 |
|---|---|
| `source_defect_id` / `target_defect_id` | 병합되어 흡수된 결함과 대표로 남는 결함. 서로 달라야 한다 |
| `merge_status` | `MERGED` 또는 `REVERTED` |
| 생명주기 제약 | `MERGED`면 `reverted_by`/`reverted_at`이 모두 NULL이어야 하고, `REVERTED`면 둘 다 채워져 있고 `reverted_at >= merged_at`이어야 한다 |

## 3. 아직 걸지 않은 FK (스키마 추가 대기)

PDF 설계에는 있지만 이 백엔드에는 아직 없는 스키마를 참조하는 컬럼이다. 컬럼 자체는
UUID로 존재하며, 해당 스키마가 생기면 후속 migration에서 FK만 추가하면 된다.

| 컬럼 | 참조 예정 대상 | 비고 |
|---|---|---|
| `defect.defect.patrol_area_id` | `robot.patrol_area.patrol_area_id` | 순찰 구역 |
| `defect.defect.patrol_route_id` | `robot.patrol_route.patrol_route_id` | 순찰 경로 |
| `defect.defect_observation.event_id` | `telemetry.robot_event.event_id` | MQTT 사건 원본 |
| `defect.defect_observation.image_id` | `storage.robot_image.image_id` | 근거 이미지 |

`robot_id`는 V19에서 FK까지 연결했다. `telemetry.robot_event`가 생기면 `event_id`로도
로봇을 역추적할 수 있어 `robot_id`는 파생 중복 칼럼이 되지만, 범위 판정 질의가 매번
telemetry를 조인하지 않도록 관측에 그대로 유지한다.

## 3.1 결함 객체 범위

`authorization-matrix.md` 11장은 결함 API의 객체 범위를 "배정 로봇에서 파생된 결함"으로
정의한다. 결함은 로봇을 직접 가리키지 않으므로 범위는 관측을 통해 판정한다. 즉
`defect_observation.robot_id`가 `auth.user_robot_assignment` 범위 안에 있는 관측이 그
결함에 하나라도 있어야 조회·수정할 수 있다.

병합된 결함의 관측은 원본 결함에 그대로 남는다(`defect_observation`에는 `UPDATE` 권한이
없다). 따라서 대표 결함의 범위·관측 목록·집계는 `merged_into_defect_id` 연결을 따라가며
자신에게 병합된 결함의 관측까지 포함한다.

## 3.2 `defect.defect_export_job` (V19)

`POST /api/v1/defects/export`는 202로 "비동기 작업 접수"를 응답한다. 접수 사실이
남지 않으면 202를 반환할 근거가 없으므로 요청자·형식·필터 snapshot을 저장한다.
`job_status`는 `QUEUED`로만 기록되며, 실제 파일 생성 worker는 아직 없다.

## 4. 권한

- `defect.defect`: `SELECT, INSERT, UPDATE` — 관리자가 `workflow_status`, `resolution` 등을
  직접 갱신한다.
- `defect.defect_observation`: `SELECT, INSERT`만 — AI 관측 사실은 수정하지 않는다.
- `defect.defect_status_history`: `SELECT, INSERT`만 — append-only trigger로 `UPDATE`,
  `DELETE`를 이미 DB에서 차단하므로 권한도 동일하게 좁힌다.
- `defect.defect_merge_history`: `SELECT, INSERT, UPDATE` — 병합 취소 시 `reverted_*`
  컬럼을 같은 행에 채워야 하므로 `UPDATE`가 필요하다.
- `defect.defect_export_job`: `SELECT, INSERT, UPDATE` — worker가 `job_status`와 결과
  컬럼을 갱신해야 하므로 `UPDATE`가 필요하다.

## 5. 마이그레이션

`V12__create_defect_schema.sql`은 앞선 migration을 수정하지 않고 다음을 새로 만든다.

- `defect` 스키마와 4개 테이블(`defect`, `defect_observation`, `defect_status_history`,
  `defect_merge_history`)
- `defect_status_history`에 대한 append-only 함수·trigger
- `auth.admin_user`로 가는 FK(`changed_by`, `merged_by`, `reverted_by`)만 연결하고, 아직
  없는 스키마로 가는 FK는 3절과 같이 보류
- 스키마·테이블 단위 최소 권한 GRANT

`V19__link_defect_to_robot_and_add_export_job.sql`은 결함 REST API를 붙이면서 다음을
추가한다.

- `defect_observation.robot_id`(`NOT NULL`)와 `robot.robot` FK, 범위 판정용 index
- `defect.defect_export_job` 테이블과 최소 권한 GRANT

`robot_id`를 `NOT NULL`로 넣을 수 있는 이유는 이 시점에 `defect_observation`에 쓰는
코드가 없어 테이블이 비어 있기 때문이다. MQTT `events/incident` 수집 경로를 구현할 때
`robot_id`를 함께 저장해야 한다.

defect 관련 항목은 `database-migrations.md`의 migration 표에도 반영돼 있다.
