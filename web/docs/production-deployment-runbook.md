# 길봄 운영 배포 Runbook

기준일: 2026-08-01

정본: `compose.prod.yaml`, `compose.build.yaml`, EC2의 `runtime.env`·`image.env`,
Commit SHA image archive, `/opt/gilbom/secrets`

## 1. 운영 원칙

- 운영 서버에서는 `compose.prod.yaml`만 사용한다. `compose.smoke.yaml`과
  `compose.resilience.yaml`은 격리된 검증 전용이다.
- 운영 Compose에는 `build:`를 두지 않는다. Backend와 Frontend image는 CI가
  생성한 동일 Commit SHA 산출물을 `docker load`한 뒤 `image.env`로 지정한다.
- Secret 값은 `.env.prod`, image ARG/ENV, 명령 인자, Git, release manifest에
  넣지 않는다. `.env.prod`에는 host Secret 파일 경로만 둔다.
- 운영에서 `docker compose down --volumes`와 `docker volume rm`은 금지한다.
- Flyway가 시작된 뒤 자동 rollback하지 않는다. 이전 Backend가 현재 DB migration과
  호환되는지 먼저 확인한다.
- 배포 전 DB·PDF·이미지·MQTT 상태 backup과 복구 가능한 이전 image digest가
  모두 있어야 한다.

## 2. 사전 요구사항

- 전용 Linux host의 Docker Engine과 Docker Compose v2
- Git, OpenSSL, SHA-256 도구
- release gate에서는 Docker Scout 또는 조직이 승인한 동등 scanner
- 외부 TCP 80·443을 받을 Frontend Nginx와 `/opt/gilbom/certbot` 영속 경로
- 로봇 source CIDR만 허용하는 MQTT 8883 방화벽
- `llm-egress`에 적용할 host firewall, security group 또는 egress proxy
- host 밖의 암호화된 backup 저장소와 root 소유 Secret 파일

운영 host의 시간 동기화와 디스크 여유를 먼저 확인한다. 최소 용량은 현재
`postgres_data`, `report_pdf_data`, `robot_images`, `mosquitto_data` 사용량의
두 배와 새 image layer 크기를 더해 산정한다.

## 3. 필수 Secret 준비

### 3.1 목록

| 파일 | 생성·발급 주체 | 필수 조건 |
|---|---|---|
| `postgres_admin_password` | OpenSSL/Secret manager | 서로 다른 32-byte 이상 난수 |
| `postgres_flyway_password` | OpenSSL/Secret manager | admin·app과 재사용 금지 |
| `postgres_app_password` | OpenSSL/Secret manager | admin·Flyway와 재사용 금지 |
| `mqtt_backend_password` | OpenSSL/Secret manager | `mosquitto_passwd`의 `gilbom` 항목과 일치 |
| `mosquitto_passwd` | Mosquitto 2.1.2 | 평문이 아닌 password verifier |
| `mqtt_ca_certificate.pem` | 조직 PKI | Broker 인증서를 신뢰하는 CA chain |
| `mosquitto_tls_fullchain.pem` | 조직 PKI | `MQTT_TLS_SERVER_NAME` SAN 포함 |
| `mosquitto_tls_private_key.pem` | 조직 PKI/HSM | 암호화되지 않은 runtime key가 필요하면 host 권한으로 보호 |
| `gilbom_storage_signing_key` | OpenSSL/Secret manager | base64 난수 |
| `gilbom_auth_rate_limit_hmac_key` | OpenSSL/Secret manager | base64 난수, signing key와 재사용 금지 |
| `gilbom_mfa_totp_keyring` | OpenSSL/Secret manager | `version=base64-32-byte-key` 형식 |
| `gilbom_initial_admin_password` | Password manager | bootstrap 활성 시에만 non-empty |
| `gilbom_llm_api_key` | 승인된 GMS 발급 절차 | LLM 활성 시에만 non-empty |

