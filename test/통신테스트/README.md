# 외부 로봇 MQTT TLS 통신 테스트 방법

이 폴더는 외부 PC 또는 Jetson에서 길봄 MQTT Broker의 robot→server Topic
전체를 발행하고, 서버가 메시지를 수신하는지 확인하기 위한 전달용 테스트
패키지입니다. 프론트엔드는 필요하지 않습니다.

## 1. 외부 팀에 전달할 파일

서버 담당자가 `build-external-package.ps1`로 만든 ZIP에는 다음 파일이
포함됩니다.

```text
통신테스트/
├─ README.md
├─ mqtt_tls_test.py
├─ requirements.txt
├─ local-ca-cert.pem
├─ .env
└─ connection-secret.md
```

`.env`와 `connection-secret.md`에는 임시 `robot-test` 비밀번호가 포함됩니다.
이 폴더와 ZIP 자체를 Credential Secret으로 취급하고 허가된 팀원만 있는
Mattermost 비공개 채널이나 DM으로 전달합니다. 서버의 `web/.env`, Mosquitto
`passwd`, CA 개인키, Broker 개인키는 전달하지 않습니다.

## 2. 외부 PC가 알아야 할 접속 정보

| 항목 | 테스트 값 |
|---|---|
| Host | `125.137.58.131` |
| Port | `8883` |
| MQTT Version | `5.0` |
| TLS | 서버 인증서 검증 사용 |
| Username | `robot-test` |
| Client ID | 스크립트가 충돌하지 않도록 매 실행 시 자동 생성 |
| Password | 패키지의 `.env`에서 자동 로드 |
| CA Certificate | 이 폴더의 `local-ca-cert.pem` |
| Client Certificate | 사용하지 않음 |

현재 테스트 구성은 mTLS가 아니라 `서버 TLS + Username/Password` 인증입니다.
`robot-test`는 외부 통신 확인용 임시 계정이며 실제 운영 로봇 계정은 아닙니다.

## 3. 발행 Topic과 QoS

| 메시지 | QoS | Retain |
|---|---:|---:|
| Presence | `1` | `true` |
| State | `1` | `true` |
| Telemetry | `0` | `false` |
| Incident Event | `1` | `false` |
| Battery Event | `1` | `false` |
| Safety Event | `1` | `false` |
| Mission Event | `1` | `false` |
| Command Result | `1` | `false` |

Topic 이름은 `/status`가 아니라 `/state`입니다. ACL의 `%u` 규칙 때문에
MQTT Username, Topic의 robot ID, Payload의 `robot_id`가 모두
`robot-test`로 일치해야 합니다. 제공된 스크립트가 이 규칙을 자동 적용합니다.

## 4. 실행 전 준비

- Python 3.10 이상
- 외부 PC에서 `125.137.58.131:8883`으로 나갈 수 있는 네트워크
- 현재 시간이 크게 틀리지 않은 PC
- 패키지에 포함된 `.env`
- 이 폴더의 공개 CA 인증서

모바일 브라우저만으로는 Python 테스트를 실행할 수 없습니다. PC 또는 Jetson에서
실행합니다.

## 5. Windows PowerShell 실행

```powershell
cd <전달받은 통신테스트 폴더>
py -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
python mqtt_tls_test.py
```

Python이 같은 폴더의 `.env`에서 Host, Port, Username, Password와 CA 경로를
자동으로 읽으므로 비밀번호를 별도로 입력하지 않아도 됩니다.

`.env`가 없거나 `MQTT_PASSWORD`가 비어 있을 때만 비밀번호 입력창이 나타납니다.

PowerShell이 가상환경 실행을 차단하면 현재 창에서만 다음 명령을 먼저 실행합니다.

```powershell
Set-ExecutionPolicy -Scope Process Bypass
```

## 6. Linux 또는 Jetson 실행

```bash
cd "<전달받은 통신테스트 폴더>"
python3 -m venv .venv
source .venv/bin/activate
python3 -m pip install -r requirements.txt
python3 mqtt_tls_test.py
```

## 7. 5초마다 계속 발행하기

기본 실행은 8개 robot→server Topic을 5초마다 계속 발행합니다. 종료는
`Ctrl+C`입니다.
각 발행 직전에 실제 전송하는 전체 JSON Payload를 Python Console에 출력합니다.
비밀번호와 TLS 인증정보는 출력하지 않습니다.

더미 Payload에는 다음 값이 채워져 있습니다.

| 분류 | 더미값 |
|---|---|
| 위치 | 위도 `37.5013`, 경도 `127.0396`, 방향 `92.3°` |
| 주행 | 이동 중 `true`, 속도 `0.75m/s` |
| 배터리 | `87%`, `25.2V`, `-1.8A`, 온도 `32.4°C` |
| 시스템 | Jetson 온도 `41.5°C` |
| 임무 | `MIS-TEST-001`, `RUNNING`, 경과 `120초`, 사고 `2건` |
| 상태 | `PATROLLING`, 모든 Health 항목 `OK` |

```powershell
python mqtt_tls_test.py
```

한 번만 발행:

```powershell
python mqtt_tls_test.py --count 1
```

10회만 발행:

```powershell
python mqtt_tls_test.py --count 10 --interval 5
```

State와 Telemetry만 기존 방식으로 발행:

```powershell
python mqtt_tls_test.py --kind both
```

한 Topic만 발행:

