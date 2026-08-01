# 변경 로그: Mission 지도 이동 시 SVG Overlay 동기화

- 작성 시각: 2026-08-01 21:53 (Asia/Seoul)
- 작업 유형: 수정
- 상태: 완료

## 작업 요약

Mission 상세 Naver 지도를 드래그할 때 지도 Tile만 이동하고 순찰 구역 SVG,
실제 궤적과 Robot 현재 위치 Marker가 화면 좌표에 남는 문제를 수정했다. 기존
크기·경계 변경 Listener에 사용자 `drag`, `dragend`와 최종 `idle` 재투영을
추가하고, 지도 이동 전후 Polygon Path와 Robot Marker 좌표가 함께 변하는
Browser 회귀 검증을 추가했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/frontend/gilbom/src/features/mission/MissionMap.tsx` | 수정 | Naver 지도 `drag` 중 매 Animation Frame에 SVG 좌표 갱신을 예약하고 `dragend`·`idle`에서 최종 좌표를 보정하도록 Viewport Listener를 확장했다. |
| `web/frontend/gilbom/src/types/naver-maps.d.ts` | 수정 | Mission 지도에서 사용하는 Naver Map Event Type에 `drag`와 `dragend`를 추가했다. |
| `web/frontend/gilbom/e2e/mission-map.spec.ts` | 수정 | 가짜 Naver Projection과 Event Registry에 지도 이동을 모사하는 기능을 추가하고, 이동 뒤 Area `path d`와 Robot Marker `transform`이 모두 갱신되는지 검증했다. |
| `CHANGLOGS/2026-08-01_2153_mission-map-drag-overlay.md` | 추가 | 원인, 변경 범위, 검증 결과와 별도 E2E 실패를 기록했다. |

## 상세 내용

- Mission Map의 Polygon·Trajectory·Robot Marker는 모두 Naver Native Overlay가
  아닌 화면 전체 React SVG Layer에 그려진다.
- 기존 구현은 `bounds_changed`와 `size_changed`만 구독해 실제 사용자 Drag
  Frame에서 SVG 좌표 재투영이 보장되지 않았다.
- Naver Map의 `drag` Event마다 기존 `requestAnimationFrame` Throttle을 사용해
  Polygon·Trajectory·Marker를 같은 Projection Snapshot으로 갱신한다.
- `dragend`와 `idle`에서도 한 번 더 갱신해 마지막 Pointer 이동 또는 관성 이동 뒤
  지도 Tile과 SVG 좌표가 어긋나지 않게 했다.
- 기존 Mission별 최초 `fitBounds` 1회와 SSE 갱신 중 Map Instance·사용자
  Viewport 유지 정책은 변경하지 않았다.

## 검증

- 실행한 명령:
  - `npx eslint src/features/mission/MissionMap.tsx src/types/naver-maps.d.ts e2e/mission-map.spec.ts`
  - `npm run build`
  - `npx playwright test e2e/mission-map.spec.ts --output=test-results/mission-map-drag-overlay`
  - `npm test`
  - `npm run test:e2e:mission -- --output=test-results/mission-drag-overlay-regression`
  - `npx playwright test e2e/mission-time.spec.ts --output=test-results/mission-time-drag-overlay-check`
  - `git diff --check -- <이번 작업 대상 파일>`
- 결과:
  - 대상 ESLint 성공
  - TypeScript·Vite Production Build 성공
  - Mission Map Chromium E2E 1건 통과: 지도 이동 뒤 순찰 구역 SVG와 Robot
    Marker 좌표가 모두 변경됨
  - Frontend 단위 테스트 38건 통과, 실패 0
  - 전체 Mission E2E 3건 중 Mission Map·순찰 구역 등록 2건 통과
  - 대상 파일 whitespace 오류 없음
- 미실행 항목과 사유:
  - 운영 Naver Origin·실제 Mouse Drag 육안 검증은 배포된 최신 Frontend Image가
    필요해 Local 자동 검증에서 수행하지 않았다.
  - 전체 Mission E2E의 `mission-time.spec.ts` 1건은 `MissionManagePage`가 Catalog
    Collection을 `undefined`로 받아 Render 중단되는 별도 오류로 실패했다. 단독
    재실행에서도 동일하게 실패했으며, 이번에 변경한 Mission Map 단독 E2E는
    통과했다.

## 영향 및 주의사항

- 순찰 구역, 실제 궤적과 Robot 현재 위치 Marker는 동일한 Projection Snapshot을
  사용하므로 지도 이동 시 함께 이동한다.
- `requestAnimationFrame`으로 같은 Browser Frame의 연속 Drag Event를 하나로
  합쳐 불필요한 React State 갱신을 제한한다.
- Naver SDK 실패 시 사용하는 고정 좌표 SVG Fallback에는 이동 가능한 Base Map이
  없어 이번 변경의 영향을 받지 않는다.
- 실제 운영 반영에는 새 Frontend Image Build와 재배포가 필요하다.

## 미완료 및 후속 작업

1. 최신 Frontend Image를 EC2 Day 8 환경에 배포한 뒤 실제 Naver 지도에서 Mouse와
   Touch Drag 중 Area·Trajectory·Robot Marker가 Tile과 함께 이동하는지 확인한다.
2. 별도 `mission-time.spec.ts` Catalog Fixture/응답 처리 오류를 독립 작업으로
   진단해 전체 Mission E2E 3건을 다시 Green으로 만든다.
