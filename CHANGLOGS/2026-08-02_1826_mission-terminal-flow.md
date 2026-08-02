# 변경 로그: Mission 실행 상태와 종료 결과 흐름 정리

- 작성 시각: 2026-08-02 18:26 (Asia/Seoul)
- 작업 유형: 수정
- 상태: 완료

## 작업 요약

Mission의 실행 상태와 종료 결과를 구분해 `PAUSED`를 종료 결과처럼 처리하지 않도록
정리했다. `COMPLETE_MISSION`은 `RUNNING`에서만 허용하고, `PAUSED` 또는
`SAFE_STOP`에서는 거부한다. `CANCEL_MISSION`은 활성 Mission을 `CANCELLED`로
종료하되 `SAFE_STOP` 중 취소한 경우 로봇의 안전 정지는 유지한다.

검토 중 추가했던 V38 초안은 제거했다. 새 Migration 없이 기존 상태 전이
`RUNNING → COMPLETED`, `READY/RUNNING/PAUSED → CANCELLED`를 그대로 사용하며,
Migration 검증 기준은 저장소에 이미 존재하는 최신 V37에 맞췄다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/backend/src/main/java/com/gilbom/robot/command/RobotCommandCreationService.java` | 수정 | `COMPLETE_MISSION` 대상 Mission을 `RUNNING`으로 제한해 PAUSED Mission에 완료 명령이 생성되지 않도록 변경 |
| `web/backend/src/test/java/com/gilbom/robot/integration/RobotCommandLifecycleIntegrationTest.java` | 수정 | RUNNING 완료 명령 연결과 PAUSED 완료 명령 거부를 실제 PostGIS/Flyway 환경에서 검증 |
| `web/frontend/gilbom/src/features/command/commandPolicy.ts` | 수정 | 완료 버튼 명칭을 `임무 완료`로 정리하고 RUNNING에서만 활성화; SAFE_STOP에서는 취소와 긴급정지 해제만 허용 |
| `web/frontend/gilbom/test/commandPolicy.test.ts` | 수정 | PAUSED 완료 거부, SAFE_STOP의 취소·해제 허용, Mission 없는 SAFE_STOP의 취소 거부 회귀 테스트 추가 |
| `web/tools/mock-robot/mock_robot.py` | 수정 | COMPLETE와 CANCEL 처리를 분리하고 PAUSED·SAFE_STOP 완료 거부 및 SAFE_STOP 취소 후 안전 상태 보존 구현 |
| `web/tools/mock-robot/tests/test_mock_robot_mission.py` | 수정 | PAUSED 완료 거부, SAFE_STOP 완료 거부, SAFE_STOP 취소 후 CANCELLED Event와 안전 상태 보존 검증 |
| `embedded/commands.py` | 수정 | 실제 Robot 명령 처리에서 COMPLETE를 RUNNING으로 제한하고 SAFE_STOP 완료를 `SAFETY_CONDITION_NOT_CLEARED`로 거부 |
| `embedded/test/test_commands.py` | 수정 | PAUSED와 SAFE_STOP에서 COMPLETE가 Mission을 비우거나 완료 Event를 발행하지 않는지 검증 |
| `embedded/docs/comms/통신임베디드가 알아야할점.md` | 수정 | 실행 상태와 종료 상태, 완료·취소·긴급정지의 확정 흐름 및 SAFE_STOP 취소 규칙 문서화 |
| `web/docs/final-mqtt-topic-schema-v2.md` | 수정 | COMPLETE/CANCEL의 Mission 상태 전제조건과 SAFE_STOP 유지 규칙을 MQTT v2 계약에 명시 |
| `web/Jenkinsfile` | 수정 | Migration 검증 이름·개수·최대 버전을 저장소 최신 V37에 맞추고 V38 참조 제거 |
| `web/scripts/generate-build-metadata.py` | 수정 | Release metadata의 Migration 연속성 및 최신 버전을 V37 기준으로 정리 |
| `web/infra/postgres/verify-empty-database.sql` | 수정 | 빈 DB Migration 검증 범위를 V1~V37로 정리 |
| `web/infra/postgres/verify-production-database.sql` | 수정 | 운영 DB Migration 검증 범위를 V1~V37로 정리 |
| `web/scripts/verify-ci-release-contract.sh` | 수정 | CI Flyway stage 이름을 V1~V37로 정리 |
| `web/scripts/verify-day8-infrastructure.sh` | 수정 | 인프라 검증의 기대 Migration 개수를 37로 정리 |
| `web/scripts/verify-production-runtime.sh` | 수정 | 운영 Runtime 검증 설명을 V1~V37로 정리 |
| `web/scripts/verify-release-artifacts.sh` | 수정 | Release artifact가 요구하는 최신 Migration을 V37로 정리 |
| `web/docs/database-migrations.md` | 수정 | V36·V37까지의 현재 Migration 목록을 유지하고 V38 초안 설명 제거 |
| `web/docs/day8-completion-runbook.md` | 수정 | 운영 Gate의 Flyway 범위를 V1~V37로 정리 |
| `web/docs/jenkins-pipeline.md` | 수정 | Pipeline 및 build metadata 설명의 Migration 범위를 V1~V37로 정리 |
| `CHANGLOGS/2026-08-02_1826_mission-terminal-flow.md` | 추가 | 이번 상태 흐름 변경, 검증, 런타임 반영 결과 기록 |

## 상세 내용

확정한 흐름은 다음과 같다.

```text
CREATED → READY → START → RUNNING
RUNNING → PAUSE → PAUSED → RESUME → RUNNING
RUNNING → COMPLETE → MISSION_COMPLETED → COMPLETED
READY/RUNNING/PAUSED → CANCEL → MISSION_CANCELLED → CANCELLED
RUNNING → EMERGENCY_STOP → SAFE_STOP + PAUSED
SAFE_STOP + PAUSED → CLEAR → PAUSED → RESUME → RUNNING
```

- `PAUSED`는 재개 가능한 실행 상태이며 종료 결과가 아니다.
- 사용자 정상 종료 결과는 `COMPLETED`, 사용자 취소 결과는 `CANCELLED`다.
- `FAILED`는 로봇·시스템 오류로 수행이 불가능해진 예외 종료에만 사용한다.
- `SAFE_STOP` 중 COMPLETE는 Frontend, Backend Mission 상태 조건, Mock, Embedded에서
  모두 차단된다.
- `SAFE_STOP` 중 CANCEL은 Mission만 종료하고 Robot mode와 reason을 유지한다.
- 계획시간 자동 완료는 기존처럼 RUNNING Mission만 대상으로 한다.

로컬 Runtime은 Backend와 Frontend 이미지를 다시 빌드해 강제 재생성했다. 두
컨테이너 모두 healthy이고 Frontend는 HTTP 200을 반환했다. Mock Robot도 새 코드로
재시작해 기존 PAUSED Mission ID와 areaCode를 다시 송신하며 Backend Mission 상태와
동기화했다.

## 검증

- 실행한 명령:
  - `./gradlew.bat test --no-daemon --tests ...RobotCommandLifecycleIntegrationTest --tests ...PatrolMissionSchemaIntegrationTest --tests ...RobotEventMissionDefectIntegrationTest`
  - `./gradlew.bat test --no-daemon`
  - `npm test -- --run`
  - `npm run lint`
  - `npm run build`
  - `python -m unittest discover -s tests -p "test_*.py"`
  - `uv run --with pytest --with paho-mqtt==2.1.0 --with PyYAML==6.0.2 --with requests pytest -q -p no:cacheprovider`
  - `docker compose -f web/compose.yaml build backend frontend`
  - `docker compose -f web/compose.yaml up -d --no-build --wait --force-recreate backend frontend`
  - `git diff --check -- <이번 작업 파일>`
- 결과:
  - Backend 대상 통합 테스트 37건 성공, 실패·Skip 0건
  - Backend 전체 테스트 783건 성공, 실패·Skip 0건
  - Frontend 테스트 42건 성공, 실패·Skip 0건
  - Frontend ESLint 성공, production build 성공
  - Embedded 전체 테스트 500건 성공
  - Mock Robot 전체 테스트 12건 성공
  - Docker 이미지 build 성공, Backend·Frontend healthy, Frontend HTTP 200
  - 실행 DB의 Flyway 최신 버전 `37`, `success=true` 확인
  - Mock 새 boot의 State/Telemetry가 동일 Mission을 `PAUSED`로 송신함을 Backend 로그에서 확인
  - V38 Migration 파일 없음 확인
  - `git diff --check` 오류 없음; Windows 작업 트리의 LF→CRLF 경고만 존재
- 미실행 항목과 사유:
  - 로그인된 실제 브라우저에서 `RESUME → COMPLETE` 버튼을 누르는 live E2E는 관리자
    세션을 자동으로 사용하지 않았으므로 미실행. Backend·Frontend·Mock·Embedded 각
    계층의 자동화 테스트와 MQTT 수신 로그로 동작 경계를 검증했다.

## 영향 및 주의사항

- PAUSED Mission의 `임무 완료` 버튼은 비활성화되며 먼저 재개해야 한다.
- SAFE_STOP에서는 `임무 취소`와 `긴급정지 해제`만 가능하다. 취소 후에도 로봇은
  SAFE_STOP이므로 새 Mission을 시작하기 전에 안전정지를 별도로 해제해야 한다.
- 기존 `COMPLETED`, `CANCELLED`, `FAILED` DB 값과 이력은 호환성을 위해 유지한다.
- Backend 전체 테스트 종료 시 Spring Session scheduler가 Testcontainer 종료 뒤
  connection을 요청하는 shutdown warning이 출력됐지만 Gradle 최종 결과와 783개
  테스트 결과는 성공이다.
- 대화에 노출된 일회용 Robot Credential은 live 검증 후 다시 회전하는 것이 안전하다.

## 미완료 및 후속 작업

- 화면에서 현재 PAUSED Mission은 `재개` 후 `임무 완료` 순서로 확인할 수 있다.
- 실제 Robot 통합 시에도 SAFE_STOP 완료 거부와 SAFE_STOP 취소 후 정지 유지 여부를
  하드웨어 구동 정지 상태와 함께 확인한다.
