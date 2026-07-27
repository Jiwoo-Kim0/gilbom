# 길봄 MQTT Broker 설정

이 폴더는 Mosquitto 연결 방식을 단계별로 검증하기 위한 설정 모음이다.

| 폴더 | 포트 | 암호화 | 클라이언트 인증 | 용도 |
|---|---:|---|---|---|
| `test` | 1883 | 없음 | 없음 | EC2 내부의 짧은 연결 테스트 |
| `tls` | 8883 | TLS | MQTT 아이디/비밀번호 | 운영 1단계 |
| `mtls` | 8883 | TLS | 클라이언트 인증서 | 최종 mTLS 구성 |

세 구성을 동시에 실행하지 않는다. `tls`와 `mtls`는 같은 호스트 포트 8883을 사용한다.

## 1. 사전 확인

모든 명령은 `web` 디렉터리에서 실행한다.

```bash
docker --version
docker compose version
```

EC2에 Docker가 없다면 먼저 Docker Engine과 Compose 플러그인을 설치해야 한다.

## 2. 평문 MQTT 로컬 테스트

기본 Compose 설정은 EC2의 `127.0.0.1:1883`에만 포트를 연다. 인터넷에서는 접근할 수 없다.

```bash
docker compose -f infra/mosquitto/test/compose.yaml up -d
docker compose -f infra/mosquitto/test/compose.yaml logs -f
```

터미널 1에서 구독한다.

```bash
docker run --rm --network host eclipse-mosquitto:2.1.2-alpine \
  mosquitto_sub -h 127.0.0.1 -p 1883 \
  -t 'tbs/v2/robots/robot-001/telemetry' -v
```

터미널 2에서 발행한다.

```bash
docker run --rm --network host eclipse-mosquitto:2.1.2-alpine \
  mosquitto_pub -h 127.0.0.1 -p 1883 \
  -t 'tbs/v2/robots/robot-001/telemetry' \
  -q 0 \
  -m '{"schema_version":"2.0","message_id":"fb9c4472-226d-4473-b684-f0d8282292bd","robot_id":"robot-001","boot_id":"572b2e59-d31f-445f-a43d-7df80ba64203","seq":1,"sampled_at":"2026-07-26T05:15:35.000Z","mission_id":null,"position":{"valid":true,"lat":37.5013,"lon":127.0396,"heading_deg":null},"motion":{"moving":false,"speed_mps":null},"battery":{"valid":true,"percent":87.0,"state":"NORMAL","source":"MEASURED"},"system":{"jetson_temp_c":41.5},"mission":null}'
```

테스트가 끝나면 내린다.

```bash
docker compose -f infra/mosquitto/test/compose.yaml down
```

외부 로봇에서 잠깐 테스트해야 한다면 `infra/mosquitto/test/compose.yaml`의 포트를 `"1883:1883"`으로 바꾸고, EC2 보안 그룹의 TCP 1883 인바운드를 **테스트 장비의 공인 IP 하나로만** 제한한다. 테스트 후 즉시 닫는다.

## 3. TLS + 아이디/비밀번호

### 인증서 준비

Compose는 EC2의 다음 실제 파일을 읽는다.

```text
/opt/gilbom/mosquitto/certs/
├── fullchain.pem
└── privkey.pem
```

`/etc/letsencrypt/live/...` 아래 파일은 심볼릭 링크이므로 컨테이너에 개별 마운트하지 않는다. Certbot deploy hook에서 위 전용 폴더로 복사하고 Mosquitto 컨테이너에 `SIGHUP`을 보내는 방식으로 갱신한다.

인증서와 개인키는 컨테이너에 읽기 전용으로 마운트된다. 호스트에서도 개인키 접근 권한을 최소화해야 한다.

컨테이너는 Mosquitto 공식 이미지의 UID/GID인 `1883:1883`으로 실행된다. 인증서 디렉터리는 이 사용자에게 읽기·탐색 권한이 있어야 한다. 예시는 다음과 같다.

```bash
sudo chown -R root:1883 /opt/gilbom/mosquitto/certs
sudo chmod 750 /opt/gilbom/mosquitto/certs
sudo chmod 640 /opt/gilbom/mosquitto/certs/fullchain.pem
sudo chmod 640 /opt/gilbom/mosquitto/certs/privkey.pem
```

### 비밀번호 파일 생성

`-c`는 기존 파일을 덮어쓰므로 **최초 생성 때만** 사용한다.

```bash
docker run --rm -it \
  -v "$PWD/infra/mosquitto/tls:/work" \
  eclipse-mosquitto:2.1.2-alpine \
  mosquitto_passwd -c /work/passwd robot-001
```

백엔드 계정을 추가할 때는 `-c`를 사용하지 않는다.

