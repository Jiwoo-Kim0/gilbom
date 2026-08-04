# 변경 로그: 순찰 구역 드래그 후 GPS 핀 좌표 정합화

- 작성 시각: 2026-08-04 18:07 (Asia/Seoul)
- 작업 유형: 수정
- 상태: 완료

## 작업 요약

순찰 구역 등록 지도에서 지도를 드래그한 뒤 경계점을 찍으면 지도 타일과 점·Polygon이 어긋날 수 있던 문제를 수정했다. 클릭 이벤트가 제공한 GPS를 `[경도, 위도]`로 저장하고, 동일 GPS를 Naver `OverlayView` 내부 SVG에 다시 투영해 핀이 클릭 위치에 표시되도록 변경했다. 드래그 후 클릭과 추가 드래그를 재현하는 Browser 회귀 테스트도 추가했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/frontend/gilbom/src/features/mission/PatrolAreaBoundaryEditor.tsx` | 수정 | 지도 바깥 React SVG를 `OverlayView.overlayLayer`에 부착되는 `MapAttachedBoundaryOverlay`로 교체했다. 클릭 `event.coord`의 GPS를 소수점 7자리로 저장하고 동일 좌표를 `fromCoordToOffset`으로 투영하며, `drag`·`panning`·`bounds_changed`·`dragend`·`idle` 등 Viewport 이벤트에서 Frame당 한 번 다시 그리도록 했다. |
| `web/frontend/gilbom/src/features/mission/patrol-area-boundary-editor.css` | 수정 | 지도 내부 Overlay SVG가 이동 중 화면 밖 벡터 형상을 보존하도록 내부 `overflow`를 `visible`로 변경했다. 상위 지도 Wrapper의 클리핑은 유지한다. |
| `web/frontend/gilbom/e2e/patrol-area-registration.spec.ts` | 수정 | Fake Naver SDK에 `OverlayView`, 지도 Pane, 드래그 오프셋, 화면 좌표↔GPS 역변환을 구현했다. 지도 이동 후 클릭 GPS와 핀 픽셀 좌표가 일치하고 추가 이동 후 같은 GPS 핀이 새 투영 위치로 이동하는지 검증한다. |
| `CHANGLOGS/2026-08-04_1807_patrol-area-drag-coordinate.md` | 추가 | 원인, 변경 범위, 검증 결과와 로컬 수동 확인 항목을 기록했다. |

## 상세 내용

- 기존 SVG는 Naver 지도 Container의 형제 React Layer라 지도 Pane의 이동 시점과 React 좌표 갱신 시점이 달랐다. `bounds_changed`만으로는 Drag 중·직후의 최종 투영 상태를 항상 맞출 수 없었다.
- 새 SVG는 `OverlayView.onAdd()`에서 Naver `overlayLayer`에 직접 부착된다. 점, 선, Polygon은 모두 저장된 GPS를 정본으로 삼고 `OverlayView.getProjection().fromCoordToOffset()` 결과만 화면 좌표로 사용한다.
- 지도 클릭 시 Naver가 전달한 `event.coord.lng()`와 `event.coord.lat()`을 각각 경도와 위도로 저장한다. 화면 클릭 픽셀에서 GPS를 별도로 추정하지 않으므로 드래그된 Viewport에서도 SDK가 판정한 실제 클릭 좌표가 등록된다.
- 각 점 SVG Group에 `data-longitude`, `data-latitude`를 넣어 테스트가 저장 GPS와 표시점을 직접 대조한다.
- 연속 Drag 이벤트는 `requestAnimationFrame`으로 합쳐 Frame당 한 번만 Overlay를 다시 그린다. `dragend`와 `idle`에서 최종 위치도 보정한다.
- 클릭 직후 `pointsRef`와 Overlay를 함께 갱신해 빠르게 연속 클릭해도 이전 점을 잃지 않는다.
- 등록 API의 GeoJSON 순서 `[longitude, latitude]`, 점 자동 정렬, Ring 닫기, 줌 17 고정 정책은 변경하지 않았다.

## 검증

- 실행한 명령:
  - `npx eslint src/features/mission/PatrolAreaBoundaryEditor.tsx src/types/naver-maps.d.ts e2e/patrol-area-registration.spec.ts`
  - `npm test`
  - `npm run build`
  - `npx playwright test e2e/patrol-area-registration.spec.ts --output=test-results/patrol-area-drag-coordinate`
  - `npm run test:e2e:mission -- --output=test-results/patrol-area-drag-coordinate-regression`
  - `git diff --check -- web/frontend/gilbom/src/features/mission/PatrolAreaBoundaryEditor.tsx web/frontend/gilbom/src/features/mission/patrol-area-boundary-editor.css web/frontend/gilbom/e2e/patrol-area-registration.spec.ts`
- 결과:
  - 대상 ESLint 통과
  - Frontend 단위 테스트 44개 통과, 실패 0
  - TypeScript·Vite Production Build 통과, 2,509개 Module 변환
  - 순찰 구역 등록 Browser E2E 1개 통과
  - 전체 Mission Browser E2E 3개 통과, 실패 0
  - 드래그 `(40px, 30px)` 후 GPS `(경도 128.417, 위도 36.108)`를 화면 `(240px, 130px)`에서 클릭했을 때 같은 픽셀에 핀이 표시됨을 확인했다.
  - 추가 드래그 `(20px, -10px)` 후 동일 GPS 핀이 새 투영 좌표 `(260px, 120px)`로 이동하고 등록 GeoJSON 값은 유지됨을 확인했다.
  - 대상 파일 whitespace 오류 없음
  - Build의 500kB 초과 Chunk 경고는 기존과 같이 남지만 Build는 성공했다.
- 미실행 항목과 사유:
  - 실제 Naver 지도 SDK와 Tile을 사용하는 로컬 수동 Drag·Click 육안 검증은 사용자가 이후 로컬에서 확인하기로 해 이번 자동 검증에서 실행하지 않았다.
  - Docker Frontend Image 재빌드·Container 재생성과 운영 배포는 실행 상태를 변경하므로 이번 소스 수정 범위에 포함하지 않았다.

## 영향 및 주의사항

- 순찰 구역 등록 화면의 점·Polyline·Polygon만 변경되며 Backend, DB, GeoJSON API 계약과 다른 지도 화면에는 영향이 없다.
- 로컬에서 확인하려면 최신 소스로 Frontend 개발 서버를 다시 시작하거나 Docker Frontend Image를 재빌드해야 한다.
- 표시점의 중심은 클릭 GPS의 지도 투영 위치와 일치한다. 실제 GPS 측정 오차가 아니라 관리자 지도 클릭 좌표를 다루는 기능이다.
- 작업 시작 전부터 존재한 다른 사용자·팀원 변경 파일과 미추적 파일은 수정하거나 정리하지 않았다.

## 미완료 및 후속 작업

- 실제 Naver 지도에서 구역 등록 화면을 열고 지도를 충분히 드래그한 뒤 네 모서리를 클릭해 핀 중심이 클릭 위치와 일치하는지 육안 확인한다.
- 필요하면 로컬 Frontend Image를 갱신한 뒤 Mouse와 Touch Drag를 각각 확인한다.
