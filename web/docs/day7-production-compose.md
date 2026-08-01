# Day 7 운영 Compose와 네트워크

기준일: 2026-08-01

## 1. 운영 토폴로지

`compose.prod.yaml`을 DB, MQTT, Backend, Frontend를 함께 기동하는 단일 운영
명세로 사용한다. host에 publish하는 포트는 Frontend HTTP·HTTPS와 로봇용 MQTT
TLS 세 개뿐이다.

```text
외부 HTTP/HTTPS ──> Frontend Nginx
                       │ app (internal)
                       v
                     Backend ──> llm-egress ──> 운영 방화벽 ──> 승인 LLM
                       │ data (internal)
            ┌──────────┴──────────┐
            v                     v
      PostgreSQL              Mosquitto <── 로봇 MQTT TLS
```

| 네트워크 | 연결 서비스 | 외부 경로 | 역할 |
|---|---|---|---|
| `edge` | Frontend, Mosquitto | Frontend HTTP, MQTT TLS publish port | 외부 사용자·로봇 요청 진입 |
| `app` | Frontend, Backend | `internal: true` | Nginx에서 API로 전달 |
| `data` | Backend, DB, Mosquitto | `internal: true` | DB·MQTT 내부 통신 |
| `llm-egress` | Backend | 기본 gateway 있음 | 선택 LLM outbound 전용 |

Compose bridge는 hostname allowlist를 강제하지 않는다. `llm-egress`는 네트워크
책임을 분리하는 경계이며 실제 목적지 제한은 host egress firewall, cloud security
group/NAT proxy와 함께 적용한다. 그 통제가 확인되기 전에는
`GILBOM_LLM_ENABLED=false`와
`GILBOM_LLM_OUTBOUND_ALLOWLIST_APPLIED=false`를 유지한다.

## 2. 포트와 시작 순서

| 서비스 | Container port | Host publish |
|---|---:|---|
| Frontend HTTP | 8080 | `${FRONTEND_BIND_ADDRESS}:${FRONTEND_HTTP_PORT}` |
| Frontend HTTPS | 8443 | `${FRONTEND_BIND_ADDRESS}:${FRONTEND_HTTPS_PORT}` |
| Mosquitto | 8883/TLS | `${MQTT_BIND_ADDRESS}:${MQTT_TLS_PORT}` |
| Backend | 8080 | 없음 |
| PostgreSQL | 5432 | 없음 |

Frontend image는 HTTP 8080과 HTTPS 8443을 제공한다. 인증서가 없으면 HTTP-01
challenge와 bootstrap health만 제공하고, 인증서가 준비되면 HTTP 요청을 HTTPS로
redirect한다. `application-ec2.yml`의 Secure Session cookie가 정상 동작하려면
외부 사용자 경로는 반드시 HTTPS여야 한다. 운영 Compose는 TLS mode를 `auto`로
고정하므로 환경 파일로 HTTP 애플리케이션 serving을 활성화할 수 없다.

Compose dependency는 다음 순서로 고정한다.

1. DB가 `pg_isready`, 로그인, PostGIS query를 통과한다.
2. Mosquitto config init이 완료되고 CA 검증·계정 인증·`$SYS` 응답이 통과한다.
3. Backend가 시작되고 Spring readiness가 `UP`이 된다.
4. Frontend가 시작되고 `/healthz`를 반환한다.

`depends_on.condition`은 최초 기동 순서를 보장한다. 실행 중 의존 서비스 장애는 각
서비스의 health 상태와 `restart: unless-stopped`로 관찰하며, 애플리케이션의
DB pool·MQTT reconnect 정책이 복구를 담당한다.

실행 중에는 DB·Flyway·PDF/이미지 쓰기 가능 여부만 Backend 기본 readiness에
포함한다. MQTT는 별도 `/actuator/health/mqtt`로 노출해 Broker 장애가 DB 기반
관리 기능까지 차단하지 않게 한다. 로봇 기능을 전체 서비스의 필수 조건으로
판단한 환경만 `.env.prod`의 `GILBOM_HEALTH_READINESS_COMPONENTS`에
`mqttConnection`을 추가한다. LLM은 template fallback 대상이라 포함하지 않는다.

