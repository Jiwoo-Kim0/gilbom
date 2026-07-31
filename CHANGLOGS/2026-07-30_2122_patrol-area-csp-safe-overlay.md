# 변경 로그: 순찰 구역 등록 지도 CSP 안전 오버레이

- 작성 시각: 2026-07-30 21:22 (Asia/Seoul)
- 작업 유형: 수정
- 상태: 완료

## 작업 요약

순찰 구역 등록 지도에서 Naver SDK Polygon과 번호 마커가 왼쪽 위로 밀리고 검게 표시되는 현상을 수정했다. 현재 CSP를 변경하지 않고, Naver 지도 투영 API로 위경도를 화면 픽셀 좌표로 변환한 뒤 React SVG와 외부 CSS로 Polygon·Polyline·번호 마커를 렌더링하도록 교체했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/frontend/gilbom/src/features/mission/PatrolAreaBoundaryEditor.tsx` | 수정 | 등록 화면의 Naver Polygon·Polyline·Marker 생성을 제거하고 `MapSystemProjection.fromCoordToOffset`으로 변환한 화면 좌표에 React SVG 도형과 번호 마커를 표시한다. 지도 이동·크기 변경 시 화면 좌표를 다시 계산한다. |
| `web/frontend/gilbom/src/features/mission/patrol-area-boundary-editor.css` | 수정 | CSP가 허용하는 외부 CSS로 SVG Polygon의 10% 채움 투명도, 경계선, 번호 마커와 라벨 스타일을 정의했다. |
| `web/frontend/gilbom/src/types/naver-maps.d.ts` | 수정 | `Point.x/y`, `MapSystemProjection`, `Map.getProjection`, 지도 경계·크기 변경 이벤트 타입을 추가했다. |
| `web/frontend/gilbom/e2e/patrol-area-registration.spec.ts` | 수정 | 지도 투영 테스트 더블을 추가하고 자동 정렬된 네 점과 SVG Polygon 픽셀 좌표가 일치하는지, 계산된 `fill-opacity`가 0.1인지, 번호 마커가 네 개인지 검증한다. |
| `CHANGLOGS/2026-07-30_2122_patrol-area-csp-safe-overlay.md` | 추가 | 화면 증상, 원인, 구현 범위 및 검증 결과를 기록했다. |

## 상세 내용

- 사용자 제공 `xx.png`에서 경계점 번호는 지도 중앙에 있지만 SDK Polygon만 왼쪽 위로 밀리고 검게 채워진 현상을 확인했다.
- Nginx CSP의 `style-src 'self'`는 그대로 유지했다.
- 등록 지도에서만 SDK가 동적으로 구성하는 도형·커스텀 마커 대신 React SVG 오버레이를 사용한다.
- SVG의 위치, 크기, 점 좌표는 HTML 인라인 스타일 없이 속성과 외부 CSS로 적용한다.
- Polygon의 면 채움은 외부 CSS의 `fill-opacity: 0.1`로 적용한다.
- 지도 투영 객체가 반환한 offset을 소수점 둘째 자리로 정규화해 점과 선·면의 화면 좌표를 동일하게 사용한다.
- 지도 `bounds_changed`, `size_changed` 이벤트에서 `requestAnimationFrame` 단위로 투영 좌표를 갱신해 드래그 이동 후에도 도형이 경계점 위치를 따라가게 했다.
- 기존 점 자동 정렬, 등록 요청 GeoJSON, 줌 잠금 및 마지막 점 취소 동작은 유지했다.
- Backend, 인증·권한, CSRF, Nginx 및 기타 보안 설정은 변경하지 않았다.
- 사용자 제공 `xx.png`는 변경하거나 커밋하지 않았다.

## 검증

- 실행한 명령:
  - `npx eslint src/features/mission/PatrolAreaBoundaryEditor.tsx src/types/naver-maps.d.ts e2e/patrol-area-registration.spec.ts`
  - `npm test`
  - `npm run build`
  - `npx playwright test e2e/patrol-area-registration.spec.ts --output=test-results/patrol-area-csp-overlay-final`
  - `git diff --check`
- 결과:
  - ESLint 통과
  - 단위 테스트 37건 통과
  - TypeScript/Vite 프로덕션 빌드 통과(2,497개 모듈 변환)
  - Chromium 등록 E2E 1건 통과
  - E2E에서 Polygon 좌표와 번호 마커 좌표 일치, `fill-opacity: 0.1` 계산 스타일 적용 확인
  - 공백 오류 없음
- 미실행 항목과 사유:
  - 실제 사용자 세션에서의 Naver 지도 육안 재검증은 최신 Docker frontend 재빌드 후 사용자 확인이 필요하다.

## 영향 및 주의사항

- 등록 화면의 Polygon·Polyline·번호 마커만 CSP 안전 SVG 레이어로 대체되며, 지도 타일과 클릭 좌표 취득은 기존 Naver SDK를 계속 사용한다.
- SVG 레이어는 `pointer-events: none`이므로 지도 클릭과 드래그를 방해하지 않는다.
- 지도 확대는 기존과 같이 17레벨로 고정된다.
- 임무 선택 미리보기와 임무 상세 지도는 이번 변경 범위에 포함하지 않았다.
- 빌드에서 기존 메인 JavaScript 청크 크기 경고가 출력됐으며 이번 변경과 직접 관련된 실패는 아니다.
- 작업 트리의 다른 팀원 변경과 사용자 스크린샷은 커밋 대상에서 제외한다.

## 미완료 및 후속 작업

- Docker frontend를 최신 소스로 재빌드한 뒤 순찰 구역 등록 화면에서 점·Polygon 정렬과 투명도를 육안 확인한다.
- 동일 CSP 영향이 임무 선택 미리보기나 임무 상세 지도에서 발견되면 같은 투영 기반 오버레이 방식을 별도 작업으로 적용한다.
