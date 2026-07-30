# Patrol Area·시간 기반 Mission·지도 구현 내용

> 담당: 팀원 B — Patrol·Mission
> 작업일: 2026-07-28 ~ 2026-07-30
> 연결 Jira: `S15P11D101-379`, `S15P11D101-471`, `S15P11D101-489`
> 최종 상태: **기능·전체 Mock E2E DONE (LOCAL) / Migration V32 반영 IN PROGRESS**

이 문서는 Patrol Area 선택부터 Mission 생성, Telemetry 진행률·Trajectory,
완료 Event와 지도까지 이어지는 **Mission 생명주기 흐름만** 묶는다. MQTT 공통
보안과 Command 일반 생명주기는 다른 작업 문서에서 관리한다.

관련 정본:
[시간 기반 Mission 계약](../../time-based-mission-contract.md),
[OpenAPI](../../openapi-v1.yaml),
[Patrol 인계](../../b-to-a-c-patrol-handoff.md)

---

## 0. 3분 요약

관리자는 Robot, ACTIVE 방범 Area, 계획 수행시간을 선택해 Mission을 만든다.
사용자 Route 선택은 제거했고 DB·API 호환을 위해 Area마다 내부
`AUTONOMOUS_DEFAULT` Route를 자동 관리한다.

진행률은 GPS Route 투영이 아니라 최신 MQTT
`mission.elapsed_sec / planned_duration_sec`로 계산한다. 현재 Embedded 의미에
따라 PAUSED 중에도 벽시계 누적시간이 증가한다. 완료 Event 전에는 최대
99.99%, `MISSION_COMPLETED` Event에서만 100%가 된다.

Mission 화면은 생성·목록·상세·이력·계획/경과/남은 시간·ETA·Stale을 표시한다.
지도는 Area Polygon/MultiPolygon, 실제 GPS Trajectory, 현재 Marker와 Area
이탈 경고를 제공한다. 실제 로그인·MFA→Frontend→Backend→PostgreSQL Mission
생성 E2E도 통과했다.

Command·MQTT까지 연결한 전체 Mock E2E는 2026-07-30 14:28 Playwright 결과에서
통과했다. START→PAUSE→RESUME→COMPLETED, PAUSED 중 경과시간, 완료 100%,
상태 이력과 실제 궤적을 확인했다. 다만 관련 Mock·Harness·Test 파일은 작업
트리에 남아 있어 재실행·CHANGLOG·커밋 정리가 필요하다.

Migration은 팀원 C Image V31을 유지하고 Mission을 V32로 옮긴 작업 트리 상태다.
현재 중복 파일은 없지만 삭제·신규 파일이 미커밋이므로 빈 DB 전체 적용 검증과
공식 반영이 남았다.

---

## 1. 요구사항과 수용 기준

### 1.1 Patrol Area·Route

- Area 등록·목록·단건·이름/경계 변경·상태 변경
- GeoJSON Polygon/MultiPolygon
- Route는 같은 `routeCode`의 불변 버전
- 새 ACTIVE 버전 등록 시 이전 ACTIVE 자동 INACTIVE
- 과거 Route 직접 수정·삭제 없음
- 각 ACTIVE Area에 내부 `AUTONOMOUS_DEFAULT` 1개

### 1.2 Mission

- 필수:
  - Robot
  - ACTIVE Patrol Area
  - `plannedDurationSec > 0`
- 선택:
  - 예정 시작 시각
- 내부:
  - 선택 Area의 ACTIVE Dummy Route ID 자동 포함
- Permission:
  - 조회 `MISSION_VIEW`
  - 생성·변경 `MISSION_MANAGE`
- 사용자 Robot Scope 적용

### 1.3 시간 진행률

```text
progressPercent
= min(99.99, elapsedSec / plannedDurationSec * 100)
```

- `elapsedSec`: 최신 유효 MQTT Telemetry
- 상태: RUNNING·PAUSED
- 역행 값은 `GREATEST`로 무시
- 오래된 Telemetry 무시
- 완료 Event 전 99.99%
- 완료 Event에서 상태·진행률을 원자적으로 COMPLETED·100%

### 1.4 지도

```text
GET /api/v1/missions/{missionId}/map?limit=5000
```

응답:

- Area Boundary
- 시간순 유효 GPS Trajectory
- 최신 유효 Current Position

Dummy Route Geometry는 API·Layer·범례에서 제외한다. Area 이탈은 경고일 뿐
진행률을 바꾸지 않는다.

