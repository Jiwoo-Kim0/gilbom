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
- V12 시점에는 `robot`, `telemetry`, `storage` 스키마가 없어 그 스키마를 참조하는 컬럼을
  FK 없이 UUID로만 보관했다. V19·V22·V23으로 대상 테이블과 FK가 모두 생겼으므로 현재
  미연결 FK는 없다. 연결 현황은 아래 3절에 정리한다.

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
| `zone_id` | `A-01` 형식 구역 코드. 정규식 `^[A-Z]-[0-9]{2}$`로 형식만 검증한다. 구역 실체는 `patrol_area_id` FK가 담당하므로 `zone_id`에는 FK를 걸지 않는다 |
| `observation_count` | 이 결함으로 병합된 관측 수 캐시. 최소 1 |
| `merged_into_defect_id` | 다른 결함에 흡수 병합된 경우의 대표 결함. 자기 자신을 가리킬 수 없다 |

### 2.2 `defect.defect_observation` — 불변 관측 사실

`events/incident` MQTT 메시지 하나가 만드는 불변 사실이다. AI 모델·이미지·BBox·파손
비율처럼 "그 순간 로봇이 무엇을 봤는가"를 기록하고, 이후 값이 바뀌지 않는다. `defect`
테이블과 N:1로 연결된다.

| 컬럼 | 규칙 |
|---|---|
| `event_id` | PK. `telemetry.robot_event`를 `(event_id, robot_id)` 복합 FK로 참조한다 (V23) |
| `robot_id` | 관측을 발행한 로봇. `robot.robot` FK, `ON DELETE RESTRICT`. V19에서 추가했다 |
| `defect_id` | 병합 대상 결함. `defect.defect` FK, `ON DELETE RESTRICT` |
| `confidence` | AI 신뢰도, 0~1 |
| `damage_ratio` | 파손 면적 비율, 0~1 (nullable) |
| `bbox_norm` | `[x_min,y_min,x_max,y_max]` 정규화 좌표 JSON 배열. `JSONB_TYPEOF = 'array'`로 형식 검증 |
| `image_id` | `storage.robot_image`를 `(image_id, robot_id)` 복합 FK로 참조한다 (V23). 업로드 실패 시 NULL (계약 11.3 핀 우선 원칙) |
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

## 3. 외부 스키마로 가는 FK

V12 시점에는 참조 대상 테이블이 없어 UUID만 보관했으나, V22가 Patrol·Mission·Event·
Image 정본을 만들고 V23이 FK를 모두 연결했다. 현재 defect 스키마에 미연결 FK는 없다.

| 컬럼 | 참조 대상 | 연결 |
|---|---|---|
| `defect.defect.patrol_area_id` | `robot.patrol_area (patrol_area_id)` | V23 `fk_defect_patrol_area` |
| `defect.defect.patrol_route_id` | `robot.patrol_route (patrol_route_id, patrol_area_id)` | V23 `fk_defect_patrol_route_area` |
| `defect.defect_observation.robot_id` | `robot.robot (robot_id)` | V19 `fk_observation_robot` |
| `defect.defect_observation.event_id` | `telemetry.robot_event (event_id, robot_id)` | V23 `fk_observation_robot_event` |
| `defect.defect_observation.image_id` | `storage.robot_image (image_id, robot_id)` | V23 `fk_observation_robot_image` |

`event_id`와 `image_id`는 `robot_id`를 포함한 **복합 FK**다. 덕분에 "같은 로봇이 발행한
이벤트·업로드한 이미지만 참조할 수 있다"는 제약까지 DB가 보장한다. 즉 V19의 `robot_id`는
범위 판정용 칼럼이면서 이 복합 FK의 구성 요소이기도 하므로, `telemetry.robot_event`가
생긴 뒤에도 파생 중복 칼럼으로 취급해 제거하지 않는다.

`defect.defect.patrol_route_id`는 `ck_defect_route_requires_area`로 경로만 있고 구역이
없는 상태를 막는다.

### 3.0.1 제약 검증 (V23 `NOT VALID` → V27 `VALIDATE`)

