# 길봄 MQTT TLS 적용 가이드

> 목표: `i15d101.p.ssafy.io:8883`에서 서버 TLS + MQTT 계정 + ACL 통신을 먼저 완료한다.  
> mTLS는 이 통신이 성공한 다음 클라이언트 인증서 계층을 추가한다.

## 디렉터리 구조

```text
infra/mosquitto/
├─ acl                       # 모든 환경이 공유하는 MQTT v2 ACL 원본
├─ mosquitto.conf            # compose.prod.yaml이 사용하는 운영 설정
├─ passwd                    # 운영 MQTT 계정 파일, Git 제외
├─ passwd.example            # 운영 계정 파일 예시
├─ README.md                 # 운영·TLS 설정 가이드
├─ PROFILES.md               # 환경별 실행 방법
│
├─ local/                    # 로컬 Docker TLS 환경
│  ├─ compose.yaml           # Mosquitto 단독 실행용 Compose
│  ├─ Dockerfile.init        # 로컬 초기화 컨테이너 이미지
│  ├─ init-local-mqtt.sh     # CA·Broker 인증서·계정·ACL 초기화
│  ├─ run-local-mosquitto.sh # passwd 변경 감지 후 Broker Reload
│  ├─ mosquitto.conf         # 로컬 TLS Broker 설정
│  ├─ openssl-local-ca.cnf   # 로컬 CA 발급 설정
│  ├─ openssl-local.cnf      # Broker 인증서 발급 설정
│  ├─ .dockerignore
│  ├─ .gitattributes
│  │
│  ├─ ca/                    # 자동 생성, Git 제외
│  │  └─ local-ca-key.pem    # 로컬 CA 개인키
│  │
│  └─ runtime/               # 자동 생성, Git 제외
│     ├─ acl                 # 실행 권한을 적용한 ACL 복사본
│     ├─ credentials/
│     │  └─ passwd           # Backend와 Broker가 공유하는 로컬 계정 파일
│     └─ certs/
│        ├─ local-ca-cert.pem
│        ├─ local-broker-cert.pem
│        └─ local-broker-key.pem
│
├─ test/                     # 평문 MQTT 단계별 검증
│  ├─ compose.yaml
│  └─ mosquitto.conf
│
├─ tls/                      # 운영 TLS 형태의 독립 검증
│  ├─ compose.yaml
│  └─ mosquitto.conf
│
└─ mtls/                     # 향후 mTLS 독립 검증
   ├─ compose.yaml
   └─ mosquitto.conf
```

### 디렉터리 책임

| 위치 | 책임 |
|---|---|
| 루트 | EC2 운영 Broker 설정과 공통 MQTT v2 ACL 관리 |
| `local/` | 로컬 개발용 CA와 CA 서명 Broker 인증서 생성 및 실행 |
| `test/` | 인증서 없이 Topic 연결만 확인하는 제한된 테스트 |
| `tls/` | 운영 TLS 구성을 독립적으로 검증 |
| `mtls/` | 클라이언트 인증서를 포함한 mTLS 구성을 독립적으로 검증 |

`acl`은 루트 파일 하나를 원본으로 관리한다. 로컬 초기화 스크립트가 이를
`local/runtime/acl`로 복사하고 Mosquitto가 요구하는 파일 권한을 적용한다.
따라서 환경별 ACL을 별도로 수정하지 않는다.

`local/ca/`, `local/runtime/`, `passwd`는 Secret 또는 자동 생성 파일이므로
Git에 커밋하지 않는다. 새로 저장소를 받은 개발자는
`init-local-mqtt.sh`를 직접 실행할 필요 없이 기본 `compose.yaml`을 실행하면
`mqtt-init` 서비스가 필요한 로컬 파일을 생성한다.

`mqtt-init`은 인증 자산 생성에만 root를 사용하고 완료 전에 `local/runtime/`과
`local/ca/`의 소유자를 bind source 소유자로 되돌린다. Runtime group은 1883을
유지하므로 Broker와 Backend는 필요한 파일을 읽고 쓸 수 있고, 개발자와 Jenkins는
컨테이너 종료 뒤 workspace를 정상적으로 정리할 수 있다. 로컬 Broker의 Docker
healthcheck는 TLS CA 검증, Backend 계정 인증과 `$SYS/broker/version` 응답을
함께 확인한다.

