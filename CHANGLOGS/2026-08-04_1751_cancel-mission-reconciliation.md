# 변경 로그: 온라인 임무 취소 왕복 및 상태 정합화

- 작성 시각: 2026-08-04 17:51 (Asia/Seoul)
- 작업 유형: 수정
- 상태: 완료

## 작업 요약

온라인 로봇의 임무 취소 요청에 서버가 대상 `mission_code`와 취소 사유를 함께 실어 보내고, 로봇이 로컬 임무 상태를 잃었거나 재개 명령을 처리 중이어도 안전하게 취소 이벤트를 회신하도록 보강했다. Backend는 `MISSION_CANCELLED` 이벤트를 최종 근거로 임무와 상태 이력을 `CANCELLED`로 확정한다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/backend/src/main/java/com/gilbom/robot/command/RobotCommandCreationService.java` | 수정 | `CANCEL_MISSION` 생성 시 DB에서 확정한 대상 임무의 `mission_code`와 관리자 취소 사유를 command parameters에 저장하고 MQTT 발행 payload에 사용하도록 변경했다. |
| `web/backend/src/main/java/com/gilbom/robot/data/mqtt/v2/outbound/CommandRequestPayload.java` | 수정 | 취소 명령의 `mission_id`, `reason` 필수 조건과 다른 불필요한 파라미터 금지 조건을 추가했다. |
| `web/backend/src/test/java/com/gilbom/robot/integration/RobotCommandLifecycleIntegrationTest.java` | 수정 | 취소 API 입력에 mission id가 없어도 Backend가 대상 `mission_code`를 찾아 DB와 MQTT payload에 넣는 통합 테스트를 추가했다. |
| `web/backend/src/test/java/com/gilbom/robot/integration/RobotEventMissionDefectIntegrationTest.java` | 수정 | `READY` 임무의 `MISSION_CANCELLED` 수신 시 종료 사유·종료 시각·상태 이력이 저장되는 통합 테스트를 추가했다. |
| `web/tools/robot+comm/communication/commands.py` | 수정 | 취소 명령에 `mission_id`를 필수화하고, 로컬 임무 유실 복구, 진행 중 재개 명령 선점, `SAFE_STOP` 유지, 취소 이벤트 발행 후 로컬 임무 정리를 구현했다. 진행 중 비상정지는 취소가 선점하지 않도록 유지했다. |
| `web/tools/robot+comm/tests/test_communication_integration.py` | 수정 | 정상 취소 payload를 새 계약에 맞추고 로컬 임무 유실·SAFE_STOP 취소 및 pending resume 선점 회귀 테스트를 추가했다. |
| `web/tools/mock-robot/mock_robot.py` | 수정 | Mock Robot도 대상 `mission_id`와 사유를 검증하고 로컬 임무가 없어도 대상 임무의 취소 이벤트를 회신하도록 동작을 맞췄다. |
| `web/tools/mock-robot/tests/test_mock_robot_mission.py` | 수정 | 취소 명령 fixture를 새 계약에 맞추고 로컬 임무 유실 복구 테스트를 추가했다. |
| `web/docs/mqtt-v2/schemas/command-request.schema.json` | 수정 | `CANCEL_MISSION` 전용 분기를 분리해 `mission_id`와 `reason`을 모두 필수로 정의했다. |
| `web/docs/final-mqtt-topic-schema-v2.md` | 수정 | 취소 요청·결과·임무 이벤트의 토픽, 필드, 로컬 임무 유실 복구 정책을 명시했다. |
| `web/docs/mqtt-device-db-mapping.md` | 수정 | 취소 명령의 대상 임무 코드·사유 매핑과 이벤트 기반 DB 확정 흐름을 반영했다. |
| `web/docs/팀원B/작업내용/로봇-명령-왕복과-ACK_구현내용.md` | 수정 | 취소 명령 필수 파라미터를 `mission_id`, `reason`으로 갱신했다. |
| `web/tools/robot+comm/docs/INGEST-RECEIPT.md` | 수정 | 로봇 취소 처리 조건과 로컬 상태 유실 시 Backend 대상 기준 복구 동작을 기록했다. |
| `CHANGLOGS/2026-08-04_1751_cancel-mission-reconciliation.md` | 추가 | 이번 변경 범위, 검증 결과와 운영 후속 작업을 기록했다. |

## 상세 내용

- 서버 발행 토픽은 `tbs/v2/robots/{mqtt_id}/commands/request`이며 QoS 1, retain false, 기본 만료 30초를 사용한다.
- `parameters.mission_id`에는 DB UUID가 아닌 외부 계약 식별자인 `mission.mission_code`를 넣는다.
- 로봇은 명령 결과를 `tbs/v2/robots/{mqtt_id}/commands/result`로 회신하고, 임무 상태 확정 이벤트를 `tbs/v2/robots/{mqtt_id}/events/mission`으로 발행한다.
- Backend는 Command Result의 `COMPLETED`만으로 임무를 종결하지 않고 `MISSION_CANCELLED` 이벤트를 받아 `mission.status`, `ended_at`, `completion_reason`, `mission_status_history`를 확정한다.
- 로봇이 임무 스냅샷을 잃었어도 서버가 보낸 대상 코드로 취소 이벤트를 생성한다. 재개 명령이 pending이면 취소가 이를 `REJECTED/ALREADY_IN_STATE`로 종료하고 진행한다.
- 비상정지 명령이 아직 적용 중이면 안전 우선으로 취소를 즉시 선점시키지 않는다. 이미 `SAFE_STOP`인 상태에서 취소하면 정지 상태는 유지하면서 임무만 정리한다.
- `COMPLETED`, `CANCELLED`, `FAILED`인 종료 임무는 기존 Backend 대상 선정 규칙에 따라 다시 취소하지 않는다.

## 검증

- 실행한 명령:
  - `uv run --with pytest --with paho-mqtt==2.1.0 --with PyYAML==6.0.2 pytest tests/test_communication_integration.py -q -p no:cacheprovider`
  - `uv run --with pytest --with paho-mqtt==2.1.0 --with PyYAML==6.0.2 pytest -q`
  - `python -m unittest tests.test_mock_robot_mission`
  - `.\gradlew.bat test --tests com.gilbom.robot.integration.RobotCommandLifecycleIntegrationTest --tests com.gilbom.robot.integration.RobotEventMissionDefectIntegrationTest --no-daemon`
  - `.\gradlew.bat test --no-daemon`
  - `python -m json.tool web/docs/mqtt-v2/schemas/command-request.schema.json`
  - Python `jsonschema`로 정상 취소 payload 승인 및 `mission_id` 누락 payload 거절 확인
  - `git diff --check`
- 결과:
  - 실제 로봇 통신 통합 테스트: 20개 통과
  - 실제 로봇 통신 전체 테스트: 189개 통과
  - Mock Robot 테스트: 13개 통과
  - Backend 취소 관련 통합 테스트: 28개 통과
  - Backend 전체 테스트: 802개 통과, 실패 0, 오류 0, 제외 0
  - JSON 문법·취소 계약 의미 검증 및 diff 공백 오류 검사 통과
- 미실행 항목과 사유:
  - EC2 운영 Backend 배포와 실제 로봇 Broker E2E는 운영 프로세스 교체 및 실제 장비 제어를 수반하므로 이번 로컬 변경 범위에서 실행하지 않았다.
  - Frontend 소스는 변경하지 않아 Frontend 테스트를 다시 실행하지 않았다.

## 영향 및 주의사항

- Backend와 실제 로봇 통신 코드를 함께 배포해야 로컬 임무 유실·pending resume 상황까지 새 정책이 적용된다.
- 웹의 취소 API 입력은 계속 사유만 받으며, Backend가 현재 활성 임무를 권위 있게 찾아 MQTT `mission_id`를 보강한다.
- 온라인 여부는 즉시 명령 왕복 가능성을 뜻할 뿐 성공 확정은 아니다. 명령 결과와 `MISSION_CANCELLED` 이벤트를 모두 관찰해야 한다.
- 작업 시작 전부터 존재한 다른 사용자 변경 파일과 미추적 파일은 수정하거나 정리하지 않았다.

## 미완료 및 후속 작업

- EC2에 Backend를 재배포하고 실제 로봇 통신 모듈을 갱신한 뒤 `READY`, `RUNNING`, `PAUSED`, `SAFE_STOP`, 로컬 임무 유실, pending resume 각 경우를 실제 Broker에서 확인한다.
- 오프라인 로봇의 관리자 강제 종결은 로봇 확인 없이 DB를 바꾸는 별도 운영 복구 기능이므로 이번 변경에 포함하지 않았다.
