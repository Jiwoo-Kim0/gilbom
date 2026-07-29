# MQTT v2 Mock Robot

Python Mock Robot은 실제 기기 대신 MQTT v2 Presence, State, Telemetry와
Command Result를 발행하고 Command Request와 Ingest Receipt를 구독한다.

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

주요 Topic:

```text
Publish   tbs/v2/robots/{mqtt_id}/presence
Publish   tbs/v2/robots/{mqtt_id}/state
Publish   tbs/v2/robots/{mqtt_id}/telemetry
Publish   tbs/v2/robots/{mqtt_id}/commands/result
Subscribe tbs/v2/robots/{mqtt_id}/commands/request
Subscribe tbs/v2/robots/{mqtt_id}/ingest/receipt
```