환경별 실행 방법은 [`PROFILES.md`](./PROFILES.md)를 참고한다.

## Credential 회전·폐기 TLS Cutover 검증

로컬 Mosquitto가 실행 중일 때 `web/`에서 다음 일회성 검증 컨테이너를 실행한다.

```bash
docker compose run --no-deps --rm mqtt-init \
  /bin/sh /mqtt-local/verify-credential-cutover.sh
```

스크립트는 임시 로봇 계정을 생성해 TLS password-file Cutover 전체를 확인한다.

- 최초 Secret 허용
- 회전 뒤 이전 Secret 거부와 새 Secret 허용
- 보상 처리 뒤 이전 verifier 복구와 회전 Secret 거부
- 폐기 뒤 재연결 거부

평문 Secret은 출력하지 않으며 종료 시 임시 계정을 제거한다. password-file을 실제로
변경하므로 로컬 격리 Broker에서만 실행한다.

Windows Docker Desktop bind mount는 passwd 변경이 Broker 컨테이너에 보일
때까지 지연될 수 있어 단계별 최대 120초를 기다린다. 필요하면
`MQTT_CUTOVER_MAX_ATTEMPTS`로 초 단위 재시도 횟수를 조정한다. 제한 시간 안에
reload되지 않으면 성공으로 간주하지 않고 실패한다.
보상 verifier를 직접 복구한 뒤에는 password-file을 bind source owner와
group `1883`, mode `0660`으로 맞춘다. uid/gid 1883인 Broker와 Backend의
group 접근을 유지하면서 host workspace 소유권을 보존한다.

검증 컨테이너에서 `mosquitto` DNS가 해석되지 않으면 컨테이너가 과거
`gilbom_default` 네트워크에 남아 있는지 확인하고, 임시 `network connect` 대신
현재 Compose 명세로 Broker만 재생성한다.

```bash
docker compose up -d --no-deps --force-recreate mosquitto
```

## 로컬 PC와 팀원 로봇 PC의 LAN 통신

두 PC가 같은 Wi-Fi 또는 같은 사설망에 있으면 ngrok이나 공유기 포트포워딩 없이
Broker PC의 LAN IPv4로 직접 연결한다. 기본 Compose는 안전하게 localhost에만
바인딩되므로, LAN 테스트할 때만 ignored `.env`를 다음처럼 변경한다.

```dotenv
MQTT_BIND_ADDRESS=0.0.0.0
MQTT_LAN_IP=192.168.100.191
MQTT_TLS_PORT=8883
MQTT_PUBLIC_BROKER_URI=ssl://192.168.100.191:8883
```

`MQTT_LAN_IP`에는 예시 값을 복사하지 말고 Broker PC에서 확인한 현재 LAN IPv4를
입력한다. 이 값은 로컬 Broker 인증서의 IP SAN에도 포함된다. IP가 바뀌면 값을
수정하고 `mqtt-init`을 다시 실행하여 Broker 인증서를 재발급해야 한다.
로봇 MQTT 계정은 고정 로컬 Secret으로 만들지 않고 Credential API에서 로봇별로
발급한다.

```bash
docker compose up --build --detach mqtt-init mosquitto
docker compose ps
docker compose logs --tail 100 mqtt-init mosquitto
```

팀원에게 전달할 값은 다음으로 제한한다.

```text
MQTT_HOST=<Broker PC의 LAN IPv4>
MQTT_PORT=8883
MQTT_USERNAME=<mqtt_id와 동일>
MQTT_PASSWORD=<해당 로봇의 비밀번호>
MQTT_CA_CERT=local-ca-cert.pem의 로봇 PC 내 경로
```

공개 인증서인
`infra/mosquitto/local/runtime/certs/local-ca-cert.pem`은 전달해도 되지만,
`local-ca-key.pem`, Broker 개인키, `runtime/credentials/passwd`,
Backend 계정 비밀번호는
전달하지 않는다. 로봇 Client는 CA 검증과 인증서 hostname/IP 검증을 모두
유지하며 insecure TLS 옵션을 사용하지 않는다.

