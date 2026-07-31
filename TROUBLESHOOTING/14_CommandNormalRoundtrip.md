# Command 정상 왕복 E2E 트러블슈팅

- 작업일: 2026-07-30
- 연결 작업: `DAY6-B-03-1`
- 범위: React 확인 Modal → REST → DB → TLS MQTT → Mock Robot →
  ACK/Result → DB → SSE → React REST 정본 재조회

## 최종 결과

다음 여섯 정상 Command를 하나의 Browser 시나리오에서 순서대로 실행했다.

1. `START_MISSION`
2. `PAUSE_MISSION`
3. `RESUME_MISSION`
4. `EMERGENCY_STOP`
5. `CLEAR_EMERGENCY_STOP`
6. `CANCEL_MISSION`

각 Command는 고유 `commandId`로 `COMPLETED`가 됐고, Result는 정확히
`ACCEPTED` 1건과 `COMPLETED` 1건이 저장·표시됐다. 최종 Mission은
`CANCELLED`, Robot mode는 `READY`, Robot의 Mission 상태는 비어 있었다.

## 전체 수행 작업

### Frontend 연결

- Robot 현재 상태의 Mission 값이 아직 없고 DB에 `READY` Mission만 있는 시작 전
  상태에서도 제어 정책이 동작하도록, Robot 상태를 우선하고 Mission REST 조회값을
  보조로 사용하는 `effectiveMissionStatus`를 적용했다.
- 기존 Robot 상세 SSE 연결을 재사용해 같은 Robot의
  `COMMAND_STATUS_CHANGED`와 재연결을 Command 갱신 Revision으로 전달했다.
- 최근 Command 목록과 상세 Drawer가 REST 정본을 다시 읽도록 연결했다.
- Browser E2E에서 확인 Modal을 실제로 조작하고 POST 응답의 `commandId`와
  목록·상세 Result를 대조했다.

### 재현 Harness

- `web/scripts/command-roundtrip-e2e.py`를 추가했다.
- 현재 Backend 이미지를 빌드하고 별도 PostgreSQL Database와 임시 Backend를
  만든다.
- 공개 등록·MFA를 거친 임시 Manager와 Robot Scope를 만들고 Patrol Area,
  내부 `AUTONOMOUS_DEFAULT` Route, Mission Fixture를 준비한다.
- 실제 TLS Mosquitto에 Python Mock Robot을 연결하고 자동 Mission 완료는
  비활성화한다.
- Vite와 Chromium을 기동해 실제 React 화면에서 여섯 Command를 실행한다.
- Browser 검증 뒤 DB의 Command·Result 개수와 Mission·Robot 최종 상태를
  직접 대조한다.
- 완료 또는 실패와 관계없이 임시 Backend·Database·Mock·Vite를 정리하고 기존
  Backend를 다시 기동한다.

### Browser 검증

- `web/frontend/gilbom/e2e/command-roundtrip-live.spec.ts`를 추가했다.
- 명령별 버튼 활성 조건, 확인 Modal, 사유와 안전 확인 Checkbox를 조작한다.
- POST가 `201`이고 요청 Command Type과 응답 Command Type이 같은지 확인한다.
- SSE 알림 뒤 별도 새로고침 없이 목록이 `COMPLETED`로 바뀌는지 확인한다.
- 상세 Drawer에서 `Robot 수신 ACK`와 `최종 Result` 및 두 Result Row를 확인한다.
- 마지막에 REST로 모든 Command·Result와 Robot 최종 Snapshot을 다시 확인한다.

## 발생 문제와 해결

### 1. 예상한 실시간 API 파일이 없었다

초기 탐색에서 별도 `robotRealtimeApi.ts`를 예상했지만 실제 구현은
`useRobotRealtime.ts` Hook 하나가 EventSource를 소유했다. 새 EventSource를
추가하지 않고 이 Hook의 Callback에 파싱 Event를 전달해 기존 연결을 재사용했다.

### 2. Python Compile 명령의 작업 디렉터리가 잘못됐다

Frontend 디렉터리에서 `python scripts/command-roundtrip-e2e.py`를 실행해 파일을
찾지 못했다. `web/`을 작업 디렉터리로 바꾸고
`python -m py_compile scripts/command-roundtrip-e2e.py`로 재검증했다.

