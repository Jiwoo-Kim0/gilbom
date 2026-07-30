# 변경 로그: 임무 상세 지도 SVG 전환 및 플리커링 제거

- 작성 시각: 2026-07-30 23:03 (Asia/Seoul)
- 작업 유형: 수정
- 상태: 완료

## 작업 요약

임무 관리 오른쪽 상세 Sidebar가 Telemetry 갱신 때마다 로딩 화면으로 교체되면서
지도가 반복 생성되는 플리커링을 제거했다. 임무 위치 지도의 순찰 구역, 실제
궤적, 현재 Robot 위치를 Naver Native Overlay 대신 React SVG Overlay로
렌더링하고, 현재 위치는 연결 상태와 이동 방향을 포함한 Marker로 표시한다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/frontend/gilbom/src/features/mission/MissionManagePage.tsx` | 수정 | 상세 정보가 이미 있으면 SSE 백그라운드 갱신 중에도 기존 상세와 지도를 유지한다. 갱신 실패 시 기존 내용을 제거하지 않고 최신 정보 조회 실패 안내를 함께 표시한다. |
| `web/frontend/gilbom/src/features/mission/MissionMap.tsx` | 수정 | Naver `Polygon`, `Polyline`, `Marker`를 매 Snapshot마다 제거·생성하던 Effect를 지도 투영 좌표 기반 React SVG Polygon·Polyline·Robot Marker로 교체했다. 지도 인스턴스와 최초 Viewport를 같은 Mission 동안 유지하고 크기·Bounds 변경 때 SVG 좌표만 재투영한다. |
| `web/frontend/gilbom/src/features/mission/mission-map.css` | 수정 | 지도 위 SVG Overlay, 투명 Polygon, 궤적, 온라인·오프라인 Robot Marker와 방향 표시를 위한 Layer·색상·그림자 스타일을 추가했다. |
| `web/frontend/gilbom/e2e/mission-map.spec.ts` | 수정 | SSE 갱신 전후 Naver Map 인스턴스 1개 유지, Viewport 재설정 방지, Native Overlay 미생성, SVG Polygon·궤적·Robot Marker 좌표·방향 갱신을 검증한다. |
| `CHANGLOGS/2026-07-30_2303_mission-map-svg-flicker.md` | 추가 | 플리커링 원인, SVG 전환 범위와 검증 결과를 기록했다. |

## 상세 내용

- 기존 상세 Renderer는 `detailLoading`이 참이면 기존 `selectedMission`이 있어도
  전체 상세를 Spinner로 교체했다. 3초 간격 Telemetry SSE마다 `MissionMap`이
  언마운트·재마운트되면서 Naver Map도 반복 생성됐다.
- 상세 최초 진입이나 Mission 선택 변경처럼 표시할 기존 데이터가 없을 때만
  전체 로딩 화면을 사용한다. 백그라운드 갱신은 현재 상세 DOM을 유지한다.
- Naver Map은 배경 Tile과 좌표 투영만 담당하고 Polygon, 실제 Trajectory,
  현재 Robot Marker는 CSP 안전 React SVG로 렌더링한다.
- SVG Robot Marker는 `ONLINE`과 `OFFLINE` 색상을 구분하며 `headingDeg`에 따라
  방향 화살표를 회전한다.
- 동일 Mission의 Telemetry 갱신은 지도 `fitBounds`를 다시 호출하지 않아
  사용자의 Zoom·Viewport와 지도 Tile이 튀지 않는다. 다른 Mission을 선택한
  경우에만 새 임무 전체 좌표 범위로 맞춘다.
- 지도 이동·확대 또는 Container 크기 변경 시 `requestAnimationFrame` 단위로
  SVG 좌표를 다시 계산하며 Overlay는 지도 입력을 가로막지 않는다.
- Backend, MQTT, 인증·권한, CSRF, Nginx 및 기타 보안 설정은 변경하지 않았다.

## 검증

- 실행한 명령:
  - `npx eslint src/features/mission/MissionManagePage.tsx src/features/mission/MissionMap.tsx e2e/mission-map.spec.ts`
  - `npm run build`
  - `npx playwright test e2e/mission-map.spec.ts --output=test-results/mission-map-svg-no-flicker`
  - `npm test`
  - `npx playwright test e2e/mission-map.spec.ts e2e/patrol-area-registration.spec.ts --output=test-results/mission-svg-regression`
  - `git diff --check -- <이번 작업 대상 파일>`
- 결과:
  - ESLint 통과
  - TypeScript 및 Vite 프로덕션 빌드 통과
  - 단위 테스트 37건 통과
  - 단독 Mission Map Chromium E2E 1건 통과
  - Mission Map·순찰 구역 등록 회귀 Chromium E2E 2건 통과
  - E2E에서 SSE 전후 Map 생성 1회·파괴 0회·`fitBounds` 1회 유지 확인
  - E2E에서 Naver Native Polygon·Polyline·Marker 생성 0건과 SVG Layer 갱신 확인
  - 공백 오류 없음
- 미실행 항목과 사유:
  - 실제 사용자 세션 육안 확인은 최신 Frontend Container 재빌드 후 수행한다.

## 영향 및 주의사항

- 오른쪽 상세 Sidebar는 실시간 갱신 중 더 이상 전체 Spinner로 바뀌지 않는다.
- Polygon과 궤적 색상·투명도는 기존 범례와 같은 의미를 유지한다.
- Robot Marker는 최신 `currentPosition`이 있을 때만 표시된다.
- Naver SDK 로딩 실패 시 기존 SVG Fallback 지도를 그대로 사용한다.
- 프로덕션 빌드의 메인 JavaScript Chunk 크기 경고는 기존 경고이며 빌드 실패가
  아니다.

## 미완료 및 후속 작업

- 최신 Frontend Container에서 실제 `RBT-001` Telemetry를 수신하는 동안 오른쪽
  임무 위치 지도의 Tile·SVG Polygon·Robot Marker가 안정적으로 유지되는지
  육안 확인한다.
