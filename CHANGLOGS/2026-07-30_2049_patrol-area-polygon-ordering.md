# 변경 로그: 순찰 구역 폴리곤 자동 정렬 및 투명도 조정

- 작성 시각: 2026-07-30 20:49 (Asia/Seoul)
- 작업 유형: 기능
- 상태: 완료

## 작업 요약

순찰 구역 등록 지도에서 점을 임의 순서로 찍어도 모든 점을 사용하는 결정적 순회 순서를 계산하고, 교차 구간을 제거한 반시계 방향 Polygon으로 등록하도록 변경했다. 등록 화면, 임무 선택 미리보기, 임무 지도에서 폴리곤 면 채움 투명도를 10%로 낮춰 배경 지도가 덜 가려지도록 조정했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/frontend/gilbom/src/features/mission/patrolAreaBoundary.ts` | 수정 | 좌표 중복 제거, 결정적 최근접 순회, 2-opt 교차 제거, 반시계 방향 및 시작점 정규화 함수와 자체 교차 검증 함수를 추가하고 Polygon 생성 시 자동 정렬을 적용했다. |
| `web/frontend/gilbom/src/features/mission/PatrolAreaBoundaryEditor.tsx` | 수정 | 클릭 원본 순서는 마지막 점 취소용으로 유지하면서 지도·목록에는 자동 정렬된 경계를 표시하고, 중복 점 입력을 방지하며 자동 정렬 안내 문구를 추가했다. |
| `web/frontend/gilbom/src/features/mission/PatrolAreaManagePage.tsx` | 수정 | 등록 직전에 경계를 다시 정렬하고 면적 및 자체 교차를 검증한 뒤 닫힌 GeoJSON Polygon을 API로 전송하도록 변경했다. |
| `web/frontend/gilbom/src/features/mission/PatrolAreaSelectionPreview.tsx` | 수정 | 임무 등록의 선택 구역 Naver Polygon 면 채움 투명도를 10%로 낮췄다. |
| `web/frontend/gilbom/src/features/mission/patrol-area-selection-preview.css` | 수정 | 지도 SDK 미사용 시 표시되는 선택 구역 SVG 폴리곤 면 채움 투명도를 10%로 낮췄다. |
| `web/frontend/gilbom/src/features/mission/MissionMap.tsx` | 수정 | 임무 상세 지도에 표시되는 순찰 구역 Naver Polygon 면 채움 투명도를 10%로 낮췄다. |
| `web/frontend/gilbom/src/features/mission/mission-map.css` | 수정 | 임무 지도 범례와 대체 SVG의 순찰 구역 면 채움 투명도를 10%로 낮췄다. |
| `web/frontend/gilbom/test/patrolAreaBoundary.test.ts` | 수정 | 입력 순열 불변성, 자체 교차 제거, 모든 점 보존, 반시계 방향, 닫힌 Polygon 생성 단위 테스트를 추가했다. |
| `web/frontend/gilbom/e2e/patrol-area-registration.spec.ts` | 수정 | 임의 클릭 순서가 정규화된 Polygon 요청으로 변환되는지, 지도 뷰포트가 클릭에 따라 자동 변경되지 않는지, 면 채움 투명도와 선택 구역 중심점이 올바른지 검증하도록 확장했다. |
| `CHANGLOGS/2026-07-30_2049_patrol-area-polygon-ordering.md` | 추가 | 구현 범위, 변경 파일, 검증 결과 및 알고리즘 주의사항을 기록했다. |

## 상세 내용

- 좌표를 경도·위도 사전순으로 정렬하고 가장 작은 점을 시작점으로 선택해 입력 순서와 무관한 결과를 만든다.
- 최근접 이웃 방식으로 초기 순회를 구성한 뒤 2-opt 개선을 반복하며 교차 간선을 우선 제거한다.
- 최종 경계는 반시계 방향으로 통일하고 시작점을 다시 정규화해 동일 좌표 집합의 API 요청이 안정적으로 유지되게 했다.
- 등록 지도는 원본 클릭 순서를 보관하므로 `마지막 점 취소`는 사용자가 실제로 마지막에 찍은 점을 제거한다.
- 등록 지도 클릭 시 `setCenter`, `setZoom`, `fitBounds`를 호출하지 않는 기존 동작을 유지했다.
- 폴리곤 경계선과 마커는 기존 가시성을 유지하고 면 채움만 10%로 낮췄다.
- Backend, 인증·권한, CSRF, 보안 설정은 변경하지 않았다.

## 검증

- 실행한 명령:
  - `npx eslint src/features/mission/PatrolAreaBoundaryEditor.tsx src/features/mission/PatrolAreaManagePage.tsx src/features/mission/PatrolAreaSelectionPreview.tsx src/features/mission/MissionMap.tsx src/features/mission/patrolAreaBoundary.ts e2e/patrol-area-registration.spec.ts test/patrolAreaBoundary.test.ts`
  - `npm test`
  - `npm run build`
  - `npx playwright test e2e/mission-time.spec.ts e2e/mission-map.spec.ts e2e/patrol-area-registration.spec.ts --output=test-results/patrol-area-auto-order-final`
  - `git diff --check`
- 결과:
  - ESLint 통과
  - 단위 테스트 37건 통과
  - TypeScript/Vite 프로덕션 빌드 통과(2,497개 모듈 변환)
  - Chromium E2E 3건 통과
  - 공백 오류 없음
- 미실행 항목과 사유:
  - 실제 Naver Maps API 및 Backend 연동 수동 검증은 로컬 E2E의 지도 SDK·API 모의 환경으로 대체했다.

## 영향 및 주의사항

- 이 정렬은 전역 최단 경로를 보장하는 완전 탐색 TSP가 아니라 최근접 순회와 2-opt를 조합한 휴리스틱이다. 구역 경계를 보기 좋고 교차 없이 구성하는 목적에 맞춰 결정성과 실행 비용을 우선했다.
- 모든 점이 일직선이거나 유효 면적을 만들 수 없는 입력은 기존과 같이 등록을 거절한다.
- 자동 정렬 후에도 수치 오차 등으로 자체 교차가 남는 예외 입력은 잘못된 Polygon을 보내지 않고 등록 단계에서 거절한다.
- 빌드 시 기존 메인 JavaScript 청크 크기 경고가 출력됐으며 이번 변경과 직접 관련된 실패는 아니다.
- 작업 트리의 다른 팀원 변경과 보안 관련 파일은 커밋 대상에서 제외한다.

## 미완료 및 후속 작업

- 실제 Naver Maps 키가 설정된 통합 환경에서 폴리곤 색감과 터치 입력 UX를 최종 육안 확인할 수 있다.
- 최적화된 경계의 지리적 의미가 복잡한 오목 구역의 의도와 다르면 향후 점 이동·간선 수동 수정 기능을 별도 UX로 검토한다.
