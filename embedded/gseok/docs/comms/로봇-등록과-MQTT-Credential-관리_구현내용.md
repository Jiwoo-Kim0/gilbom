# Robot 등록·MQTT Credential 관리 구현 내용

> 담당: 팀원 B — Robot·MQTT Provisioning
> 작업일: 2026-07-29 ~ 2026-07-30
> 연결 Jira: `S15P11D101-434`, Robot 등록·Credential Provisioning
> 최종 상태: **DONE (LOCAL)** — 등록·발급·회전·Registry 복구 UI 검증 완료, Jetson 주입 대기

이 문서는 관리자가 Robot을 등록한 뒤 MQTT Credential을 최초 발급하고 실제
Robot에 전달하는 **Provisioning 흐름만** 묶는다. Broker TLS·ACL·Credential
Backend 정본은
[MQTT v2 계약과 통신 보안](MQTT-v2-계약과-통신보안_구현내용.md)에서 관리한다.

관련 정본:
[Robot 등록·Credential 발급](../V2/로봇%20등록%20및%20MQTT%20Credential%20발급.md),
[Robot 환경변수 전달](../V2/로봇%20MQTT%20환경변수%20및%20Credential%20전달.md),
[OpenAPI](../../openapi-v1.yaml)

---

## 0. 3분 요약

Robot 등록과 MQTT 비밀번호 발급은 같은 동작이 아니다.

```text
Robot 등록
→ PROVISIONING + mqttId 생성
→ Robot 관리에서 최초 Credential 발급 확인
→ Broker 계정 반영 + Robot ACTIVE
→ 비밀번호를 성공 Modal에서 한 번만 표시
→ Jetson 보호 설정에 한 번 주입
→ 노출·분실·정기 교체 때만 회전
```

임무를 할당할 때마다 Credential을 바꾸지 않는다. 최초 발급과 회전은 Robot 관리
화면의 수명주기 작업이고, 임무 생성 화면은 `ACTIVE`이면서 현재 임무가 없는
Robot만 선택한다.

DB에는 활성 Credential이 없지만 Mosquitto에 같은 계정이 남은 경우
`ROBOT_CREDENTIAL_CONFLICT`를 표시하고, 기존 Registry 정합성 복구 API로 잔존
계정을 정리한 뒤 같은 사용자 시도의 Idempotency-Key로 발급을 재시도한다.

---

## 1. 요구사항과 수용 기준

### 1.1 Robot 등록

- `POST /api/v1/robots`
- 입력: `robotName`, `serialNumber`
- Permission: `ROBOT_REGISTER`
- CSRF와 UUID `Idempotency-Key`
- 성공 시 `robotId`, `mqttId`, `PROVISIONING` 상태 표시
- 등록 후 Robot 목록·요약·지도를 다시 조회

### 1.2 Credential 최초 발급

- `POST /api/v1/robots/{robotId}/credentials`
- PROVISIONING Robot을 ACTIVE로 전환
- Broker URI·Username·Client ID·비밀번호를 성공 응답의 `mqtt` 객체로 표시
- 비밀번호는 성공 직후 Modal에서 한 번만 표시
- Modal을 닫으면 Browser 메모리 상태에서 제거
- 동일 발급 시도의 실패 후 재시도는 같은 Idempotency-Key 유지

### 1.3 회전·Registry 복구

- ACTIVE Robot은 노출·분실·정책상 교체 시만 회전
- 회전 성공 즉시 기존 비밀번호 폐기
- 새 회전 시도는 새 Idempotency-Key 사용
- 최초 발급 충돌은
  `/credentials/registry-reconcile`로 정합성 복구 후 재발급
- Password file이나 DB를 Frontend에서 직접 수정하지 않음

### 1.4 Mission 경계

- Mission 화면에서 Credential을 발급·회전하지 않음
- Mission 생성 후보: `operationalStatus === ACTIVE`
- 현재 임무가 없는 Robot만 후보
- Credential 관리 실패가 Mission 계약을 우회하지 않음

---

## 2. 무엇을 만들었나

### 2.1 Robot 등록 UI

```text
features/robot/RobotManagePage.tsx
features/robot/robotApi.ts
features/robot/robotTypes.ts
app/robot/css/robot.css
```

Robot 관리 화면에 권한 기반 등록 버튼, 입력 검증, 확인 Modal, 오류·성공 안내와
등록 후 Snapshot 갱신을 추가했다.

### 2.2 Credential 관리 UI

```text
features/robot/RobotCredentialManager.tsx
features/robot/robotCredentialApi.ts
features/robot/robot-credential-manager.css
e2e/robot-credential-management.spec.ts
```

PROVISIONING Robot에는 `최초 발급·활성화`, ACTIVE Robot에는
`Credential 회전`을 표시한다. 발급 결과는 실제 Backend 응답의
`mqtt.brokerUri`, `mqtt.username`, `mqtt.clientId`, `mqtt.password`를 사용한다.

### 2.3 Backend 재사용

새 보안 정책을 추가하지 않고 기존 Credential 생명주기를 사용한다.

```text
MqttCredentialIssuanceController
MqttCredentialIssuanceService
MqttCredentialRegistryReconciliationService
MosquittoCredentialRegistry
```

기존 Backend가 발급·회전·폐기·Registry 정합성·감사·Scope를 최종 통제한다.

---

## 3. 설계 판단과 그 이유

### 3.1 등록과 Secret 발급을 분리했다

