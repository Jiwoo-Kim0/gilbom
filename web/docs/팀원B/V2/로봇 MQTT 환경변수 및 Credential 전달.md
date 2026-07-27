# 로봇 MQTT 환경변수 및 Credential 전달

> 문서 버전: V2  
> 작성 기준일: 2026-07-27  
> 대상: 관리자, Backend, 임베디드팀, Jetson Python 애플리케이션  
> 관련 문서: [로봇 등록 및 MQTT Credential 발급](<./로봇 등록 및 MQTT Credential 발급.md>)  
> MQTT 기준: `tbs/v2`, TLS `8883`, 로봇별 username/password

## 1. 목적

관리자 화면에서 발급한 로봇 식별정보와 MQTT Credential을 실제 Jetson 로봇에
전달하고, Python 애플리케이션이 이를 읽어 Mosquitto에 연결하는 방법을 정의한다.

로봇은 DB에 직접 접근하거나 등록정보를 INSERT하지 않는다. 등록은 Backend가
수행하고, 로봇은 발급받은 설정을 사용하여 MQTT Broker에 연결한다.

```text
관리자 등록 화면
→ Backend 로봇 등록
→ MQTT Credential 발급·Broker 반영
→ 관리자에게 Provisioning 정보 1회 제공
→ 임베디드팀이 Jetson에 안전하게 전달
→ Python 프로그램이 환경변수를 읽어 MQTT CONNECT
```

## 2. 현재 Embedded 프로젝트 상태

현재 `embedded` 프로젝트는 구현 전 TODO 골격이다.

- `embedded/config.yaml`은 일반 설정의 후보 구조만 주석으로 제공한다.
- MQTT 비밀번호와 API Key는 환경변수 또는 별도 미추적 파일을 사용하도록 적혀 있다.
- `embedded/main.py`에는 YAML 설정 로딩 TODO만 있다.
- `embedded/comms/mqtt_client.py`에는 Paho MQTT 구현 TODO만 있다.
- `python-dotenv` 의존성은 없으며 실제 `.env` 로더도 없다.
- 일부 주석은 이전 `tbs/v1` 기준이므로 구현할 때 `tbs/v2`로 통일해야 한다.
- `firmware_version` 주석은 V2 결정에 따라 제거 대상이다.

이 문서는 현재 코드가 이미 동작한다고 가정하지 않고 구현 기준을 정의한다.

## 3. 설정 분리 원칙

설정은 일반 설정과 Secret 설정을 분리한다.

| 구분 | 저장 위치 | 예 |
|---|---|---|
| 일반 로봇·하드웨어 설정 | `embedded/config.yaml` | GPS 포트, I2C 주소, 감지 임계값 |
| 로봇 식별·서버 연결 설정 | `/etc/gilbom/robot/robot.env` | Robot ID, Broker Host·Port |
| Secret | `/etc/gilbom/robot/robot.env` 또는 전용 OS Secret | MQTT Password |
| TLS 신뢰 인증서 | `/etc/gilbom/robot/certs/ca.pem` | 로컬 CA 또는 사설 CA 인증서 |

실제 `robot.env`와 인증서는 Git 저장소 안에 두지 않는다. `embedded/.env`를
개발 편의로 사용하더라도 실제 Credential을 커밋하지 않는다.

## 4. 등록 완료 시 서버가 제공할 정보

Credential 발급과 Mosquitto 반영이 모두 성공한 뒤 다음 정보를 한 번 제공한다.

| 항목 | 예 | Secret |
|---|---|:---:|
| `robot_id` | `RBT-001` | X |
| `mqtt_broker_host` | `mqtt.example.com` | X |
| `mqtt_broker_port` | `8883` | X |
| `mqtt_username` | `RBT-001` | X |
| `mqtt_password` | 무작위 32바이트 이상 | O |
| `mqtt_client_id` | `RBT-001-main` | X |
| `mqtt_keepalive_sec` | `30` | X |
| `mqtt_schema_version` | `2.0` | X |
| `ca_certificate` 또는 설치 안내 | `ca.pem` | X |
| `credential_expires_at` | UTC 시각 | X |

`robot_id`와 username은 ACL 적용을 위해 같은 `robot_code`를 사용한다.
Client ID는 인증·권한 식별자가 아니며 동시에 연결되는 MQTT Client마다 고유해야
한다. 단일 로봇 프로세스는 `<robot_code>-main` 같은 값을 사용할 수 있다.
비밀번호만 Secret이다. CA 인증서는 공개 가능한 신뢰 정보지만 변조되지 않도록
서버의 인증서 Fingerprint 또는 승인된 경로로 전달한다.