V23은 FK 6개와 CHECK 1개를 `NOT VALID`로 걸었다. `NOT VALID`는 신규 INSERT·UPDATE에는
관계 무결성을 적용하지만 제약을 걸기 전에 이미 있던 행은 검사하지 않고, 플래너도 제약을
신뢰하지 않는다. V23 주석이 예고한 "기존 데이터 정리 후 별도 배포"가
`V27__validate_defect_and_command_constraints.sql`이다.

V27이 안전한 근거는 세 가지다.

1. V23 이후에 쓰인 행은 이미 FK가 적용된 상태로 들어갔으므로 orphan일 수 없다.
2. 따라서 위험은 V23 적용 시점에 이미 있던 행뿐인데, 참조 대상 테이블은 V22에서 처음
   생겼다. 그 전에는 이 칼럼들을 채울 방법이 없었다.
3. 적용 대상 DB에서 `defect.defect`, `defect.defect_observation`,
   `robot.robot_command`가 모두 0행임을 확인했다.

`VALIDATE`는 `SHARE UPDATE EXCLUSIVE` 락만 잡아 읽기·쓰기를 막지 않는다. 위반 행이 있으면
migration이 실패해 기동이 막히므로, 데이터가 쌓인 환경에 적용할 때는 먼저 orphan 수를
센다.

```sql
SELECT conrelid::regclass AS table_name, conname, contype
FROM pg_constraint WHERE NOT convalidated ORDER BY 1, 2;
```

V27 적용 후 이 질의는 defect·robot_command 관련 행을 반환하지 않아야 한다.

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
- `auth.admin_user`로 가는 FK(`changed_by`, `merged_by`, `reverted_by`)만 연결하고, 당시
  존재하지 않던 스키마로 가는 FK는 보류 (V19·V23에서 연결 완료, 3절 참고)
- 스키마·테이블 단위 최소 권한 GRANT

`V19__link_defect_to_robot_and_add_export_job.sql`은 결함 REST API를 붙이면서 다음을
추가한다.

- `defect_observation.robot_id`(`NOT NULL`)와 `robot.robot` FK, 범위 판정용 index
- `defect.defect_export_job` 테이블과 최소 권한 GRANT

`robot_id`를 `NOT NULL`로 넣을 수 있었던 이유는 그 시점에 `defect_observation`에 쓰는
코드가 없어 테이블이 비어 있었기 때문이다. 현재는 `IncidentEventIngestionService`가
`robot_id`를 함께 저장한다.

`V23__link_defect_and_command_to_patrol_mission.sql`이 남은 FK를 모두 연결한다. V23의
제약은 `NOT VALID`로 추가되었고, V27이 이를 `VALIDATE`로 승격한다 (3.0.1 참고).

### 5.1 V19와 V23의 실행 순서 (번호 변경 금지)

`defect_observation.robot_id`는 V23이 추가하는 두 복합 FK의 구성 요소다.

| V23 제약 | 참조 대상 | 대상의 UNIQUE |
|---|---|---|
| `(event_id, robot_id)` | `telemetry.robot_event` | `uq_robot_event_robot_identity` |
| `(image_id, robot_id)` | `storage.robot_image` | `uq_robot_image_robot_identity` |

즉 V22가 이 복합 FK를 받기 위해 대상 테이블에 복합 UNIQUE를 만들어 뒀고, V19가
`robot_id`를 만든다. **V19를 V23 이후 번호로 옮기면 V23이
`column robot_id does not exist`로 실패한다.** 반대로 V19가 없으면 V22·V23도 성립하지
않으므로, 세 migration은 이 순서를 전제로 읽어야 한다.

이 복합 FK 덕분에 "같은 로봇이 발행한 이벤트·업로드한 이미지만 참조할 수 있다"는 제약이
DB에서 보장된다. `telemetry.robot_event`가 생긴 뒤에는 `event_id`로도 로봇을 역추적할 수
있어 `robot_id`가 형식상 파생 중복 칼럼이 되지만, 위 제약의 근거이자 범위 판정 질의가
매번 telemetry를 조인하지 않게 하는 칼럼이므로 제거하지 않는다.

> V19는 이미 배포·적용된 migration이므로 위 설명을 파일 주석으로 넣지 않고 이 문서에
> 둔다. 적용된 `V*.sql`을 고치면 Flyway checksum이 달라져 이미 적용한 모든 DB의 기동이
> 막힌다.

defect 관련 항목은 `database-migrations.md`의 migration 표에도 반영돼 있다.
