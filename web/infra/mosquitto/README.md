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
│     ├─ passwd              # 로컬 MQTT 계정 파일
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

환경별 실행 방법은 [`PROFILES.md`](./PROFILES.md)를 참고한다.

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

`-c`는 기존 파일을 덮어쓰므로 최초 계정 생성 때 한 번만 사용한다.

```bash
docker run --rm -it \
  -v "$PWD/infra/mosquitto:/work" \
  eclipse-mosquitto:2.1.2-alpine \
  mosquitto_passwd -c /work/passwd robot-test
```

백엔드 계정을 추가할 때는 `-c`를 사용하지 않는다.

```bash
docker run --rm -it \
  -v "$PWD/infra/mosquitto:/work" \
  eclipse-mosquitto:2.1.2-alpine \
  mosquitto_passwd /work/passwd gilbom
```

실행 컨테이너의 UID/GID 1883에서만 읽을 수 있도록 비밀번호와 ACL 권한을 제한한다. Git에서 `acl`을 갱신한 뒤에도 이 명령을 다시 실행한다.

```bash
sudo chown 1883:1883 infra/mosquitto/passwd infra/mosquitto/acl
sudo chmod 0600 infra/mosquitto/passwd infra/mosquitto/acl
```

## 3. EC2 실행

```bash
cd web
docker compose -f compose.prod.yaml config
docker compose -f compose.prod.yaml up -d
docker compose -f compose.prod.yaml ps
docker compose -f compose.prod.yaml logs -f mosquitto
```

EC2 보안 그룹 인바운드에서 TCP 8883을 테스트 PC와 IoT 기기의 공인 IP에만 허용한다.

## 4. PC에서 TLS 통신 확인

인증서 도메인 검증을 위해 IP가 아니라 `i15d101.p.ssafy.io`로 접속한다. `--insecure`는 사용하지 않는다.

터미널 1에서 로봇 명령을 구독한다.

```bash
mosquitto_sub \
  -h i15d101.p.ssafy.io -p 8883 \
  --tls-use-os-certs \
  -u robot-test -P '테스트 비밀번호' \
  -t 'tbs/v2/robots/robot-test/commands/request' -v -d
```

터미널 2에서 로봇 텔레메트리를 발행한다.

```bash
mosquitto_pub \
  -h i15d101.p.ssafy.io -p 8883 \
  --tls-use-os-certs \
  -u robot-test -P '테스트 비밀번호' \
  -t 'tbs/v2/robots/robot-test/telemetry' \
  -q 0 \
  -m '{"schema_version":"2.0","message_id":"fb9c4472-226d-4473-b684-f0d8282292bd","robot_id":"robot-test","boot_id":"572b2e59-d31f-445f-a43d-7df80ba64203","seq":1,"sampled_at":"2026-07-26T05:15:35.000Z","mission_id":null,"position":{"valid":true,"lat":37.5013,"lon":127.0396,"heading_deg":null},"motion":{"moving":false,"speed_mps":null},"battery":{"valid":true,"percent":87.0,"state":"NORMAL","source":"MEASURED"},"system":{"jetson_temp_c":41.5},"mission":null}' \
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

`robot-test`는 자기 텔레메트리는 발행할 수 있지만 다른 로봇 Topic에는 발행할 수 없어야 한다.

```bash
mosquitto_pub \
  -h i15d101.p.ssafy.io -p 8883 \
  --tls-use-os-certs \
  -u robot-test -P '테스트 비밀번호' \
  -t 'tbs/v2/robots/robot-999/telemetry' \
  -m '{"test":"must-be-denied"}' -d
```

Broker 로그에서 ACL 거부를 확인한다.

```bash
docker compose -f compose.prod.yaml logs --tail 100 mosquitto
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
- [ ] `robot-test` 로그인 성공
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