DB password 세 개, MQTT password와 암호화/signing key는 서로 다른 값을 사용한다.
MFA keyring을 잃으면 DB에 암호화된 기존 TOTP Secret을 복호화할 수 없으므로
DB backup과 별도로 동일 복구 시점의 Secret manager version을 보존한다.

### 3.2 생성 명령

조직 PKI가 발급한 세 파일을 안전한 임시 경로에 준비한 뒤 실행한다.

```sh
MQTT_CA_CERTIFICATE_SOURCE=/secure/pki/mqtt-ca.pem \
MOSQUITTO_TLS_FULLCHAIN_SOURCE=/secure/pki/mqtt-fullchain.pem \
MOSQUITTO_TLS_PRIVATE_KEY_SOURCE=/secure/pki/mqtt-private-key.pem \
INITIAL_ADMIN_PASSWORD_SOURCE=/secure/bootstrap/initial-admin-password \
SECRET_DIR="$PWD/secrets" \
  sh infra/scripts/generate-production-secrets.sh
```

LLM을 승인해 활성화하는 배포만 다음 file source를 추가한다.

```sh
GILBOM_LLM_API_KEY_SOURCE=/secure/gms/gilbom-api-key
```

스크립트는 기존 파일을 덮어쓰지 않고, TLS chain·private key 형식과 CA 신뢰,
인증서/private key 일치와 Mosquitto verifier 생성을 확인한다. 값은 출력하지
않는다. 생성 후 다음
metadata만 확인한다.

```sh
stat -c '%a %n' secrets secrets/*
openssl x509 -in secrets/mosquitto_tls_fullchain.pem \
  -noout -subject -issuer -dates -ext subjectAltName
```

Secret 디렉터리는 `0700`, 파일은 `0444`다. host backup은 일반 volume bundle과
분리해 Secret manager로 가져간 뒤 임시 발급 파일을 안전하게 폐기한다.

## 4. `.env.prod`와 image 고정

```sh
cp .env.prod.example .env.prod
chmod 0600 .env.prod
```

다음을 반드시 운영 값으로 바꾼다.

- `BACKEND_IMAGE`, `FRONTEND_IMAGE`: CI가 생성하고 checksum을 검증한 Commit SHA tag
- `CERTBOT_DOMAIN`, `CERTBOT_EMAIL`, Certbot·Nginx certificate host 경로
- `MQTT_TLS_SERVER_NAME`: 인증서 SAN과 일치하는 이름
- `MQTT_PUBLIC_BROKER_URI`: 로봇이 접근할 TLS URI
- `VITE_NAVER_MAP_CLIENT_ID`: 공개 browser client ID
- 공개 bind address/port와 resource 상한
- 실제 Secret file 경로
- 최초 관리자 bootstrap을 사용할 경우 email, display name, user code

LLM을 비활성화하면 `GILBOM_LLM_ENABLED=false`로 두고
`GILBOM_LLM_API_KEY_SECRET_FILE` 줄을 제거할 수 있다. Compose는 `/dev/null`
빈 mount를 사용한다. 활성화할 때는 API key, 승인 model/prompt, request·budget
상한과 네 가지 정책 승인 값이 모두 필요하다.

## 5. 배포 전 게이트

```sh
RUNTIME_ENV_FILE=runtime.env \
IMAGE_ENV_FILE=image.env \
  sh scripts/verify-production-compose.sh
sh scripts/verify-secret-boundary.sh

BACKEND_IMAGE='registry.example/gilbom/backend@sha256:...' \
FRONTEND_IMAGE='registry.example/gilbom/frontend@sha256:...' \
IMAGE_SCANNER_MODE=required \
  sh scripts/verify-production-images.sh
```

신규 release 후보는 CI에서 다음을 모두 통과해야 한다.

```sh
sh scripts/backend-test.sh
cd frontend/gilbom
npm ci
npm test
npm run lint
npm run build
```

Backend 최종 summary에 `failures=0, skipped=0`, Frontend에 실패와 skip 0이
명시되지 않으면 배포하지 않는다.

## 6. 최초 기동

