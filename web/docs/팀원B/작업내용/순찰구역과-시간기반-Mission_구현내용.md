# Patrol Area·시간 기반 Mission·지도 구현 내용

> 담당: 팀원 B — Patrol·Mission
> 작업일: 2026-07-28 ~ 2026-07-30
> 연결 Jira: `S15P11D101-379`, `S15P11D101-400`, `S15P11D101-404`,
> `S15P11D101-405`, `S15P11D101-406`, `S15P11D101-489`, `S15P11D101-525`
> 최종 상태: **DONE (LOCAL)** — 등록·Mission·Mock E2E 완료, 운영 지도·Jetson 확인 대기

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

`/patrol-areas` 전용 화면에서는 Naver 지도에 경계점을 임의 순서로 찍어도
모든 점을 사용하는 교차 없는 반시계 Polygon으로 정렬해 ACTIVE Area를 등록한다.
Mission 생성에서 등록 구역을 선택하면 경계점 평균점을 중앙으로 표시하고,
현재 임무가 없는 ACTIVE Robot의 위치·방향을 같은 지도에서 확인한다.

Command·MQTT까지 연결한 전체 Mock E2E는 2026-07-30 14:28 Playwright 결과에서
통과했다. START→PAUSE→RESUME→COMPLETED, PAUSED 중 경과시간, 완료 100%,
상태 이력과 실제 궤적을 확인했다.

Mission 상세 지도는 Naver Tile·투영은 유지하고 Polygon·Trajectory·Robot
Marker를 React SVG로 표시한다. Telemetry SSE 갱신 중 기존 상세 DOM과 지도
인스턴스를 유지해 플리커링과 반복 확대를 제거했다.

Migration은 Image V31, 시간 기반 Mission V32로 정리·커밋됐고 빈 임시 DB에
V1~V32를 적용한 Backend 전체 625건과 Mission Mock E2E가 통과했다.

---

## 1. 요구사항과 수용 기준

### 1.1 Patrol Area·Route

- Area 등록·목록·단건·이름/경계 변경·상태 변경
- `/patrol-areas` 전용 관리 Route
- 지도 경계점 입력과 직접 좌표 사각형 대체 입력
- 임의 클릭 순서를 결정적·교차 없는 Polygon 순서로 정규화
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
- 생성 후보는 ACTIVE이면서 현재 임무가 없는 Robot
- 선택 Area Polygon·경계점 평균 중심·선택 Robot 위치 미리보기

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
진행률을 바꾸지 않는다. SSE 갱신은 기존 지도 인스턴스·사용자 Viewport를
유지하고 SVG 좌표만 다시 투영한다.

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