## 3. 영속 데이터와 MQTT 설정

| Named volume | 소비자 | 목적 |
|---|---|---|
| `postgres_data` | DB | PostgreSQL/PostGIS 데이터 |
| `mosquitto_config` | config init → Broker RO | 승인된 Broker 설정·ACL·wrapper |
| `mosquitto_credentials` | config init, Backend RW, Broker RO | mutable password verifier |
| `mosquitto_data` | Broker | QoS session·inflight persistence |
| `report_pdf_data` | Backend | 승인 보고서 PDF |
| `robot_images` | Backend | 결함 이미지 |

`mosquitto-config-init`은 매 배포마다 Git의 공개 config·ACL·wrapper를 config
volume에 복사한다. `mosquitto_credentials`가 비어 있을 때만
`/run/secrets/mosquitto_passwd`를 초기 seed로 복사하므로, 재배포가 운영 중 발급된
로봇 credential을 덮어쓰지 않는다.

Backend credential provisioning을 활성화하면 Backend만 credential volume에
쓸 수 있고 Broker는 read-only로 읽는다. wrapper는 verifier checksum 변경을
감지해 Broker에 HUP을 보내므로 container 재시작 없이 신규 credential을 반영한다.
TLS key·certificate와 Backend 접속 원문 비밀번호는 계속 `/run/secrets`에서만
read-only로 제공한다.

Mosquitto는 5분마다 QoS 상태를 `mosquitto_data`에 저장한다. DB가 업무 데이터의
정본이지만 Broker 재시작 중 session·inflight 전달 상태를 잃지 않기 위한 별도
복구 계층이다.

## 4. Secret 준비

`.env.prod.example`에는 실제 값이 아니라 host Secret 파일 경로만 둔다.

운영 MQTT에는 다음 세 종류가 서로 일치해야 한다.

- `mqtt_backend_password`: Backend가 로그인할 원문
- `mosquitto_passwd`: 같은 계정의 hash를 포함한 verifier 파일
- `mqtt_ca_certificate.pem`: Backend가 Broker certificate를 검증할 CA

Broker에는 `mosquitto_tls_fullchain.pem`과
`mosquitto_tls_private_key.pem`도 필요하다. private key, verifier, 원문
비밀번호는 Git이나 image에 포함하지 않는다. `secrets/` 디렉터리는 `0700`,
UID 1883이 읽어야 하는 file Secret은 `0444`로 준비한다.

`MQTT_TLS_SERVER_NAME`은 Broker certificate SAN과 정확히 같아야 한다. Compose는
이 이름을 `data` network의 Mosquitto DNS alias로 등록하고 Backend도
`ssl://${MQTT_TLS_SERVER_NAME}:8883`에 연결한다. 따라서 공개 IP hairpin 없이
내부 통신을 유지하면서 TLS hostname 검증을 약화하지 않는다.

LLM과 최초 관리자 기능이 비활성화된 경우 대응 파일은 0 byte로 만들 수 있다.
DB·저장소 서명·HMAC·MFA·MQTT Secret과 TLS 파일은 비어 있으면 안 된다.

## 5. 기동

```sh
cp .env.prod.example .env.prod
# .env.prod의 공개 주소·Client ID·Secret 파일 경로를 운영값으로 수정

ENV_FILE=.env.prod sh scripts/verify-production-compose.sh

docker compose --env-file .env.prod -f compose.build.yaml build backend frontend
docker compose --env-file .env.prod -f compose.prod.yaml up -d --no-build --wait
docker compose --env-file .env.prod -f compose.prod.yaml ps
```

`scripts/verify-production-compose.sh`와 runtime acceptance는 DB와 Backend의
실제 Docker `PortBindings`가 비어 있는지 검사한다. 외부 방화벽에서는 Frontend
HTTPS 종단과 승인된 로봇 source CIDR의 MQTT 8883만 허용한다.

운영에서는 `docker compose down --volumes`를 사용하지 않는다. named volume은
배포 전 DB·PDF·이미지·MQTT credential/data backup과 복원 시험 대상이다.

## 6. Resource·로그 기준