로봇 DB 등록과 MQTT Credential 발급은 별도 단계다. 관리자가
`POST /api/v1/robots/{robotId}/credentials`를 호출하면 Backend가 비밀번호를
자동 생성하고 `runtime/credentials/passwd`를 갱신한다. Broker 실행 래퍼는
파일 변경을 감지해 설정을 Reload한다. 응답의 원문 비밀번호는 한 번만 표시되므로
즉시 로봇의 보호된 Secret 저장소에 주입한다.

EC2에서는 같은 API 코드를 사용하되 `ec2` Profile의 자동 발급이 기본적으로
비활성화되어 있다. 운영 배포에서 Backend와 Broker에 전용 Credential 볼륨,
`mosquitto_passwd`, Reload 방식, 외부 Broker URI를 명시한 뒤에만
`MQTT_CREDENTIAL_PROVISIONING_ENABLED=true`로 연다. 로컬 CA·로컬 passwd를
EC2로 복사하지 않는다.

Windows Defender Firewall은 Private 프로필에서 TCP 8883을 팀원 PC의 LAN
IP 하나에만 허용한다. 관리자 PowerShell 예시는 다음과 같다.

```powershell
New-NetFirewallRule `
  -DisplayName "Gilbom MQTT LAN Test" `
  -Direction Inbound `
  -Protocol TCP `
  -LocalPort 8883 `
  -RemoteAddress <TEAMMATE_LAN_IP> `
  -Profile Private `
  -Action Allow
```

테스트 종료 후 규칙을 제거하거나 비활성화하고 `.env`의
`MQTT_BIND_ADDRESS`를 다시 `127.0.0.1`로 되돌린다.

```powershell
Remove-NetFirewallRule -DisplayName "Gilbom MQTT LAN Test"
```

## 서로 다른 네트워크에서 ngrok TCP로 통신

서로 다른 Wi-Fi에서 당일 통합할 때는 공유기 포트포워딩보다 ngrok TCP를
우선한다. Broker는 계속 localhost에만 바인딩하고 ngrok agent가 outbound
tunnel을 만든다.

```dotenv
MQTT_BIND_ADDRESS=127.0.0.1
MQTT_LAN_IP=
```

별도 터미널에서 tunnel을 시작한다. 가능하면 팀원 PC의 공인 egress IP만
허용한다.

```powershell
ngrok tcp 8883 --cidr-allow=<TEAMMATE_PUBLIC_IP>/32
```

ngrok이 출력한 주소가 다음과 같다고 가정한다.

```text
tcp://0.tcp.ap.ngrok.io:12345
```

`.env`에는 scheme과 port를 제외한 hostname만 넣는다.

```dotenv
MQTT_PUBLIC_HOSTNAME=0.tcp.ap.ngrok.io
```

그다음 `mqtt-init`과 Broker를 재생성한다. 초기화 스크립트는 hostname이 기존
인증서에 없을 때 Broker 인증서만 다시 발급하고 로컬 CA는 재사용한다.

```bash
docker compose up --build --detach --force-recreate mqtt-init mosquitto
```

팀원 로봇 PC에는 다음을 전달한다.

```text
MQTT_HOST=0.tcp.ap.ngrok.io
MQTT_PORT=12345
MQTT_USERNAME=<mqtt_id와 동일>
MQTT_PASSWORD=<해당 로봇의 비밀번호>
MQTT_CA_CERT=<전달받은 local-ca-cert.pem 경로>
```

Backend는 ngrok endpoint를 사용하지 않고 같은 PC의 Broker에 직접 연결한다.

| Backend 실행 방식 | MQTT Broker URI | CA 인증서 |
|---|---|---|
| IDE·Gradle 로컬 실행 | `ssl://localhost:8883` | `../infra/mosquitto/local/runtime/certs/local-ca-cert.pem` |
| Docker Compose 실행 | `ssl://mosquitto:8883` | `/app/config/mqtt/certs/local-ca-cert.pem` |

로컬 Backend 실행:

```powershell
cd C:\ssafy\SSAFY-TASK\web
docker compose up --build --detach db mqtt-init mosquitto

cd backend
.\gradlew.bat bootRun
```

Docker Backend 실행:

```powershell
cd C:\ssafy\SSAFY-TASK\web
docker compose up --build --detach db mqtt-init mosquitto backend
```

