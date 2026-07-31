# 변경 로그: Command 비정상 상태 E2E

- 작성 시각: 2026-07-30 17:40 (Asia/Seoul)
- 작업 유형: 테스트
- 상태: 완료

## 작업 요약

Robot 상세 제어 화면에서 `PUBLISH_FAILED`, `FAILED`, `REJECTED`, `EXPIRED`를
서로 구분하고 ACK와 최종 Result를 별도 단계로 표시하는지 실제 React, REST,
TLS MQTT, Mock Robot, PostgreSQL, SSE 경로로 검증했다. 정상 여섯 명령 왕복도
회귀 실행해 기존 `ACCEPTED`·`COMPLETED` 흐름이 유지되는지 확인했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/frontend/gilbom/e2e/command-abnormal-live.spec.ts` | 추가 | Robot 상세에서 실제 `PUBLISH_FAILED` 상세 안내를 확인하고 START 완료, PAUSE 실패, 긴급정지 거절, CANCEL Timeout을 순서대로 실행해 상태 배지·ACK·최종 Result·늦은 Result 불변성을 검증 |
| `web/scripts/command-abnormal-e2e.py` | 추가 | 이름이 고유한 임시 DB·Backend, Manager Session, Robot·READY Mission Fixture, 현재 Vite 소스와 Chromium을 연결하고 Broker 단절, 시나리오 Mock Robot, DB 증거 검증, 원래 서비스 복구와 임시 자원 정리를 자동화 |
| `web/tools/mock-robot/mock_robot.py` | 수정 | E2E에서 Command 유형별 최종 `FAILED`·`REJECTED` 응답과 ACK·Result 지연을 재현할 수 있도록 반복 가능한 `--command-outcome`, `--command-delay-sec` 옵션 및 입력 검증을 추가 |
| `CHANGLOGS/2026-07-30_1740_command-abnormal-state-e2e.md` | 추가 | 비정상 Command E2E 범위, 변경 파일, 검증 결과, 환경 주의사항과 후속 범위를 기록 |

## 상세 내용

- Broker를 실제로 중지한 상태에서 START를 요청해 `PUBLISH_FAILED`와
  `publish_attempt_count=1`을 생성했다.
- 정상 START는 `ACCEPTED` ACK 뒤 `COMPLETED` Result를 수신했다.
- PAUSE는 `ACCEPTED` ACK 뒤 `FAILED(E2E_FORCED_FAILURE)`를 수신했다.
- EMERGENCY_STOP은 `ACCEPTED` ACK 뒤
  `REJECTED(E2E_SAFETY_REJECTED)`를 수신했다.
- CANCEL의 ACK와 Result를 31초 늦춰 Backend의 30초 TTL과 Timeout Scanner가
  먼저 `EXPIRED`로 전이하도록 했다.
- 만료 뒤 Mock Robot이 보낸 늦은 ACK와 `COMPLETED` Result는 저장되지 않았고,
  REST와 DB의 Command 상태가 계속 `EXPIRED`인 것을 확인했다.
- Browser는 SSE 연결 상태와 자동 목록 갱신을 사용하고, Command 상세와 Result는
  REST 정본을 조회했다.
- 실행 전후 정상 Compose Backend, PostgreSQL, Frontend, Mosquitto가 모두
  실행 상태이며 Backend는 `127.0.0.1:8080` Port Binding과 Health를 복구했다.

## 검증

- 실행한 명령:
  - `COMMAND_ABNORMAL_E2E_SKIP_BACKEND_BUILD=1 python scripts/command-abnormal-e2e.py`
  - `COMMAND_ROUNDTRIP_E2E_SKIP_BACKEND_BUILD=1 python scripts/command-roundtrip-e2e.py`
  - `npm run lint`
  - `npm run test`
  - `npm run build`
  - Python `ast.parse` 구문 검사
  - `python tools/mock-robot/mock_robot.py --help`
  - 대상 파일 `git diff --check`
  - `docker compose ps`
- 결과:
  - Chromium 비정상 상태 E2E 1건 통과
  - Browser에서 `PUBLISH_FAILED`, `COMPLETED`, `FAILED`, `REJECTED`,
    `EXPIRED` 다섯 상태와 각 안내 문구 표시 확인
  - DB Command 상태와 순서 5건 일치
  - `COMPLETED`, `FAILED`, `REJECTED`는 각각 `ACCEPTED`와 최종 Result 2건,
    `PUBLISH_FAILED`와 `EXPIRED`는 Result 0건 확인
  - 만료 4초 뒤 REST 재조회와 늦은 MQTT Result 처리 후에도 `EXPIRED` 유지
  - Chromium 정상 여섯 Command 왕복 E2E 1건 통과
  - 정상 여섯 Command 모두 `ACCEPTED`와 `COMPLETED` 확인
  - Frontend ESLint 통과
  - Frontend Node 테스트 24건 통과, 실패·Skip 0
  - TypeScript Project Build와 Vite Production Build 통과
  - Python 구문·Mock Robot CLI 검사 통과
  - 대상 diff 공백 오류 없음
  - E2E 종료 후 Backend·DB·Frontend healthy, Mosquitto 실행 중
- 미실행 항목과 사유:
  - 권한·Robot Scope·CSRF·감사 Browser E2E는 별도 보안 E2E 범위
  - REST·MQTT 중복 메시지의 Browser 재전송 시나리오는 Backend 통합 테스트와
    별도 후속 범위
  - 실제 Jetson·EC2 Broker 검증은 로컬 Mock Robot E2E와 다른 Day 7 통합 범위

## 영향 및 주의사항

- 새 Mock Robot 옵션은 지정한 Command 유형에만 적용되며 옵션을 생략하면 기존
  정상 동작이 유지된다.
- `EXPIRED`는 Robot의 실제 실행 여부가 불명확하므로 화면은 무조건 재시도하지
  않도록 안내한다.
- 로컬 Docker 환경에서 Mosquitto 중지로 연결이 끊긴 Spring MQTT Client가
  Broker 재기동 후 60초 안에 자동 복귀하지 않는 현상이 반복됐다. E2E Harness는
  `PUBLISH_FAILED` 저장 뒤 임시 Backend만 재시작해 다음 시나리오를 진행한다.
  실제 운영 장애 복구 정책은 별도로 확인해야 한다.
- E2E는 기존 Backend를 잠시 중지하므로 같은 로컬 Backend를 사용하는 다른 작업과
  동시에 실행하지 않는다.
- Playwright 스크린샷과 `.last-run.json`은
  `web/frontend/gilbom/test-results/`에 생성되는 로컬 검증 산출물이며 소스 변경
  범위에는 포함하지 않는다.

## 미완료 및 후속 작업

1. 권한·Robot Scope·CSRF·감사 Browser E2E
2. Broker 재기동 뒤 Spring MQTT Client 자동 재연결 원인 진단과 운영 복구 정책
3. REST Idempotency와 MQTT QoS 중복을 결합한 Browser 재전송 E2E
4. 실제 Jetson과 EC2 TLS Broker 비정상 흐름 재검증
