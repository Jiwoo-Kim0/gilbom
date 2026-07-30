# 변경 로그: 순찰 구역 전용 페이지와 미션 구역 선택

- 작성 시각: 2026-07-30 20:06 (Asia/Seoul)
- 작업 유형: 기능
- 상태: 완료

## 작업 요약

순찰 임무 화면에 함께 있던 구역 등록 기능을 `/patrol-areas` 전용 페이지로
분리했다. 전용 페이지에서 Naver 지도 경계점을 순서대로 선택해 ACTIVE 순찰
구역을 등록하고 등록 구역 목록을 확인할 수 있다. `/missions`의 임무 생성
폼은 기존처럼 서버에서 조회한 ACTIVE 구역만 선택 목록에 표시하며, 구역 관리
페이지로 이동하는 링크를 제공한다.

기존 `MISSION_VIEW`, `MISSION_MANAGE` 권한과 `/api/v1/patrol-areas` 계약을
그대로 사용했으며 Backend, Spring Security, CSRF, Nginx CSP 설정은 변경하지
않았다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/frontend/gilbom/src/app/AppLayout.tsx` | 수정 | `/patrol-areas` Route의 상단 제목을 `순찰 구역 관리`로 등록했다. |
| `web/frontend/gilbom/src/app/router.tsx` | 수정 | `MISSION_VIEW`가 필요한 `/patrol-areas` Route와 `PatrolAreaManagePage`를 연결했다. |
| `web/frontend/gilbom/src/features/navigationbar.tsx` | 수정 | `MISSION_VIEW` 보유 사용자에게 순찰 구역 관리 메뉴를 표시하도록 추가했다. |
| `web/frontend/gilbom/src/features/mission/PatrolAreaManagePage.tsx` | 추가 | 등록 구역 요약·목록, 지도 Polygon 등록 폼, 직접 좌표 사각형 대체 입력, `MISSION_MANAGE` 등록 권한 분기를 구현했다. |
| `web/frontend/gilbom/src/features/mission/PatrolAreaBoundaryEditor.tsx` | 추가 | Naver 지도 클릭으로 번호 Marker와 Polyline·Polygon을 그리고 경계점 취소·개별 삭제·초기화를 지원한다. |
| `web/frontend/gilbom/src/features/mission/patrolAreaBoundary.ts` | 추가 | 지도 점 배열을 닫힌 GeoJSON Polygon으로 변환하고 면적 유효성을 검사하며, 직접 좌표 입력 시 사각형 Polygon을 생성한다. |
| `web/frontend/gilbom/src/features/mission/patrol-area-boundary-editor.css` | 추가 | 지도 경계 편집기, 로딩·대체 입력 안내, 경계점 목록의 반응형 스타일을 정의했다. |
| `web/frontend/gilbom/src/features/mission/MissionManagePage.tsx` | 수정 | 인라인 구역 등록을 제거하고 전용 구역 관리 링크를 추가했다. 임무 생성 폼은 등록된 ACTIVE 구역만 선택한다는 안내를 표시한다. |
| `web/frontend/gilbom/src/features/mission/mission.css` | 수정 | 전용 등록 폼의 입력 방식 전환, 구역 목록, lifecycle 상태와 미션 선택 가능 표시 스타일을 추가했다. |
| `web/frontend/gilbom/src/features/mission/missionApi.ts` | 수정 | 공통 API Client로 `POST /api/v1/patrol-areas`를 호출하는 `createPatrolArea()`를 추가했다. |
| `web/frontend/gilbom/src/features/mission/missionTypes.ts` | 수정 | `[longitude, latitude]` GeoJSON 좌표와 순찰 구역 생성 요청 타입을 명시했다. |
| `web/frontend/gilbom/src/types/naver-maps.d.ts` | 수정 | 지도 click Event와 Listener 타입을 추가해 경계점 입력을 타입 안전하게 처리했다. |
| `web/frontend/gilbom/playwright.config.ts` | 수정 | Browser E2E의 지도 SDK 대역이 동작하도록 테스트용 Naver 지도 Client ID 환경값을 주입했다. |
| `web/frontend/gilbom/package.json` | 수정 | 미션 E2E 명령에 순찰 구역 등록·미션 선택 시나리오를 포함했다. 기존의 별도 `recharts` 변경은 이번 작업 범위가 아니다. |
| `web/frontend/gilbom/e2e/patrol-area-registration.spec.ts` | 추가 | 전용 페이지에서 3개 경계점을 찍어 닫힌 GeoJSON을 전송하고, 등록 목록 확인 후 미션 화면에서 같은 구역을 선택해 임무를 생성하는 흐름을 검증한다. |
| `CHANGLOGS/2026-07-30_2006_patrol-area-page-mission-selection.md` | 추가 | 이번 기능 범위, 검증 결과, 보안·Backend 무변경과 후속 범위를 기록했다. |

## 상세 내용

- `/patrol-areas`는 `MISSION_VIEW` Route Guard 안에 두어 등록 구역을 조회할 수
  있게 하고, 등록 폼은 `MISSION_MANAGE` 보유 사용자에게만 표시한다.
- 지도에서 선택한 좌표는 GeoJSON 순서인 `[longitude, latitude]`로 유지하고,
  마지막에 첫 좌표를 한 번 덧붙여 닫힌 Polygon Ring으로 전송한다.
- 서로 다른 점 3개 미만이거나 Shoelace 계산 결과 면적이 없는 Polygon은
  Frontend에서 등록을 차단한다.
- 지도 SDK를 사용할 수 없는 경우에는 중심 위도·경도와 가로폭을 입력해
  사각형 Polygon을 생성할 수 있다.
- 등록 완료 후 전용 페이지의 카탈로그를 다시 조회한다. 새 ACTIVE 구역은
  `/missions` 진입 시 다시 조회되어 순찰 구역 Select에 나타난다.
- Mission 생성 시 선택 구역의 `AUTONOMOUS_DEFAULT` 경로를 조회해 기존
  `patrolRouteId` API·DB 호환 계약을 유지한다.
- 보안 설정을 수정하지 않았다. 기존 공통 API Client가 POST 요청의 CSRF
  Token을 처리하며 E2E에서 해당 Header도 검증했다.

## 검증

- 실행한 명령:
  - `npx eslint src/app/AppLayout.tsx src/app/router.tsx src/features/navigationbar.tsx src/features/mission/MissionManagePage.tsx src/features/mission/PatrolAreaManagePage.tsx src/features/mission/PatrolAreaBoundaryEditor.tsx src/features/mission/patrolAreaBoundary.ts e2e/patrol-area-registration.spec.ts`
  - `npm run build`
  - `npm test`
  - `npx playwright test e2e/patrol-area-registration.spec.ts --output=test-results/patrol-area-separate-page`
  - `npx playwright test e2e/mission-time.spec.ts e2e/mission-map.spec.ts e2e/patrol-area-registration.spec.ts --output=test-results/patrol-area-mission-regression`
  - `git diff --check -- <이번 작업 대상 Frontend 파일>`
- 결과:
  - 대상 ESLint 통과
  - TypeScript·Vite Production Build 통과, 1,920개 Module 변환 완료
  - Frontend Node 단위 테스트 31건 통과, 실패·건너뜀 0건
  - 전용 구역 등록·미션 선택 E2E 1건 통과
  - 미션 시간·지도·구역 등록 회귀 E2E 3건 통과
  - 등록 POST의 CSRF Header와 닫힌 GeoJSON 좌표 Payload 검증 통과
  - 대상 파일 whitespace 오류 없음
  - Build의 기존 500kB 초과 JavaScript Chunk 경고만 있으며 실패는 없음
- 미실행 항목과 사유:
  - 실제 운영 Naver 지도에서 사람의 클릭으로 등록하는 수동 검증은 운영
    Client ID와 허용 도메인을 사용하는 배포 환경 검증이 필요해 실행하지 않았다.
    지도 SDK Event는 동일 인터페이스의 Browser 대역으로 E2E 검증했다.
  - Backend·PostGIS 테스트는 API 계약과 Backend 코드를 변경하지 않아 이번
    최종 검증에서는 다시 실행하지 않았다.

## 영향 및 주의사항

- 미션 생성 화면에는 `lifecycleStatus === ACTIVE`인 구역만 나타난다.
- 등록 즉시 ACTIVE가 되며 Backend가 기존 정책대로
  `AUTONOMOUS_DEFAULT` 내부 경로를 자동 생성해야 Mission 생성이 가능하다.
- 등록 구역의 수정·비활성화·보관 기능은 이번 페이지에 추가하지 않았다.
- Naver 지도 Client ID가 없거나 SDK 로딩에 실패하면 직접 좌표 입력으로
  전환된다.
- Backend Route, 권한 상수, CSRF 정책, Nginx 보안 Header/CSP는 변경하지
  않았다.

## 미완료 및 후속 작업

1. 배포 환경의 실제 Naver 지도에서 Polygon 경계점 선택과 등록을 수동 확인한다.
2. 운영 요구가 확정되면 별도 Jira로 구역 수정, INACTIVE 전환, ARCHIVED
   전환 UI를 추가한다.
