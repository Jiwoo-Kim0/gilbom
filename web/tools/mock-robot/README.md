# MQTT v2 Mock Robot

Python Mock Robot은 실제 기기 대신 MQTT v2 Presence, State, Telemetry,
Safety·Battery·Incident·Mission Event와 Command Result를 발행하고 Command
Request와 Ingest Receipt를 구독한다.

## 다른 팀·임베디드 참고 범위

이 Mock은 웹 Backend의 MQTT 수신·명령 왕복·DB 저장·SSE 화면 갱신을 검증하는
테스트 장비다. 임베디드 팀은 다음 구현을 MQTT v2 Wire 대조 자료로 사용할 수 있다.

- Topic·QoS·Retain과 Presence LWT 구성
- State·Telemetry·Safety·Battery·Incident·Mission Event Payload
- START·PAUSE·RESUME·CANCEL·긴급정지·해제 Command 처리와 Result 발행
- 같은 `command_id` 재수신 시 명령을 재실행하지 않고 기존 Result를 재발행하는 처리
- Mission `elapsed_sec`, 동적 GPS·Battery·Jetson 온도 표본 생성 방식

Mock은 서버 시험 범위를 넓히기 위해 `ingest/receipt`도 구독하지만, 현재 MVP에서
Jetson이 반드시 구독해야 하는 하향 Topic은 `commands/request` 하나다. Receipt 구독을
실기 필수 계약으로 해석하지 않는다.

대조할 코드는 `mock_robot.py`, 회귀 예시는
`tests/test_mock_robot_mission.py`에 있다. Mock의 난수·주기 Event·가상 GPS 이동은
화면과 서버를 시험하기 위한 값이므로 실제 Jetson 센서·모터 제어 코드로 복사하지 않는다.

저장소 Root에서 네트워크 연결 없이 계약·상태 전이 회귀를 먼저 확인할 수 있다.

```bash
python3 -m unittest web/tools/mock-robot/tests/test_mock_robot_mission.py
python3 -m py_compile web/tools/mock-robot/mock_robot.py
```

실제 Broker에 연결할 때는 관리자 화면에서 별도로 발급한 **전용 Test Robot
Credential**을 사용한다. 실제 Jetson과 Mock을 같은 `mqtt_id`로 동시에 연결하면 같은
MQTT Client ID 연결이 서로를 끊고 Retained Presence·State와 Telemetry가 섞인다.
운영 Robot Credential, 비밀번호와 CA 원문은 저장소·명령 예시·로그에 남기지 않는다.

실기 전환 순서는 다음과 같다.

1. Mock 프로세스를 종료하고 Retained Presence가 `OFFLINE`인지 확인한다.
2. 실제 Jetson에는 등록 Modal에서 받은 `MQTT_ID`, 1회 표시 비밀번호와 승인된 CA를 설정한다.
3. Jetson을 연결한 뒤 새 `boot_id`의 `ONLINE`·State·Telemetry를 확인한다.
4. 관리자 Mission 명령과 `commands/result` 왕복을 확인한다.

## 테스트봇 구성 파일 목록

아래 핵심 파일과 연계 E2E는 모두 Git 추적 대상이다. 원격에 Push한 뒤 상대 팀도 같은
경로에서 확인할 수 있으며 `.gitignore`에는 포함되지 않는다.

| 구분 | 저장소 경로 | 용도 |
| --- | --- | --- |
| 주 Test Bot | `web/tools/mock-robot/mock_robot.py` | MQTT v2 전체 Presence·State·Telemetry·Event 발행과 Command 수신·Result·Mission 전이를 수행한다. 현재 시연과 통합 검증에서 사용하는 본체다. |
| 단위 회귀 | `web/tools/mock-robot/tests/test_mock_robot_mission.py` | Mission 상태 전이, 중복 명령, 자동 완료, Event Payload, 동적 Telemetry와 GPS 주기를 네트워크 없이 검증한다. |
| 사용 설명 | `web/tools/mock-robot/README.md` | 설치·실행 옵션, Topic, 수행지역 GPS 주입, 재시작 복원, 팀 인계와 실기 전환 절차를 제공한다. |
| Python 의존성 | `web/tools/mock-robot/requirements-test.txt` | Test Bot과 MQTT E2E에 필요한 Paho MQTT 버전을 고정한다. |
| 단발 연결 점검 | `web/tools/mock-robot/publish_test.py` | 로컬 TLS Broker에 Telemetry 한 건을 발행해 연결과 Backend 구독을 빠르게 확인한다. 전체 Mission Bot을 대신하지 않는다. |
| Mission 전체 E2E | `web/scripts/mission-mock-e2e.py` | 임시 DB·Backend, TLS MQTT, Mock Robot, REST/SSE와 Chromium Mission 화면을 종단 간 검증한다. |
| Command 비정상 E2E | `web/scripts/command-abnormal-e2e.py` | 실패·거절·지연·중복 Command Result를 Broker·DB·SSE·React까지 검증한다. |
| Browser 보조 E2E | `web/scripts/mission-time-browser-e2e.py` | 실행 중인 로컬 Stack에서 Mission 생성·시간 진행 화면을 확인하는 보조 도구다. |

