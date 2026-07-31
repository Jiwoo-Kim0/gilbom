# 변경 로그: Credential 응답 표시 및 임무 지도 범위 보정

- 작성 시각: 2026-07-30 22:23 (Asia/Seoul)
- 작업 유형: 수정
- 상태: 완료

## 작업 요약

Credential 발급 성공 후 비밀번호가 비어 보이던 응답 계약 불일치와, 순찰 구역에서 멀리 있는 Robot GPS 때문에 임무 생성 지도가 전국 단위로 축소되던 문제를 수정했다. 실제 Backend 응답의 `mqtt` 객체를 사용하고, 이미 ACTIVE인 Robot은 Credential 회전으로 새 1회 비밀번호를 받을 수 있게 했다. 지도 Bounds는 선택 구역만 기준으로 유지한다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/frontend/gilbom/src/features/robot/robotCredentialApi.ts` | 수정 | 발급 응답 타입을 실제 Backend `MqttCredentialIssuanceResponse` 계약의 `mqtt` 필드와 일치시켰다. |
| `web/frontend/gilbom/src/features/mission/MissionManagePage.tsx` | 수정 | Broker·Username·Client ID·비밀번호를 `mqtt` 응답에서 표시하고, 선택한 ACTIVE Robot에 Credential 회전 확인 Modal을 제공한다. 회전 시 기존 비밀번호 폐기와 새 비밀번호 1회 표시를 안내한다. |
| `web/frontend/gilbom/src/features/mission/PatrolAreaSelectionPreview.tsx` | 수정 | Naver 지도와 SVG 대체 화면의 확대 범위를 순찰 구역 경계점만으로 계산한다. Robot GPS는 위치 정보와 투영 Marker에 사용하지만 멀리 있는 GPS가 구역 지도 Zoom을 변경하지 못하게 했다. |
| `web/frontend/gilbom/src/features/mission/mission.css` | 수정 | Robot 선택 필드와 Credential 회전 버튼 배치를 위한 스타일을 추가했다. |
| `web/frontend/gilbom/e2e/patrol-area-registration.spec.ts` | 수정 | 실제 `mqtt.password` 응답, ACTIVE Credential 회전과 새 Idempotency-Key, 구미 구역과 서울 Robot GPS 조합에서 구역 Bounds 유지 및 평균 중심을 검증한다. |
| `CHANGLOGS/2026-07-30_2223_credential-response-and-map-bounds.md` | 추가 | 증상, 원인, 변경 범위와 검증 결과를 기록했다. |

## 상세 내용

- 실제 Backend 응답 필드는 `connection`이 아니라 `mqtt`였으나 Frontend 타입과 E2E Fixture가 `connection`을 사용해 성공 응답의 연결 정보가 빈칸으로 렌더링됐다.
- 실제 로컬 DB에서 `RBT-001`은 Credential v1 발급과 함께 ACTIVE로 전환된 것을 확인했다.
- v1 비밀번호 원문은 Backend와 DB에서 재조회할 수 없으므로 선택한 ACTIVE Robot에 기존 발급 API를 다시 호출하는 회전 동작을 제공한다.
- 회전은 별도 확인 Modal을 사용하고, 기존 비밀번호가 즉시 폐기되며 새 비밀번호가 한 번만 표시됨을 안내한다.
- 회전 시 이전 Modal의 Idempotency-Key를 재사용하지 않고 새 발급 시도 키를 생성한다.
- 실제 순찰 구역 `구미-1`은 경도 128.3426438~128.3473537, 위도 36.1183062~36.1206288이고 `RBT-001` 최신 GPS는 서울 인근 37.5013, 127.0396으로 확인됐다.
- 지도 `fitBounds`와 SVG 대체 투영 범위에서 Robot 좌표를 제외해 순찰 구역 평균 중심과 확대 수준을 유지한다.
- 멀리 있는 Robot Marker는 지도 밖에서 잘리고, 하단 선택 Robot 요약에는 최신 GPS가 계속 표시된다.
- Backend, 인증·권한, CSRF, Nginx 및 기타 보안 설정은 변경하지 않았다.

## 검증

- 실행한 명령:
  - `npx eslint src/features/mission/MissionManagePage.tsx src/features/mission/PatrolAreaSelectionPreview.tsx src/features/robot/robotCredentialApi.ts e2e/patrol-area-registration.spec.ts`
  - `npm test`
  - `npm run build`
  - `npx playwright test e2e/patrol-area-registration.spec.ts --output=test-results/mission-credential-response-map-bounds`
  - `npx playwright test e2e/patrol-area-registration.spec.ts --output=test-results/mission-credential-response-map-bounds-final`
  - `git diff --check`
- 결과:
  - ESLint 통과
  - 단위 테스트 37건 통과
  - TypeScript/Vite 프로덕션 빌드 통과(2,498개 모듈 변환)
  - 첫 Chromium E2E는 기능 오류가 아닌 `Credential 회전` 부분 일치 버튼 두 개로 인한 strict locator 충돌로 실패
  - Modal 버튼을 `exact` locator로 보정한 최종 Chromium E2E 1건 통과
  - 최종 E2E에서 `mqtt.password` 1회 표시, ACTIVE 회전의 새 Idempotency-Key, 멀리 있는 Robot GPS와 무관한 순찰 구역 Bounds 및 평균 중심 유지 확인
  - 공백 오류 없음
- 미실행 항목과 사유:
  - 실제 `RBT-001` Credential 회전은 새 비밀번호를 사용자가 직접 보관해야 하므로 최신 frontend에서 사용자 확인 동작으로 수행한다.

## 영향 및 주의사항

- 현재 발급된 v1 비밀번호를 화면에서 보지 못했다면 ACTIVE Robot의 `Credential 회전`으로 새 비밀번호를 받아야 한다.
- 회전 성공 즉시 기존 비밀번호는 Broker에서 사용할 수 없게 된다.
- 구역에서 멀리 있는 Robot은 Polygon 지도 안에 Marker가 보이지 않지만 최신 GPS 텍스트는 유지된다.
- 실제 Robot과 구역을 동시에 전국 축척으로 표시하는 대신 임무 대상 구역의 가독성을 우선한다.
- 빌드에서 기존 메인 JavaScript 청크 크기 경고가 출력됐으며 이번 수정의 실패는 아니다.

## 미완료 및 후속 작업

- Docker frontend를 최신 소스로 재빌드한 후 `RBT-001 Credential 회전`을 실행하고 새 비밀번호를 안전하게 보관한다.
- 새 Credential을 Jetson 환경변수에 주입한 뒤 MQTT TLS 재연결을 확인한다.
