# 변경 로그: 순찰 구역 마커 표시와 우측 줌 컨트롤

- 작성 시각: 2026-08-04 18:23 (Asia/Seoul)
- 작업 유형: 수정
- 상태: 완료

## 작업 요약

순찰 구역 등록 지도에서 GPS 경계점은 저장되지만 마커가 보이지 않던 실제 Naver 지도 문제를 수정했다. Naver `overlayLayer`의 높이가 `0px`인 구조에서도 SVG의 명시적 높이 속성이 적용되도록 백분율 크기 CSS를 제거했다. 장소를 정밀하게 지정할 수 있도록 오른쪽 위 `+ / −` 줌 컨트롤을 활성화하고, 휠·핀치·키보드 확대는 차단했다. 임무 화면 지도는 변경하지 않고 구현 방식만 참고했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/frontend/gilbom/src/features/mission/PatrolAreaBoundaryEditor.tsx` | 수정 | 순찰 구역 등록 지도의 줌 범위를 10~21로 열고 우측 상단 SMALL 줌 컨트롤을 활성화했다. 휠·핀치·키보드·더블클릭·더블탭 확대는 비활성화하고, `zooming`·`zoom_changed` 이벤트에서 저장 GPS를 다시 화면 좌표로 투영하도록 했다. |
| `web/frontend/gilbom/src/features/mission/patrol-area-boundary-editor.css` | 수정 | Naver `overlayLayer`의 실제 높이 `0px`를 상속하던 SVG `width: 100%`, `height: 100%`를 제거했다. Component가 지정하는 `width`·`height` SVG 속성이 적용되어 마커·선·Polygon이 보이도록 했다. |
| `web/frontend/gilbom/e2e/patrol-area-registration.spec.ts` | 수정 | Fake Naver Overlay Pane을 실제와 같이 높이 `0px`로 구성하고 SVG 계산 높이 `360px`를 검증한다. 줌 범위, 우측 컨트롤, 입력 차단 옵션 계약도 검증한다. |
| `CHANGLOGS/2026-08-04_1823_patrol-area-marker-visibility-zoom-control.md` | 추가 | 실제 원인, 수정 범위, 자동·실제 Naver SDK 검증과 런타임 반영 결과를 기록했다. |

## 상세 내용

- 수정 전 실제 로컬 Naver SDK에서 클릭 GPS `위도 36.1195917`, `경도 128.3424650`과 SVG Circle `cx=260`, `cy=169.42`, `r=14`는 정상 생성됐다.
- SVG의 `height` 속성은 `360`이었지만 CSS `height: 100%`가 높이 `0px`인 Naver Overlay Pane을 기준으로 계산되어 실제 SVG 사각형 높이가 `0px`였다. GPS나 클릭 이벤트가 아니라 표시 Layer 크기 문제였다.
- 백분율 `width`·`height`를 제거한 뒤 SVG 계산 크기는 `918×360px`, 마커 원은 `28×28px`로 표시됐다.
- GPS는 Naver 클릭 `event.coord`에서 얻어 `[경도, 위도]`로 저장하고, 마커는 저장 GPS를 `fromCoordToOffset()`으로 다시 투영한다. 확대·축소나 드래그 후에도 클릭 픽셀을 고정값으로 재사용하지 않는다.
- 우측 상단 `+ / −` 컨트롤만 확대·축소 입력으로 허용한다. Mouse Wheel, Touch Pinch, Keyboard, Double Click, Double Tap, Two-finger Tap 확대는 모두 차단한다.
- 오른쪽 컨트롤 조작 시 `zooming`·`zoom_changed` 이벤트가 Overlay를 다시 그려 동일 GPS 마커가 새 확대 비율에 맞는 위치로 이동한다.
- `web/frontend/gilbom/src/features/mission/MissionMap.tsx`와 `mission-map.css`는 변경하지 않았다.

## 검증

- 실행한 명령:
  - `npx eslint src/features/mission/PatrolAreaBoundaryEditor.tsx e2e/patrol-area-registration.spec.ts`
  - `npm test`
  - `npm run build`
  - `npx playwright test e2e/patrol-area-registration.spec.ts --output=test-results/patrol-area-visible-zoom-control`
  - `npm run test:e2e:mission -- --output=test-results/patrol-area-visible-zoom-final`
  - 실제 Naver SDK Local Playwright 진단 (`http://localhost:3000/patrol-areas`)
  - `docker compose -f web/compose.yaml build frontend`
  - `docker compose -f web/compose.yaml up -d --no-deps --force-recreate frontend`
  - `docker compose -f web/compose.yaml ps frontend`
  - `curl.exe -fsS -o NUL -w "frontend_http=%{http_code}" http://127.0.0.1:3000/`
  - `git diff --check -- web/frontend/gilbom/src/features/mission/PatrolAreaBoundaryEditor.tsx web/frontend/gilbom/src/features/mission/patrol-area-boundary-editor.css web/frontend/gilbom/e2e/patrol-area-registration.spec.ts`
  - `git diff -- web/frontend/gilbom/src/features/mission/MissionMap.tsx web/frontend/gilbom/src/features/mission/mission-map.css`