## 5. 관리자 화면의 1회 발급 결과

발급 화면은 다음과 같이 표시할 수 있다.

```text
로봇 코드       RBT-001
Broker          mqtt.example.com:8883
MQTT Username   RBT-001
MQTT Password   ************  [표시] [복사]
Client ID       RBT-001
만료 시각       2027-01-23 13:00 KST

[Provisioning 파일 1회 다운로드]
```

화면 규칙:

- Credential이 Mosquitto에 정상 반영된 뒤에만 Password를 표시한다.
- Password 원문은 해당 성공 응답에서만 반환한다.
- 새로고침하거나 화면을 닫으면 다시 조회할 수 없다.
- Response에 `Cache-Control: no-store`, `Pragma: no-cache`를 적용한다.
- Frontend는 원문을 `localStorage`, `sessionStorage`, URL, 전역 상태 영속 저장소,
  Analytics, 오류 리포트에 넣지 않는다.
- 화면 캡처·메신저 전달 대신 승인된 전달 절차를 안내한다.

## 6. 로봇용 Environment 파일

### 6.1 실제 파일 경로

MVP 권장 경로:

```text
/etc/gilbom/robot/robot.env
```

권장 소유권과 권한:

```text
owner: gilbom-robot
group: gilbom-robot
mode:  0600
```

설정 디렉터리는 다른 일반 사용자가 열람할 수 없게 제한한다.

### 6.2 `robot.env` 구조

```dotenv
# 로봇 식별
ROBOT_ID=RBT-001

# MQTT Broker
MQTT_BROKER_HOST=mqtt.example.com
MQTT_BROKER_PORT=8883
MQTT_USERNAME=RBT-001
MQTT_PASSWORD=<발급 화면에서 한 번 받은 실제 비밀번호>
MQTT_CLIENT_ID=RBT-001
MQTT_KEEPALIVE_SEC=30
MQTT_SCHEMA_VERSION=2.0

# TLS
MQTT_TLS_ENABLED=true
MQTT_CA_CERT=/etc/gilbom/robot/certs/ca.pem
```

실제 비밀번호에는 공백·개행·따옴표 처리가 필요하지 않도록 Base64URL 형태의
고엔트로피 값을 사용하는 것을 권장한다. 문서·예제 파일에는 실제 값을 넣지 않는다.

### 6.3 저장소용 예제 파일

저장소에는 실제 값이 없는 예제만 둘 수 있다.

```dotenv
ROBOT_ID=
MQTT_BROKER_HOST=
MQTT_BROKER_PORT=8883
MQTT_USERNAME=
MQTT_PASSWORD=
MQTT_CLIENT_ID=
MQTT_KEEPALIVE_SEC=30
MQTT_SCHEMA_VERSION=2.0
MQTT_TLS_ENABLED=true
MQTT_CA_CERT=/etc/gilbom/robot/certs/ca.pem
```

예제 파일에 테스트용 실제 비밀번호를 넣지 않는다.

## 7. 서버에서 로봇으로 전달하는 방법

### 7.1 MVP 권장 방식: 관리자 경유 수동 Provisioning

현재 로봇은 최초 Credential이 없으므로 MQTT로 Credential을 받을 수 없다.
MVP에서는 등록 작업자와 임베디드팀이 다음 절차로 전달한다.

1. 관리자가 실제 로봇의 Serial Number를 확인한다.
2. 관리자 화면에서 로봇을 등록한다.
3. Credential을 발급하고 Mosquitto 반영 성공을 확인한다.
4. 발급 결과를 한 번 표시하거나 Provisioning 파일로 한 번 다운로드한다.
5. 작업자는 연결 대상 로봇의 Serial Number와 `robot_code`를 다시 확인한다.
6. 승인된 로컬 네트워크의 SSH/SCP 또는 물리 USB로 Jetson에 전달한다.
7. `/etc/gilbom/robot/robot.env`에 설치하고 소유권·권한을 제한한다.
8. 필요한 경우 CA 인증서를 `/etc/gilbom/robot/certs/ca.pem`에 설치한다.
9. 로봇 서비스를 시작하거나 재시작한다.
10. 관리자 화면에서 Presence `ONLINE`과 첫 State·Telemetry를 확인한다.
11. 작업자 PC와 USB에 남은 원문 Provisioning 파일을 안전하게 삭제한다.