```powershell
python mqtt_tls_test.py --kind presence
python mqtt_tls_test.py --kind state
python mqtt_tls_test.py --kind telemetry
python mqtt_tls_test.py --kind incident
python mqtt_tls_test.py --kind battery
python mqtt_tls_test.py --kind safety
python mqtt_tls_test.py --kind mission
python mqtt_tls_test.py --kind command-result
```

기본값이 바뀐 경우에만 Host·Port·Username을 명시합니다.

```powershell
python mqtt_tls_test.py `
  --host 125.137.58.131 `
  --port 8883 `
  --username robot-test
```

패키지의 `.env` 대신 운영체제 환경변수를 지정하면 그 값을 우선 사용합니다.
테스트 후 반드시 제거합니다.

```powershell
$env:MQTT_PASSWORD = "<새 비밀번호>"
python mqtt_tls_test.py
Remove-Item Env:MQTT_PASSWORD
```

공용 PC, 화면 공유 중인 PC, 명령 기록을 수집하는 환경에서는 대화형 입력을
사용합니다.

## 8. 외부 PC의 성공 출력

```text
Connected host=125.137.58.131 port=8883 username=robot-test tls=on
Sending topic=tbs/v2/robots/robot-test/state qos=1 retain=True seq=1
{
  "schema_version": "2.0",
  "robot_id": "robot-test",
  ...
}
Published topic=tbs/v2/robots/robot-test/state qos=1 retain=True seq=1
Sending topic=tbs/v2/robots/robot-test/telemetry qos=0 retain=False seq=2
{
  "schema_version": "2.0",
  "message_id": "...",
  "robot_id": "robot-test",
  ...
}
Published topic=tbs/v2/robots/robot-test/telemetry qos=0 retain=False seq=2
```

이 출력은 외부 PC가 Broker에 TLS 연결하고 메시지 발행 요청을 완료했다는
의미입니다. 최종 서버 수신 여부는 서버 담당자가 Backend 로그로 확인합니다.

## 9. 서버 담당자의 수신 확인

Backend를 Docker로 실행한 경우:

```powershell
cd C:\ssafy\SSAFY-TASK\web
docker compose logs -f --tail 100 backend mosquitto
```

Backend를 STS에서 실행한 경우에는 STS Console을 확인합니다. Docker Backend와
STS Backend를 동시에 실행하지 않습니다.

정상적으로 파싱되면 다음과 비슷한 로그가 보입니다.

```text
MQTT message received. topic=..., qos=1, payload={"schema_version":"2.0",...}
MQTT message routed to handler. type=STATE, robotId=robot-test, qos=1
MQTT message received. topic=..., qos=0, payload={"schema_version":"2.0",...}
MQTT message routed to handler. type=TELEMETRY, robotId=robot-test, qos=0
```

현재 `robot-test`는 DB에 등록된 실제 로봇이 아니므로 Telemetry 수신 로그 뒤에
`ROBOT_NOT_FOUND`가 표시될 수 있습니다. 이는 MQTT 외부 통신 실패가 아니라
DB 저장 대상 로봇이 없다는 뜻입니다. 현재 테스트 목적이 통신·Payload 확인이면
수신 로그까지 확인하면 됩니다.

로컬 Profile은 더미값 확인을 위해 전체 JSON Payload를 로그에 출력합니다.
비밀번호와 TLS 인증정보는 MQTT Payload에 포함되지 않습니다. EC2 Profile에서는
전체 Payload 로그가 비활성화됩니다.

## 10. 오류별 확인 방법

### 연결 Timeout 또는 Connection refused

- 공인 IP와 Port가 `125.137.58.131:8883`인지 확인
- 공유기 포트포워딩의 내부 목적지가 Broker PC인지 확인
- Windows Firewall TCP 8883 허용 여부 확인
- 서버에서 Mosquitto가 실행 중인지 확인

### TLS certificate verification failed

- 전달받은 `local-ca-cert.pem`을 사용했는지 확인
- `--host`를 임의의 다른 IP나 hostname으로 바꾸지 않았는지 확인
- 서버 인증서가 재발급되었다면 최신 CA 파일을 다시 전달받아 확인

### Not authorized 또는 Bad user name or password

- Username이 정확히 `robot-test`인지 확인
- 서버 담당자에게 받은 비밀번호 앞뒤에 공백이 들어가지 않았는지 확인
- 비밀번호가 변경되었다면 최신 값을 다시 전달받아 확인

### MQTT_QOS_MISMATCH

- State는 QoS 1
- Telemetry는 QoS 0
- 제공된 스크립트를 수정하지 않았다면 자동으로 올바르게 전송됨

### ROBOT_NOT_FOUND

- MQTT와 TLS 통신 자체는 성공
- `robot-test`가 DB 정식 로봇이 아니어서 Telemetry 저장만 거절된 상태

## 11. 보안 규칙

- `local-ca-cert.pem`은 공개 CA 인증서이므로 외부 로봇에 전달 가능
- 비밀번호가 든 `connection-secret.md`와 ZIP을 Git 또는 공개 채널에 올리지 않음
- 서버의 `web/.env` 파일 전체를 외부 팀에 전달하지 않음
- 패키지용 `.env`에는 MQTT 테스트 접속정보만 포함
- `local-ca-key.pem`, `local-broker-key.pem`, Mosquitto `passwd`는 전달 금지
- 테스트 종료 후 외부 PC의 `MQTT_PASSWORD` 환경변수를 제거
- 테스트 종료 후 전달한 ZIP, 패키지용 `.env`, `connection-secret.md`를 삭제
- 실제 로봇 전환 시 `robot-test`를 제거하고 자동 발급된 `RBT-*` 계정 사용