1. `INITIAL_ADMIN_BOOTSTRAP_ENABLED=true`와 최초 관리자 정보를 설정한다.
2. 첫 배포 전 전체 backup은 없으므로 빈 volume임을 change record에 명시한다.
3. digest 고정과 scanner가 확인된 뒤 배포한다.

```sh
docker compose \
  --env-file runtime.env \
  --env-file image.env \
  -f compose.prod.yaml \
  up --detach --no-build --wait
```

의존 순서는 PostgreSQL·MQTT health, Backend readiness, Frontend health다.
인증서가 아직 없으면 Frontend는 ACME bootstrap 모드로 기동한다. 이 상태에서는
애플리케이션을 HTTP로 공개하지 않는다. 운영 Compose의 TLS mode는 `auto`로
고정되어 있으며 `runtime.env`에 `GILBOM_NGINX_TLS_MODE=off`를 넣어도 적용되지
않는다.

### 6.1 최초 인증서 발급

EC2에서 root 소유 경로와 갱신 스크립트를 먼저 준비한다.

```sh
sudo install -d -m 0755 \
  /opt/gilbom/certbot/conf \
  /opt/gilbom/certbot/www
sudo install -d -m 0750 -o root -g 101 \
  /opt/gilbom/certbot/nginx
sudo install -o root -g root -m 0755 \
  infra/certbot/certificate.sh \
  /usr/local/sbin/gilbom-certificate
```

DNS가 EC2 공인 주소를 가리키고 외부 TCP 80이 열려 있는 상태에서 발급한다.

```sh
sudo /usr/local/sbin/gilbom-certificate issue
curl -fsSI http://i15d101.p.ssafy.io/
curl -fsS https://i15d101.p.ssafy.io/healthz
```

스크립트는 Certbot 원본에서 Nginx UID/GID가 읽을 수 있는 최소 인증서 복사본을
만들고, Nginx 설정을 검증한 뒤 HUP reload한다. Frontend에는 원본
`/etc/letsencrypt`와 Certbot 계정 상태를 노출하지 않는다.

갱신과 dry run:

```sh
sudo /usr/local/sbin/gilbom-certificate dry-run
sudo /usr/local/sbin/gilbom-certificate renew
```

`renew`는 성공한 인증서를 동기화한 뒤 Nginx를 reload한다. systemd Service·Timer는
EC2에서 root 소유로 생성하고 `renew`를 호출한다.

최초 관리자로 로그인해 즉시 비밀번호를 변경하고 MFA 등록을 마친다. 다른 관리자
계정을 확인한 뒤 bootstrap을 끈다.

```sh
# .env.prod에서 INITIAL_ADMIN_BOOTSTRAP_ENABLED=false로 변경한 뒤
chmod 0600 secrets/gilbom_initial_admin_password
: > secrets/gilbom_initial_admin_password
chmod 0444 secrets/gilbom_initial_admin_password

docker compose --env-file .env.prod -f compose.prod.yaml \
  up --detach --no-build --force-recreate --wait backend frontend
```

빈 최초 관리자 Secret은 bootstrap이 꺼진 경우에만 허용된다.

## 7. Health와 readiness 확인

```sh
docker compose --env-file .env.prod -f compose.prod.yaml ps

docker compose --env-file .env.prod -f compose.prod.yaml exec -T backend \
  wget -qO- http://127.0.0.1:8080/actuator/health/liveness

docker compose --env-file .env.prod -f compose.prod.yaml exec -T backend \
  wget -qO- http://127.0.0.1:8080/actuator/health/readiness

docker compose --env-file .env.prod -f compose.prod.yaml exec -T frontend \
  /usr/local/bin/gilbom-nginx-healthcheck

docker compose --env-file .env.prod -f compose.prod.yaml exec -T frontend \
  wget --no-check-certificate -qO- https://127.0.0.1:8443/api/health
```

