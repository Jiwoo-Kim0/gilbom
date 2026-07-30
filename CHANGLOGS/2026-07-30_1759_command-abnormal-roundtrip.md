# 변경 로그: Command 비정상·중복·늦은 Result E2E

- 작성 시각: 2026-07-30 17:59 (Asia/Seoul)
- 작업 유형: 테스트
- 상태: 완료

## 작업 요약

실제 TLS Broker·Mock Robot·임시 Backend·PostgreSQL·SSE·React를 연결해 Command
발행 실패, 업무 실패, 안전 거절, Result Timeout, REST/MQTT 중복과 늦은 Result를
검증했다. Broker에서 실제 늦은 Result와 중복 재응답을 관찰하고 DB 정본에
부작용이 없는지 대조했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/tools/mock-robot/mock_robot.py` | 수정 | Command Type별 최종 결과 Override·지연·Result 직접 중복 발행 옵션을 추가하고, 처리 완료 `command_id` Cache로 중복 Command를 재실행하지 않고 기존 Result를 재발행 |
| `web/tools/mock-robot/README.md` | 수정 | `--command-outcome`, `--command-delay-sec`, `--duplicate-results-for`, 중복 `command_id` 정책과 비정상 E2E 실행 명령을 문서화 |
| `web/frontend/gilbom/e2e/command-abnormal-live.spec.ts` | 수정 | PUBLISH_FAILED·FAILED·REJECTED·EXPIRED 화면과 Timeout 안내를 검증하고 UI 요청의 Idempotency-Key·CSRF·Body를 재사용해 REST 중복 요청이 동일 Command·1회 Publish인지 확인 |
| `web/scripts/command-abnormal-e2e.py` | 수정 | 실제 Broker 중단, Scenario Mock, MQTT Result Observer, 동일 Command 재발행, 늦은 Result 관찰과 DB 무부작용 검증 및 임시 자원 정리를 자동화 |
| `DailyLog/2026-07-30_JIRA_Day6_작업.md` | 수정 | `DAY6-B-03-2`를 DONE으로 바꾸고 활성 하위 작업 집계와 검증 근거·후속 보안 작업을 갱신 |
| `DailyLog/2026-07-30_Day6_상세_구현.md` | 수정 | 비정상·중복·늦은 Result의 실제 검증 결과와 체크리스트를 완료 처리 |
| `web/docs/2026-07-28-story-subtask-status.md` | 수정 | Command Story에 정상/비정상 Broker E2E 완료 증거를 반영하고 보안·실기 검증만 후속으로 분리 |
| `web/docs/팀원B/작업내용/로봇-명령-왕복과-ACK_구현내용.md` | 수정 | 비정상 E2E 재현 명령·검증 범위와 현재 미완료 보안·Jetson/EC2 범위를 반영 |
| `TROUBLESHOOTING/15_CommandAbnormalRoundtrip.md` | 추가 | 전체 수행 작업, 관찰 증거 보강, Playwright Strict Mode 실패와 Broker 재연결·자원 원복 절차를 기록 |
| `CHANGLOGS/2026-07-30_1759_command-abnormal-roundtrip.md` | 추가 | 변경 범위, 검증 결과, 주의사항과 후속 작업을 기록 |

## 상세 내용

- Mosquitto를 실제로 중지한 동안 START Command를 생성해
  `PUBLISH_FAILED`, 발행 시도 1회와 오류 코드를 보존했다.
- 정상 START 뒤 PAUSE는 `FAILED`, EMERGENCY_STOP은 `REJECTED`로 Mock 결과를
  Override하고 UI·REST·DB의 상태와 Reason Code를 대조했다.
- CANCEL의 ACK·Result를 31초 지연해 Scheduler의 `EXPIRED`와 Timeout 화면을
  확인했다.
- MQTT Observer는 만료 뒤 실제 ACCEPTED·COMPLETED를 관찰했지만 Backend는
  Result를 저장하지 않고 `EXPIRED`를 유지했다.
- UI POST와 같은 Idempotency-Key를 다시 보내도 같은 `commandId`, DB Command
  1건, Publish 시도 1회를 유지했다.
- 성공한 START Payload를 같은 `command_id`로 Broker에 재발행했다. Mock는
  명령을 다시 적용하지 않고 같은 최종 `result_id`를 재발행했으며 Backend Result
  행은 ACCEPTED·COMPLETED 2건에서 늘지 않았다.
- PAUSE의 동일 ACCEPTED·FAILED Result를 각각 두 번 직접 발행해도 Backend Result
  행은 2건이며 상태·SSE 부작용이 없었다.
- Broker 재기동 뒤 임시 Backend를 재시작하지 않고 MQTT 자동 재연결로 복구했으며
  Container Restart Count가 0임을 확인했다.

## 검증

- 실행한 명령:
  - `python scripts/command-abnormal-e2e.py`
  - `COMMAND_ABNORMAL_E2E_SKIP_BACKEND_BUILD=1` 상태의 동일 E2E 선행 실행
  - `npm run lint -- --no-cache`
  - `npm test`
  - `npm run build`
  - `python -m py_compile scripts/command-abnormal-e2e.py tools/mock-robot/mock_robot.py`
  - 임시 Container·Database·4173 Listener 0건 확인
  - `docker compose ps`와 Backend readiness 확인
  - 대상 파일 `git diff --check`
- 결과:
  - 현재 Backend 소스 Docker 이미지 Build 성공
  - Chromium 비정상 왕복 1건 통과, 약 44초
  - `PUBLISH_FAILED`, `FAILED`, `REJECTED`, `EXPIRED`의 DB·REST·UI 일치
  - REST Idempotency 재요청은 동일 Command·Publish 1회
  - MQTT 중복 Command는 Mock 재실행 없이 같은 Result 재발행
  - 같은 Result 직접 중복 발행에도 DB Result 건수·상태 변화 없음
  - Broker 복구 시 Backend 재시작 없이 MQTT 자동 재연결
  - 늦은 Result는 Broker에서 관찰됐지만 DB Result 0건·`EXPIRED` 유지
  - Frontend ESLint 통과
  - Frontend Node 테스트 26건 통과, 실패·Skip 0
  - TypeScript Project Build와 Vite Production Build 통과
  - Python Compile 통과
  - 테스트 자원 정리 후 DB·Backend·Frontend healthy, Mosquitto 실행 중
- 미실행 항목과 사유:
  - 권한·Robot Scope·CSRF·감사 Browser E2E는 `DAY6-B-03-3` 후속 범위
  - 실제 Jetson의 재부팅을 넘는 Command Cache와 EC2 Broker 재검증은 실기·배포
    환경이 필요한 Day 7 이후 범위

## 영향 및 주의사항

- Timeout은 Robot 미실행을 뜻하지 않는다. Result를 받지 못했으므로 실행 여부가
  불명확하며 즉시 같은 명령을 재시도하기 전에 Robot·Mission 정본을 확인해야 한다.
- Mock의 처리 완료 Cache는 프로세스 메모리 기반이다. 실제 Embedded 구현은 재부팅
  뒤에도 필요한 중복 방지 기간과 영속 정책을 별도로 정해야 한다.
- Harness는 발행 실패를 만들기 위해 로컬 Broker와 일반 Backend를 일시 중지한다.
  다른 MQTT·Backend 작업과 동시에 실행하지 않는다.
- Browser에는 MQTT Client를 추가하지 않았다. 별도 Observer는 E2E Harness에서만
  사용하며 운영 UI는 SSE 알림 뒤 REST 정본을 읽는다.

## 미완료 및 후속 작업

1. 권한·Robot Scope·CSRF·감사 Browser E2E
2. 실제 Jetson의 영속 `command_id` Cache·재부팅 중복 정책 검증
3. EC2 Broker의 발행 실패·재연결·Message Expiry 재검증
