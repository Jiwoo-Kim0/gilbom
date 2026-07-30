# Mission 구역·실제 궤적·현재 위치 지도 트러블슈팅

- 대상 Jira: `S15P11D101-471`
- 내부 작업: `DAY6-B-00-7`
- 작성일: 2026-07-30 (Asia/Seoul)
- 상태: 로컬 구현·계약·자동 검증 완료

## 1. 목표와 완료 범위

Mission 상세 화면 한 곳에서 다음 정보를 같은 좌표계로 표시한다.

1. Mission에 지정된 방범 Area의 Polygon 또는 MultiPolygon
2. Mission 수행 중 저장된 유효 GPS Telemetry의 실제 Trajectory
3. Mission 담당 Robot의 최신 유효 현재 위치와 이동 방향
4. 현재 위치가 Area 밖일 때의 별도 경고
5. `ROBOT_TELEMETRY_UPDATED` SSE 수신 후 새로고침 없는 Snapshot 갱신

시간 기반 Mission 계약에서 `AUTONOMOUS_DEFAULT` Route는 DB·API FK 호환용 내부
Dummy다. 따라서 이번 지도는 Dummy Route Geometry를 조회하거나 표시하지 않고,
실제 이동 이력과 현재 위치만 표시한다. 지도 좌표와 영역 이탈 여부는 Mission
진행률 계산에도 사용하지 않는다.

## 2. Naver Maps 무료 사용 판단

Naver Cloud Platform의 2026-07-30 공식 가격표 기준 Dynamic Map은 대표 계정
기준 월 6,000,000건 이하 무료 구간이 있다. 이번 기능은 JavaScript 지도와
`Polygon`, `Polyline`, `Marker`만 사용하므로 별도 Geocoding API가 필요 없다.

- 가격표: <https://www.ncloud.com/api-cms/service-product/static/maps>
- JavaScript API: <https://navermaps.github.io/maps.js.ncp/docs/>
- 인증 파라미터: <https://navermaps.github.io/maps.js.ncp/docs/tutorial-2-Getting-Started.html>

운영에서는 Naver Cloud Console의 Web Dynamic Map 사용 설정, `ncpKeyId`, 서비스
Domain 등록이 필요하다. 무료 구간은 무제한 무료를 뜻하지 않으므로 호출량과
가격 정책은 배포 전 다시 확인한다.

## 3. 구현 판단

### 3.1 Mission 전용 지도 Snapshot API

기존 `/api/v1/robots/map`은 `ROBOT_STATUS_VIEW` 권한을 사용한다. Mission 화면에서
별도의 Robot 관제 권한을 요구하면 `MISSION_VIEW`만 가진 사용자가 자신의 Mission
지도를 볼 수 없는 권한 불일치가 생긴다.

이를 피하기 위해 다음 Mission 전용 API를 추가했다.

```http
GET /api/v1/missions/{missionId}/map?limit=5000
Permission: MISSION_VIEW
```

응답은 아래 정본을 한 Snapshot으로 묶는다.

- `patrolAreaBoundary`: Mission이 참조하는 Area의 SRID 4326 GeoJSON
- `trajectory`: 해당 Mission에 연결된 유효 GPS Telemetry를 시간순으로 조회한 값
- `currentPosition`: 담당 Robot의 `robot_current_state` 최신 유효 위치

Mission과 Robot은 기존 사용자 객체 범위 SQL을 그대로 적용한다. 범위 밖
Mission은 존재하지 않는 Mission과 동일하게 처리한다.

### 3.2 Frontend Layer

Naver Maps SDK가 준비되면 다음 객체를 생성한다.

- `naver.maps.Polygon`: 지정 방범 Area
- `naver.maps.Polyline`: 실제 Trajectory
- `naver.maps.Marker`: 현재 위치와 heading
- `naver.maps.LatLngBounds`: Area·Trajectory·Marker 전체가 보이도록 Viewport 조정

GeoJSON 입력은 `[longitude, latitude]`이고 Naver `LatLng` 생성자는
`(latitude, longitude)`이므로 Adapter에서 순서를 명시적으로 바꾼다.

SDK Key가 없거나 Script 로드가 실패하면 동일 Snapshot을 SVG 좌표 Layer로
표시한다. 외부 지도 장애 중에도 Area·Trajectory·현재 위치와 영역 이탈 여부를
확인할 수 있지만, 이 Fallback은 배경 지도 자체를 제공하지 않는다.

### 3.3 실시간 갱신과 영역 이탈

기존 `useRobotRealtime` SSE Hook을 재사용한다. `ROBOT_TELEMETRY_UPDATED`를 받으면
400ms Debounce 뒤 Mission 지도 Snapshot을 다시 읽는다. 새 Snapshot 적용 시
기존 Naver Layer를 제거하고 새 Polygon·Polyline·Marker를 생성해 중복 Overlay를
남기지 않는다.

영역 이탈은 Frontend의 Point-in-Polygon으로 계산한다.

- Polygon의 바깥 Ring 안이고 Hole 안이 아니면 내부
- MultiPolygon 중 하나라도 내부면 전체 Area 내부
- 현재 위치가 없으면 경고를 계산하지 않음