`web/tools/mock-robot/publisher.py`와 `Dockerfile`은 현재 내용이 없는 과거 Placeholder라
실행·대조 대상으로 사용하지 않는다. Python 실행 시 생성되는 `__pycache__/`만
`.gitignore` 대상이며 소스, README, 요구사항과 테스트 파일은 제외되지 않는다.

## 설치

`web` 디렉터리에서 실행한다.

```powershell
python -m pip install -r tools/mock-robot/requirements-test.txt
```

## 실행

로봇 MQTT username은 `mqtt_id`와 같아야 한다.

```powershell
$env:MQTT_PASSWORD='robot-test MQTT 비밀번호'
python tools/mock-robot/mock_robot.py --mqtt-id robot-test
```

기본 연결 대상은 `localhost:8883`이며 CA 인증서는
`infra/mosquitto/local/runtime/certs/local-ca-cert.pem`을 신뢰 인증서로 사용한다.

관제 화면에서 값이 계속 바뀌는 모습을 확인하려면 Telemetry를 3초마다 발행하고
Jetson 온도와 Battery를 변화시킨다. GPS와 속도는 Mission이 RUNNING인 동안에만
변한다. Frontend가 이 값을 직접 생성하는 것이 아니라 Mock MQTT 표본을 Backend가
DB에 저장하고, SSE 알림 뒤 Frontend가 REST Snapshot을 다시 조회한다.

```powershell
$env:MQTT_PASSWORD='robot-test MQTT 비밀번호'
python tools/mock-robot/mock_robot.py `
  --mqtt-id robot-test `
  --telemetry-interval 3 `
  --jetson-temp-c 43.0 `
  --jetson-temp-step-c 0.3 `
  --battery-step-percent 0.2 `
  --position-step-m 1.5 `
  --position-update-interval 20 `
  --speed-mps 0.8
```

변화량 옵션의 기본값은 `0`이다. 값은 제한된 범위 안에서 왕복하므로 장시간
실행해도 무한히 커지거나 음수가 되지 않는다. Telemetry는 설정된 주기로 계속
발행하지만 GPS는 `--position-update-interval`이 지난 경우에만 변경할 수 있다.
예를 들어 Telemetry 5초, 위치 변경 20초를 지정하면 같은 DB 표본 좌표가 네 번까지
유지된 뒤 다음 위치로 이동한다.

초기 GPS는 하드코딩된 시연 좌표보다 DB에서 선택한 수행지역 Polygon의 내부점을
`--latitude`, `--longitude`로 주입하는 것이 안전하다. MQTT `START_MISSION`에는
Area 코드만 있고 Polygon은 없으므로 Test Bot이 Topic만 보고 중심점을 계산할 수는
없다. 로컬 DB에서는 다음처럼 수행지역 내부점을 구한다.

```sql
SELECT
  ST_Y(ST_PointOnSurface(boundary)) AS latitude,
  ST_X(ST_PointOnSurface(boundary)) AS longitude
FROM robot.patrol_area
WHERE patrol_area_id = :mission_patrol_area_id;
```

실행 중 Mission을 유지한 채 Test Bot 프로세스만 재시작할 때는 DB Mission Snapshot을
`--initial-mission-id`, `--initial-zone-id`, `--initial-mission-state`,
`--initial-mission-elapsed-sec`로 넘긴다. 이 옵션은 재시작 복구용이며 새로운 Mission을
임의로 시작하거나 DB 상태를 변경하지 않는다.

State와 각 Event 계열을 서로 다른 주기로 계속 발행하려면 다음 옵션을 사용한다.
Presence는 연결·종료를 나타내는 Retained 메시지라 주기 발행하지 않고, Mission
Event는 실제 START·PAUSE·RESUME·CANCEL 명령, 실행 중 긴급정지와 자동 완료
시점에만 발행한다.