- 결과:
  - 대상 ESLint 통과
  - Frontend 단위 테스트 44개 통과, 실패 0
  - TypeScript·Vite Production Build 통과, 2,509개 Module 변환
  - 순찰 구역 등록 Browser E2E 1개 통과
  - 전체 Mission Browser E2E 3개 통과, 실패 0
  - 실제 Naver Overlay Pane 높이 `0px`에서 SVG 계산 높이 `360px`, 마커 원 `28×28px`, Fill `rgb(23, 92, 76)` 확인
  - 오른쪽 위에 28px 크기의 줌 컨트롤 2개가 표시됨을 확인
  - Mouse Wheel 입력 후 마커 투영 좌표가 유지되어 휠 줌 차단 확인
  - 오른쪽 `+` 컨트롤 클릭 후 동일 GPS 마커 투영이 `cx=260, cy=169.42`에서 `cx=61.01, cy=158.84`로 변경되어 버튼 줌과 재투영 확인
  - 새 Frontend Docker Image Build 및 Container 재생성 완료
  - `gilbom-frontend-1` 상태 Healthy, `http://127.0.0.1:3000/` HTTP 200
  - 임무 화면 지도 두 파일 Diff 없음
  - 대상 파일 whitespace 오류 없음
  - Build의 500kB 초과 Chunk 경고는 기존과 같이 남지만 Build는 성공했다.
- 미실행 항목과 사유:
  - 실제 사용자 로그인 Session에서의 수동 Mouse 클릭은 사용자가 브라우저에서 확인할 항목이다. 동일 Docker Frontend에 실제 Naver SDK와 Mock 인증 API를 연결한 Headless Browser로 표시·입력 동작은 검증했다.
  - 운영 EC2 배포는 이번 로컬 수정 범위에 포함하지 않았다.

## 영향 및 주의사항

- 변경 범위는 순찰 구역 등록 지도에 한정된다. 임무 화면 지도와 Backend, DB, GeoJSON API 계약은 변경하지 않았다.
- 구역 등록 GPS 좌표는 관리자 클릭 위치이며 Robot Telemetry GPS와는 다른 데이터 흐름이다.
- 오른쪽 줌 컨트롤로 확대·축소한 뒤 클릭해도 Naver SDK가 반환한 현재 Viewport의 GPS를 저장한다.
- 작업 시작 전부터 존재한 다른 사용자·팀원 변경 파일과 미추적 파일은 수정하거나 정리하지 않았다.

## 미완료 및 후속 작업

- 사용자 브라우저에서 강력 새로고침 후 순찰 구역 등록 지도의 오른쪽 `+ / −` 버튼, 드래그, 경계점 표시를 최종 육안 확인한다.