- liveness는 JVM/Spring context 생존만 반영한다.
- readiness는 DB, Flyway 완료, PDF·이미지·MQTT credential 경로 쓰기를 반영한다.
- LLM은 Template fallback 대상이라 readiness에서 제외한다.
- MQTT는 별도 health detail이며 기본 운영 readiness에는 포함하지 않지만,
  최초 Compose 시작은 Broker health를 기다린다.

외부 HTTPS는 `/healthz`, Backend 진단은 내부 readiness를 사용한다.

## 8. 로그 확인

```sh
docker compose --env-file .env.prod -f compose.prod.yaml \
  logs --since 15m --tail 300 backend frontend db mosquitto

BACKEND_CONTAINER="$(
  docker compose --env-file .env.prod -f compose.prod.yaml ps -q backend
)" \
FRONTEND_CONTAINER="$(
  docker compose --env-file .env.prod -f compose.prod.yaml ps -q frontend
)" \
SECRET_SCAN_DIR=secrets \
IMAGE_VERIFY_MODE=logs \
  sh scripts/verify-production-images.sh
```

Authorization, cookie, API key, prompt·LLM 응답 원문을 ticket이나 채팅에 복사하지
않는다. 기본 json-file log는 10 MiB × 3개로 회전한다. 보존 기간이 더 필요하면
민감정보 필터가 적용된 중앙 log 수집기로 전달한다.

## 9. Backup

### 9.1 온라인 DB backup

```sh
ENV_FILE="$PWD/.env.prod" \
BACKUP_DIR=/srv/gilbom-backups/db \
  sh infra/scripts/backup-db.sh
```

custom-format dump를 만든 뒤 `pg_restore --list`와 SHA-256을 확인한다.

### 9.2 전체 상태 snapshot

```sh
ENV_FILE="$PWD/.env.prod" \
BACKUP_ROOT=/srv/gilbom-backups/state \
  sh infra/scripts/backup-volumes.sh
```

Frontend·Backend·Mosquitto writer를 잠시 중지하고 다음을 한 bundle에 저장한다.

- PostgreSQL transaction-consistent custom dump
- 승인 PDF
- 결함 이미지
- Mosquitto config, credential registry, session/data
- `SHA256SUMS`

스크립트는 완료 후 같은 Compose를 다시 healthy 상태로 올린다. bundle은 자체
암호화되지 않으므로 즉시 암호화된 immutable storage로 이동하고 host 사본의
보존 기간을 제한한다.

## 10. 복구

복구는 별도 host/project에서 분기별로 rehearsal한 절차만 운영에 사용한다.
운영 복구 전 현재 상태의 추가 안전 backup을 만들고 change approval을 받는다.

1. bundle의 `SHA256SUMS`를 검증한다.
2. 동일 `.env.prod`, image digest와 Secret manager version을 준비한다.
3. Frontend·Backend·Mosquitto를 중지하고 DB만 healthy로 유지한다.
4. DB dump를 복구한다.

```sh
cd /srv/gilbom-backups/state/<bundle>
sha256sum -c SHA256SUMS

docker compose --env-file /app/gilbom/.env.prod \
  -f /app/gilbom/compose.prod.yaml stop frontend backend mosquitto

docker compose --env-file /app/gilbom/.env.prod \
  -f /app/gilbom/compose.prod.yaml exec -T db sh -ec '
    export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"
    exec pg_restore \
      --host 127.0.0.1 \
      --username "$POSTGRES_USER" \
      --dbname "$POSTGRES_DB" \
      --no-password \
      --clean \
      --if-exists \
      --exit-on-error
  ' < <timestamp-project>.dump
```

5. PDF·이미지·MQTT archive는 Compose label로 정확한 volume 하나를 확인한 뒤
   빈 volume에만 복원한다. 기존 volume을 지우는 작업은 별도 승인 대상이다.

```sh
project=gilbom
logical=report_pdf_data
volume="$(
  docker volume ls \
    --filter "label=com.docker.compose.project=$project" \
    --filter "label=com.docker.compose.volume=$logical" \
    --quiet
)"
test -n "$volume"
docker volume inspect "$volume"

docker run --rm \
  --mount "type=volume,src=$volume,dst=/target" \
  --mount "type=bind,src=$PWD,dst=/backup,readonly" \
  alpine:3.22 \
  tar -C /target -xzf "/backup/$logical.tar.gz"
```

