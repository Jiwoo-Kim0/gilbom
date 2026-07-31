# 변경 로그: MQTT Credential Registry 충돌 복구

- 작성 시각: 2026-07-30 22:03 (Asia/Seoul)
- 작업 유형: 수정
- 상태: 완료

## 작업 요약

DB에는 활성 Credential이 없지만 Mosquitto에 같은 Robot 계정이 남아 있어 최초 발급이 `ROBOT_CREDENTIAL_CONFLICT`로 실패하는 경우를 처리했다. 임무 생성의 발급 Modal에서 기존 Registry 정합성 복구 API로 잔존 계정을 제거한 뒤 같은 Idempotency-Key로 Credential 발급을 다시 수행할 수 있게 했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/frontend/gilbom/src/features/robot/robotCredentialApi.ts` | 수정 | 기존 `POST /api/v1/robots/{robotId}/credentials/registry-reconcile` Client와 Secret을 포함하지 않는 Registry 상태 응답 타입을 추가했다. |
| `web/frontend/gilbom/src/features/mission/MissionManagePage.tsx` | 수정 | `ROBOT_CREDENTIAL_CONFLICT`를 식별해 Registry Drift 원인과 복구 동작을 표시한다. 사용자가 `잔존 계정 정리 후 다시 발급`을 선택하면 기존 복구 API 호출 뒤 최초 발급을 재시도하고 ACTIVE 후보 갱신을 이어간다. |
| `web/frontend/gilbom/src/features/mission/mission.css` | 수정 | Registry Drift 복구 안내 영역 스타일을 추가했다. |
| `web/frontend/gilbom/e2e/patrol-area-registration.spec.ts` | 수정 | 첫 발급 409 충돌, 정합성 복구 요청의 CSRF·감사 사유, 복구 후 재발급과 동일 Idempotency-Key 유지, ACTIVE 자동 선택을 검증한다. |
| `CHANGLOGS/2026-07-30_2203_mqtt-credential-registry-conflict-recovery.md` | 추가 | 충돌 원인, 복구 범위와 검증 결과를 기록했다. |

## 상세 내용

- 실제 로컬 상태는 `RBT-001`이 `PROVISIONING`, DB Credential 없음, Mosquitto 동일 계정 존재로 확인됐다.
- 발급 오류가 `ROBOT_CREDENTIAL_CONFLICT`일 때만 정합성 복구 버튼을 표시한다.
- 복구 API는 DB를 기준으로 동작하므로 활성 DB Credential이 없는 현재 사례에서는 Broker의 잔존 계정을 제거한다.
- 복구 감사 사유는 `임무 배정 전 잔존 MQTT Broker 계정 정리`로 전송한다.
- 복구가 완료되면 사용자가 처음 발급을 시도할 때 만든 Idempotency-Key를 그대로 사용해 발급을 다시 수행한다.
- 복구 또는 재발급 실패 시 오류를 Modal에 유지하고 Robot은 PROVISIONING 상태에 남긴다.
- Broker password-file이나 DB를 Frontend에서 직접 수정하지 않는다.
- Backend, 인증·권한, CSRF, Nginx 및 기타 보안 설정은 변경하지 않았다.

## 검증

- 실행한 명령:
  - `npx eslint src/features/mission/MissionManagePage.tsx src/features/robot/robotCredentialApi.ts e2e/patrol-area-registration.spec.ts`
  - `npm test`
  - `npm run build`
  - `npx playwright test e2e/patrol-area-registration.spec.ts --output=test-results/mission-robot-registry-reconcile`
  - `git diff --check`
- 결과:
  - ESLint 통과
  - 단위 테스트 37건 통과
  - TypeScript/Vite 프로덕션 빌드 통과(2,498개 모듈 변환)
  - Chromium E2E 1건 통과
  - E2E에서 409 `ROBOT_CREDENTIAL_CONFLICT`, Registry Reconcile의 CSRF와 감사 사유, 복구 전후 발급의 동일 Idempotency-Key, 1회 비밀번호 표시와 ACTIVE 자동 선택 확인
  - 공백 오류 없음
- 미실행 항목과 사유:
  - 실제 `RBT-001`의 잔존 계정 정리와 신규 Credential 발급은 1회 비밀번호를 사용자가 직접 보관해야 하므로 최신 frontend에서 사용자 확인 동작으로 수행한다.

## 영향 및 주의사항

- 복구 동작은 `ROBOT_REGISTER` 권한과 Backend의 Robot Scope 검사를 그대로 적용한다.
- 활성 DB Credential이 존재하는 다른 Drift 유형에서는 기존 verifier를 Broker로 복구하며, 이 화면은 최초 발급 충돌에서만 복구 동작을 안내한다.
- Registry 복구와 신규 발급은 각각 기존 필수 감사 로그를 남긴다.
- 발급 성공 Modal을 닫으면 신규 비밀번호는 다시 표시되지 않는다.
- 빌드에서 기존 메인 JavaScript 청크 크기 경고가 출력됐으며 이번 수정의 실패는 아니다.

## 미완료 및 후속 작업

- Docker frontend를 최신 소스로 재빌드한 뒤 `RBT-001`에서 `잔존 계정 정리 후 다시 발급`을 실행한다.
- 새 연결 정보를 실제 Robot에 주입하고 MQTT TLS 재연결을 확인한다.