초기 상한은 DB 2 CPU/2 GiB, Backend 2 CPU/2 GiB, MQTT 1 CPU/256 MiB,
Frontend 0.5 CPU/128 MiB다. 이는 무제한 자원 고갈과 noisy-neighbor 영향을
막는 보수적인 시작값이지 부하 시험 결과가 아니다.

Nginx worker는 host CPU 전체를 따라가는 `auto` 대신 2개로 고정했다. 기본
0.5 CPU quota에서 검증 host의 20개 worker가 불필요하게 생성된 결과를 반영한
것으로, SSE event loop 여유는 유지하면서 scheduling·PID·memory overhead를
제한한다.

운영 telemetry에서 다음을 함께 관찰해 `.env.prod` 값을 조정한다.

- container OOM·memory working set·JVM heap
- DB shared buffer/query latency와 Backend connection pool
- SSE 동시 연결 수와 Nginx memory
- MQTT connection/session/inflight 수와 reconnect 지연
- CPU throttle time 및 request latency

모든 서비스 log는 stdout/stderr와 `json-file` driver를 사용하며 기본
`max-size=10m`, `max-file=3`으로 회전한다.

## 7. 자동 검증

`scripts/verify-production-compose.sh`는 정규화된 Compose를 기준으로 다음 계약을
검사한다.

- 기본 서비스 5개와 `certificate` profile의 Certbot, 네트워크 4개
- DB·Backend host port 부재와 공개 port 정확히 3개
- 운영 Compose의 `build:` 부재와 CI 전용 `compose.build.yaml`
- Frontend 8080·8443, ACME webroot·인증서 read-only mount
- DB/MQTT health → Backend readiness → Frontend 순서
- internal `app`·`data` network
- named volume 6개
- CPU·memory·PID·log 상한
- `/run/secrets` read-only file Secret target
- Backend·Frontend·Mosquitto의 read-only rootfs, `cap_drop: ALL`,
  `no-new-privileges`, graceful stop window

이 검증은 Jenkins repository policy 단계에서도 image build 전에 실행한다.

실제 Docker daemon에서는 다음 수동 acceptance가 임시 Secret, 자체서명 TLS,
고유 project/volume을 만들고 전체 stack을 `--wait`로 기동한 뒤 자동 정리한다.

```sh
sh scripts/verify-production-runtime.sh
```

이 acceptance는 DB·MQTT·Backend·Frontend health, API proxy, MQTT TLS handshake,
실제 Docker `PortBindings` 기준 Backend/DB 비공개 port와 Frontend 선행 시작
방지, Secret read-only mount와 config init 재실행 시 기존 MQTT credential 보존을
실제 container로 검증한다. 또한 MQTT와 DB를 차례로 종료·복구해 liveness,
readiness와 별도 MQTT health의 장애 전환을 확인하고, 마지막에는 in-flight
readiness 요청 중 Backend에 SIGTERM을 보내 graceful 종료도 확인한다. 실제
Secret과 요청 canary의 container log 비노출 검사는
[`day7-production-image-verification.md`](day7-production-image-verification.md)에
정리했다.

보고서 업무 전체 흐름과 승인 PDF volume 영속성은 별도의 빈-volume acceptance인
`scripts/container-report-smoke.sh`로 검증한다. 설계, 필수 fallback 시나리오와
조건부 GMS canary gate는
[`day7-container-report-smoke.md`](day7-container-report-smoke.md)에 정리했다.
그 정상 흐름 이후의 PID·DB·MQTT·LLM 장애, 자동 재시작과 전체 volume 영속성은
`scripts/container-resilience-smoke.sh`로 검증하며 상세 계약은
[`day7-resilience-verification.md`](day7-resilience-verification.md)에 정리했다.
실제 운영 배포, health, backup·복구와 rollback 절차는
[`production-deployment-runbook.md`](production-deployment-runbook.md)를
정본으로 사용한다.

2026-07-31 최종 결과는 정적 contract와 전체 runtime acceptance 모두 PASS다.
임시 stack에서 DB와 MQTT health 뒤 Backend, Backend readiness 뒤 Frontend가
순서대로 시작됐고, Frontend와 MQTT TLS에만 임의 host port가 생성됐다.