이 값은 화면 경고만 제어하며 `elapsed_sec / planned_duration_sec` 시간 진행률과
혼합하지 않는다.

## 4. 변경 파일과 수행 작업

| 파일 | 수행 작업 |
| --- | --- |
| `web/backend/src/main/java/com/gilbom/robot/api/MissionController.java` | `GET /{missionId}/map`, `MISSION_VIEW`, Trajectory 최대 5,000개 제한을 추가했다. |
| `web/backend/src/main/java/com/gilbom/robot/data/api/response/MissionDtos.java` | 지도 Snapshot과 현재 위치 DTO를 추가했다. |
| `web/backend/src/main/java/com/gilbom/robot/mission/MissionQueryService.java` | 객체 범위 안에서 Area GeoJSON·현재 상태·실제 Trajectory를 조회하는 `findMap`을 추가했다. |
| `web/backend/src/test/java/com/gilbom/robot/controller/MissionControllerTest.java` | 지도 Endpoint 응답과 `MISSION_VIEW` 호출을 검증했다. |
| `web/backend/src/test/java/com/gilbom/robot/integration/MissionManagementIntegrationTest.java` | 실제 PostGIS Area, 두 Telemetry 좌표, Current State와 객체 범위를 검증했다. |
| `web/docs/openapi-v1.yaml` | 지도 Path, Polygon/MultiPolygon, 현재 위치와 Snapshot Schema를 추가했다. |
| `web/docs/authorization-matrix.md` | Mission 지도 조회의 `MISSION_VIEW` 권한을 추가했다. |
| `web/frontend/gilbom/src/features/mission/MissionMap.tsx` | Naver Polygon·Polyline·Marker, SVG Fallback, SSE 재조회, Point-in-Polygon 경고를 구현했다. |
| `web/frontend/gilbom/src/features/mission/MissionManagePage.tsx` | 선택 Mission 상세에 `MissionMap`을 연결했다. |
| `web/frontend/gilbom/src/features/mission/missionApi.ts` | Mission 지도 Snapshot Client를 추가했다. |
| `web/frontend/gilbom/src/features/mission/missionTypes.ts` | GeoJSON Polygon/MultiPolygon, Trajectory, 현재 위치와 Snapshot Type을 추가했다. |
| `web/frontend/gilbom/src/features/mission/mission-map.css` | 지도 Card, Canvas, Layer 범례, Fallback, 영역 이탈 경고와 반응형 Style을 지도 전용 파일로 추가했다. |
| `web/frontend/gilbom/src/types/naver-maps.d.ts` | `LatLngBounds`, `fitBounds`, `Polygon`, `Polyline` Type 선언을 추가했다. |
| `web/frontend/gilbom/e2e/mission-time.spec.ts` | 시간 Mission 회귀에서 지도 API Fixture와 Naver Script 실패 시 Fallback을 추가했다. |
| `web/frontend/gilbom/e2e/mission-map.spec.ts` | 실제 Chromium에서 Naver SDK Adapter Layer 수, SSE 갱신, Marker 이동, 영역 이탈과 Dummy Route 비노출을 검증했다. |
| `web/frontend/gilbom/package.json` | Mission E2E Script가 시간 계약과 지도 Spec을 함께 실행하도록 확장했다. |
| `DailyLog/2026-07-30_JIRA_Day6_작업.md` | `S15P11D101-471`, `DAY6-B-00-7`을 완료로 갱신하고 검증 근거를 기록했다. |
| `DailyLog/2026-07-30_Day6_상세_구현.md` | 구현 결과, 검증 수치와 운영 Naver Origin 후속 범위를 기록했다. |
| `TROUBLESHOOTING/11_MissionMap.md` | 전체 구현 판단, 수행 작업, 문제와 해결, 검증 결과를 기록했다. |

이번 작업 시작 전부터 같은 파일에 있던 시간 기반 Mission 변경과 다른 사용자의
변경은 되돌리지 않았다. 위 표는 이번 작업에서 직접 추가·수정한 지도 범위만
설명한다.

## 5. 발생한 문제와 해결

### 5.1 PostGIS 좌표의 부동소수점 표현 차이

- 현상: 집중 통합 테스트에서 기대값 `127.007`과 JDBC 반환값
  `127.00699999999999`가 정확 비교에서 달라 1건 실패했다.
- 원인: PostGIS `double precision` 좌표를 Java `double`로 변환할 때 생기는
  정상적인 IEEE 754 표현 차이다. 지도 데이터 오류가 아니다.
- 해결: 좌표의 의미를 훼손하지 않는 매우 좁은 허용 오차 범위로 Assertion을
  변경했다.
- 재발 방지: 공간 좌표는 문자열 또는 `double` 완전 일치 대신 명시적 허용 오차로
  검증한다.

### 5.2 OpenAPI 검증기의 잘못된 Path 기대값

