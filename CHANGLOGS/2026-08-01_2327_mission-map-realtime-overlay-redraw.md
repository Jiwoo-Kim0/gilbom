# 변경 로그: Mission 지도 실시간 Overlay 재투영

- 작성 시각: 2026-08-01 23:27 (Asia/Seoul)
- 작업 유형: 수정
- 상태: 완료

## 작업 요약

Mission 상세 지도를 확대하거나 이동하는 애니메이션 중 Naver Tile보다 SVG 순찰 구역,
실제 궤적과 Robot Marker의 재투영이 늦어 지도 경계에서 잘린 모양이 잠시 유지되는 문제를
수정했다. Naver `OverlayView` 부착 구조는 유지하면서 연속 이동·확대 이벤트를 구독하고,
동일 Browser Frame에 발생한 여러 이벤트를 `requestAnimationFrame` 한 번으로 병합해
최신 Projection으로 Overlay를 다시 그린다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/frontend/gilbom/src/features/mission/MissionMap.tsx` | 수정 | `drag`, `panning`, `zooming`, `bounds_changed`, `size_changed`, `zoom_changed`, `dragend`, `idle`을 같은 재투영 경로에 연결했다. 연속 이벤트는 `requestAnimationFrame`으로 Frame당 1회만 `OverlayView.draw()`를 호출하고, Effect 정리 시 Listener와 예약 Frame을 해제한다. |
| `web/frontend/gilbom/src/types/naver-maps.d.ts` | 수정 | 실제 지도 애니메이션 중 사용하는 Naver Map Event Type에 `panning`, `zooming`을 추가했다. |
| `web/frontend/gilbom/e2e/mission-map.spec.ts` | 수정 | Fake SDK가 이동·확대 시 Overlay `draw()`를 직접 호출하던 가정을 제거했다. 실제 SDK처럼 연속 Viewport Event만 발행한 뒤 Application Listener가 Area Path와 Marker를 갱신하는지 검증한다. |
| `CHANGLOGS/2026-08-01_2327_mission-map-realtime-overlay-redraw.md` | 추가 | 원인, Frame 병합 최적화, 검증 결과와 운영 확인 항목을 기록했다. |

## 상세 내용

- 이전 Map-attached Overlay는 지도 Pane에 부착되어 Drag 시 Tile과 같은 계층으로 이동하지만,
  애니메이션 중 좌표 재계산 시점은 Naver SDK의 `OverlayView.draw()` 호출에 의존했다.
- 지도 SDK가 최종 상태에서만 또는 낮은 빈도로 `draw()`를 호출하는 구간에는 화면 크기의
  SVG와 `overflow: hidden`이 이전 좌표를 기준으로 적용되어, 확대 중 잘린 경계가 다음
  갱신까지 남을 수 있었다.
- Naver Map이 연속 이동 시 발생시키는 `panning`과 확대 시 발생시키는 `zooming`을 포함해
  Viewport 변경 Event를 직접 구독한다. 종료 보정용 `dragend`, `zoom_changed`, `idle`도 같은
  경로를 사용한다.
- Event마다 즉시 전체 Polygon·Trajectory를 다시 계산하지 않는다. 한 Frame에 여러 Event가
  발생해도 예약된 Animation Frame이 있으면 추가 예약을 생략해 최대 화면 주사율 수준으로
  갱신 횟수를 제한한다.
- Component 해제나 지도 재생성 시 Event Listener를 모두 제거하고 대기 중 Frame을 취소해
  해제된 Map 또는 Overlay를 다시 그리지 않는다.
- CSP, Nginx, Compose, Backend, DB, MQTT 설정은 변경하지 않았다.

## 검증

- 실행한 명령:
  - `npx eslint src/features/mission/MissionMap.tsx src/types/naver-maps.d.ts e2e/mission-map.spec.ts`
  - `npm test`
  - `npx playwright test e2e/mission-map.spec.ts --output=test-results/mission-map-realtime-overlay`
  - `npm run build`
  - `npm run test:e2e:mission -- --output=test-results/mission-realtime-overlay-regression`
  - `git diff --check -- web/frontend/gilbom/src/features/mission/MissionMap.tsx web/frontend/gilbom/src/types/naver-maps.d.ts web/frontend/gilbom/e2e/mission-map.spec.ts`
- 결과:
  - 대상 ESLint 통과
  - Frontend 단위 테스트 38건 통과, 실패 0
  - Mission Map Chromium E2E 1건 통과
  - TypeScript·Vite Production Build 성공
  - 전체 Mission Chromium E2E 3건 통과, 실패 0
  - 변경 파일 whitespace 오류 없음
  - Production Build의 500kB 초과 Chunk 경고는 기존과 같이 남지만 Build를 실패시키지 않음
- 미실행 항목과 사유:
  - EC2 HTTPS 배포와 실제 Mouse Wheel·Touch Pinch 육안 검증은 배포 권한과 운영 반영 절차가
    필요한 후속 작업이므로 실행하지 않았다.

## 영향 및 주의사항

- 순찰 구역, 실제 궤적과 Robot Marker가 확대·이동 중에도 같은 Projection Frame으로
  갱신되며, 지도 프레임 밖 요소가 정상적으로 잘리는 동작 자체는 유지된다.
- `requestAnimationFrame` 병합으로 `drag`, `panning`, `bounds_changed`처럼 같은 동작에서
  중복 발생하는 Event가 중복 렌더링을 만들지 않는다.
- Trajectory 좌표 수가 매우 커지면 Frame당 한 번이어도 전체 좌표 재투영 비용이 커질 수
  있다. 현재 API 기본·최대 Limit 안에서는 기존 전체 궤적 렌더링 계약을 유지한다.
- 참고한 Naver Map 공식 계약은 `setZoom`·`zoomBy`의 `zooming` Event와 `panTo`의
  `panning` Event 발생 명세다.

## 미완료 및 후속 작업

1. Local 실제 Naver SDK에서 Mouse Wheel 확대와 Drag 중 경계·궤적·Marker가 Tile과 같은
   Frame으로 움직이고 축소 시 잘린 도형이 즉시 복원되는지 육안 확인한다.
2. 최신 Frontend Image를 Day 8 EC2 HTTPS 환경에 배포한 뒤 Mouse·Touch 조작을 재검증한다.
3. 실제 Jetson 장시간 궤적이 커질 경우 점 단순화 또는 Downsampling을 별도 성능 작업으로
   검토한다.