허용할 수 있는 전달 수단:

- 통제된 현장 네트워크의 SSH/SCP
- 담당자가 직접 관리하는 암호화 USB
- 조직에서 승인한 만료형 Secret 전달 도구

사용하면 안 되는 전달 수단:

- Git Commit
- Jira 설명·댓글
- 일반 메신저·이메일
- 코드에 하드코딩
- Docker Image
- 공개 공유 폴더
- MQTT Topic

### 7.2 자동 Provisioning — 후속 범위

완전 자동 전달을 하려면 로봇이 최초 등록 전에 사용할 별도의 신뢰 수단이 필요하다.

예:

- 출고 시 주입한 1회용 Bootstrap Token
- 로봇별 Client Certificate
- 관리자가 현장에서 스캔하는 1회용 QR Enrollment Code

자동 방식의 예시는 다음과 같다.

```text
로봇 → HTTPS Enrollment API
     → Bootstrap Credential 검증
     → 자기 Serial Number와 등록 요청 연결
     → MQTT Credential 1회 수신
     → Bootstrap Credential 즉시 폐기
```

공용 Bootstrap Password 하나를 모든 로봇에 넣는 방식은 한 대가 유출되면 전체
로봇 등록이 위조될 수 있으므로 사용하지 않는다. 현재 프로젝트에는 자동 Enrollment
구조가 없으므로 V2 MVP 범위에서는 수동 Provisioning을 사용한다.

## 8. Jetson에 파일 설치

설치 결과 구조:

```text
/etc/gilbom/robot/
├── robot.env
└── certs/
    └── ca.pem
```

설치 후 확인할 항목:

- `robot.env` 소유자가 로봇 서비스 계정인지
- 다른 사용자의 읽기 권한이 제거됐는지
- CA 인증서 경로가 실제 파일과 일치하는지
- Broker Host가 인증서의 DNS 이름과 일치하는지
- `ROBOT_ID`, username, Client ID가 같은 로봇 코드인지
- 로봇 시스템 시간이 NTP로 동기화됐는지

비밀번호 원문을 Shell 명령 인자로 넘기지 않는다. 명령 인자는 Process 목록과
Shell History에 남을 수 있다.

## 9. systemd 환경변수 주입

로봇 Python 프로세스를 systemd로 실행할 때의 개념 예시다.

```ini
[Unit]
Description=Gilbom Robot Service
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=gilbom-robot
Group=gilbom-robot
EnvironmentFile=/etc/gilbom/robot/robot.env
WorkingDirectory=/opt/gilbom/embedded
ExecStart=/opt/gilbom/venv/bin/python /opt/gilbom/embedded/main.py
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

환경변수 파일을 변경하면 로봇 서비스를 재시작하여 새 값을 읽게 한다.
실제 unit 파일은 JetPack·Python venv 경로가 확정된 뒤 `embedded/scripts`에 작성한다.

## 10. Python 설정 로딩

Python 소스코드는 비밀번호를 포함하지 않고 환경변수 이름만 알고 있어야 한다.

```python
import os
from dataclasses import dataclass


def required_env(name: str) -> str:
    value = os.getenv(name)
    if value is None or not value.strip():
        raise RuntimeError(f"Required environment variable is missing: {name}")
    return value


@dataclass(frozen=True)
class MqttSettings:
    robot_id: str
    host: str
    port: int
    username: str
    password: str
    client_id: str
    keepalive_sec: int
    ca_certificate: str


def load_mqtt_settings() -> MqttSettings:
    robot_id = required_env("ROBOT_ID")
    username = required_env("MQTT_USERNAME")
    client_id = required_env("MQTT_CLIENT_ID")

    if username != robot_id:
        raise RuntimeError("MQTT username must match ROBOT_ID")

    return MqttSettings(
        robot_id=robot_id,
        host=required_env("MQTT_BROKER_HOST"),
        port=int(required_env("MQTT_BROKER_PORT")),
        username=username,
        password=required_env("MQTT_PASSWORD"),
        client_id=client_id,
        keepalive_sec=int(required_env("MQTT_KEEPALIVE_SEC")),
        ca_certificate=required_env("MQTT_CA_CERT"),
    )
```

오류 메시지에는 환경변수 이름만 포함하고 값은 출력하지 않는다. 설정 객체 전체를
로그로 출력하거나 `repr()`로 남기지 않는다.

## 11. Python MQTT 연결

설정 객체를 Paho MQTT Client에 주입한다.

```python
import paho.mqtt.client as mqtt


