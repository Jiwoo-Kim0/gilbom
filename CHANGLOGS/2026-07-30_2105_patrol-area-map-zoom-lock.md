# 변경 로그: 순찰 구역 등록 지도 확대 잠금

- 작성 시각: 2026-07-30 21:05 (Asia/Seoul)
- 작업 유형: 수정
- 상태: 완료

## 작업 요약

순찰 구역 등록 지도에서 연속 경계점 클릭이 확대 동작으로 이어질 수 있는 사용자 인터랙션을 차단했다. 경계점 편집 중 지도 줌 레벨을 17로 고정하고 확대 입력 및 줌 컨트롤을 비활성화했으며, 지도 이동과 경계점 입력은 유지했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/frontend/gilbom/src/features/mission/PatrolAreaBoundaryEditor.tsx` | 수정 | 등록 지도 초기 줌과 최소·최대 줌을 17로 통일하고 휠, 핀치, 키보드, 더블클릭·더블탭 확대 및 줌 컨트롤을 비활성화했다. |
| `web/frontend/gilbom/e2e/patrol-area-registration.spec.ts` | 수정 | 등록 지도에 고정 줌과 모든 확대 차단 옵션이 전달되는지 검증하도록 지도 옵션 계약 검증을 확장했다. |
| `CHANGLOGS/2026-07-30_2105_patrol-area-map-zoom-lock.md` | 추가 | 확대 현상 보정 범위와 검증 결과를 기록했다. |

## 상세 내용

- `zoom`, `minZoom`, `maxZoom`을 모두 17로 지정해 SDK 내부 확대 이벤트가 발생해도 줌 범위를 벗어날 수 없게 했다.
- `pinchZoom`, `scrollWheel`, `keyboardShortcuts`, `zoomControl`을 비활성화했다.
- 기존 `disableDoubleClickZoom`, `disableDoubleTapZoom`, `disableTwoFingerTapZoom` 설정을 유지했다.
- 경계점 추가를 위한 지도 클릭과 위치 탐색을 위한 드래그 이동은 유지했다.
- 임무 선택 구역 미리보기의 구역 맞춤 표시와 다른 지도 화면은 변경하지 않았다.
- Backend, 인증·권한, CSRF, 보안 설정은 변경하지 않았다.

## 검증

- 실행한 명령:
  - `npx eslint src/features/mission/PatrolAreaBoundaryEditor.tsx e2e/patrol-area-registration.spec.ts`
  - `npm test`
  - `npm run build`
  - `npx playwright test e2e/patrol-area-registration.spec.ts --output=test-results/patrol-area-zoom-lock`
  - `git diff --check`
- 결과:
  - ESLint 통과
  - 단위 테스트 37건 통과
  - TypeScript/Vite 프로덕션 빌드 통과(2,497개 모듈 변환)
  - Chromium 등록 E2E 1건 통과
  - 공백 오류 없음
- 미실행 항목과 사유:
  - 실제 Naver Maps API 수동 검증은 로컬 E2E의 지도 SDK 모의 환경으로 대체했다.

## 영향 및 주의사항

- 등록 지도는 경계점을 찍는 동안 17레벨로 고정되므로 사용자가 수동으로 확대·축소할 수 없다.
- 지도 드래그는 활성 상태여서 현재 줌 범위에서 다른 위치로 이동해 구역을 지정할 수 있다.
- 빌드에서 기존 메인 JavaScript 청크 크기 경고가 출력됐으며 이번 수정과 직접 관련된 실패는 아니다.
- 작업 트리의 다른 팀원 변경은 커밋 대상에서 제외한다.

## 미완료 및 후속 작업

- 실제 Naver Maps 키가 설정된 통합 환경에서 연속 클릭 및 터치 입력 시 줌 고정 동작을 최종 육안 확인할 수 있다.