```bash
docker run --rm -it \
  -v "$PWD/infra/mosquitto/tls:/work" \
  eclipse-mosquitto:2.1.2-alpine \
  mosquitto_passwd /work/passwd gilbom
```

실제 `passwd` 파일은 `mqtt/.gitignore`에 포함되어 Git에 커밋되지 않는다.

실행 컨테이너에서 읽을 수 있도록 권한을 제한한다.

```bash
sudo chown root:1883 infra/mosquitto/tls/passwd
sudo chmod 640 infra/mosquitto/tls/passwd
```

### 실행 및 확인

```bash
docker compose -f infra/mosquitto/tls/compose.yaml config
docker compose -f infra/mosquitto/tls/compose.yaml up -d
docker compose -f infra/mosquitto/tls/compose.yaml logs -f
```

서버 인증서의 도메인은 `i15d101.p.ssafy.io`와 일치해야 한다. 클라이언트에서 IP 주소가 아니라 도메인으로 접속한다.

```bash
mosquitto_sub \
  -h i15d101.p.ssafy.io -p 8883 \
  --cafile /path/to/ca.pem \
  -u robot-001 -P '비밀번호' \
  -t 'tbs/v2/robots/robot-001/commands/request' -v
```

비밀번호를 명령행에 직접 넣으면 shell history에 남을 수 있으므로 실제 애플리케이션에서는 환경변수나 secret 저장소를 사용한다.

## 4. mTLS

mTLS에서는 서버 인증서 외에 로봇별 클라이언트 인증서가 필요하다.

EC2 인증서 폴더:

```text
/opt/gilbom/mosquitto/certs/
├── fullchain.pem
├── privkey.pem
└── client-ca.pem
```

- `client-ca.pem`: 로봇과 백엔드의 클라이언트 인증서를 발급한 CA
- 로봇 인증서 CN: 해당 `robotId`와 동일하게 설정
- 백엔드 인증서 CN: `gilbom`
- 개인키는 각 클라이언트 밖으로 유출하거나 Git에 커밋하지 않음

`client-ca.pem`도 컨테이너의 GID 1883에서 읽을 수 있도록 `root:1883`, 권한 `640`으로 설정한다.

실행:

```bash
docker compose -f infra/mosquitto/mtls/compose.yaml config
docker compose -f infra/mosquitto/mtls/compose.yaml up -d
docker compose -f infra/mosquitto/mtls/compose.yaml logs -f
```

클라이언트 테스트:

```bash
mosquitto_sub \
  -h i15d101.p.ssafy.io -p 8883 \
  --cafile /path/to/server-ca.pem \
  --cert /path/to/robot-001.crt \
  --key /path/to/robot-001.key \
  -t 'tbs/v2/robots/robot-001/commands/request' -v
```

`use_identity_as_username true` 설정 때문에 클라이언트 인증서 CN이 ACL의 사용자 이름으로 사용된다.

## 5. ACL 동작

로봇 사용자 `robot-001`은 다음 권한만 가진다.

- 발행: `tbs/v2/robots/robot-001/presence`
- 발행: `tbs/v2/robots/robot-001/state`
- 발행: `tbs/v2/robots/robot-001/telemetry`
- 발행: `tbs/v2/robots/robot-001/events/*`
- 발행: `tbs/v2/robots/robot-001/commands/result`
- 구독: `tbs/v2/robots/robot-001/commands/request`
- 구독: `tbs/v2/robots/robot-001/ingest/receipt`

다른 로봇 ID의 토픽에는 접근할 수 없어야 한다.

백엔드 계정은 모든 로봇의 텔레메트리·상태·알림·ACK를 구독하고 명령을 발행한다.

## 6. EC2 운영 점검표

- EC2 보안 그룹에서 8883만 필요한 IP 대역에 허용
- 운영 환경에서 1883과 anonymous listener 제거
- `passwd`, 개인키, 클라이언트 인증서를 Git에 커밋하지 않음
- 인증서 호스트 디렉터리와 Compose 마운트를 읽기 전용으로 관리
- 인증서 갱신 후 Mosquitto 재로딩 및 TLS 접속 확인
- `docker compose logs`에서 인증 실패와 ACL 거부 확인
- 로봇 연결·단절, ACK, Timeout을 백엔드에서 별도로 기록

## 7. 버전 참고

이 설정은 공식 레지스트리에 실제 존재하는 `eclipse-mosquitto:2.1.2-alpine` 태그를 고정 사용하며, 2.1 공식 이미지에 포함된 password-file 및 ACL 플러그인을 사용한다. 이전의 `password_file`, `acl_file` 지시어는 deprecated이므로 사용하지 않는다.
