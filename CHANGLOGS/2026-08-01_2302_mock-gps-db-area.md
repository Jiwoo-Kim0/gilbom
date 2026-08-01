# 변경 로그: Mock Robot DB 수행지역 초기 GPS

- 작성 시각: 2026-08-01 23:02 (Asia/Seoul)
- 작업 유형: 기능
- 상태: 완료

## 작업 요약

로컬 RBT-001 Test Bot의 초기 GPS를 고정 기본값이 아니라 현재 DB Mission에 연결된
수행지역 Polygon의 내부점으로 설정했다. Telemetry 발행 주기와 GPS 위치 변경 주기를
분리해 Telemetry는 5초마다 저장하면서 위치는 20초마다만 변하도록 했고, 실행 중인
Mission의 상태와 경과시간을 보존한 채 Test Bot 프로세스를 재시작할 수 있는 옵션을
추가했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/tools/mock-robot/mock_robot.py` | 수정 | `--position-update-interval`을 추가해 GPS 변화 주기를 Telemetry와 분리했다. 실행 중인 DB Mission Snapshot을 재시작 뒤 복원할 수 있도록 초기 Mission 코드·Area 코드·상태·경과시간 옵션을 추가하고, START 시 다음 GPS 변경시각을 다시 계산했다. |
| `web/tools/mock-robot/tests/test_mock_robot_mission.py` | 수정 | GPS가 독립 주기 전에는 유지되고 도달 시에만 변경되는 동작과, 재시작 시 Mission 문맥·초기 DB 수행지역 좌표·경과시간이 복원되는 동작을 추가 검증했다. |
| `web/tools/mock-robot/README.md` | 수정 | DB 수행지역 Polygon의 `ST_PointOnSurface`를 초기 위·경도로 주입하는 방법, GPS 독립 주기와 활성 Mission 재시작 복구 옵션을 문서화했다. |
| `CHANGLOGS/2026-08-01_2302_mock-gps-db-area.md` | 추가 | 변경 범위, 실제 DB·MQTT 검증 결과와 운영 주의사항을 기록했다. |

## 상세 내용

- Test Bot 자체는 MQTT `START_MISSION`에서 Area 코드만 받으며 Polygon Geometry를
  받지 않는다. 따라서 동결된 MQTT v2 계약을 확장하지 않고, 로컬 실행 시 DB
  `robot.patrol_area.boundary`의 `ST_PointOnSurface`를 계산해 `--latitude`와
  `--longitude`로 주입했다.
- `--position-update-interval=20`이면 5초 Telemetry 네 표본 동안 같은 GPS가 유지되고,
  다음 표본부터 1.5m 이동한 좌표가 보인다. Robot이 `PATROLLING` 상태일 때만 이동한다.
- 실행 중 프로세스를 재시작하면서 DB Mission의 업무 코드, Area 코드, RUNNING/PAUSED
  상태와 경과시간을 초기 옵션으로 복원한다. 새 Mission Event를 임의 발행하거나 DB
  상태를 직접 변경하지 않는다.
- 현재 로컬 RBT-001 Test Bot을 DB 수행지역 내부점과 활성 RUNNING Mission Snapshot으로
  재시작했다. 비밀번호 원문은 명령행·소스·로그·이 문서에 기록하지 않았다.

## 검증

- 실행한 명령:
  - `python -m unittest web/tools/mock-robot/tests/test_mock_robot_mission.py`
  - `python -m py_compile web/tools/mock-robot/mock_robot.py`
  - `git diff --check -- web/tools/mock-robot/...`
  - PostgreSQL에서 활성 Mission과 `ST_PointOnSurface(boundary)` 조회
  - Test Bot 재시작 뒤 `telemetry.robot_telemetry` 최근 GPS 시계열 조회
  - `ST_Contains(patrol_area.boundary, robot_current_state.position)` 조회
  - Test Bot PID 생존과 stderr 확인
- 결과:
  - Mock Robot 단위 테스트 7개 성공, Python 구문 검사 성공
  - 대상 파일 whitespace 오류 없음
  - RBT-001이 `ONLINE/PATROLLING/RUNNING`, `speed_mps=0.8`, `moving=true`로 저장됨
  - 재시작 첫 GPS가 DB 수행지역 내부점과 일치함
  - 5초 Telemetry는 계속 저장되고 같은 좌표가 유지되다가 약 20초마다 GPS가 변경됨
  - 현재 위치에 대한 `ST_Contains` 결과 `true`
  - 재시작한 Test Bot 프로세스 생존, stderr 비어 있음
- 미실행 항목과 사유:
  - 실제 Jetson GPS·모터 검증은 로컬 Test Bot 변경이므로 실행하지 않았다.
  - Frontend 코드는 변경하지 않아 Frontend Build와 Browser E2E는 재실행하지 않았다.

## 영향 및 주의사항

- DB 수행지역 Geometry가 변경되면 Test Bot을 다시 시작할 때 새 내부점을 재조회해야
  한다. Test Bot이 운영 DB에 직접 접속하도록 만들지는 않았다.
- 초기 Mission 복원 옵션은 로컬 Test Bot 전용이다. DB Snapshot과 다른 값을 주입하면
  사실과 다른 상태가 되므로 반드시 재시작 직전에 DB에서 조회한 값을 사용한다.
- 주기형 Incident Event는 로컬 DB에 테스트 결함 관측을 계속 추가한다.
- 대화에 노출된 MQTT Credential은 실제 Jetson 연결 전에 회전해야 한다.

## 미완료 및 후속 작업

1. 실제 Jetson에서는 이 옵션 대신 GPS 센서 측정값을 사용한다.
2. 실제 장비 전환 직전에 로컬 Test Bot을 종료하고 MQTT Credential을 회전한다.
3. 수행지역 변경 후 재시연하면 새 Polygon 내부점으로 Test Bot을 재기동한다.
