# 변경 로그: 임무 생성 Robot Credential 발급 및 활성화

- 작성 시각: 2026-07-30 21:56 (Asia/Seoul)
- 작업 유형: 기능
- 상태: 완료

## 작업 요약

로봇 등록 후 `PROVISIONING` 상태에 머물러 순찰 임무를 배정할 수 없던 흐름을 보완했다. 임무 생성 화면에서 기존 권한과 CSRF 검사를 통과한 관리자가 MQTT Credential 발급을 별도로 확인하면 Robot이 `ACTIVE`로 전환되고 임무 생성 후보로 자동 선택되도록 연결했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/frontend/gilbom/src/features/robot/robotCredentialApi.ts` | 추가 | 기존 `POST /api/v1/robots/{robotId}/credentials`를 호출하는 전용 Client와 1회 발급 응답 타입을 추가했다. 재시도 안전성을 위해 호출자가 전달한 `Idempotency-Key`와 공통 CSRF 재발급 정책을 사용한다. |
| `web/frontend/gilbom/src/features/mission/MissionManagePage.tsx` | 수정 | `ROBOT_REGISTER` 권한 사용자에게 임무 미수행 `PROVISIONING` Robot의 발급·활성화 동작을 제공한다. 확인 Modal에서 별도 발급을 수행하고 성공 후 1회 연결 정보를 표시하며, 목록을 갱신해 ACTIVE Robot을 생성 후보로 자동 선택한다. |
| `web/frontend/gilbom/src/features/mission/mission.css` | 수정 | 활성화 안내, 확인 Modal, 1회 Credential 결과를 위한 외부 CSS 스타일을 추가했다. |
| `web/frontend/gilbom/e2e/patrol-area-registration.spec.ts` | 수정 | PROVISIONING Robot 발급 요청의 CSRF·Idempotency-Key, 1회 비밀번호 표시, ACTIVE 전환 후 자동 선택과 기존 수행 중 Robot 제외를 검증한다. |
| `CHANGLOGS/2026-07-30_2156_mission-robot-credential-activation.md` | 추가 | 구현 범위, 검증 결과와 보안 경계를 기록했다. |

## 상세 내용

- 로봇 등록은 기존 계약대로 `PROVISIONING` 상태와 MQTT ID만 생성하며 Credential을 자동 발급하지 않는다.
- 임무 생성 목록에는 `ACTIVE`이면서 `missionStatus === null`인 Robot만 표시하는 기존 안전 조건을 유지했다.
- `PROVISIONING`이면서 임무가 없는 Robot은 별도 활성화 안내에 표시하고 관리자가 확인 Modal에서 발급을 명시적으로 실행한다.
- 발급 버튼은 기존 `ROBOT_REGISTER` permission이 있는 사용자에게만 표시하며 Backend의 동일 권한 검사를 우회하지 않는다.
- 발급 시 생성한 Idempotency-Key는 동일 Modal의 실패 후 재시도에서 유지한다.
- 성공하면 Backend가 Robot을 `ACTIVE`로 전환하고, Frontend는 Robot·구역·지도 목록을 다시 조회한 뒤 해당 Robot을 자동 선택한다.
- MQTT 비밀번호는 발급 성공 응답을 받은 Modal에서 한 번만 표시하고 Modal을 닫을 때 메모리 상태에서 제거한다.
- Backend, 인증·권한, CSRF, Nginx 및 기타 보안 설정은 변경하지 않았다.
- 기존 작업 트리의 다른 팀원 변경과 사용자 PNG는 수정하거나 커밋하지 않는다.

## 검증

- 실행한 명령:
  - `npx eslint src/features/mission/MissionManagePage.tsx src/features/robot/robotCredentialApi.ts e2e/patrol-area-registration.spec.ts`
  - `npm test`
  - `npm run build`
  - `npx playwright test e2e/patrol-area-registration.spec.ts --output=test-results/mission-robot-credential-activation`
  - `git diff --check`
- 결과:
  - ESLint 통과
  - 단위 테스트 37건 통과
  - TypeScript/Vite 프로덕션 빌드 통과(2,498개 모듈 변환)
  - Chromium E2E 1건 통과
  - E2E에서 발급 요청의 기존 CSRF token과 UUID Idempotency-Key 전달, 201 응답의 1회 비밀번호 표시, PROVISIONING에서 ACTIVE로 갱신 후 자동 선택 확인
  - 공백 오류 없음
- 미실행 항목과 사유:
  - 실제 `RBT-001` Credential 발급은 1회 비밀번호를 사용자가 직접 보관해야 하므로 최신 frontend 재빌드 후 로그인한 사용자 확인 동작으로 수행한다.

## 영향 및 주의사항

- 발급 성공 Modal을 닫으면 MQTT 비밀번호를 화면에서 다시 확인할 수 없다.
- 표시된 Broker·Username·Client ID·비밀번호는 승인된 경로로 실제 Robot에 주입해야 한다.
- 발급 API 또는 Broker 반영이 실패하면 Robot은 PROVISIONING 상태에 남고 같은 Idempotency-Key로 안전하게 재시도한다.
- 빌드에서 기존 메인 JavaScript 청크 크기 경고가 출력됐으며 이번 기능 실패는 아니다.
- 임무 생성·Credential Backend 계약이나 보안 정책은 변경하지 않았다.

## 미완료 및 후속 작업

- Docker frontend를 최신 소스로 재빌드한 후 임무 생성 화면에서 `RBT-001`의 발급·활성화를 확인한다.
- 발급 성공 후 표시되는 연결 정보를 실제 Robot에 주입하고 새 Credential로 MQTT TLS 재연결을 검증한다.
