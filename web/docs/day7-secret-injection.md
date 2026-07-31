# Day 7 Secret·환경설정 주입 구조

기준일: 2026-07-31

## 1. 결정

운영 Secret은 image build 입력이 아니다. Dockerfile의 `ARG`·`ENV`, Compose의
직접 environment 값, Git 추적 `.env`에 원문을 넣지 않는다. host의 승인된 Secret
파일을 Compose `secrets`로 container 시작 시에만 `/run/secrets`에 mount한다.

일반 환경설정과 공개 식별자는 `.env.prod`에 둘 수 있지만 실제 API key, 비밀번호,
서명·암호화 key는 `*_SECRET_FILE`로 host 파일 위치만 지정한다. Secret 파일은
한 줄 UTF-8, 최대 16 KiB를 기준으로 한다.

로컬 Docker Compose의 file Secret은 source UID/GID/mode를 그대로 bind한다.
따라서 host의 `secrets/` 디렉터리를 `0700`, 내부 파일을 `0444`로 둔다. host의
다른 사용자는 디렉터리를 통과할 수 없고, container의 nonroot UID 1883은
read-only 파일을 읽을 수 있다. Swarm·외부 orchestrator에서는 해당 플랫폼의
Secret UID/GID/mode 기능으로 같은 읽기 전용 경계를 만든다.

## 2. Runtime mount 계약

| Secret | Host source 변수 | Container target | 소비 방식 |
|---|---|---|---|
| LLM API key | `GILBOM_LLM_API_KEY_SECRET_FILE` | `/run/secrets/gilbom_llm_api_key` | Backend allowlist loader |
| 이미지 URL 서명 key | `GILBOM_STORAGE_SIGNING_KEY_SECRET_FILE` | `/run/secrets/gilbom_storage_signing_key` | Backend allowlist loader |
| 인증 Rate Limit HMAC key | `AUTH_RATE_LIMIT_HMAC_KEY_SECRET_FILE` | `/run/secrets/gilbom_auth_rate_limit_hmac_key` | Backend allowlist loader |
| MFA TOTP keyring | `MFA_TOTP_KEYRING_SECRET_FILE` | `/run/secrets/gilbom_mfa_totp_keyring` | Backend allowlist loader |
| 초기 관리자 비밀번호 | `INITIAL_ADMIN_PASSWORD_SECRET_FILE` | `/run/secrets/gilbom_initial_admin_password` | Backend allowlist loader |
| DB bootstrap 비밀번호 | `POSTGRES_ADMIN_PASSWORD_SECRET_FILE` | `/run/secrets/postgres_admin_password` | Postgres 공식 `POSTGRES_PASSWORD_FILE` |
| DB Flyway 비밀번호 | `POSTGRES_FLYWAY_PASSWORD_SECRET_FILE` | `/run/secrets/postgres_flyway_password` | DB init script와 Backend loader |
| DB runtime 비밀번호 | `POSTGRES_APP_PASSWORD_SECRET_FILE` | `/run/secrets/postgres_app_password` | DB init script와 Backend loader |
| Backend MQTT 비밀번호 | `MQTT_PASSWORD_SECRET_FILE` | `/run/secrets/mqtt_backend_password` | `mqtt-init`과 Backend loader |
| 로컬 시험 로봇 비밀번호 | `MQTT_ROBOT_PASSWORD_SECRET_FILE` | `/run/secrets/mqtt_robot_password` | 로컬 `mqtt-init` 전용 |
| 운영 MQTT CA certificate | `MQTT_CA_CERTIFICATE_FILE` | `/run/secrets/mqtt_ca_certificate` | Backend TLS trust |
| 운영 Mosquitto verifier | `MOSQUITTO_PASSWORD_FILE_SECRET_FILE` | `/run/secrets/mosquitto_passwd` | 운영 credential volume 최초 seed |
| 운영 Mosquitto certificate | `MOSQUITTO_TLS_FULLCHAIN_FILE` | `/run/secrets/mosquitto_tls_fullchain` | Broker TLS |
| 운영 Mosquitto private key | `MOSQUITTO_TLS_PRIVATE_KEY_SECRET_FILE` | `/run/secrets/mosquitto_tls_private_key` | Broker TLS |

Backend의 `SecretFileEnvironmentPostProcessor`는 위 Secret만 Spring property로
변환한다. 명시적 `_FILE` 경로는 절대경로만 허용하고, 기본 Docker Secret 경로가
존재하면 환경변수 없이도 읽는다. 파일 내용이나 원래 I/O 예외는 오류 메시지에
포함하지 않는다.