def create_client(settings: MqttSettings) -> mqtt.Client:
    client = mqtt.Client(
        callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
        client_id=settings.client_id,
        protocol=mqtt.MQTTv5,
    )
    client.username_pw_set(
        username=settings.username,
        password=settings.password,
    )
    client.tls_set(ca_certs=settings.ca_certificate)
    client.reconnect_delay_set(min_delay=1, max_delay=30)
    return client
```

연결 순서:

1. 환경변수 필수값과 Robot ID·username 일치를 검증한다.
2. LWT용 OFFLINE Presence를 `connect()` 전에 설정한다.
3. Broker `8883`에 TLS로 연결한다.
4. MQTT CONNECT에 username과 password를 포함한다.
5. CONNACK 성공 Callback에서 명령 Topic을 구독한다.
6. ONLINE Presence를 Retain·QoS 1로 발행한다.
7. State와 Telemetry 발행을 시작한다.

연결이 끊어지면 같은 Python Client가 보호된 설정의 Credential로 다시 CONNECT한다.
Password는 매 Telemetry마다 보내지 않고 새 MQTT 연결을 만들 때만 사용한다.

## 12. 최초 연결 확인

관리자 화면에서 다음 순서로 확인한다.

| 확인 단계 | 성공 기준 |
|---|---|
| Credential | `robot_credential.status=ACTIVE` |
| Broker 인증 | 로봇이 CONNACK 성공 |
| 연결 상태 | Presence `ONLINE` 수신 |
| 상태 통신 | State 수신 |
| 측정 통신 | 첫 Telemetry 수신 |
| 명령 통신 | 테스트 명령과 Command Result 수신 |

Credential이 ACTIVE지만 Presence가 없으면 `연결 대기`로 표시한다. 연결 대기 상태가
Credential 발급 실패를 의미하지는 않는다.

## 13. 비밀번호 재발급과 로봇 반영

비밀번호 원문을 분실하면 기존 값을 조회하지 않고 새 Credential을 발급한다.

현재 username 고정 방식은 Broker의 비밀번호가 바뀌는 순간 기존 비밀번호가
무효가 되므로 짧은 연결 중단을 포함한 Cutover가 필요하다.

1. 유지보수 시간을 정한다.
2. 새 Credential을 발급하고 Broker 반영 성공을 확인한다.
3. 새 원문을 한 번 전달한다.
4. 로봇에 임시 파일로 저장한다.
5. 기존 `robot.env`를 새 파일로 원자적으로 교체한다.
6. 로봇 서비스를 재시작한다.
7. Presence ONLINE과 State·Telemetry를 확인한다.
8. 이전 Credential과 임시 전달 파일을 폐기한다.

로봇 분실이나 탈취 시 전달을 기다리지 않고 Broker Credential을 즉시 폐기하고
로봇 운영 상태를 `DISABLED`로 변경한다.

## 14. 로깅 금지 항목

다음 값은 Backend, Frontend, Provisioner, Python 로봇 로그에 남기지 않는다.

```text
MQTT_PASSWORD
Password Hash
Credential Fingerprint
robot.env 전체 내용
CONNECT Packet의 인증 필드
```

로그에 허용되는 값:

- Robot Code
- Credential ID
- Broker Host
- 연결 성공·실패 상태
- 제한된 오류 코드
- 발생·수신 시각

인증 실패 로그는 username 존재 여부, Hash 형식, 비밀번호 일치 여부를 구분하여
외부에 노출하지 않는다.

## 15. 구현 체크리스트

- [ ] `embedded`의 MQTT Topic 주석을 `tbs/v2`로 변경
- [ ] `firmware_version` 설정 제거
- [ ] 환경변수 검증 Loader 구현
- [ ] 실제 Secret이 없는 `robot.env.example` 작성
- [ ] 실제 `robot.env`가 Git에서 제외되는지 확인
- [ ] systemd unit과 전용 서비스 계정 생성
- [ ] `/etc/gilbom/robot` 소유권·권한 설정
- [ ] CA 인증서 배포와 Hostname 검증
- [ ] Paho MQTT TLS·username/password 설정
- [ ] LWT, ONLINE Presence, 재연결 Backoff 구현
- [ ] 설정·오류 로그의 Secret 마스킹 테스트
- [ ] 등록 Robot Code와 Topic·Payload Robot ID 일치 테스트
- [ ] Credential 재발급·폐기 절차 테스트
- [ ] 전달 작업 후 관리자 PC·USB의 원문 파일 삭제
