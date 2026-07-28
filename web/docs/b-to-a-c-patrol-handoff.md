# 팀원 B → 팀원 A/C 순찰·Mission 인계

## 1. 목적

팀원 A의 기간·지역 통계·보고서와 팀원 C의 결함 지도·필터·상세 개발이 B의
미완료 구현 때문에 대기하지 않도록, 현재 사용할 수 있는 DB·REST·Fixture 계약과
책임 경계를 고정한다.

## 2. 즉시 사용 가능한 B 제공 계약

| 소비자 | 필요한 값 | B 제공 위치 | 상태 |
| --- | --- | --- | --- |
| A Backend | Robot·Area·Route·Mission FK, 상태, 시작·종료, 결과 | `robot.mission`과 V22 | 사용 가능 |
| A Backend | 기간·Area·Route·Status 인덱스 | V22 `ix_mission_*` | 사용 가능 |
| A Backend | runtime 통계 조회 권한 | V22 `${applicationRole}` SELECT | 검증 완료 |
| A Backend | 결정적 통계 입력과 예상값 | SQL Fixture·예상 결과 문서 | 검증 완료 |
| A/C Frontend | Area UUID·코드·이름·Boundary | `GET /api/v1/patrol-areas` | 사용 가능 |
| A/C Frontend | Area 단건 | `GET /api/v1/patrol-areas/{patrolAreaId}` | 사용 가능 |
| A/C Frontend | Route UUID·코드·버전·Geometry | `GET /api/v1/patrol-areas/{patrolAreaId}/routes` | 사용 가능 |
| C Backend | Incident 공통 Event와 Mission UUID | `telemetry.robot_event` | 사용 가능 |
| C Backend | Defect Area·Route, Observation Event·Image FK | V23 | 신규 데이터 적용 |
| C Frontend | 결함 목록·지도·상세·관측·이력·상태 API | `DefectController` | Backend 사용 가능 |

## 3. Patrol Catalog REST 계약

세 Endpoint는 인증과 `MISSION_VIEW` 권한을 요구한다. C/A 화면을 사용하는 Role이
Catalog를 직접 호출하려면 해당 권한을 함께 받아야 한다.

### Area 목록

```http
GET /api/v1/patrol-areas
```

```json
{
  "areas": [
    {
      "patrolAreaId": "30000000-0000-4000-8000-000000000001",
      "areaCode": "A-11",
      "areaName": "Fixture Area A",
      "boundary": {
        "type": "Polygon",
        "coordinates": []
      },
      "lifecycleStatus": "ACTIVE",
      "createdAt": "2026-06-30T00:00:00Z",
      "updatedAt": "2026-06-30T00:00:00Z"
    }
  ]
}
```

목록 정렬은 `ACTIVE` → `INACTIVE` → `ARCHIVED`, Area 코드 순이다. 보고서의 과거
지역 선택을 위해 비활성·보관 Area도 숨기지 않는다.

### Area별 Route

```http
GET /api/v1/patrol-areas/{patrolAreaId}/routes
```

```json
{
  "routes": [
    {
      "patrolRouteId": "31000000-0000-4000-8000-000000000001",
      "patrolAreaId": "30000000-0000-4000-8000-000000000001",
      "routeCode": "A-MAIN",
      "routeName": "Fixture Area A Main",
      "routeVersion": 1,
      "routeGeometry": {
        "type": "LineString",
        "coordinates": []
      },
      "lifecycleStatus": "ACTIVE",
      "createdAt": "2026-06-30T00:00:00Z",
      "updatedAt": "2026-06-30T00:00:00Z"
    }
  ]
}
```

존재하지 않는 Area의 단건·Route 목록은 HTTP 404와
`PATROL_AREA_NOT_FOUND`를 반환한다.

## 4. A 통계 개발 입력

- SQL Fixture:
  `backend/src/test/resources/fixtures/patrol-mission-statistics.sql`
