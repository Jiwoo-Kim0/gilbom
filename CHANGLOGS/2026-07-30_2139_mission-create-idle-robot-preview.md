# 변경 로그: 임무 생성 구역 및 미수행 로봇 지도 미리보기

- 작성 시각: 2026-07-30 21:39 (Asia/Seoul)
- 작업 유형: 기능
- 상태: 완료

## 작업 요약

순찰 임무 생성 화면에서 선택한 등록 구역을 CSP 안전 Polygon으로 표시하고, 임무를 수행 중이지 않은 선택 로봇의 최신 GPS 위치와 진행 방향을 같은 지도에 표시했다. 임무 수행 중인 로봇은 생성 후보에서 제외하며, 구역 경계점 평균 좌표가 지도 중앙에 유지되도록 화면 범위를 계산한다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/frontend/gilbom/src/features/mission/MissionManagePage.tsx` | 수정 | 로봇 지도 조회 결과를 임무 생성 화면에 연결하고, `ACTIVE`이면서 `missionStatus`가 없는 로봇만 생성 후보로 제공한다. 선택 로봇의 최신 위치를 구역 미리보기에 전달하며 실시간 이벤트 후 위치를 다시 조회한다. 위치 조회 실패는 임무 생성 목록 로딩 실패로 전파하지 않는다. |
| `web/frontend/gilbom/src/features/mission/PatrolAreaSelectionPreview.tsx` | 수정 | Naver 지도 투영 좌표 기반 React SVG로 선택 구역 Polygon과 경계점 평균 중심을 렌더링하고, 선택한 미수행 로봇의 최신 GPS 위치·연결 상태·방향을 같은 레이어에 표시한다. 실제 지도를 불러오지 못한 경우에도 동일 정보를 좌표 SVG 대체 화면에 표시한다. |
| `web/frontend/gilbom/src/features/mission/patrol-area-selection-preview.css` | 수정 | CSP 허용 외부 CSS에 구역 Polygon 10% 투명도, 평균 중심, 온라인·오프라인 로봇 위치와 방향, 로봇 요약 스타일을 추가했다. |
| `web/frontend/gilbom/src/features/mission/missionTypes.ts` | 수정 | 로봇 목록 응답의 nullable `missionStatus` 계약을 `RobotOption`에 반영해 임무 미수행 후보를 판별할 수 있게 했다. |
| `web/frontend/gilbom/e2e/patrol-area-registration.spec.ts` | 수정 | 임무 생성에서 선택 구역 Polygon의 투영 좌표·투명도, 미수행 로봇의 위치·상태·GPS 표시와 수행 중 로봇의 후보 제외를 검증한다. |
| `CHANGLOGS/2026-07-30_2139_mission-create-idle-robot-preview.md` | 추가 | 구현 범위, 변경 파일, 검증 결과와 주의사항을 기록했다. |

## 상세 내용

- 임무 생성 후보는 로봇 목록 응답에서 `operationalStatus === "ACTIVE"`이고 `missionStatus === null`인 로봇으로 제한했다.
- 선택 로봇의 위치는 기존 `GET /api/v1/robots/map` 응답을 사용하며 새 API나 Backend 변경을 추가하지 않았다.
- 위치 조회가 실패하거나 GPS가 없으면 로봇 선택과 임무 생성은 유지하고 `현재 GPS 위치 미수신`을 표시한다.
- GPS가 있으면 연결 상태에 따라 온라인·오프라인 색상을 구분하고 `headingDeg`만큼 방향 표식을 회전한다.
- 구역과 로봇을 모두 포함하는 대칭 범위를 계산한 뒤 경계점 평균 좌표를 다시 지도 중앙으로 지정한다.
- Naver SDK의 동적 Polygon·Marker 스타일 대신 지도 투영 API와 React SVG·외부 CSS를 사용해 현재 CSP에서 도형이 검게 변하거나 왼쪽 위로 밀리지 않게 했다.
- Backend, 인증·권한, CSRF, Nginx 및 기타 보안 설정은 변경하지 않았다.
- 사용자 제공 `xx.png`와 작업 트리의 다른 팀원 변경은 수정하거나 커밋하지 않는다.

## 검증

- 실행한 명령:
  - `npx eslint src/features/mission/MissionManagePage.tsx src/features/mission/PatrolAreaSelectionPreview.tsx src/features/mission/missionTypes.ts e2e/patrol-area-registration.spec.ts`
  - `npm test`
  - `npm run build`
  - `npx playwright test e2e/patrol-area-registration.spec.ts --output=test-results/mission-create-idle-robot-preview`
  - `git diff --check`
- 결과:
  - ESLint 통과
  - 단위 테스트 37건 통과
  - TypeScript/Vite 프로덕션 빌드 통과(2,497개 모듈 변환)
  - Chromium E2E 1건 통과
  - E2E에서 선택 구역 Polygon의 투영 좌표와 `fill-opacity: 0.1`, 미수행 로봇의 GPS 픽셀 위치, `임무 미수행` 표시 및 수행 중 로봇 후보 제외 확인
  - 공백 오류 없음
- 미실행 항목과 사유:
  - 실제 Naver 지도와 운영 로봇 데이터의 육안 확인은 최신 Docker frontend 재빌드 후 사용자 세션에서 확인한다.

## 영향 및 주의사항

- 임무 생성의 로봇 후보 수는 임무 수행 중인 `ACTIVE` 로봇을 제외하므로 기존보다 적게 보일 수 있다.
- 로봇 지도 조회는 부가 정보 조회로 처리하므로 해당 요청이 실패해도 임무 생성 폼 전체를 막지 않는다.
- SVG 오버레이는 `pointer-events: none`으로 지도 조작을 방해하지 않는다.
- 빌드에서 기존 메인 JavaScript 청크 크기 경고가 출력됐으며 이번 기능의 실패는 아니다.
- 이번 변경은 임무 생성 화면에 한정하며 임무 수행 상세 지도는 변경하지 않았다.

## 미완료 및 후속 작업

- Docker frontend를 최신 소스로 재빌드한 뒤 임무 생성 화면에서 등록 구역과 선택한 미수행 로봇 위치를 육안 확인한다.
- 실제 로봇 목록에서 `missionStatus`가 임무 종료 후 `null`로 정리되는지 통합 데이터로 확인한다.