- 현상: 첫 Python 검증에서 `missing path: /missions/{missionId}/map`이 발생했다.
- 원인: 실제 OpenAPI Path는 공통 접두사를 포함한
  `/api/v1/missions/{missionId}/map`인데, 임시 검증 Script에서 `/api/v1`을
  빠뜨렸다.
- 해결: 검증 대상 Path를 실제 계약과 동일하게 고친 뒤 YAML Parse, 필수 Schema,
  전체 로컬 `$ref` 600개를 다시 검증했다.
- 판정: 계약 파일 결함이 아니라 검증 명령 작성 오류다.

### 5.3 전체 Backend 테스트 실행 Timeout

- 현상: 첫 전체 테스트 실행 명령이 1초 제한으로 종료됐다.
- 원인: Tool의 초기 Timeout을 전체 Testcontainers 회귀 시간보다 지나치게 짧게
  지정했다.
- 해결: 동일한 `.\gradlew.bat test --rerun-tasks`를 600초 제한으로 재실행했다.
- 결과: 186.2초, 546건, 실패 0, Skip 0, `BUILD SUCCESSFUL`.

### 5.4 Spring Session 종료 정리 로그

- 현상: 전체 회귀 종료 단계에서 Spring Session Cleanup Thread가 닫힌
  Testcontainers PostgreSQL Connection을 가져오려 하며
  `CannotCreateTransactionException`, `Interrupted during connection acquisition`
  로그를 출력했다.
- 원인: Test ApplicationContext·Container 종료와 비동기 Session Cleanup
  Schedule이 겹치는 기존 종료 순서 문제다.
- 판정: Gradle Exit Code는 0이고 XML/요약은 546건, 실패 0, Skip 0이므로 이번
  Mission 지도 기능 실패가 아니다.
- 후속: 테스트 로그 가독성이 필요하면 Test Profile에서 Session Cleanup
  Schedule을 끄거나 종료 순서를 별도 정리한다.

### 5.5 운영 Naver Origin을 자동 검증하지 못한 범위

- 현상: 자동 E2E에서는 외부 Naver Server를 직접 호출하지 않았다.
- 원인: Naver Web Dynamic Map은 등록 Domain과 `ncpKeyId` 조합에 의존하며,
  CI·로컬의 외부 Network와 Origin 등록 상태에 따라 결과가 달라진다.
- 해결: Chromium 안에 Naver SDK와 같은 생성자 계약을 가진 결정적 Adapter를
  주입해 Polygon·Polyline·Marker 생성, Layer 정리, SSE 뒤 Marker 이동을
  검증했다. 별도 Spec에서는 Script 실패를 강제해 SVG Fallback도 검증했다.
- 후속: 배포 Domain을 Naver Console에 등록한 뒤 실제 운영 Key로 지도 Tile과
  Overlay가 함께 보이는지 Smoke Test한다.

## 6. 검증 결과

### Backend 집중 검증

```powershell
.\gradlew.bat test --rerun-tasks `
  --tests "com.gilbom.robot.controller.MissionControllerTest" `
  --tests "com.gilbom.robot.integration.MissionManagementIntegrationTest"
```

- 결과: 19건 통과, 실패 0, Skip 0

### Backend 전체 회귀

```powershell
.\gradlew.bat test --rerun-tasks
```

- 결과: 546건 통과, 실패 0, Skip 0
- Gradle: `BUILD SUCCESSFUL`

### Frontend 정적 검증·Build

```powershell
npm run lint -- --no-cache
npm run build
```

- ESLint 통과
- TypeScript·Vite Production Build 통과
- 1,895개 Module 변환

### Chromium Browser E2E

```powershell
npm run test:e2e:mission -- --output=test-results/mission-map-work
```

- 결과: 2건 통과
- 시간 Mission 화면과 Dummy Route 비노출
- Naver SDK Adapter의 Polygon 1, Polyline 1, Marker 1
- `[longitude,latitude]` → `LatLng(latitude,longitude)` 변환
- SSE 뒤 Trajectory 3→4개, Marker 이동
- 현재 위치의 Area 이탈 경고
- Naver Script 실패 시 SVG Fallback

### OpenAPI와 Diff

- PyYAML Parse 통과
- `/api/v1/missions/{missionId}/map` 존재 확인
- `MissionMapResponse`, `MissionMapCurrentPosition`,
  `GeoJsonPolygonOrMultiPolygon` 존재 확인
- 로컬 `$ref` 600개 해석 통과
- `git diff --check` 오류 없음

## 7. 완료 판정과 후속

`S15P11D101-471`, `DAY6-B-00-7`의 로컬 구현·계약·Backend·Frontend·Browser
완료 기준은 충족했다.

남은 외부 검증은 기능 구현 미완료가 아니라 배포 환경 확인 범위다.

1. 운영 Domain을 Naver Web Service URL에 등록한다.
2. 운영 `VITE_NAVER_MAP_CLIENT_ID`의 `ncpKeyId` Script 로드를 확인한다.
3. 실제 Naver Tile 위에 Area·Trajectory·Marker가 표시되는지 Smoke Test한다.
4. 실제 Jetson GPS로 Marker 이동과 Area 이탈 경고를 Day 7 통합에서 확인한다.
