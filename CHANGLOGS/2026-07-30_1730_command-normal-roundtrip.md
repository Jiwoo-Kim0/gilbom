# 변경 로그: Command 정상 왕복 E2E

- 작성 시각: 2026-07-30 17:30 (Asia/Seoul)
- 작업 유형: 테스트
- 상태: 완료

## 작업 요약

관리자 React 화면에서 여섯 정상 Command를 순서대로 발행하고 실제 TLS Mosquitto,
Python Mock Robot, 임시 Backend·PostgreSQL, SSE와 REST 정본 재조회를 거쳐 다시
화면에 완료 상태가 표시되는 전체 왕복을 구현·검증했다. 각 Command의
`ACCEPTED`와 최종 `COMPLETED` Result, DB와 Robot·Mission 최종 상태를 대조했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/frontend/gilbom/src/features/command/CommandControlPage.tsx` | 수정 | 시작 전 Robot Current State에 Mission 상태가 아직 없을 때 Mission REST의 `READY` 상태를 보조 정본으로 사용해 `START_MISSION` 버튼 정책과 요청 생성이 일치하도록 보정 |
| `web/frontend/gilbom/e2e/command-roundtrip-live.spec.ts` | 추가 | 실제 로그인 Session으로 여섯 제어 Modal을 조작하고 POST 응답, 자동 상태 갱신, Command 상세의 ACCEPTED·COMPLETED Result, 최종 Robot REST 상태를 검증 |
| `web/scripts/command-roundtrip-e2e.py` | 추가 | 현재 Backend 이미지, 임시 DB·Backend, MFA Manager·Robot·READY Mission Fixture, TLS Mock Robot, Vite와 Chromium을 연결하고 Browser·DB 증거 확인 및 자원 정리를 자동화 |
| `DailyLog/2026-07-30_JIRA_Day6_작업.md` | 수정 | `DAY6-B-02-3`, `DAY6-B-03-1`을 DONE으로 전환하고 활성 작업 집계와 실제 검증 근거를 갱신 |
| `DailyLog/2026-07-30_Day6_상세_구현.md` | 수정 | Command Frontend와 정상 Broker 왕복의 완료 상태, E2E 경로·결과·체크리스트와 남은 비정상·보안 범위를 기록 |
| `web/docs/2026-07-28-story-subtask-status.md` | 수정 | Command Story를 Backend·Frontend·정상 E2E 완료로 갱신하고 후속 비정상·보안·실기 범위를 분리 |
| `web/docs/팀원B/작업내용/로봇-명령-왕복과-ACK_구현내용.md` | 수정 | 더 이상 존재하지 않는 Frontend 미구현 설명을 제거하고 현재 UI·SSE·정상 E2E 재현 명령과 미완료 범위를 반영 |
| `TROUBLESHOOTING/14_CommandNormalRoundtrip.md` | 추가 | 전체 수행 작업, CREATED Mission Fixture, Windows Vite Process 정리, Backend Bootstrap Origin 충돌을 포함한 문제·원인·해결·검증 결과 기록 |
| `CHANGLOGS/2026-07-30_1730_command-normal-roundtrip.md` | 추가 | 정상 왕복 구현 범위, 직접 변경 파일, 검증 결과와 후속 작업을 기록 |

## 상세 내용

- 지원 Command:
  - `START_MISSION`
  - `PAUSE_MISSION`
  - `RESUME_MISSION`
  - `EMERGENCY_STOP`
  - `CLEAR_EMERGENCY_STOP`
  - `CANCEL_MISSION`
- Browser는 MQTT에 직접 연결하지 않고 REST로 생성하고 기존 Robot SSE를 변경
  알림으로만 사용한다. 화면 상태는 Command·Result REST 재조회값을 정본으로 삼는다.
- Mission Fixture는 생성 직후의 정식 `CREATED` 상태에서 관리 API로 `READY` 전이를
  수행한 뒤 시작한다.
- 각 명령은 고유 `commandId`를 사용하며 Mock Robot의 ACK와 최종 Result를 상세
  화면에서 별도 단계로 확인한다.
- Harness는 자동 완료를 끈 Mock Robot을 사용해 START→PAUSE→RESUME→
  EMERGENCY_STOP→CLEAR_EMERGENCY_STOP→CANCEL 순서를 안정적으로 재현한다.
- Windows에서는 `npm.cmd` 자식 Vite가 남는 문제를 피하기 위해 설치된 Vite
  Entry Point를 `node`로 직접 기동한다.

## 검증

- 실행한 명령:
  - `python scripts/command-roundtrip-e2e.py`
  - `COMMAND_ROUNDTRIP_E2E_SKIP_BACKEND_BUILD=1` 상태의 동일 E2E 재실행
  - `npm run lint -- --no-cache`
  - `npm test`
  - `npm run build`
  - `python -m py_compile scripts/command-roundtrip-e2e.py`
  - 임시 Container·Database·4173 Listener 0건 확인
  - `docker compose ps`와 Backend readiness 확인
  - 대상 파일 `git diff --check`
- 결과:
  - 현재 Backend 소스 Docker 이미지 빌드 성공
  - Chromium 정상 왕복 1건 통과
  - Command 6건 모두 `COMPLETED`
  - Result `ACCEPTED` 6건, `COMPLETED` 6건
  - 최종 Mission `CANCELLED`, Robot mode `READY`, Robot Mission 상태 `NULL`
  - Frontend ESLint 통과
  - Frontend Node 테스트 24건 통과, 실패·Skip 0
  - TypeScript Project Build와 Vite Production Build 통과
  - Python Compile 통과
  - 테스트 자원 정리 후 DB·Backend·Frontend healthy, Mosquitto 실행 중
- 미실행 항목과 사유:
  - `FAILED`, `REJECTED`, `PUBLISH_FAILED`, `EXPIRED`, Timeout, 늦은 Result,
    REST·MQTT 중복 Browser E2E는 `DAY6-B-03-2` 후속 범위
  - 권한·Robot Scope·CSRF·감사 Browser E2E는 `DAY6-B-03-3` 후속 범위
  - 실제 Jetson·EC2 Broker 검증은 로컬 정상 Mock 왕복과 다른 Day 7 통합 범위

## 영향 및 주의사항

- `ACCEPTED`는 Robot 수신 ACK이며 실행 성공이 아니다. 화면과 테스트는 최종
  `COMPLETED`를 별도로 요구한다.
- 정상 E2E는 로컬 TLS Broker에서 계약과 전체 연결을 검증하지만 실제 Robot의
  안전 조건과 EC2 Network·ACL을 대신하지 않는다.
- 테스트는 기존 Backend를 일시 중지하고 8080 포트에 임시 Backend를 사용한 뒤
  원복하므로 같은 로컬 환경에서 다른 Backend 작업과 동시에 실행하지 않는다.
- 로컬 정상 Backend 원복 시 `.env`의 Bootstrap 설정과 DB Account Origin 불일치가
  드러났다. 이번 검증에서는 파일·계정 데이터를 변경하지 않고 해당 Container에만
  Bootstrap 비활성값을 적용해 healthy로 복구했다.

## 미완료 및 후속 작업

1. 실패·거절·발행 실패·Timeout·만료 Browser E2E
2. 늦은 Result와 REST/MQTT 중복 처리 Browser E2E
3. 권한·Robot Scope·CSRF·감사 Browser E2E
4. 실제 Jetson과 EC2 TLS Broker 정상 왕복 재검증
5. 운영자가 로컬 `.env` Bootstrap 설정과 DB Account Origin을 정합하게 정리