```powershell
$env:MQTT_PASSWORD='robot-test MQTT 비밀번호'
python -u tools/mock-robot/mock_robot.py `
  --mqtt-id robot-test `
  --telemetry-interval 5 `
  --state-interval 30 `
  --safety-event-interval 45 `
  --battery-event-interval 60 `
  --incident-event-interval 75
```

Safety 주기는 네 Event Type을, Battery 주기는 다섯 Event Type을 순서대로
순환한다. Incident 주기는 새로운 `event_id`의 점자블록 파손 탐지를 발행하므로
로컬 DB에 테스트 결함 관측이 계속 추가된다. 각 Event 주기에 `0`을 주면 해당
계열만 비활성화한다.

Mission 시작·일시정지·재개·취소 명령이 성공하면 각각 대응하는 Mission Event를
발행한다. 로컬 완료 흐름이 필요하면 `--mission-auto-complete-sec`에 양수를
지정한다. RUNNING 상태의 벽시계 누적시간이 해당 값에 도달하면 마지막
Telemetry를 먼저 발행하고 `MISSION_COMPLETED`와 완료 State를 발행한다.

RUNNING Mission에서 긴급정지가 완료되면 Mock은 Mission 식별자를 유지한 채
`SAFE_STOP`·`PAUSED`로 전환하고 `MISSION_PAUSED`를 한 번 발행한다. 긴급정지
해제는 주행을 자동 재개하지 않고 `PAUSED`를 유지하며, 이후 별도 `RESUME_MISSION`
명령이 완료되어야 `PATROLLING`·`RUNNING`과 `MISSION_RESUMED`로 전환한다.

```powershell
$env:MQTT_PASSWORD='robot-test MQTT 비밀번호'
python tools/mock-robot/mock_robot.py `
  --mqtt-id robot-test `
  --telemetry-interval 0.5 `
  --mission-auto-complete-sec 12
```

임시 DB·Backend와 실제 TLS Broker·Chromium을 연결하는 전체 Mission Mock E2E는
`web` 디렉터리에서 다음 명령으로 실행한다. 실행 중 일반 Backend만 잠시
중지되며 `finally` 정리 단계에서 임시 컨테이너·DB를 제거하고 다시 기동한다.

```powershell
python scripts/mission-mock-e2e.py
```

Command 실패·거절·늦은 Result 시나리오는 명령 유형별 Override와 지연 옵션을
사용한다. `--command-outcome`은 `TYPE=STATUS:REASON` 형식이며 최종 상태는
`COMPLETED`, `FAILED`, `REJECTED`만 허용한다. `--command-delay-sec`은 ACK와
최종 Result 발행을 지정한 초만큼 늦춘다.

```powershell
$env:MQTT_PASSWORD='robot-test MQTT 비밀번호'
python tools/mock-robot/mock_robot.py `
  --mqtt-id robot-test `
  --command-outcome PAUSE_MISSION=FAILED:E2E_FORCED_FAILURE `
  --command-outcome EMERGENCY_STOP=REJECTED:E2E_SAFETY_REJECTED `
  --command-delay-sec CANCEL_MISSION=31 `
  --duplicate-results-for PAUSE_MISSION
```

Mock Robot은 처리 완료한 `command_id`와 최종 Result를 메모리에 보관한다. 같은
`command_id`가 다시 전달되면 명령을 재실행하지 않고 기존 Result를 그대로
재발행한다. `--duplicate-results-for`는 지정한 Command의 같은 ACCEPTED와 최종
Result를 각각 두 번 발행해 Backend의 `result_id` 멱등성을 검증한다. 전체 비정상
E2E는 다음과 같다.

```powershell
python scripts/command-abnormal-e2e.py
```

주요 Topic:

```text
Publish   tbs/v2/robots/{mqtt_id}/presence
Publish   tbs/v2/robots/{mqtt_id}/state
Publish   tbs/v2/robots/{mqtt_id}/telemetry
Publish   tbs/v2/robots/{mqtt_id}/events/safety
Publish   tbs/v2/robots/{mqtt_id}/events/battery
Publish   tbs/v2/robots/{mqtt_id}/events/incident
Publish   tbs/v2/robots/{mqtt_id}/events/mission
Publish   tbs/v2/robots/{mqtt_id}/commands/result
Subscribe tbs/v2/robots/{mqtt_id}/commands/request
Subscribe tbs/v2/robots/{mqtt_id}/ingest/receipt
```