다른 네 archive도 같은 방식으로 복원한다. 기존 파일이 있는 volume에 겹쳐
풀지 않는다. 새 volume 또는 승인 후 비운 volume을 사용한다.

6. 전체 Compose를 올리고 health, 보고서/PDF 조회, MQTT TLS와 image hash를
   확인한다.

```sh
docker compose --env-file /app/gilbom/.env.prod \
  -f /app/gilbom/compose.prod.yaml \
  up --detach --no-build --wait --wait-timeout 240
```

## 11. Rollback

애플리케이션 회귀이고 이전 Backend가 현재 Flyway schema와 호환될 때만 image
rollback을 수행한다. DB corruption, Secret key 손실, 비호환 migration이면
image rollback으로 해결하지 말고 10장의 상태 복구를 사용한다.

```sh
ROLLBACK_APPROVED=true \
PREVIOUS_BACKEND_IMAGE='registry.example/gilbom/backend@sha256:...' \
PREVIOUS_FRONTEND_IMAGE='registry.example/gilbom/frontend@sha256:...' \
ENV_FILE="$PWD/.env.prod" \
RELEASE_DIR=/var/lib/gilbom/releases \
ROLLBACK_PULL=true \
  sh infra/scripts/rollback.sh
```

rollback은 Frontend를 먼저 중지하고 이전 Backend readiness가 통과한 뒤 이전
Frontend를 시작한다. DB와 named volume, Flyway migration은 되돌리지 않는다.
성공 후 원인 분석 전까지 재배포를 중단한다.

## 12. Image tag·digest 기록

`deploy.sh`는 다음을 mode `0600` release record에 남긴다.

- UTC 배포 시각과 Git revision
- Secret 값이 없는 canonical Compose SHA-256
- 서비스별 설정 image reference
- 실행 container의 image ID와 registry RepoDigest

```sh
ls -l /var/lib/gilbom/releases
sed -n '1,120p' /var/lib/gilbom/releases/<timestamp>-deployed.txt
```

release record와 CI SBOM/CVE artifact를 같은 release ID에 연결한다. local image
ID만 있고 RepoDigest가 없는 build는 운영 release로 판정하지 않는다.

## 13. 알려진 제한사항

- Compose 단일 host 배포라 PostgreSQL, MQTT와 Frontend의 다중 host HA가 없다.
- named volume은 host 장애를 견디지 못하므로 외부 backup이 필수다.
- Compose network만으로 LLM hostname allowlist를 강제할 수 없다.
- 단일 EC2의 Frontend Nginx가 TLS를 종료하므로 host 장애에 대한 HTTPS HA가 없다.
- 운영 Compose는 최초 시작 시 MQTT health를 필수로 기다린다. 실행 후 MQTT
  장애는 웹 readiness와 분리되지만 로봇 기능은 사용할 수 없다.
- 실제 GMS canary는 운영 Secret과 정책 승인 없이는 실행되지 않는다.
- 현재 CVE 보고서에는 수정 가능한 Critical은 없지만 High/Medium 항목이 남아
  있어 release별 재검사와 dependency 갱신이 필요하다.
- Frontend 주 진입 chunk가 Vite 500 kB 경고 기준을 넘는 기준선이 남아 있다.
- 자동 DB migration downgrade는 제공하지 않는다.

## 14. 정기 운영 점검

- 매 배포: 전체 gate, 상태 backup, digest/release record, rollback digest 확인
- 매일: DB backup, health와 용량·인증서 만료 확인
- 매주: 전체 상태 snapshot과 외부 저장소 복제 확인
- 분기: 별도 host에서 DB·PDF·이미지·MQTT 복구 rehearsal
- Secret 회전: 각 소비자별 순서와 이전 MFA key version 보존 기간을 별도 승인