두 Backend를 동시에 실행하거나 같은 MQTT Client ID로 접속하지 않는다.

ngrok TCP는 MQTT TLS byte stream을 Broker까지 전달한다. 로봇 Client의 CA와
hostname 검증을 끄지 않는다. 무료·임시 주소가 바뀌면 새 hostname을 `.env`에
반영하고 Broker 인증서를 다시 발급해야 한다.

이 절차에서도 MQTT `1883` 평문 포트를 열지 않고, 공유기 포트포워딩과 Windows
인바운드 방화벽 규칙을 추가하지 않는다. 장기 운영은 ngrok이나 가정용
포트포워딩이 아니라 운영 Broker와 정식 인증서를 사용한다.

## Git과 EC2에서 관리할 파일

| 파일 | 위치 | Git 커밋 |
|---|---|---|
| `compose.prod.yaml` | `web/` | O |
| `mosquitto.conf` | `web/infra/mosquitto/` | O |
| `acl` | `web/infra/mosquitto/` | O |
| `passwd` | EC2의 프로젝트 디렉터리 | X |
| `fullchain.pem` | `/opt/gilbom/mosquitto/certs/` | X |
| `privkey.pem` | `/opt/gilbom/mosquitto/certs/` | X |

## 1. EC2 인증서 준비

Let's Encrypt의 `live` 파일은 심볼릭 링크이므로 Mosquitto 전용 경로에 실제 파일을 복사한다.

```bash
sudo install -d -m 0750 -o root -g 1883 /opt/gilbom/mosquitto/certs

sudo install -m 0640 -o root -g 1883 \
  "$(readlink -f /etc/letsencrypt/live/i15d101.p.ssafy.io/fullchain.pem)" \
  /opt/gilbom/mosquitto/certs/fullchain.pem

sudo install -m 0640 -o root -g 1883 \
  "$(readlink -f /etc/letsencrypt/live/i15d101.p.ssafy.io/privkey.pem)" \
  /opt/gilbom/mosquitto/certs/privkey.pem
```

확인:

```bash
sudo ls -al /opt/gilbom/mosquitto/certs
openssl x509 \
  -in /opt/gilbom/mosquitto/certs/fullchain.pem \
  -noout -subject -issuer -dates
```

## 2. MQTT 계정 생성

명령은 저장소의 `web` 디렉터리에서 실행한다.

`-c`는 기존 파일을 덮어쓰므로 Backend 최초 계정 생성 때 한 번만 사용한다.

```bash
docker run --rm -it \
  -v "$PWD/secrets:/work" \
  eclipse-mosquitto:2.1.2-alpine \
  mosquitto_passwd -c /work/mosquitto_passwd gilbom
```

로봇 계정은 이 파일에 수동으로 추가하지 않는다. 로봇 등록 뒤 Credential API가
발급·회전·폐기하고 같은 verifier 파일에 반영한다.

실행 컨테이너의 UID/GID 1883에서만 읽을 수 있도록 비밀번호와 ACL 권한을 제한한다. Git에서 `acl`을 갱신한 뒤에도 이 명령을 다시 실행한다.

```bash
chmod 0444 secrets/mosquitto_passwd
```

## 3. EC2 실행

```bash
cd web
cp .env.prod.example .env.prod
# .env.prod의 공개 주소와 Secret 파일 경로를 운영값으로 수정한다.
ENV_FILE=.env.prod sh scripts/verify-production-compose.sh
docker compose --env-file .env.prod -f compose.build.yaml build backend frontend
docker compose --env-file .env.prod -f compose.prod.yaml up --no-build -d --wait
docker compose --env-file .env.prod -f compose.prod.yaml ps
docker compose --env-file .env.prod -f compose.prod.yaml logs -f mosquitto
```

EC2 보안 그룹 인바운드에서 TCP 8883을 테스트 PC와 IoT 기기의 공인 IP에만 허용한다.
DB와 Backend port는 host에 publish하지 않는다. Frontend Nginx가 host의
80·443을 받아 HTTP-01 challenge와 HTTPS 종단을 처리한다.

## 4. PC에서 TLS 통신 확인

인증서 도메인 검증을 위해 IP가 아니라 `i15d101.p.ssafy.io`로 접속한다. `--insecure`는 사용하지 않는다.

