# 변경 로그: Mission 지도 초기 구역 맞춤과 확대·축소 Overlay 동기화

- 작성 시각: 2026-08-01 22:41 (Asia/Seoul)
- 작업 유형: 수정
- 상태: 완료

## 작업 요약

Mission 상세 지도에서 Naver Tile은 표시되더라도 순찰 구역과 Robot Marker가 큰
음수 화면 좌표로 계산되어 보이지 않는 문제를 수정했다. 지도 생성 직후 너무 이른
`fitBounds` 호출을 없애고, 최초 중심을 임무 좌표로 설정한 다음 화면 Projection이
준비됐을 때 구역 전체 경계를 맞춘다. 확대·축소 Control을 명시적으로 표시하고
`zoom_changed`마다 순찰 구역, 실제 궤적, Robot Marker를 동일 Projection으로 다시
계산한다. 별도 `mission-time` E2E의 `/robots/map` Fixture 충돌도 정정했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/frontend/gilbom/src/features/mission/MissionMap.tsx` | 수정 | 최초 Map 중심을 Mission 좌표 경계 중심으로 설정하고 Projection 준비 확인 후 `fitBounds`를 적용했다. 우측 상단 SMALL 확대·축소 Control과 `zoom_changed` 재투영을 추가하고, 준비 실패 시 8초 뒤 좌표 Fallback으로 전환하도록 했다. |
| `web/frontend/gilbom/src/types/naver-maps.d.ts` | 수정 | Mission Map이 구독하는 `init`, `drag`, `dragend`, `zoom_changed` Event Type을 반영했다. |
| `web/frontend/gilbom/e2e/mission-map.spec.ts` | 수정 | Map 초기화·Idle, Zoom Control 옵션, Drag·Zoom Projection 변화를 모사하고 구역 Path와 Robot Marker가 함께 다시 계산되는지 검증했다. |
| `web/frontend/gilbom/e2e/mission-time.spec.ts` | 수정 | `/api/v1/robots/map`을 Mission `/map`으로 오인하던 범용 suffix Fixture를 정확한 Robot Map 응답으로 선점하고, Robot Catalog의 `missionStatus: null` 계약을 추가했다. |
| `CHANGLOGS/2026-08-01_2241_mission-map-viewport-zoom.md` | 추가 | 원인, 변경 범위, 로컬 실제 SDK 검증, 자동 검증과 운영 반영 주의사항을 기록했다. |

## 상세 내용

- 이전 구현은 Map을 구미 기본 좌표로 생성한 직후 React Effect에서 서울권 Mission
  좌표로 `fitBounds`를 호출했다. 실제 Naver SDK에서는 Map 중심 값만 바뀌고 화면
  Projection 원점이 이전 중심에 남아 Area Path와 Marker가 약 `-31,000px` 위치로
  계산될 수 있었다.
- 최초 Snapshot의 구역·궤적·현재 위치 전체 좌표로 경계 중심을 계산해 Map 생성
  시점부터 올바른 지역을 사용한다.
- `getProjection().fromCoordToOffset()` 결과가 유효하고 현재 Viewport에 가까워진
  뒤에만 `fitBounds`를 실행한다. 그 뒤 `idle` 또는 1초 안전 Timeout에서 Overlay를
  표시하므로 초기화 중 잘못된 화면 좌표가 노출되지 않는다.
- 좌표가 여러 개면 전체 Boundary가 보이도록 `fitBounds`, 하나면 Zoom 17을
  적용한다. Naver Projection이 8초 안에 준비되지 않으면 기존 좌표 SVG Fallback을
  사용한다.
- `drag`, `dragend`, `bounds_changed`, `size_changed`, `idle`에 더해
  `zoom_changed`도 같은 `requestAnimationFrame` 갱신 경로를 사용한다. 따라서
  구역·궤적·Robot Marker가 지도 확대 비율과 이동량을 함께 따른다.
- 확대·축소 Control은 `zoomControl: true`, 우측 상단 `ZoomControlStyle.SMALL`로
  명시했다.
- `mission-time.spec.ts` 실패는 제품 Catalog 오류가 아니었다. 범용
  `pathname.endsWith('/map')`가 `/api/v1/robots/map`까지 가로채 Mission Map DTO를
  반환했고, 신규 Robot 선택 규칙에 필요한 `missionStatus`도 Fixture에 없었다.
- Nginx, Compose, EC2 설정 파일은 변경하지 않았다. 로컬 실제 SDK 확인을 위해
  Docker Frontend Container만 중지하고 `127.0.0.1:3000` Vite Server를 실행했다.

## 검증

- 실행한 명령:
  - `npx eslint src/features/mission/MissionMap.tsx src/types/naver-maps.d.ts e2e/mission-map.spec.ts`
  - `npx playwright test e2e/mission-map.spec.ts`
  - `npm test`
  - `npm run build`
  - 실제 Naver SDK를 사용하는 Local Playwright 진단 (`http://localhost:3000/missions`)
  - `npx playwright test e2e/mission-time.spec.ts`
  - `npm run test:e2e:mission`
  - `git diff --check -- <이번 작업 대상 파일>`
  - `git diff -- web/frontend/nginx/server-common.conf web/frontend/nginx/site-http.conf web/frontend/nginx/site-https.conf`
- 결과:
  - 대상 ESLint 성공
  - Frontend 단위 테스트 38건 통과, 실패 0
  - TypeScript·Vite Production Build 성공
  - Mission Map E2E 1건 통과: Drag와 Zoom 뒤 Area Path·Robot Marker 동시 갱신,
    Zoom Control 활성화 확인
  - 전체 Mission E2E 3건 통과, 실패 0
  - 실제 Naver SDK에서 320×300 Viewport에 Tile Image 22개와 Zoom Markup 9개가
    생성됐고, 순찰 구역이 x=43.49~276.51, y=4.10~295.89 범위로 전체 표시됨
  - 대상 파일 whitespace 오류 없음
  - Nginx 3개 파일 Diff 없음
- 미실행 항목과 사유:
  - EC2에는 배포하지 않았다. 운영 Image 배포와 HTTPS Origin에서의 육안 확인은
    배포 권한과 Day 8 배포 절차가 필요한 후속 작업이다.

## 영향 및 주의사항

- 로컬 Docker Frontend의 HTTP Nginx 응답은 CSP가 Naver SDK의 HTTP 내부 요청을
  차단한다. Nginx를 변경하지 않는 조건에서는 현재 Local 확인 주소를 정확히
  `http://localhost:3000`으로 사용하고 Vite Server를 유지해야 한다.
- 운영 HTTPS에서는 Naver SDK 내부 요청도 HTTPS를 사용하므로, Naver Console에
  등록된 운영 HTTPS Origin과 최신 Frontend Image가 일치하면 지도 조건을 충족한다.
- Production Build에는 500kB 초과 Chunk 경고가 남지만 Build는 성공했으며 이번
  지도 동작의 차단 사유는 아니다.
- 기존 Docker Backend, DB, Mosquitto Container는 중지하거나 설정을 바꾸지 않았다.

## 미완료 및 후속 작업

1. 최신 Frontend Image를 Day 8 EC2 HTTPS 환경에 배포한다.
2. 운영 Mission 상세에서 Zoom +/-와 Mouse Drag를 실행해 Tile, 순찰 구역, 궤적,
   Robot Marker가 함께 이동·확대되는지 육안 확인한다.
3. 운영 확인이 끝난 뒤 Local Docker Frontend가 필요하면 Vite를 종료하고 Container를
   다시 시작한다. 단, HTTP Nginx에서는 현재 CSP 조건상 Naver Base Map이 보이지 않는다.