Robot identity를 만들었다고 Broker 비밀번호까지 자동 생성하면 Secret을 받을
관리자가 준비되지 않은 상태에서 1회 값이 소실될 수 있다. 등록은 PROVISIONING,
명시적 확인 뒤 발급은 ACTIVE 전환으로 분리했다.

### 3.2 Credential 관리를 Mission에서 제거했다

Credential은 Robot 연결 수명주기이고 Mission마다 달라지는 값이 아니다. 임무
화면에 두면 사용자가 할당 때마다 회전해야 한다고 오해하고 기존 Robot 연결을
끊을 수 있어 Robot 관리 화면으로 이동했다.

### 3.3 비밀번호를 재조회하지 않는다

Backend와 DB는 원문을 보관·반환하지 않는다. 사용자가 성공 Modal을 놓치면 기존
값을 복구하는 대신 회전해 새 1회 Secret을 받아야 한다.

### 3.4 Registry 충돌은 기존 복구 API를 사용한다

Mosquitto password file을 수동 편집하면 DB Registry·감사와 다시 어긋난다.
DB 정본을 기준으로 하는 Reconcile API를 호출하고 동일 발급 시도를 재사용한다.

### 3.5 Jetson 반영은 최초 또는 회전 때만 한다

발급받은 값은 Jetson의 보호된 환경변수나 Secret 파일에 주입한다. 회전하지 않는
한 임무를 새로 만들 때 다시 변경하지 않는다.

---

## 4. 검증과 재현 명령

Frontend 정적·단위 검증:

```powershell
cd web/frontend/gilbom
npm test
npm run lint -- --no-cache
npm run build
```

Credential Browser E2E:

```powershell
cd web/frontend/gilbom
npx playwright test `
  e2e/patrol-area-registration.spec.ts `
  e2e/robot-credential-management.spec.ts `
  --output=test-results/robot-credential-management
```

완료 결과:

- Robot 등록 대상 ESLint와 Production Build 통과
- Frontend 단위 테스트 37건 통과
- 전체 ESLint·Production Build 통과
- Robot Credential·순찰 구역 회귀 Chromium E2E 2건 통과
- 최초 발급 충돌→Registry 복구→같은 키 재발급 확인
- `mqtt.password` 1회 표시 확인
- ACTIVE Robot 회전이 별도 Idempotency-Key를 사용하는지 확인
- 보안·CSRF·RBAC·Broker 설정 변경 없음

실제 운영 Robot의 회전은 기존 비밀번호를 즉시 폐기하는 외부 상태 변경이므로
자동 검증에서 수행하지 않았다.

---

## 5. 작업 중 겪은 함정

### 등록만 하면 Mission 후보가 되지 않았다

등록 직후 상태는 PROVISIONING이다. Credential 발급 성공으로 ACTIVE가 된 뒤에야
임무 미수행 Robot 후보에 나타난다.

### 응답 필드 이름이 달라 비밀번호가 빈칸으로 보였다

Frontend 초기 타입은 `connection`을 예상했지만 실제 응답은 `mqtt` 객체였다.
OpenAPI와 실제 DTO를 기준으로 타입·Fixture를 맞췄다.

### Broker 잔존 계정이 최초 발급을 막았다

DB 활성 Credential은 없지만 Mosquitto에 Username이 남아 있으면 충돌한다.
수동 파일 편집 대신 Registry Reconcile 후 같은 발급 시도를 재개한다.

### 회전은 기존 Robot을 즉시 Offline으로 만들 수 있다

새 Secret을 Jetson에 반영하기 전에 회전하면 기존 연결이 다시 인증되지 못한다.
새 값을 안전하게 받을 준비가 된 관리자만 확인 Modal에서 실행해야 한다.

### Credential을 Mission UI에 두면 수명주기를 오해한다

임무별 토큰처럼 보이지만 실제로는 Robot MQTT 계정이다. 관리 위치를 Robot
화면으로 옮겨 최초 발급·회전의 의미를 분리했다.

---

## 6. 미완 항목

### 내 몫

- 최신 Frontend 실행 환경에서 실제 Robot 등록·최초 발급 흐름 수동 확인
- 회전 직후 Robot 목록·ONLINE 상태와 Broker Registry 상태 대조
- 실제 Credential을 출력·로그에 남기지 않는 운영 절차 재확인

### 팀원 A

- EC2 Broker Secret 전달 경로와 운영 관리자 접근 통제
- 운영 로그·감사에서 Credential 원문이 노출되지 않는지 점검

### Embedded팀

- 발급받은 Broker URI·Username·Client ID·비밀번호를 Jetson 보호 설정에 주입
- TLS MQTT CONNECT, Presence ONLINE, Telemetry 수신 확인
- 회전 시 기존 연결 종료와 새 Credential 재연결 절차 검증

---

## 7. 관련 문서와 커밋

- `CHANGLOGS/2026-07-30_1602_robot-registration-ui.md`
- `CHANGLOGS/2026-07-30_2156_mission-robot-credential-activation.md`
- `CHANGLOGS/2026-07-30_2203_mqtt-credential-registry-conflict-recovery.md`
- `CHANGLOGS/2026-07-30_2223_credential-response-and-map-bounds.md`
- `CHANGLOGS/2026-07-30_2238_robot-credential-management-placement.md`
- Commit `baed2a6` — `feat: 로봇 등록 관리 UI`
- Commit `85986b5` — Credential 관리를 Robot 관리 화면으로 이동
- [MQTT v2 계약과 통신 보안](MQTT-v2-계약과-통신보안_구현내용.md)
- [팀원 B Story 현황](../../2026-07-28-story-subtask-status.md)