---

## 2. 무엇을 만들었나

### 2.1 DB

```text
robot.patrol_area
robot.patrol_route
robot.mission
robot.mission_status_history
telemetry.robot_telemetry.mission_id
```

Migration 흐름:

- V22 Patrol·Mission·Event·Image 기반
- V23 Command·Defect FK 연결
- V24 시작 전 실패 시각 제약 보정
- V25 Robot별 활성 Mission 하나
- 시간 기반 Mission Migration
  - `planned_duration_sec`
  - 기존 Mission 3,600초 Backfill
  - 기존 ACTIVE Area Dummy Route

현재 작업 트리 정본은 Image V31, 시간 Mission V32다. 번호 중복은 없지만
Migration 이동이 아직 미커밋이므로 정식 반영 전 전체 검증이 필요하다.

### 2.2 Backend

```text
PatrolCatalogController
PatrolAreaManagementService
PatrolRouteManagementService
PatrolCatalogQueryService
MissionController
MissionManagementService
MissionQueryService
MissionProgressService
MissionEventIngestionService
```

### 2.3 Frontend

```text
features/mission/missionTypes.ts
features/mission/missionApi.ts
features/mission/MissionManagePage.tsx
features/mission/MissionMap.tsx
features/mission/mission.css
features/mission/mission-map.css
```

화면:

- Mission 생성
- 목록·상세
- 상태 이력
- 계획·경과·남은 시간
- 진행률·ETA·Stale
- Area·Trajectory·현재 위치
- Area 이탈
- Naver SDK 실패 시 SVG Fallback

### 2.4 운영형 Browser E2E

```text
공개 가입
→ 승인·Role·Robot Scope
→ TOTP 등록
→ 실제 로그인·MFA
→ Docker Frontend :3000
→ Backend API
→ Mission POST 201
→ PostgreSQL 계획시간 대조
→ 임시 데이터 정리
```

45분 Mission의 API `plannedDurationSec=2700`과 DB
`planned_duration_sec=2700`을 확인했다.

---

## 3. 설계 판단과 그 이유

### 3.1 Route 선택을 숨기고 내부 Dummy를 유지했다

실제 Robot은 Area 안에서 자율 주행하며 서버 Route Geometry를 명령으로
소비하지 않는다. 하지만 기존 DB·API는 `patrol_route_id NOT NULL`과 FK를 갖는다.

전체 스키마를 깨는 대신:

- 사용자에게 Route를 노출하지 않음
- Area별 내부 Dummy Route 자동 관리
- DB 호환에만 사용
- Robot Payload·지도·진행률에서는 제외

### 3.2 진행률을 GPS Route 투영에서 시간으로 바꿨다

자율 주행은 장애물 회피 때문에 계획 선을 그대로 따르지 않는다. GPS를 Route에
투영하면 정상 우회도 진행률 역행·정지로 보인다. Embedded가 이미 제공하는
단조 증가 `elapsed_sec`를 계획시간과 비교하는 것이 현재 업무 의미에 맞다.

### 3.3 계획시간 도달로 자동 완료하지 않는다

통신 지연·일시정지·Robot 업무 상태를 서버 시계가 알 수 없다. 계획시간 도달은
99.99%까지만 표시하고 Robot 완료 Event가 실제 종결을 확정한다.

### 3.4 PAUSED 중 증가를 현재 계약으로 유지했다

Embedded 값이 Mission 시작 후 벽시계 시간이므로 PAUSED 중에도 증가한다.
서버만 일시정지 제외로 바꾸면 같은 필드의 의미가 양쪽에서 달라진다. 정책 변경은
Embedded 누적 방식과 MQTT Major 계약을 함께 바꾸는 별도 작업이다.

### 3.5 지도 Snapshot을 Mission Permission으로 제공했다

기존 Robot Map은 `ROBOT_STATUS_VIEW`가 필요하다. Mission 화면이 두 Permission에
동시에 의존하지 않도록 Mission Scope 아래 Area·Trajectory·Current Position
Snapshot을 제공한다.

### 3.6 SSE 뒤 Snapshot을 다시 읽는다

Telemetry Event는 “변경됐다”는 알림으로 사용한다. 400ms Debounce 뒤 지도
Snapshot을 다시 읽어 Marker·Polyline을 일관되게 교체한다.

---

## 4. 검증과 재현 명령

Backend:

```powershell
cd web/backend
.\gradlew.bat test --rerun-tasks `
  --tests "com.gilbom.robot.controller.MissionControllerTest" `
  --tests "com.gilbom.robot.integration.MissionManagementIntegrationTest" `
  --tests "com.gilbom.robot.integration.PatrolMissionSchemaIntegrationTest"
```

Frontend:

```powershell
cd web/frontend/gilbom
npm run lint
npm run build
npm run test:e2e:mission
```

운영형 Mission 생성:

```powershell
python web/scripts/mission-time-browser-e2e.py
```

완료 기록:

- Mission 집중 29개 통과
- Backend 전체 546개 통과(당시 Migration 정본 기준)
- Frontend lint·production build
- Chromium Mission Fixture 2개
- 실제 로그인·MFA Mission E2E 1개
- API·DB 계획시간 2700초 일치
- 임시 계정·Robot·Area·Mission 잔존 0건

현재 작업 트리는 V32로 이동했으므로 위 과거 성공을 현재 V32 정본의 성공으로
그대로 재사용하면 안 된다. 빈 DB에서 다시 실행해야 한다.

---

## 5. 작업 중 겪은 함정

### Migration 번호는 작업 시작 때가 아니라 커밋 직전에 확인한다

동시에 다른 팀이 Migration을 만들면 몇 시간 사이에도 번호가 선점된다. 이
작업에서도 과거 Image와 Mission이 모두 V31을 사용한 충돌이 있었다. 현재
작업 트리는 Image V31·Mission V32로 정리되어 번호 중복은 없지만 아직
재검증·커밋 전이다. `Get-ChildItem`로 버전 그룹 중복을 검사하고 빈 DB
Testcontainers를 실행해야 한다.

### PostGIS 좌표의 정확 실수 비교

`127.007`이 Java double에서 `127.00699999999999`로 보일 수 있다. 공간 좌표
Test는 좁은 허용 오차를 사용한다.

### Browser Seed도 운영 등록 불변식을 따라야 한다

Robot만 INSERT하고 `robot_current_state`를 만들지 않으면 목록 Join에서 빠진다.
E2E Fixture는 운영 Robot Registration과 같은 최소 행을 만든다.

### Append-only 이력 때문에 Cleanup이 실패할 수 있다

운영형 E2E의 Mission 이력·감사 삭제는 임시 사용자 Transaction 안에서만
Trigger를 비활성화하고 같은 Transaction에서 복구한다. Cleanup 실패도 E2E
실패로 처리한다.

### Naver SDK 실패와 업무 API 실패를 분리한다

운영 Origin/Key가 없을 때도 SVG Fallback으로 좌표·Layer·이탈 계산을 검증한다.
실제 Tile Smoke Test는 배포 환경 후속으로 남긴다.

---

## 6. 미완 항목

### 내 몫 — P0

- V31 Image·V32 Mission 상태로 빈 DB 전체 Migration 재검증
- Mock Mission Event·자동 완료 코드 최종 검토
- Mock 단위 테스트와 `mission-mock-e2e.py` 재실행
- 실행 로그·DB 결과·Browser 증거·Cleanup을 새 CHANGLOG에 기록
- Migration·Mock·Harness·Test 파일 커밋

### 내 몫 — P1

- 장기 Mission Trajectory Downsampling
- Telemetry History 별도 REST 필요성 결정
- 진행률과 별도의 순찰 Coverage 품질 지표 검토

### 팀원 A

- Naver 운영 Origin·Client ID
- EC2 Broker·Secret·Nginx SSE

### 팀원 C

- V31 이미지 Migration 정본 유지와 이후 번호 선점 사항 공유
- 시연용 ACTIVE Patrol Area 사용
- Incident의 Mission·Area FK 계약 유지

### Embedded팀

- 실제 `elapsed_sec`와 PAUSED 동작 확인
- 완료 Event·Result 값
- 실제 GPS Marker·Trajectory·이탈

---

## 7. 관련 문서와 기록

- `CHANGLOGS/2026-07-30_1214_time-based-mission-contract-ui-e2e.md`
- `CHANGLOGS/2026-07-30_1243_mission-map.md`
- `CHANGLOGS/2026-07-30_1342_time-mission-live-browser-e2e.md`
- `CHANGLOGS/2026-07-30_1413_mission-migration-version.md`
- `TROUBLESHOOTING/7_PatrolAreaRouteConsistency.md`
- `TROUBLESHOOTING/10_TimeBasedMission.md`
- `TROUBLESHOOTING/11_MissionMap.md`
