# 변경 로그: Mission 지도 확대 클리핑 복원

- 작성 시각: 2026-08-01 23:35 (Asia/Seoul)
- 작업 유형: 수정
- 상태: 완료

## 작업 요약

Mission 상세 지도는 Drag 시 정상적으로 이동했지만 확대 중 SVG 순찰 구역과 실제 궤적이
지도 화면 경계에서 잘린 뒤, 잘린 형상 그대로 확대되어 구역 정보가 사라지는 문제를
수정했다. Map-attached SVG 내부의 클리핑만 해제하고 바깥 지도 Card의 클리핑은 유지해,
확대 애니메이션 중에도 전체 벡터 형상을 보존하면서 지도 밖으로는 표시되지 않게 했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/frontend/gilbom/src/features/mission/MissionMap.tsx` | 수정 | `MapAttachedMissionOverlay`가 생성하는 SVG Root에 `overflow="visible"` Presentation Attribute를 지정해 확대 변환 전에 화면 밖이던 Polygon·Polyline도 벡터 형상으로 유지한다. |
| `web/frontend/gilbom/src/features/mission/mission-map.css` | 수정 | `.mission-map-native-overlay`의 내부 클리핑을 `hidden`에서 `visible`로 변경했다. 상위 `.mission-map-canvas-wrap`의 `overflow: hidden`은 유지해 지도 Card 바깥 출력은 계속 차단한다. |
| `web/frontend/gilbom/e2e/mission-map.spec.ts` | 수정 | Map-attached SVG의 `overflow` Attribute와 실제 Computed CSS가 모두 `visible`인지 검증해 확대 클리핑 회귀를 고정했다. |
| `CHANGLOGS/2026-08-01_2335_mission-map-zoom-clip.md` | 추가 | 확대 클리핑의 실제 원인, 이전 진단 정정, 검증 결과와 운영 확인 항목을 기록했다. |

## 상세 내용

- 사용자 확인으로 Drag 동기화는 정상이고, 확대할 때 화면 경계에서 잘린 도형이 복구되지
  않는 증상으로 범위를 좁혔다.
- `CHANGLOGS/2026-08-01_2327_mission-map-realtime-overlay-redraw.md`의 Frame 단위 재투영은
  유지하지만 그것만으로는 충분하지 않았다. Naver `overlayLayer` 안의 300px SVG Root가
  `overflow: hidden`이라 확대 변환 직전에 화면 밖이던 도형 부분을 먼저 잘랐기 때문이다.
- SVG Root는 `overflow: visible`로 바꿔 전체 Area·Trajectory Geometry를 보존한다.
  실제 지도 Card 바깥은 상위 `.mission-map-canvas-wrap`이 계속 자르므로 다른 UI 위로 선이
  넘치지 않는다.
- Attribute와 외부 CSS를 함께 적용한다. Inline `style`은 사용하지 않아 운영
  `style-src 'self'` CSP를 완화할 필요가 없다.
- Nginx, CSP Header, Compose, Backend, DB, MQTT 설정은 변경하지 않았다.

## 검증

- 실행한 명령:
  - `npx eslint src/features/mission/MissionMap.tsx src/types/naver-maps.d.ts e2e/mission-map.spec.ts`
  - `npx playwright test e2e/mission-map.spec.ts --output=test-results/mission-map-zoom-clip`
  - `npm run build`
  - `npm run test:e2e:mission -- --output=test-results/mission-zoom-clip-regression`
  - `git diff --check -- web/frontend/gilbom/src/features/mission/MissionMap.tsx web/frontend/gilbom/src/features/mission/mission-map.css web/frontend/gilbom/e2e/mission-map.spec.ts`
- 결과:
  - 대상 ESLint 통과
  - Mission Map Chromium E2E 1건 통과
  - TypeScript·Vite Production Build 성공
  - 전체 Mission Chromium E2E 3건 통과, 실패 0
  - 변경 파일 whitespace 오류 없음
  - Production Build의 500kB 초과 Chunk 경고는 기존과 같이 남지만 Build 성공
- 미실행 항목과 사유:
  - EC2 HTTPS 배포와 실제 Mouse Wheel·Touch Pinch 육안 검증은 운영 배포 후 확인이
    필요한 후속 작업이라 실행하지 않았다.

## 영향 및 주의사항

- 확대 과정에서 지도 화면 밖이던 구역 경계와 궤적도 SVG 내부에서는 유지되며, 확대·축소
  후 화면 안으로 들어오면 다시 정상적으로 보인다.
- 최종 화면에서 실제로 지도 Viewport 밖에 있는 구역이 보이지 않는 것은 정상이다.
- 바깥 Card의 `overflow: hidden`을 제거하면 지도선이 범례나 주변 UI로 넘칠 수 있으므로
  상위 클리핑은 유지해야 한다.
- Frame 단위 재투영과 SVG 전체 형상 보존은 서로 대체 관계가 아니다. 전자는 좌표 동기화,
  후자는 확대 변환 중 벡터 손실 방지를 담당한다.

## 미완료 및 후속 작업

1. Local 실제 Naver 지도에서 여러 단계 연속 확대 후 축소했을 때 구역 Polygon과 실제
   궤적이 온전하게 복원되는지 육안 확인한다.
2. 최신 Frontend Image를 Day 8 EC2 HTTPS 환경에 배포한 뒤 Mouse Wheel과 Touch Pinch를
   재검증한다.