터미널 1에서 로봇 명령을 구독한다.

```bash
mosquitto_sub \
  -h i15d101.p.ssafy.io -p 8883 \
  --tls-use-os-certs \
  -u '<발급된 MQTT username>' -P '<일회성 password>' \
  -t 'tbs/v2/robots/<발급된 MQTT username>/commands/request' -v -d
```

터미널 2에서 로봇 텔레메트리를 발행한다.

```bash
mosquitto_pub \
  -h i15d101.p.ssafy.io -p 8883 \
  --tls-use-os-certs \
  -u '<발급된 MQTT username>' -P '<일회성 password>' \
  -t 'tbs/v2/robots/<발급된 MQTT username>/telemetry' \
  -q 0 \
  -m '{"schema_version":"2.0","message_id":"fb9c4472-226d-4473-b684-f0d8282292bd","mqtt_id":"<발급된 MQTT username>","boot_id":"572b2e59-d31f-445f-a43d-7df80ba64203","seq":1,"sampled_at":"2026-07-26T05:15:35.000Z","mission_id":null,"position":{"valid":true,"lat":37.5013,"lon":127.0396,"heading_deg":null},"motion":{"moving":false,"speed_mps":null},"battery":{"valid":true,"percent":87.0,"state":"NORMAL","source":"MEASURED"},"system":{"jetson_temp_c":41.5},"mission":null}' \
  -d
```

백엔드 계정으로 텔레메트리를 구독한다.

```bash
mosquitto_sub \
  -h i15d101.p.ssafy.io -p 8883 \
  --tls-use-os-certs \
  -u gilbom -P '백엔드 비밀번호' \
  -t 'tbs/v2/robots/+/telemetry' -v
```

## 5. ACL 부정 테스트

발급된 로봇 계정은 자기 텔레메트리는 발행할 수 있지만 다른 로봇 Topic에는
발행할 수 없어야 한다.

```bash
mosquitto_pub \
  -h i15d101.p.ssafy.io -p 8883 \
  --tls-use-os-certs \
  -u '<발급된 MQTT username>' -P '<일회성 password>' \
  -t 'tbs/v2/robots/robot-999/telemetry' \
  -m '{"test":"must-be-denied"}' -d
```

Broker 로그에서 ACL 거부를 확인한다.

```bash
docker compose --env-file .env.prod -f compose.prod.yaml logs --tail 100 mosquitto
```

## 6. IoT 기기에 전달할 TLS 설정

```text
HOST=i15d101.p.ssafy.io
PORT=8883
USERNAME=기기의 robotId
PASSWORD=기기별 고유 비밀번호
TLS=true
SERVER_CA=Let's Encrypt 인증서를 검증할 CA
```

- 기기 시간은 TLS 연결 전에 NTP로 동기화한다.
- 기기마다 서로 다른 비밀번호를 발급한다.
- 비밀번호를 펌웨어 소스나 Git에 포함하지 않는다.
- `clientId`도 기기별로 고유하게 설정한다.

## 7. 내일까지 통신 완료 기준

- [ ] PC → EC2 8883 TLS handshake 성공
- [ ] Credential API에서 발급한 로봇 계정 로그인 성공
- [ ] 로봇 Topic 텔레메트리 발행 성공
- [ ] 백엔드 계정의 텔레메트리 구독 성공
- [ ] 백엔드 명령 발행 → 로봇 명령 구독 성공
- [ ] 로봇 ACK 발행 → 백엔드 ACK 구독 성공
- [ ] 다른 로봇 Topic 발행이 ACL로 거부됨
- [ ] 실제 IoT 기기에서 같은 흐름 성공
- [ ] Mosquitto 재시작 후 기기 자동 재연결 성공

## 8. mTLS 전환 시 추가되는 파일

TLS 통신 완료 후 다음만 추가한다.

```text
/opt/gilbom/mosquitto/certs/client-ca.pem
IoT 기기별 client.crt
IoT 기기별 client.key
```

Mosquitto 설정에는 다음을 추가한다.

```conf
cafile /mosquitto/certs/client-ca.pem
require_certificate true
use_identity_as_username true
```

이때 인증서 CN을 `robotId`와 동일하게 발급하면 현재 ACL Topic 패턴을 그대로 사용할 수 있다.