현재 정본은 Image V31, 시간 Mission V32다. V32 이동은 커밋됐고 빈 임시 DB에서
V1~V32 총 32개 Migration의 Version 중복 0과 Backend 정상 기동을 확인했다.

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
features/mission/PatrolAreaManagePage.tsx
features/mission/PatrolAreaBoundaryEditor.tsx
features/mission/PatrolAreaSelectionPreview.tsx
features/mission/patrolAreaBoundary.ts
features/mission/mission.css
features/mission/mission-map.css
features/mission/patrol-area-boundary-editor.css
features/mission/patrol-area-selection-preview.css
```

화면:

- 전용 순찰 구역 관리·Polygon 등록
- 경계점 자동 정렬·취소·삭제·초기화
- Mission 생성
- 선택 Area·미수행 Robot 지도 미리보기
- 목록·상세
- 상태 이력
- 계획·경과·남은 시간
- 진행률·ETA·Stale
- SVG Area·Trajectory·현재 위치·heading
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

### 3.7 Polygon 순서는 결정적 휴리스틱으로 정규화한다

사용자가 점을 임의 순서로 찍어도 같은 점 집합은 같은 결과가 되도록 좌표를
정규화한다. 최근접 순회로 초기 배열을 만들고 2-opt로 교차 간선을 제거한 뒤
반시계 방향과 시작점을 통일한다. 전역 최단 TSP보다 모든 점 보존·교차 제거·
결정성과 Browser 실행 비용을 우선했다.

### 3.8 Native Overlay 대신 CSP 안전 SVG를 사용한다

현재 CSP에서 SDK 동적 스타일 기반 Polygon·Marker가 검게 보이거나 왼쪽 위로
밀렸다. 보안 설정을 바꾸지 않고 Naver 투영 API의 화면 좌표를 React SVG와 외부
CSS에 전달한다. SVG는 `pointer-events: none`으로 지도 클릭·드래그를 막지 않는다.

### 3.9 Mission 갱신 중 지도 인스턴스를 유지한다

SSE마다 상세 전체를 Spinner로 교체하면 지도도 반복 생성되고 Zoom이 튄다. 기존
상세가 있으면 백그라운드 로딩 중에도 DOM과 지도 인스턴스를 유지하고, 새
Snapshot의 SVG 좌표만 갱신한다.

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
- V32 빈 DB 기반 Backend 전체 625개 통과
- Frontend lint·production build
- Chromium Mission Fixture 2개
- 실제 로그인·MFA Mission E2E 1개
- 실제 TLS MQTT·Mock Robot Mission 전체 E2E 1개
- 순찰 구역 등록·Mission 선택 Chromium E2E
- 최신 Frontend 단위 테스트 37개와 지도 회귀 E2E 2개
- API·DB 계획시간 2700초 일치
- 임시 계정·Robot·Area·Mission 잔존 0건
- SSE 전후 Mission Map 생성 1회·파괴 0회·`fitBounds` 1회 유지
- Naver Native Polygon·Polyline·Marker 생성 0건과 SVG 갱신 확인

---

## 5. 작업 중 겪은 함정

### Migration 번호는 작업 시작 때가 아니라 커밋 직전에 확인한다

동시에 다른 팀이 Migration을 만들면 몇 시간 사이에도 번호가 선점된다. 이
작업에서도 과거 Image와 Mission이 모두 V31을 사용한 충돌이 있었다. Image를
V31로 유지하고 Mission을 V32로 이동한 뒤 빈 DB 전체 적용과 Version 중복 검사를
통과시켰다. 이후에도 새 Migration 전에는 최신 번호를 다시 확인해야 한다.

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

### 지도 Overlay 오류를 CSP 완화로 해결하지 않는다

Polygon이 검거나 좌상단에 나타난 원인은 업무 좌표가 아니라 SDK 동적 스타일과
현재 CSP의 결합이었다. Nginx 보안 설정은 유지하고 투영 기반 SVG로 해결했다.

### Robot 위치를 Bounds에 넣으면 Area가 너무 작아진다

선택 Robot이 Area에서 멀리 있으면 전국 단위로 축소돼 등록 구역이 보이지 않는다.
Mission 생성 미리보기의 Bounds는 Area만 사용하고 Robot GPS는 Marker·텍스트
정보로만 표시한다.

---

## 6. 미완 항목

### 내 몫 — P0

- 최신 Frontend Container에서 실제 Naver Tile·SVG Polygon·Robot Marker 육안 확인
- 실제 Robot Telemetry 수신 중 Mission 상세 지도 플리커링·Viewport 유지 확인
- 시연용 ACTIVE Area의 `areaCode`와 Embedded Incident `zone_id` 대조

### 내 몫 — P1

- 장기 Mission Trajectory Downsampling
- Telemetry History 별도 REST 필요성 결정
- 진행률과 별도의 순찰 Coverage 품질 지표 검토
- 복잡한 오목 구역이 필요하면 점 이동·간선 수동 수정 UX 검토

### 팀원 A

- Naver 운영 Origin·Client ID
- EC2 Broker·Secret·Nginx SSE

### 팀원 C

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
- `CHANGLOGS/2026-07-30_1449_mission-mock-e2e.md`
- `CHANGLOGS/2026-07-30_2006_patrol-area-page-mission-selection.md`
- `CHANGLOGS/2026-07-30_2025_mission-area-selection-preview.md`
- `CHANGLOGS/2026-07-30_2049_patrol-area-polygon-ordering.md`
- `CHANGLOGS/2026-07-30_2122_patrol-area-csp-safe-overlay.md`
- `CHANGLOGS/2026-07-30_2139_mission-create-idle-robot-preview.md`
- `CHANGLOGS/2026-07-30_2303_mission-map-svg-flicker.md`
- Commit `acc4e26` ~ `85986b5` — `S15P11D101-525` 순찰 구역·Mission 생성 보강
- Commit `79591ce` — Mission 상세 SVG 지도·플리커링 제거
- `TROUBLESHOOTING/7_PatrolAreaRouteConsistency.md`
- `TROUBLESHOOTING/10_TimeBasedMission.md`
- `TROUBLESHOOTING/11_MissionMap.md`
- `TROUBLESHOOTING/12_MissionMockE2E.md`