### 3. 동시 Frontend 변경 중 TypeScript Build가 한 번 실패했다

`CommandControlPage`에 `streamStatus`, `realtimeRevision` Prop가 먼저 추가되고
`RobotDetailPage` 전달부가 아직 반영 중인 순간 Build를 실행해 필수 Prop 오류가
발생했다. 두 파일의 최종 Diff를 대조한 뒤 다시 Build하여 통과했다.

### 4. 첫 START 버튼이 비활성화됐다

실패 화면에는 Robot이 `ONLINE`, `ACTIVE`, `READY`였지만 Mission 상태가 표시되지
않았다. 원인은 Mission 생성 API의 정식 초기 상태가 `CREATED`인데 Fixture가
`START_MISSION`의 선행 상태인 `READY` 전이를 빠뜨린 것이었다.

해결:

```text
POST /api/v1/missions
→ CREATED
→ PATCH /api/v1/missions/{missionId} {"status":"READY"}
→ START_MISSION
```

화면 정책을 우회하지 않고 공식 Mission 상태 전이 API를 Fixture에 추가했다.

### 5. 왕복 성공 뒤 Windows Vite 정리에서 Timeout이 발생했다

현재 Backend 이미지 재빌드와 Browser 왕복은 성공했지만, `npm.cmd` 부모만
종료하면서 Vite `node.exe` 자식이 stdout Pipe와 4173 포트를 유지했다.
성공 출력 뒤 정리 단계의 `communicate(timeout=5)`가 다시 Timeout을 발생시켜
프로세스 종료 코드는 1이 됐다.

해결:

- `npm.cmd`를 거치지 않고 설치된 Vite Entry Point를 `node`로 직접 기동했다.
- 실패 실행에서 남은 정확한 Vite PID, 임시 Container와 Database만 확인해
  제거했다.
- 재실행 뒤 4173 Listener, `gilbom-command-roundtrip-e2e-*` Container,
  `gilbom_command_roundtrip_e2e_*` Database가 모두 0건임을 확인했다.

### 6. 정상 Backend 원복 중 초기 관리자 Origin 충돌이 드러났다

현재 `.env`의 초기 관리자 이메일과 DB의 같은 이메일 계정 Origin이
`PUBLIC_REGISTRATION`이라, 재빌드된 Backend의 Bootstrap 보호 로직이 기동을
거부했다. DB에는 별도의 정상 `BOOTSTRAP` 관리자가 이미 있었다.

파일이나 계정 데이터를 바꾸지 않고 이번 로컬 Container를
`INITIAL_ADMIN_BOOTSTRAP_ENABLED=false`로 재생성했다. 이후 Flyway V34 검증과
Backend readiness가 통과했고 Compose의 DB·Backend·Frontend가 healthy로
복구됐다. 다음 Compose 재생성 전에는 `.env`의 Bootstrap 이메일과 계정 Origin을
운영자가 정합하게 정리해야 한다.

## 검증 명령과 결과

```powershell
cd web
python scripts/command-roundtrip-e2e.py

cd frontend/gilbom
npm run lint -- --no-cache
npm test
npm run build
```

- 현재 Backend 소스 Docker Build: 성공
- Backend Build 단계 전체 테스트: 성공
- Chromium 정상 왕복: 1건 통과
- 여섯 Command: 모두 `COMPLETED`
- Result: `ACCEPTED` 6건, `COMPLETED` 6건
- Frontend ESLint: 통과
- Frontend Node 테스트: 24건 통과, 실패·Skip 0
- Frontend TypeScript·Vite Production Build: 통과
- Python Compile: 통과
- 최종 정리: 임시 Container·Database·4173 Listener 0건
- 정상 서비스: DB·Backend·Frontend healthy, Mosquitto 실행 중

## 후속 작업

- `FAILED`, `REJECTED`, `PUBLISH_FAILED`, `EXPIRED` Browser E2E
- Result 미수신 Timeout, 늦은 Result, REST·MQTT 중복 Browser E2E
- 권한·Robot Scope·CSRF·감사 Browser E2E
- 실제 Jetson과 EC2 Broker에서 같은 정상 왕복 재검증
- 로컬 `.env` 초기 관리자 이메일과 DB Account Origin 정합화