- 예상 결과와 대표 Query:
  [patrol-mission-statistics-fixture.md](patrol-mission-statistics-fixture.md)
- 기간 규칙:
  UTC 반열림 구간 `[from,to)`
- MQTT 문자열 Mission ID:
  `robot.mission.mission_code`
- 내부 Join/FK:
  `robot.mission.mission_id UUID`

A는 MQTT Broker나 실제 Robot을 기다리지 않고 Fixture로 통계 Query와 Report API를
개발할 수 있다. Fixture는 운영 DB에 실행하지 않는다.

## 5. C 결함 프론트 개발 입력

C의 기존 Backend는 다음 Endpoint를 제공한다.

| 화면 기능 | Endpoint | 권한 |
| --- | --- | --- |
| 요약 | `GET /api/v1/defects/summary` | `DEFECT_STATUS_VIEW` |
| 지도 | `GET /api/v1/defects/map` | `DEFECT_STATUS_VIEW` |
| 목록 | `GET /api/v1/defects` | `DEFECT_STATUS_VIEW` |
| 상세 | `GET /api/v1/defects/{defectId}` | `DEFECT_STATUS_VIEW` |
| 관측·bbox | `GET /api/v1/defects/{defectId}/observations` | `DEFECT_STATUS_VIEW` |
| 변경 이력 | `GET /api/v1/defects/{defectId}/history` | `DEFECT_STATUS_VIEW` |
| 상태 변경 | `PATCH /api/v1/defects/{defectId}/status` | `DEFECT_STATUS_UPDATE` |
| 정보 수정 | `PATCH /api/v1/defects/{defectId}` | `DEFECT_STATUS_UPDATE` |
| 이미지 URL | `GET /api/v1/defects/{defectId}/image-url` | `DEFECT_STATUS_VIEW` |

Area·Route 선택지는 B의 Patrol Catalog를 사용하고 선택된 UUID를 Defect 목록·지도
Query의 `patrolAreaId`, `patrolRouteId`에 전달한다.

현재 이미지 URL Port의 기본 구현은 503을 반환한다. 실제 S3/MinIO Presigned URL
구현은 C 책임이며 B의 Patrol/Mission 작업을 기다릴 필요가 없다.

## 6. B 병목 판정

| 항목 | A/C 개발 차단 여부 | 설명 |
| --- | --- | --- |
| 실제 MQTT Broker QoS 1 E2E | 차단하지 않음 | Fixture와 직접 저장 통합 Test로 화면·통계 개발 가능 |
| Mission 생성·수정 REST | 현재 A/C 작업은 차단하지 않음 | B Mission 관리 화면과 운영 입력을 위한 B 후속 작업 |
| Patrol Area·Route 변경 REST | 현재 조회 작업은 차단하지 않음 | Admin 편집 기능을 위한 B 후속 작업 |
| 실제 S3 Presigned URL | C 이미지 화면만 차단 | C 소유 Storage Adapter 작업 |
| Report API·LLM | A 보고서 화면만 차단 | A 소유 작업 |
| Git 공유 | **차단함** | V22/V23·코드·문서가 commit/push되기 전에는 다른 팀원이 받을 수 없음 |

## 7. 공유 전 체크리스트

- [x] V1~V23 빈 DB Migration
- [x] runtime role Patrol/Mission/Event/Image SELECT
- [x] Event 재전송 멱등과 Sequence 충돌
- [x] Mission Event·Telemetry·Incident DB 연결
- [x] 결정적 SQL Fixture와 예상 집계
- [x] Patrol Catalog REST 권한·GeoJSON·404
- [x] Patrol Catalog 추가 후 Backend 전체 403 Test 통과
- [ ] 선택적 Git stage·commit·push
- [ ] A/C에게 commit SHA와 이 문서 전달

마지막 두 항목이 끝나기 전까지 로컬 구현은 준비됐어도 팀 공유 완료로 판정하지 않는다.
