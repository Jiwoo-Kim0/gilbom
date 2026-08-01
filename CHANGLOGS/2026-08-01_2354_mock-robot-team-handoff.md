# 변경 로그: Mock Robot 팀 인계와 Smart Commit 준비

- 작성 시각: 2026-08-01 23:54 (Asia/Seoul)
- 작업 유형: 문서
- 상태: 완료

## 작업 요약

Jira `S15P11D101-680`의 Mock Robot 임무 제어·주기 Telemetry 변경을 다른 웹 팀원과
임베디드 팀이 저장소에서 직접 확인할 수 있도록 실행·대조·안전 경계를 README에
보강했다. Mock 구현과 회귀 테스트를 실제 코드 기준으로 다시 대조하고, 네트워크 없이
실행할 수 있는 7개 상태 전이 테스트와 Python 구문 검사를 통과했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/tools/mock-robot/mock_robot.py` | 수정 | State·Safety·Battery·Incident 독립 주기 발행, 동적 Battery·Jetson 온도·GPS·속도 표본, GPS 독립 변경 주기, 활성 Mission 문맥 복원과 기존 Command 중복 Result 재발행 흐름을 제공한다. |
| `web/tools/mock-robot/tests/test_mock_robot_mission.py` | 수정 | Mission START·PAUSE·RESUME·CANCEL·자동 완료·중복 명령, Event 계열 Payload, 동적 Telemetry, GPS 독립 주기와 재시작 Mission 복원을 7개 테스트로 검증한다. |
| `web/tools/mock-robot/README.md` | 수정 | 임베디드 팀이 대조할 Wire·상태 전이 범위, 네트워크 없는 Linux 검증 명령, 전용 Test Credential 원칙, 동일 `mqtt_id` 동시 접속 금지와 Mock→Jetson 전환 절차를 추가했다. |
| `CHANGLOGS/2026-08-01_2354_mock-robot-team-handoff.md` | 추가 | Jira 680 커밋 범위, 팀 인계 내용, 검증 결과와 미실기 항목을 기록했다. |

## 상세 내용

- 상대 팀은 `mock_robot.py`의 Topic·QoS·Retain, LWT, Payload Factory, Command 처리와
  같은 `command_id` 재수신 정책을 MQTT v2 Wire 대조 자료로 사용할 수 있다.
- Synthetic GPS 이동, 주기 Incident와 Battery·Safety 순환 Event는 웹 화면과 저장
  파이프라인을 시험하기 위한 값이다. 실제 Jetson 센서·모터 동작의 구현 근거로 사용하지
  않도록 README에 명시했다.
- Mock과 Jetson이 같은 `mqtt_id`를 동시에 사용하면 같은 MQTT Client ID가 기존 연결을
  끊고 Retained Presence·State와 Telemetry 원천이 섞인다. 반드시 전용 Test Robot
  Credential을 사용하고 실기 전환 전에 Mock을 종료하도록 했다.
- Credential, MQTT Password와 CA 원문은 코드·문서·변경 로그에 포함하지 않았다.
- `CHANGLOGS/2026-08-01_2221_mission-direct-control-periodic-mock.md`는 Frontend Mission
  직접 제어까지 함께 기록한 원 작업 로그라 이번 Mock 전용 커밋에서는 제외한다.
  Mock 전용 GPS 변경 기록인 `CHANGLOGS/2026-08-01_2302_mock-gps-db-area.md`와 이 로그를
  Jira 680 인계 근거로 사용한다.

## 검증

- 실행한 명령:
  - `python -m unittest web/tools/mock-robot/tests/test_mock_robot_mission.py`
  - `python -m py_compile web/tools/mock-robot/mock_robot.py`
  - `python web/tools/mock-robot/mock_robot.py --help`
  - `git diff --check -- web/tools/mock-robot/mock_robot.py web/tools/mock-robot/tests/test_mock_robot_mission.py web/tools/mock-robot/README.md`
- 결과:
  - Mock Robot 단위 테스트 7건 통과, 실패 0
  - Python 구문 검사 성공
  - 신규·기존 CLI 옵션 Help 정상 출력
  - 대상 파일 whitespace 오류 없음
- 미실행 항목과 사유:
  - 이번 인계 검증에서는 실제 Broker·Backend·DB·Browser E2E를 재실행하지 않았다.
    이전 로컬 왕복 결과는 `2026-08-01_2221`과 `2026-08-01_2302` 로그에 있으며,
    EC2와 실제 Jetson 검증은 별도 Credential과 운영 절차가 필요한 후속 작업이다.

## 영향 및 주의사항

- README의 Linux 명령은 저장소 Root 기준이며 Windows에서는 `python3` 대신 현재
  가상환경의 `python`을 사용할 수 있다.
- Incident 주기 발행은 실제 DB에 테스트 결함을 누적하므로 운영 Broker에서 기본값처럼
  실행하지 않는다.
- 초기 Mission 복원 옵션에는 DB에서 직전에 확인한 Mission 업무 코드·Area 코드·상태와
  경과시간만 주입한다. Mock 자체가 운영 DB를 읽거나 상태를 변경하지 않는다.
- 이번 범위는 Mock Robot과 인계 문서뿐이며 Frontend Mission 직접 제어, Backend, DB,
  Compose와 Nginx를 변경하지 않는다.

## 미완료 및 후속 작업

1. Jira Smart Commit `S15P11D101-680 #done`으로 Mock 코드·테스트·README와 Mock 전용
   변경 로그를 커밋한다.
2. 원격 Push 후 상대 팀이 README의 단위 테스트와 `--help`를 실행해 참조 가능 여부를
   확인한다.
3. 실제 Jetson 연결 전 Mock 프로세스를 종료하고 별도 장비 Credential로 TLS·명령 왕복을
   검증한다.
