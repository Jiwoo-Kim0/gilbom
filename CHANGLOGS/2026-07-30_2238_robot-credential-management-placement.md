# 변경 로그: Robot Credential 관리 위치 정정

- 작성 시각: 2026-07-30 22:38 (Asia/Seoul)
- 작업 유형: 수정
- 상태: 완료

## 작업 요약

MQTT Credential 발급·회전을 임무 생성 화면에서 제거하고 Robot 관리 화면으로
이동했다. Credential은 Robot 최초 프로비저닝 시 한 번 발급하고 분실·노출·정기
교체가 필요할 때만 회전한다는 수명주기를 화면에 명확히 안내한다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/frontend/gilbom/src/features/robot/RobotCredentialManager.tsx` | 추가 | `ROBOT_REGISTER` 권한 사용자에게 PROVISIONING Robot의 최초 발급·활성화와 ACTIVE Robot의 Credential 회전을 제공한다. 발급 확인 Modal, Broker 잔존 계정 복구, 동일 시도 멱등 키 유지, 발급 비밀번호 1회 표시를 구현했다. |
| `web/frontend/gilbom/src/features/robot/robot-credential-manager.css` | 추가 | Robot Credential 목록, 상태 Badge, 확인 Modal, 1회 발급 결과의 반응형 스타일을 정의했다. |
| `web/frontend/gilbom/src/features/robot/RobotManagePage.tsx` | 수정 | 기존 Robot 관리 화면에 `RobotCredentialManager`를 연결하고 발급 후 Robot 현황을 다시 불러오도록 했다. 같은 파일의 기존 미커밋 Robot 등록 작업은 변경하거나 이번 커밋 범위에 포함하지 않는다. |
| `web/frontend/gilbom/src/features/mission/MissionManagePage.tsx` | 수정 | 임무 생성 폼의 Credential 최초 발급·회전 버튼과 Modal을 제거하고 Robot 관리에서 처리한다는 안내만 남겼다. |
| `web/frontend/gilbom/src/features/mission/mission.css` | 수정 | 임무 화면에서 제거한 Credential 안내·Modal·결과 UI 전용 스타일을 삭제했다. |
| `web/frontend/gilbom/e2e/patrol-area-registration.spec.ts` | 수정 | 임무 생성 E2E에서 Credential 발급·회전 Fixture와 검증을 제거하고 순찰 구역 등록·선택·지도 범위 회귀 검증에 집중하도록 정리했다. |
| `web/frontend/gilbom/e2e/robot-credential-management.spec.ts` | 추가 | Robot 관리에서 최초 발급 충돌 복구, 동일 시도 멱등 키 재사용, 1회 비밀번호 표시, ACTIVE Robot 회전과 별도 멱등 키 사용을 검증한다. |
| `CHANGLOGS/2026-07-30_2238_robot-credential-management-placement.md` | 추가 | Credential 관리 위치 정정의 변경 범위와 검증 결과를 기록했다. |

## 상세 내용

- Robot 관리 화면은 PROVISIONING과 ACTIVE Robot을 각각 `최초 발급·활성화`,
  `Credential 회전`으로 구분해 표시한다.
- ACTIVE Robot 회전 전에는 기존 MQTT 비밀번호가 즉시 폐기됨을 확인
  Modal에서 안내한다.
- `ROBOT_CREDENTIAL_CONFLICT`가 발생하면 기존 정합성 복구 API를 사용해 Broker
  잔존 계정을 정리한 다음 같은 Idempotency-Key로 발급을 재시도한다.
- 발급 결과는 실제 Backend 계약인 `mqtt.brokerUri`, `mqtt.username`,
  `mqtt.password`, `mqtt.clientId`를 사용하며 Modal을 닫으면 메모리 상태에서
  제거한다.
- 임무 생성 화면은 ACTIVE이면서 현재 임무가 없는 Robot만 선택하며 Credential
  수명주기에는 관여하지 않는다.
- 인증, 권한 상수, CSRF 처리, Backend, Broker 및 기타 보안 설정은 변경하지
  않았다.
- 대화에 노출된 실제 Credential 값은 소스, 테스트 Fixture, 변경 로그에
  기록하지 않았다.

## 검증

- 실행한 명령:
  - `npx eslint src/features/mission/MissionManagePage.tsx src/features/robot/RobotCredentialManager.tsx src/features/robot/RobotManagePage.tsx e2e/patrol-area-registration.spec.ts e2e/robot-credential-management.spec.ts`
  - `npm test`
  - `npm run build`
  - `npx playwright test e2e/patrol-area-registration.spec.ts e2e/robot-credential-management.spec.ts --output=test-results/robot-credential-management`
  - `git diff --check -- <이번 작업 대상 파일>`
- 결과:
  - ESLint 통과
  - 단위 테스트 37건 통과
  - TypeScript 및 Vite 프로덕션 빌드 통과
  - Chromium E2E 2건 통과
  - 공백 오류 없음
- 미실행 항목과 사유:
  - 실제 운영 Robot의 Credential 회전은 기존 비밀번호를 즉시 폐기하는
    외부 상태 변경이므로 자동 검증에서 실행하지 않았다.

## 영향 및 주의사항

- Credential은 임무마다 바꾸지 않는다. 최초 등록 후 한 번 발급하고 노출·분실
  또는 정책상 교체 시에만 Robot 관리에서 회전한다.
- 회전 직후 기존 비밀번호는 사용할 수 없으므로 새 비밀번호를 Jetson의 보호된
  설정에 반영한 뒤 MQTT 재연결을 확인해야 한다.
- 채팅에 노출된 기존 Credential은 재사용하지 말고 새 UI에서 회전해야 한다.
- 프로덕션 빌드의 메인 JavaScript Chunk 크기 경고는 기존 기준과 같으며 빌드
  실패가 아니다.

## 미완료 및 후속 작업

- 최신 Frontend를 실행 환경에 반영한 뒤 Robot 관리에서 노출된 Credential을
  회전하고 새 값을 안전하게 보관한다.
- 새 Credential을 Jetson에 주입한 뒤 TLS MQTT CONNECT, Presence ONLINE,
  Telemetry 수신을 실제 장비로 확인한다.
