# Jetson 주입·기동

웹 관리자 화면에서 받은 값을 로봇에 넣고 띄우는 절차. Day 7 실기 E2E의 4번 단계다.

```text
Nginx HTTPS → 관리자 로그인 → 로봇 등록 → Credential 발급
  → [여기] Jetson 주입 → TLS 접속 → Presence·State·Telemetry → Command 왕복
```

## 0. 보드로 옮기기

젯슨에는 git을 연결하지 않는다. 아래만 복사하면 통신 프로세스가 돈다.

```text
main.py  robot_state.py  telemetry.py  events.py  commands.py
config.yaml  requirements.txt  .python-version
comms/  drive/  detect/  sensors/
```

**복사하지 않는 것**

| 대상 | 이유 |
| --- | --- |
| `test/`, `docs/`, `comms+a/` | 실행에 안 쓴다 |
| `var/` | 런타임 산출물(outbox·증거 이미지). 보드에서 새로 만들어진다 |
| `__pycache__/`, `.pytest_cache/` | 개발 PC 아키텍처(x86) 캐시다. aarch64에서 쓰면 안 된다 |
| `scripts/orincar.env` | 보드에서 직접 만든다 (§2) |
| `gilbom-mqtt-ca.crt` | CA는 §1처럼 따로 넣는다 |

`rsync`가 있으면 한 줄로 된다.

```bash
rsync -av --delete \
  --exclude='__pycache__' --exclude='.pytest_cache' --exclude='var' \
  --exclude='test' --exclude='docs' --exclude='comms+a' --exclude='scripts/orincar.env' \
  ./ ubuntu@<jetson>:/home/ubuntu/SSAFY/embedded/
```

`--delete`는 지운 파일이 보드에 남지 않게 한다. git이 없으니 이게 유일한 정리 수단이다.

### 의존성

Python은 **3.10.12 고정**이다(`.python-version`). 보드 시스템 파이썬과 같은 버전이라야
JetPack이 깔아 둔 TensorRT·torch를 그대로 쓸 수 있다.

```bash
cd /home/ubuntu/SSAFY/embedded
python3.10 -m venv .venv --system-site-packages
. .venv/bin/activate
pip install -r requirements.txt
```

`--system-site-packages`가 빠지면 JetPack이 설치한 TensorRT·torch가 안 보인다.

복사가 온전한지 한 줄로 확인한다.

```bash
python3 -c "import main" && echo OK
```

`ModuleNotFoundError`가 나면 빠진 패키지거나 안 옮긴 디렉터리다. 이 시점에는 장치를
열지 않으므로 카메라·시리얼이 없어도 통과해야 한다.

## 1. CA 인증서

브로커 CA를 Jetson 안에 넣고 경로를 `MQTT_CA_CERT`로 준다.

```bash
sudo mkdir -p /etc/orincar
sudo cp gilbom-mqtt-ca.crt /etc/orincar/
sudo chmod 644 /etc/orincar/gilbom-mqtt-ca.crt
```

비워 두면 저장소에 동봉된 `gilbom-mqtt-ca.crt`를 쓴다(`config.yaml` 위치 기준 상대경로).
운영 브로커는 8883 TLS 전용이라 CA 없이는 접속 자체가 안 된다.

## 2. 값 채우기

```bash
cp scripts/orincar.env.example scripts/orincar.env
chmod 600 scripts/orincar.env
$EDITOR scripts/orincar.env
```

`MQTT_PASSWORD`는 발급 성공 Modal에서 **한 번만** 보인다. 놓치면 회전해서 새로 받아야 한다.
서버는 원문을 보관하지 않는다.

값의 출처는 `orincar.env.example` 주석에 항목별로 적어 두었다.

> **`config.yaml`에 직접 박지 말 것.** 값을 넣으면 동작은 한다(placeholder는 문자열
> 전체가 `${...}`일 때만 치환하므로 리터럴은 그대로 쓰인다). 하지만 §0의 `rsync`가
> `config.yaml`을 덮어쓰므로 다음 배포 때 값이 사라진다. `orincar.env`는 rsync에서
> 제외돼 있어 살아남는다. 개발 PC 쪽 `config.yaml`에 넣는 경우엔 비밀번호가 그대로
> 커밋된다 — 그러면 Credential을 회전해야 한다.

## 3. 수동 실행 (현재 방식)

