# 변경 로그: Mission 지도 부착 Overlay와 원래 임무지 Viewport

- 작성 시각: 2026-08-01 23:08 (Asia/Seoul)
- 작업 유형: 수정
- 상태: 완료

## 작업 요약

Mission 지도 위 React SVG가 지도 Drag보다 늦게 재투영되어 창에 붙은 것처럼 따로
움직이는 문제를 구조적으로 수정했다. Overlay를 지도 바깥 React Layer에 두지 않고
Naver `OverlayView`의 `overlayLayer` 안에 한 번만 부착한다. 순찰 구역, 실제 궤적과
Robot Marker는 서버가 제공한 GPS 위도·경도를 원본으로 유지하고, 지도 이동·확대 시
Naver가 호출하는 Overlay `draw()`에서 같은 Projection으로 갱신한다. 최초 Viewport는
Trajectory나 구역 밖 Robot 위치가 아니라 사용자가 지정한 원래 순찰 구역 전체에
맞춘다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/frontend/gilbom/src/features/mission/MissionMap.tsx` | 수정 | 외부 React SVG와 Viewport Event 추적을 제거하고 Naver `OverlayView` 내부에 Map-attached SVG를 생성했다. GPS 기반 Area·Trajectory·Marker를 같은 Projection으로 직접 갱신하고, 최초 Center·`fitBounds`는 Patrol Area Boundary만 우선 사용한다. |
| `web/frontend/gilbom/src/features/mission/mission-map.css` | 수정 | Naver Overlay Pane 내부 SVG가 외부 CSS만으로 전체 Map Viewport를 덮도록 `mission-map-native-overlay` Layer 스타일을 추가하고, 제거된 외부 Projection Layer 스타일을 정리했다. |
| `web/frontend/gilbom/src/types/naver-maps.d.ts` | 수정 | `OverlayView`, `MapPanes`와 Overlay 갱신에 필요한 Naver Map Type을 추가했다. |
| `web/frontend/gilbom/e2e/mission-map.spec.ts` | 수정 | 가짜 Naver `OverlayView` Lifecycle·Pane·Projection을 구현하고 Drag·Zoom에서 Area Path와 Marker가 함께 이동하며 SSE 중 Map·Overlay가 재생성되지 않는지 검증했다. |
| `CHANGLOGS/2026-08-01_2308_mission-map-attached-overlay.md` | 추가 | 외부 SVG 방식의 한계, Map-attached 구현, 실제 SDK·CSP 검증 결과와 운영 후속 작업을 기록했다. |

## 상세 내용

- `CHANGLOGS/2026-08-01_2241_mission-map-viewport-zoom.md`까지의 구현은 Map
  Container와 형제 관계인 React SVG를 사용했다. `drag` Event마다 좌표를 다시
  계산해도 Map Tile의 내부 Pane 이동과 React Render Frame이 달라 사용자가 빠르게
  끌 때 Overlay가 화면에 붙어 보일 수 있었다.
- 새 Overlay SVG는 `OverlayView.onAdd()`에서 Naver Map의 `overlayLayer`에 직접
  들어간다. Drag 중에는 Naver Pane 자체가 Tile과 함께 이동하고, Zoom·Idle에서는
  Naver가 `draw()`를 호출해 최신 화면 좌표로 보정한다.
- 순찰 구역과 궤적의 GPS 값은 확대 비율로 변환하거나 다시 생성하지 않는다.
  `LatLng → fromCoordToOffset()` 화면 투영만 현재 Map Center·Zoom에 맞춰 바뀐다.
- Telemetry Snapshot 갱신 시 같은 Overlay Instance에 새 GPS Data만 전달한다.
  Map과 Overlay를 삭제·재생성하지 않아 2026-07-30 Native Overlay 구현의 SSE
  플리커링 문제를 되살리지 않는다.
- 최초 화면 좌표는 Patrol Area Boundary가 있으면 그것만 사용한다. Robot이 구역
  밖에 있거나 오래된 궤적이 멀리 있어도 원래 임무 구역이 작아지지 않고 전체
  화면에 맞춰진다. Boundary가 없을 때만 Trajectory·Current Position을 대체값으로
  사용한다.
- Naver 기본 `Polygon`은 운영과 같은 `style-src 'self'` CSP 재현에서 Inline Style이
  차단되어 Area가 사라질 수 있었다. Map-attached SVG는 Self CSS Class와 SVG
  Attribute를 사용해 Nginx CSP 완화 없이 Area·Trajectory·Marker를 표시한다.
- Nginx, Compose, Backend, DB, MQTT와 EC2 설정은 변경하지 않았다.

## 검증

- 실행한 명령:
  - `npx eslint src/features/mission/MissionMap.tsx src/types/naver-maps.d.ts e2e/mission-map.spec.ts`
  - `npm test`
  - `npm run build`
  - `npm run test:e2e:mission`
  - 실제 Naver SDK Local Playwright Drag·Zoom 진단 (`http://localhost:3000/missions`)
  - `style-src 'self'` CSP Standalone Naver `OverlayView` Browser 진단
  - `git diff --check -- <이번 작업 대상 파일>`
  - `git diff -- web/frontend/nginx/server-common.conf web/frontend/nginx/site-http.conf web/frontend/nginx/site-https.conf`
