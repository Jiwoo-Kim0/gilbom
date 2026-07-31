# 변경 로그: Command 권한·Scope·CSRF·감사 Browser E2E

- 작성 시각: 2026-07-30 19:27 (Asia/Seoul)
- 작업 유형: 테스트
- 상태: 완료

## 작업 요약

Robot Command의 권한, Robot 객체 범위, CSRF, 감사 계약을 임시 PostgreSQL DB,
실제 로그인·MFA 세션, TLS Mosquitto, Mock Robot, React와 Chromium을 연결한
Browser E2E로 검증했다.

익명 조회, 실행 권한 제거, `VIEWER` Robot 배정, CSRF 누락·불일치 요청이 각각
계약 상태로 거절되고 Command를 생성하지 않는지 확인했다. `OPERATOR` 배정
사용자는 화면에서 정상 명령을 발행하고, 같은 Idempotency Key 재요청 뒤에도
Command·Publish·필수 감사가 각각 한 건만 남는지 DB와 대조했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/frontend/gilbom/e2e/command-security-live.spec.ts` | 추가 | 익명 `401`, 권한 제거 사용자 조회 전용 UI와 `403`, `VIEWER` 배정 `404`, CSRF 누락·불일치 `403`, `OPERATOR` 정상 발행과 멱등 재요청을 Chromium에서 검증했다. |
| `web/scripts/command-security-e2e.py` | 추가 | 임시 DB·Backend와 세 가지 보안 사용자 세션을 구성하고 TLS Broker·Mock·Frontend·Chromium을 실행한 뒤 Command 및 감사 DB 증거와 환경 정리를 검증하는 실행기를 추가했다. |
| `web/docs/팀원B/작업내용/로봇-명령-왕복과-ACK_구현내용.md` | 수정 | 보안 Browser E2E 완료 상태, 실행 명령, 검증 항목을 기록하고 로컬 미완 항목에서 보안 E2E를 제거했다. |
| `CHANGLOGS/2026-07-30_1927_command-security-browser-e2e.md` | 추가 | 변경 파일, 검증 결과, 영향과 실제 환경 후속 작업을 기록했다. |

## 상세 내용

- 익명 Context에서 Command 목록 API가 `401 AUTH_UNAUTHENTICATED`를 반환하고
  Robot 상세 Route가 로그인 화면으로 이동하는지 확인한다.
- `OPERATOR` 역할에서 `ROBOT_COMMAND_EXECUTE`가 `DENY`된 사용자는 Robot과
  Command를 조회할 수 있지만 제어 버튼 대신 조회 전용 안내가 표시된다. 유효한
  CSRF를 붙여 API를 직접 호출해도 `403 AUTH_FORBIDDEN`이다.
- 전역 `ROBOT_COMMAND_EXECUTE` 권한이 있어도 대상 Robot 배정이 `VIEWER`면
  Backend가 발행을 `404 ROBOT_NOT_FOUND`로 거절해 객체 범위를 최종 통제한다.
- `OPERATOR` 배정 사용자의 CSRF Header 누락과 불일치는 각각
  `403 AUTH_FORBIDDEN`이며, 정상 화면 요청만 Mock Robot까지 전달된다.
- 정상 화면 요청을 같은 Idempotency Key로 다시 보내도 같은 `commandId`와
  `publishAttemptCount=1`을 반환한다.
- E2E DB 검증은 전체 Command 1건, 거절 사용자 Command 0건,
  `ROBOT_COMMAND_ISSUED` 1건, `AUTH_CSRF_FAILED` 2건,
  `AUTH_ACCESS_DENIED` 1건, Idempotency 행 1건을 정확히 요구한다.
- E2E가 끝나면 임시 Backend와 DB를 제거하고 메인 Backend를 현재 이미지로
  복구한 뒤 건강 상태를 확인한다.

## 검증

- 실행한 명령:
  - `python -m py_compile scripts/command-security-e2e.py`
  - `npm run lint -- --no-warn-ignored e2e/command-security-live.spec.ts`
  - `$env:COMMAND_SECURITY_E2E_SKIP_BACKEND_BUILD='1'; python -u scripts/command-security-e2e.py`
  - `npm run test`
  - `npm run build`
  - `$env:COMMAND_ROUNDTRIP_E2E_SKIP_BACKEND_BUILD='1'; python -u scripts/command-roundtrip-e2e.py`
  - `$env:COMMAND_ABNORMAL_E2E_SKIP_BACKEND_BUILD='1'; python -u scripts/command-abnormal-e2e.py`
  - `.\gradlew.bat test -PisolatedBuildDirectory=build-codex-command-security --tests "com.gilbom.robot.controller.RobotCommandControllerTest" --tests "com.gilbom.robot.integration.RobotCommandLifecycleIntegrationTest"`
  - `git diff --check -- <이번 작업 파일>`
- 결과:
  - 보안 Chromium E2E 1개 성공: 익명 `401`, 권한 `403`, `VIEWER` Scope `404`,
    CSRF 거절 감사, 정상 명령과 멱등성·필수 감사 증거 통과
  - 정상 Chromium E2E 1개 성공: 여섯 Command의 ACCEPTED·COMPLETED 왕복 통과
  - 비정상 Chromium E2E 1개 성공: 발행 실패·업무 실패·거절·만료·중복·늦은
    Result와 Backend 무재시작 재연결 통과
  - Backend Command Controller·Lifecycle 집중 테스트 14개 성공,
    실패·건너뜀 0개
  - Frontend lint 성공, 단위 테스트 29개 성공, 프로덕션 빌드 성공
  - Python 구문 검사 성공
  - E2E 정리 뒤 메인 Backend가 현재 이미지와 일치하고 `healthy`,
    `RestartCount=0`임을 확인
  - 대상 파일 `git diff --check` 오류 없음
- 미실행 항목과 사유:
  - 실제 Jetson·EC2 Broker 왕복은 해당 장비와 운영 환경 연결이 없어 미실행했다.

## 영향 및 주의사항

- 제품 코드, REST·MQTT 계약과 DB Schema는 변경하지 않고 E2E와 문서만
  추가했다.
- Frontend는 유효 권한으로 제어 버튼의 조회 전용 여부를 결정하고, Robot 배정
  등급은 Backend가 최종 검사한다. 현재 사용자 응답에는 Robot별 배정 등급이
  포함되지 않으므로 `VIEWER` 배정이면서 전역 실행 권한이 있는 사용자는 버튼을
  볼 수 있지만 발행은 `404`로 거절된다.
- 빌드에서 기존 500kB 초과 JavaScript Chunk 경고가 출력됐지만 빌드는
  성공했다.

## 미완료 및 후속 작업

- 실제 Jetson에서 여섯 Command 실행, ACK·최종 Result 시점과 중복 실행 방지 확인
- EC2 TLS Broker·MQTT ACL·mTLS·Nginx SSE를 통과하는 운영 환경 왕복
- Robot별 배정 등급을 Frontend에 제공해 `VIEWER` 제어 버튼을 미리 조회 전용으로
  표시할지는 별도 UX·API 계약으로 결정
