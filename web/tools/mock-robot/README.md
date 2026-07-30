# MQTT v2 Mock Robot

Python Mock Robot은 실제 기기 대신 MQTT v2 Presence, State, Telemetry,
Mission Event와 Command Result를 발행하고 Command Request와 Ingest Receipt를
구독한다.

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
Jetson 온도를 매번 0.3℃씩 변화시킨다. 온도는 시작값부터 5단계 상승한 뒤 같은
폭으로 하강하는 과정을 반복하므로 장시간 실행해도 무한히 커지지 않는다.

```powershell
$env:MQTT_PASSWORD='robot-test MQTT 비밀번호'
python tools/mock-robot/mock_robot.py `
  --mqtt-id robot-test `
  --telemetry-interval 3 `
  --jetson-temp-c 43.0 `
  --jetson-temp-step-c 0.3
```

`--jetson-temp-step-c`의 기본값은 `0`이므로 옵션을 생략하면 기존처럼 같은 값을
반복 발행한다.

Mission 시작·일시정지·재개·취소 명령이 성공하면 각각 대응하는 Mission Event를
발행한다. 로컬 완료 흐름이 필요하면 `--mission-auto-complete-sec`에 양수를
지정한다. RUNNING 상태의 벽시계 누적시간이 해당 값에 도달하면 마지막
Telemetry를 먼저 발행하고 `MISSION_COMPLETED`와 완료 State를 발행한다.

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

주요 Topic:

```text
Publish   tbs/v2/robots/{mqtt_id}/presence
Publish   tbs/v2/robots/{mqtt_id}/state
Publish   tbs/v2/robots/{mqtt_id}/telemetry
Publish   tbs/v2/robots/{mqtt_id}/events/mission
Publish   tbs/v2/robots/{mqtt_id}/commands/result
Subscribe tbs/v2/robots/{mqtt_id}/commands/request
Subscribe tbs/v2/robots/{mqtt_id}/ingest/receipt
```