- 결과:
  - 대상 TypeScript ESLint 성공
  - Frontend 단위 테스트 38건 통과, 실패 0
  - TypeScript·Vite Production Build 성공
  - 전체 Mission Chromium E2E 3건 통과, 실패 0
  - 실제 Naver SDK에서 Map 내부 Overlay 부착 확인, 외부 React Projection Layer 0개
  - 최초 320×300 Viewport에서 Area x=43.49~276.51, y=4.10~295.89로 원래
    순찰 구역 전체 표시, Zoom Control 2개와 Tile 22개 표시
  - Zoom 후 Area 크기 233.02×291.79 → 466.04×583.58, Marker도 동일 GPS
    Projection으로 이동
  - 실제 Drag 후 Area와 Marker 화면 좌표가 모두 정확히 x +64px, y +45px 이동
  - `style-src 'self'` CSP 재현에서 Area Stroke `rgb(23, 92, 76)`, Fill
    `rgba(47, 128, 108, 0.1)`와 30×30 Marker 정상 표시
  - 대상 파일 whitespace 오류 없음, Nginx 3개 파일 Diff 없음
- 미실행 항목과 사유:
  - EC2 HTTPS 배포는 수행하지 않았다. 최신 Frontend Image 배포 권한과 Day 8
    배포 절차가 필요한 후속 작업이다.

## 영향 및 주의사항

- 지도 이동 중 Area·Trajectory·Marker가 React Render를 기다리지 않고 Naver Map
  Pane과 함께 움직인다.
- SSE 갱신은 GPS Data와 SVG Attribute만 바꾸므로 사용자가 선택한 Center·Zoom을
  초기화하지 않는다.
- Robot이 Patrol Area 밖에 있으면 최초 화면에서 Marker가 보이지 않을 수 있으며,
  기존 Area 이탈 경고가 이를 알린다. 이는 원래 임무지를 우선 표시한다는 정책이다.
- Production Build의 500kB 초과 Chunk 경고는 남지만 Build는 성공했으며 지도
  동작과 무관한 기존 최적화 항목이다.
- Local은 Docker Frontend 대신 Vite `http://localhost:3000`이 실행 중이다.

## 미완료 및 후속 작업

1. 최신 Frontend Image를 Day 8 EC2 HTTPS 환경에 배포한다.
2. 실제 Mission 상세에서 Mouse Drag와 +/- Zoom을 사용해 순찰 구역·궤적·Robot
   Marker가 Tile과 함께 움직이는지 육안 확인한다.
3. 실제 Jetson GPS Telemetry 수신 중 Overlay Instance와 사용자 Viewport가 유지되는지
   확인한다.
