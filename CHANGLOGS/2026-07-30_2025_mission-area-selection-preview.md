# 변경 로그: 미션 선택 구역 Polygon 미리보기

- 작성 시각: 2026-07-30 20:25 (Asia/Seoul)
- 작업 유형: 기능
- 상태: 완료

## 작업 요약

순찰 임무 생성 폼에서 ACTIVE 순찰 구역을 선택하면 등록된 Polygon 또는
MultiPolygon을 지도에 즉시 표시하고, 각 외곽 Ring의 닫힘 중복 좌표를 제외한
경계점 산술 평균을 지도 중앙과 중심 Marker로 표시한다.

구역 등록 지도는 경계점을 클릭할 때 자동으로 `setZoom`, `setCenter`,
`fitBounds`를 호출하던 동작을 제거해 단일 클릭으로 지도가 확대되지 않게 했다.
더블클릭·더블탭 확대도 비활성화했다. 임무 생성의 선택 구역 미리보기 지도는
조회 전용이므로 드래그, 휠, 키보드, 핀치와 클릭 계열 확대를 모두 비활성화했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/frontend/gilbom/src/features/mission/MissionManagePage.tsx` | 수정 | 미션 생성의 구역 Select 아래에 선택된 ACTIVE 구역의 `PatrolAreaSelectionPreview`를 연결했다. |
| `web/frontend/gilbom/src/features/mission/PatrolAreaSelectionPreview.tsx` | 추가 | 선택 구역 Polygon/MultiPolygon, 경계점 평균 중심 Marker·좌표, Naver 지도와 SVG Fallback을 구현했다. Naver 지도는 `fitBounds`로 축척을 맞춘 뒤 평균점으로 `setCenter`하며 조회 전용 Interaction을 차단한다. |
| `web/frontend/gilbom/src/features/mission/patrol-area-selection-preview.css` | 추가 | 선택 구역 미리보기 Card, 지도·Fallback, Polygon, 평균 중심과 반응형 Style을 정의했다. |
| `web/frontend/gilbom/src/features/mission/patrolAreaBoundary.ts` | 수정 | Polygon/MultiPolygon 외곽 Ring 추출, 닫힘 중복 좌표 제거와 경계점 산술 평균 계산 함수를 추가했다. |
| `web/frontend/gilbom/src/features/mission/missionTypes.ts` | 수정 | MultiPolygon 좌표를 `GeoJsonCoordinate[][][]`로 구체화해 미리보기 좌표 처리를 타입 안전하게 만들었다. |
| `web/frontend/gilbom/src/features/mission/PatrolAreaBoundaryEditor.tsx` | 수정 | 경계점 변경 시 자동 Center·Zoom·fitBounds를 제거하고 더블클릭·더블탭 확대를 비활성화했다. Marker와 Polyline·Polygon 갱신은 유지한다. |
| `web/frontend/gilbom/src/types/naver-maps.d.ts` | 수정 | Naver `MapOptions`의 드래그·휠·키보드·핀치와 더블클릭·탭 확대 제어 타입을 추가했다. |
| `web/frontend/gilbom/test/patrolAreaBoundary.test.ts` | 추가 | 닫힌 Polygon의 중복점 제외 평균, MultiPolygon 외곽점 결합·Hole 제외, 빈 경계 처리를 검증한다. |
| `web/frontend/gilbom/e2e/patrol-area-registration.spec.ts` | 수정 | 등록 지도 클릭 시 Viewport 함수 미호출, 선택 미리보기의 고정 Interaction 옵션, 평균 좌표 표시와 최종 Naver `setCenter` 값을 검증한다. |
| `CHANGLOGS/2026-07-30_2025_mission-area-selection-preview.md` | 추가 | 이번 미리보기, 자동 확대 제거, 검증 결과와 영향 범위를 기록했다. |

## 상세 내용

- 선택 구역은 별도 API를 추가 호출하지 않고 기존
  `GET /api/v1/patrol-areas` 응답의 `boundary`를 사용한다.
- Polygon은 하나, MultiPolygon은 각 Polygon별 Naver Overlay를 만든다.
- 평균 계산에는 각 Polygon의 외곽 Ring만 사용하며, GeoJSON Ring을 닫는 마지막
  좌표가 첫 좌표와 같으면 마지막 좌표는 평균 모수에서 제외한다. Hole 좌표는
  평균에서 제외한다.
- Naver 지도는 전체 외곽점으로 `fitBounds`해 Polygon이 보이는 Zoom을 계산한
  뒤 평균 좌표를 `setCenter`해 사용자가 요청한 평균점을 화면 중앙에 둔다.
- SDK를 사용할 수 없는 SVG Fallback은 평균점을 화면 정중앙에 고정한 대칭
  좌표 범위로 Polygon을 투영하고 중심 Crosshair를 표시한다.
- 등록 지도는 클릭이 경계점 추가에만 영향을 주며 Viewport를 자동 변경하지
  않는다. 사용자가 직접 드래그·휠·Zoom Control로 지도를 조절할 수 있다.
- 선택 미리보기는 조회 전용이므로 지도 Interaction과 Zoom Control을 끈다.

## 검증

- 실행한 명령:
  - `npx eslint src/features/mission/MissionManagePage.tsx src/features/mission/PatrolAreaBoundaryEditor.tsx src/features/mission/PatrolAreaSelectionPreview.tsx src/features/mission/patrolAreaBoundary.ts src/features/mission/missionTypes.ts e2e/patrol-area-registration.spec.ts test/patrolAreaBoundary.test.ts`
  - `npm test`
  - `npm run build`
  - `npx playwright test e2e/mission-time.spec.ts e2e/mission-map.spec.ts e2e/patrol-area-registration.spec.ts --output=test-results/patrol-area-mission-preview-final`
  - `git diff --check -- <이번 작업 대상 파일>`
- 결과:
  - 대상 ESLint 경고·오류 없이 통과
  - Frontend Node 단위 테스트 34건 통과, 실패·건너뜀 0건
  - 평균 중심 신규 단위 테스트 3건 통과
  - TypeScript·Vite Production Build 통과, 2,497개 Module 변환
  - 미션 시간·상세 지도·구역 등록 및 선택 미리보기 Browser E2E 3건 통과
  - E2E에서 등록 지도 경계점 3회 클릭 후 `setZoom`, `setCenter`,
    `fitBounds` 호출이 모두 0회임을 확인
  - E2E에서 미리보기 평균 중심
    `위도 36.1073333 · 경도 128.4166667`과 Naver `setCenter` 인자를 확인
  - E2E에서 미리보기의 드래그·휠·키보드·핀치·클릭 Zoom 차단 옵션을 확인
  - 대상 파일 whitespace 오류 없음
  - Build의 기존 Main JavaScript 500kB 초과 경고만 있으며 실패는 없음
- 미실행 항목과 사유:
  - 운영 Client ID·허용 Domain의 실제 Naver Tile 수동 클릭 검증은 배포 환경이
    필요해 실행하지 않았다. Naver Map Adapter와 Interaction 옵션은 Browser
    대역으로 검증했다.
  - Backend·PostGIS 테스트는 API, DB, Backend 코드를 변경하지 않아 실행하지
    않았다.

## 영향 및 주의사항

- 평균점은 면적 중심(Centroid)이 아니라 사용자 요청에 따른 경계점 좌표의 산술
  평균이다.
- 미리보기 지도는 조회 전용이므로 사용자가 확대·축소하거나 이동할 수 없다.
- 등록 지도는 클릭 자동 확대만 제거했으며 직접 휠·Zoom Control 조작은 가능하다.
- 기존 Mission 상세 지도 Interaction은 변경하지 않았다.
- Backend Route, 권한, CSRF, Nginx 보안 Header·CSP는 변경하지 않았다.
- 이번 Build에는 작업 트리에 별도로 존재하는 Robot Telemetry Recharts 변경도
  함께 컴파일됐지만 이 작업 커밋에는 포함하지 않는다.

## 미완료 및 후속 작업

1. 배포 환경의 실제 Naver 지도에서 등록 지도 클릭 시 Zoom이 유지되고 선택
   미리보기가 평균점을 중앙에 두는지 수동 확인한다.