```bash
cd /home/ubuntu/SSAFY/embedded
set -a; . scripts/orincar.env; set +a
python3 -u main.py
```

`set -a`는 이후 대입을 전부 export한다. 이게 없으면 셸 변수로만 남아 Python이 못 읽는다.

기동 직후 확인할 것:

- `connected to i15d101.p.ssafy.io:8883` — TLS 접속 성공
- `subscribed` 로그에 거부 없음 — ACL이 `commands/request` 읽기를 막으면 명령을 영원히 못 받는다
- 브로커에서 `presence` ONLINE, 5초마다 `telemetry`, 30초마다 `state`

접속이 안 되면 이 순서로 본다.

| 증상 | 원인 |
| --- | --- |
| 기동 즉시 `ConfigError` | `MQTT_ID`·`MQTT_HOST`·`MQTT_PASSWORD` 중 빠진 것이 있다 |
| `mqtt.username must equal robot.id` | `MQTT_USERNAME`과 `MQTT_ID`가 다르다 |
| `FileNotFoundError` (CA) | `MQTT_CA_CERT` 경로가 틀렸다 |
| 무한 재접속, 오류 없음 | 평문으로 붙는 중이다 — `ca_cert`가 비었는지 확인 |
| 접속은 되는데 발행이 서버에 안 보임 | `mqtt_id` 불일치 또는 QoS 강등. 서버 `gilbom.mqtt.inbound.rejected` 메트릭을 본다 |
| 명령이 안 옴 | SUBACK 거부(ACL) 또는 서버 `robot_command.command_status = PUBLISH_FAILED` |

서버가 거부한 메시지는 **로봇이 알 수 없다.** 브로커가 이미 PUBACK을 보냈으므로 Outbox는
`sent`로 지워진다. `ingest/receipt`가 MVP 미사용이라 메울 채널이 없다 — 통합 기간에는
웹 쪽 거부 메트릭을 같이 봐야 한다.

### 로봇 없이 통신만 스모크

하드웨어 없이 실브로커에 발행 8종·명령 수신만 확인하려면 (개발 PC에서도 됨):

```bash
python scripts/mqtt_live_smoke.py --ca-cert <브로커 CA 경로>   # 접속값은 .env 또는 환경변수
python scripts/mqtt_live_smoke.py --kind incident --count 3 --interval 2
```

payload를 손으로 만들지 않고 `MessageFactory`·`MqttClient`를 그대로 쓴다 — 여기서
통과하면 `main.py`도 같은 wire를 탄다. 예전 목 로봇(`test/통신테스트/mqtt_tls_test.py`)은
`robot_id`·`ttl_sec` 없음 등 계약이 갈라져 현재 서버가 거부한다 — 더 쓰지 말 것.

## 4. 서비스 등록 (선택)

수동 실행이 끝나고 상시 가동으로 넘어갈 때 쓴다.

```bash
sudo cp scripts/orincar.env /etc/orincar/orincar.env
sudo chmod 600 /etc/orincar/orincar.env
sudo cp scripts/orincar.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now orincar
journalctl -u orincar -f
```

unit이 `time-sync.target`에 의존하는 이유: `expires_at` 판정과 서버 freshness 검사가
둘 다 NTP 동기를 전제한다. 시계가 밀리면 유효한 명령을 버리거나 만료된 명령을 실행하고,
발행한 telemetry·event도 서버가 거부한다.

`WorkingDirectory`는 경로 두 개에 영향을 준다 — `outbox_path`(작업 디렉터리 기준)와
증거 이미지 `evidence_dir`. `ca_cert`는 `config.yaml` 위치 기준이라 영향받지 않는다.

## 5. 아직 안 되는 것

| 항목 | 상태 |
| --- | --- |
| 증거 이미지 업로드 | 로봇용 `X-API-Key` 발급 경로가 없다. `UPLOAD_*`를 비워 두면 업로드만 건너뛰고 나머지는 정상 동작한다 |
| `detect.model_version` | `0.0.0-stub`. 실제 export 버전으로 교체해야 `incident.model.version` 추적이 의미를 갖는다 |
| 순찰구역 코드 | `START_MISSION`으로 받은 `area_code`를 불투명 문자열로 보관하고 Incident에 그대로 echo한다. 별도 형식 검증은 하지 않는다 |