LLM이 비활성화되거나 초기 관리자 bootstrap이 비활성화된 환경에서는 대응 파일을
0 byte로 둘 수 있다. 운영 필수 DB·서명·HMAC·MFA·MQTT Secret은 빈 파일을 허용하지
않는 배포 전 검증 대상으로 둔다. 애플리케이션 loader는 비활성 기능의 빈 Secret을
안전하게 전달할 수 있도록 빈 파일 자체는 허용한다.

## 3. DB·MQTT 검토 결과

DB와 MQTT도 직접 environment 주입을 유지하지 않는다.

- PostgreSQL bootstrap은 image가 제공하는 `POSTGRES_PASSWORD_FILE`을 사용한다.
- Flyway/runtime role 초기화 스크립트는 각각의 `_FILE`을 읽은 뒤 SQL 변수로만
  전달한다.
- Backend datasource와 Flyway는 같은 mount의 읽기 전용 사본을 사용한다.
- Backend MQTT 접속 비밀번호와 로컬 시험 로봇 비밀번호는 서로 다른 파일이다.
- Broker가 사용하는 Mosquitto `passwd`는 원문 비밀번호 파일과 별개의 verifier
  파일이며 기존처럼 Backend RW, Broker RO로 유지한다.
- 운영 `mosquitto_passwd` Secret은 credential named volume이 비어 있을 때만
  초기 seed로 복사한다. 이후 발급 결과는 volume에 보존되고 Broker wrapper가
  변경을 감지해 reload한다.
- Broker TLS certificate/private key와 Backend CA 파일은 runtime Secret target을
  직접 읽으며 image·config volume에 복사하지 않는다.

직접 값과 `_FILE`이 동시에 전달되면 Backend와 DB/MQTT 초기화 모두 모호한
우선순위를 허용하지 않고 실패한다.

## 4. Frontend 경계

Frontend에 허용되는 build-time 변수는 브라우저에 공개 가능한 `VITE_*`뿐이다.
현재 허용 항목은 `VITE_NAVER_MAP_CLIENT_ID`다. Client ID는 bundle에서 사용자가
볼 수 있으므로 Secret으로 취급하지 않는다.

LLM API key, DB·MQTT 비밀번호, 서명 key, 개인키를 `VITE_*`, Frontend Docker
build argument, 정적 파일에 넣지 않는다. `scripts/verify-secret-boundary.sh`가
Frontend source의 비-`VITE_*` 환경 접근과 Dockerfile Secret ARG/ENV를 CI에서
차단한다.

## 5. 로컬·운영 준비

`.env.example` 또는 `.env.prod.example`을 복사한 뒤 `secrets/`를 mode `0700`으로
만들고 승인된 Secret manager의 값을 대응 파일에 기록한다. 값 자체를 shell
history나 작업 문서에 입력하지 않는다. 파일 기록이 끝나면 mode를 `0444`로
바꾼다.

```sh
install -d -m 0700 secrets
chmod 0444 secrets/*
docker compose --env-file .env.prod config --quiet
```

Compose 파일은 source Secret 파일이 없어도 image build 구성을 해석할 수 있다.
실제 container를 만들 때만 source 파일이 필요하며 `/run/secrets/<name>`에
read-only로 mount된다.

운영 명세는 파일을 명시해야 한다.

```sh
docker compose --env-file .env.prod -f compose.prod.yaml config --quiet
```

## 6. 회전과 재기동

Backend Secret은 시작 시 한 번 읽으므로 source 파일을 승인된 새 파일로 원자적으로
교체한 뒤 Backend container를 재생성한다. 파일을 in-place로 수정하지 않는다.

DB Secret 파일 교체만으로 기존 PostgreSQL role 비밀번호가 바뀌지는 않는다.
승인된 `ALTER ROLE` 절차로 DB 값을 먼저 회전하고 Backend/Flyway Secret과
container를 순서대로 교체한다. MQTT bootstrap 비밀번호는 `mqtt-init`이 verifier
파일을 갱신한 뒤 Broker reload와 Backend 재생성을 함께 검증한다.

## 7. 검증 기준

```sh
sh scripts/verify-secret-boundary.sh
docker compose build backend frontend
docker image inspect gilbom-backend:local
docker image inspect gilbom-frontend:local
```

두 image의 `Config.Env`에는 실제 Secret이나 `_FILE` runtime 경로가 없어야 한다.
`_FILE` 경로는 Compose가 container 생성 시 설정하므로 image build history와
image environment에 남지 않는다.
