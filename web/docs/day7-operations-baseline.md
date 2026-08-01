# Day 7 — Docker·운영 패키징·배포 안정화 기준선

기준일: 2026-07-31

기준 Git HEAD: `06187751f3e7`

검증 범위: `web/` 애플리케이션, Dockerfile, Compose, 런타임 설정

이 문서는 Day 6 기능 상태를 Day 7 운영 패키징 작업의 회귀 기준으로 고정한다.
실제 Secret 값은 확인하거나 기록하지 않았으며, 변수명·소비자·파일 경로만
인벤토리에 포함한다.

## 1. 기준선 판정

| 게이트 | 실행 결과 |
|---|---|
| Backend 전체 회귀 | `728 tests, failures=0, skipped=0`, `BUILD SUCCESSFUL in 6m 27s` |
| Day 6 보고서·LLM acceptance | `110 tests, failures=0, skipped=0`, `BUILD SUCCESSFUL in 2m 41s` |
| Day 6 보고서 계약 동기화 | `Report API contract synchronization: PASS` |
| Frontend 테스트 | 공식 Day 6 게이트와 독립 재실행 모두 `fail=0`, `skipped=0` |
| Frontend production build | TypeScript와 Vite build PASS, 2,500 modules transformed |
| 로컬 전체 스택 Compose | `docker compose config --quiet` PASS |
| MQTT local/test/tls/mtls Compose | 각 `docker compose ... config --quiet` PASS |
| 운영 Compose | 전체 5개 서비스와 4개 네트워크 config·정책 검증 PASS |

검증 명령은 다음과 같다.

```sh
sh scripts/backend-test.sh
sh scripts/report-acceptance-test.sh
cd frontend/gilbom
npm ci
npm test
npm run build
```

Backend 종료 과정에서 Spring Session scheduler가 이미 종료 중인 Testcontainers
PostgreSQL에 접근해 경고·오류 로그를 남긴다. 테스트 assertion 실패나 skip은
아니지만 종료 시간이 늘고 실제 장애 로그와 혼동될 수 있으므로 Day 7 안정화
관찰 항목으로 유지한다.

Frontend build 결과의 주 진입 청크는 539.17 kB(gzip 151.56 kB)로 Vite의
500 kB 경고 기준을 넘는다. build 실패는 아니지만 이미지 전송 크기와 별개로
브라우저 초기 로딩 성능의 기준값으로 기록한다.

## 2. 담당 범위 기능 동결 원칙

이 기준선은 프로젝트 전체의 신규 기능을 중단시키지 않는다. 이번 작업 담당자의
완료된 보고서·LLM 및 Day 7 운영 패키징 범위만 기능 확장을 중단한다. 로봇,
결함과 다른 담당 영역의 기능 개발은 계속한다.

허용 범위는 Dockerfile·Compose·CI/CD·환경설정·Secret 주입·헬스체크·관측성,
nonroot/read-only 전환, 이미지 경량화, 배포·롤백 안정화, 그리고 이 작업에서
발견된 버그의 회귀 수정이다. 허용된 수정도 기존 API, DB, 권한, 화면 동작의
회귀 테스트를 통과해야 한다.

동결된 보고서 영역의 새 API·화면 기능·도메인 상태와 LLM Prompt·응답 계약
확장은 금지한다. 로봇·결함 영역에 필요한 API·화면·Flyway·MQTT 변경은 이
동결에 포함되지 않으며 각 영역의 계약과 회귀 검증을 따른다. 공용 runtime이나
배포 구성에 영향이 있으면 이 문서의 관련 운영 게이트도 다시 실행한다.

## 3. 서비스 중요도와 실패 경계

| 등급 | 서비스 | 운영 요구사항 |
|---|---|---|
| 필수 | PostgreSQL/PostGIS | 영속 DB. 준비되지 않으면 Backend를 시작하지 않는다. |
| 필수 | Backend | API, 인증·세션, 업무 로직의 필수 서비스다. DB readiness 뒤 시작한다. |
| 필수 | Frontend/Nginx | 사용자 진입점과 `/api` reverse proxy다. Backend readiness 뒤 공개한다. |
| 로봇 기능 의존 | MQTT Broker | 로봇 상태·Telemetry·명령에는 필수지만 웹 핵심 기능의 필수 등급에는 넣지 않는다. |
| 선택 | LLM/GMS | 비활성·정책 미승인·외부 장애 시 외부 호출 없이 `TEMPLATE` fallback을 사용한다. |
| 로컬 초기화 보조 | `mqtt-init` | 로컬 CA, Broker 인증서, password file을 준비하고 생성물 소유권을 bind source 소유자에게 돌려준 뒤 종료하는 one-shot 서비스다. |

운영 Compose는 로봇 통합 기능이 포함된 배포 단위로 정의해 DB와 MQTT가 모두
healthy인 뒤 Backend를 시작한다. 웹 기능만 분리 운영해야 하는 환경은 별도 승인된
override가 필요하다. LLM은 계속 선택 서비스이며 정책 gate 미충족이나 외부 장애
시 `TEMPLATE` fallback을 사용한다.

## 4. 이미지와 Compose 인벤토리

### 4.1 Dockerfile

| 파일 | 단계와 고정 이미지 | 현재 런타임 권한 | 판정 |
|---|---|---|---|
| `backend/Dockerfile` | Corretto 21 builder → Mosquitto 도구 → Alpine + Corretto `jlink` runtime | `spring:mqttcred`, UID/GID `1883:1883` | 멀티스테이지·nonroot·JRE 최소화 적용됨 |
| `frontend/Dockerfile` | Node 24.18 builder → Nginx 1.30.4 runtime | `nginx`, UID/GID `101:101` | test 포함 멀티스테이지·nonroot 적용됨 |
| `infra/mosquitto/local/Dockerfile.init` | Mosquitto 2.1.2 + OpenSSL | UID 0 | 로컬 one-shot 초기화로만 제한 |
| `tools/mock-robot/Dockerfile` | 0 byte placeholder | 정의 없음 | 배포 이미지로 사용 금지 |

Backend와 Frontend의 builder 산출물만 runtime 단계로 복사하므로 빌드 도구는
운영 단계에 남지 않는다. Backend runtime에는 custom Java runtime, 실행 JAR,
`mosquitto_passwd`, `ca-certificates`, `cjson`, `libcrypto3`만 추가된다.
healthcheck는 BusyBox `wget`을 사용하며 `curl`은 제거했다.
Frontend runtime에는 Nginx 설정과 정적 `dist/`만 있으며 Node, npm,
`node_modules`, TypeScript, source, test, sourcemap은 없다.

Day 7 작업 2에서 root·Backend·Frontend의 `.dockerignore`를 보강해 `.env`,
Secret, 개인키, 의존성, 빌드·테스트 결과와 IDE 파일을 context에서 제외했다.
실제 빌드 전송량은 Backend 약 164 kB, Frontend 약 460 kB로 확인했으며,
Frontend의 약 248 MiB `node_modules`는 더 이상 builder로 전송되지 않는다.

### 4.2 Compose

| 파일 | 용도 | 상태 |
|---|---|---|
| `compose.yaml` | DB, Backend, Frontend, Mosquitto, `mqtt-init` 로컬 전체 스택 | Broker TLS·인증 health와 문법 검증 PASS |
| `compose.prod.yaml` | DB, MQTT, Backend, Frontend와 config init 운영 스택 | 문법·포트·network·dependency·resource 정책 검증 PASS |
| `infra/mosquitto/local/compose.yaml` | 로컬 TLS Broker 단독 실행 | Broker TLS·인증 health와 문법 검증 PASS |
| `infra/mosquitto/test/compose.yaml` | localhost 평문 MQTT 시험 | 문법 검증 PASS |
| `infra/mosquitto/tls/compose.yaml` | 운영 형태 TLS 시험 | 문법 검증 PASS |
| `infra/mosquitto/mtls/compose.yaml` | mTLS 시험 | 문법 검증 PASS |

## 5. 포트와 볼륨

### 5.1 포트

| 서비스 | 컨테이너 포트 | 현재 host publish |
|---|---:|---|
| PostgreSQL/PostGIS | 5432 | `127.0.0.1:${POSTGRES_PORT:-5432}` |
| Backend | 8080 | `127.0.0.1:${BACKEND_PORT:-8080}` |
| Frontend/Nginx | 8080 | `127.0.0.1:${FRONTEND_PORT:-3000}` |
| 로컬 MQTT TLS | 8883 | `${MQTT_BIND_ADDRESS:-127.0.0.1}:${MQTT_TLS_PORT:-8883}` |
| 운영 Frontend HTTP | 8080 | `${FRONTEND_BIND_ADDRESS:-0.0.0.0}:${FRONTEND_HTTP_PORT:-80}` |
| 운영 Frontend HTTPS | 8443 | `${FRONTEND_BIND_ADDRESS:-0.0.0.0}:${FRONTEND_HTTPS_PORT:-443}` |
| 운영 MQTT | 8883 | `${MQTT_BIND_ADDRESS:-0.0.0.0}:${MQTT_TLS_PORT:-8883}` |
| 운영 Backend | 8080 | 없음 |
| 운영 PostgreSQL | 5432 | 없음 |
| MQTT 평문 시험 | 1883 | `127.0.0.1:1883` |
| MQTT TLS/mTLS 시험 | 8883 | `0.0.0.0:8883`에 해당, 격리 시험에서만 사용 |

로컬 DB·Backend·Frontend·MQTT는 기본적으로 loopback에만 공개된다. 운영
`compose.prod.yaml`은 Frontend HTTP·HTTPS와 MQTT TLS만 publish한다. HTTP는
ACME HTTP-01과 HTTPS redirect 전용이며 애플리케이션은 HTTPS에서만 제공한다.
MQTT bind address와 host 방화벽은 승인된 로봇 source CIDR로 제한해야 한다.

### 5.2 영속 볼륨과 bind mount

| 이름/원본 | 컨테이너 경로 | 접근 | 책임 |
|---|---|---|---|
| `postgres_data` | `/var/lib/postgresql/data` | DB RW | DB 영속 데이터 |
| `report_pdf_data` | `/app/data/report-pdf` | Backend UID 1883 RW | 승인 PDF와 같은 디렉터리의 원자적 저장 임시 파일 |
| `robot_images` | `/app/data/robot-images` | Backend UID 1883 RW | 결함 관측 이미지 |
| `infra/mosquitto/local/runtime/certs` | `/app/config/mqtt/certs` | Backend RO | 로컬 CA/Broker 인증서 |
| `infra/mosquitto/local/runtime/credentials` | `/app/config/mqtt/credentials` | Backend RW | MQTT `passwd` 발급·회전·폐기 |
| `infra/mosquitto/local/runtime` | `/mosquitto/local` | Broker RO | Broker 인증서, ACL, 갱신되는 `passwd` 조회 |
| `mosquitto_config` | `/mosquitto/config` | init RW, Broker RO | 운영 config·ACL·reload wrapper |
| `mosquitto_credentials` | `/mosquitto/credentials`, `/app/config/mqtt/credentials` | Backend RW, Broker RO | 운영 mutable password verifier |
| `mosquitto_data` | `/mosquitto/data` | Broker RW | QoS session·inflight persistence |
| Mosquitto 설정·실행 스크립트 | `/mosquitto/config/*` | Broker RO | Broker 설정과 wrapper |
| `infra/mosquitto/local` | `/mqtt-local` | `mqtt-init` RW | 로컬 인증 자산 생성 후 source UID/GID로 소유권 정상화 |
| `infra/mosquitto/acl` | `/mqtt-input/acl` | `mqtt-init` RO | ACL 원본 |
| 운영 `passwd`, ACL, 인증서 디렉터리 | `/mosquitto/config/*`, `/mosquitto/certs` | Broker RO | 운영 Broker 인증 자산 |

`docker compose down`은 named volume을 삭제하지 않으며 `down --volumes`는
DB·PDF·이미지를 함께 삭제하므로 운영 절차에서 금지하거나 별도 승인 대상으로 둔다.

## 6. 실제 런타임 쓰기 경로

| 프로세스 | 쓰기 경로 | 지속성·권한 요구사항 |
|---|---|---|
| Backend PDF 저장소 | `/app/data/report-pdf/**` | 영속 RW. 저장 중 `.<name>.<uuid>.tmp`를 같은 디렉터리에 만든 뒤 이동한다. |
| Backend 이미지 저장소 | `/app/data/robot-images/**` | 영속 RW. 하위 object key 디렉터리 생성과 파일 덮어쓰기가 필요하다. |
| Backend MQTT credential | `/app/config/mqtt/credentials/passwd` | 영속 RW. 파일과 부모가 GID 1883 쓰기를 허용해야 한다. |
| Backend/JVM·Tomcat | `/tmp` | multipart/JVM 임시 작업을 위한 비영속 RW. read-only rootfs 전환 시 tmpfs 필요 |
| Nginx PID | `/tmp/nginx.pid` | 32 MiB tmpfs의 비영속 RW |
| Nginx body/proxy temp | `/tmp/client_temp`, `proxy_temp`, `fastcgi_temp`, `uwsgi_temp`, `scgi_temp` | 동일 tmpfs. SSE proxy buffering은 비활성 |
| Nginx log | stdout, stderr | 파일 쓰기와 별도 영속 볼륨 불필요 |
| Mosquitto | `/tmp`, `/mosquitto/data`, `/mosquitto/log` | Compose tmpfs. 현재 `persistence false`, log는 stdout |
| `mqtt-init` | `/mqtt-local/runtime/**`, `/mqtt-local/ca/**`, `/tmp/mqtt-local-init.*` | root one-shot이 생성하지만 bind source UID/GID로 되돌려 개발자·Jenkins cleanup을 보장 |
| PostgreSQL | `/var/lib/postgresql/data` | 영속 RW |

Nginx 1.30.4의 compile-time PID·temp 기본값을 사용하지 않고 port 8080과
`/tmp` 경로를 명시했다. UID 101, read-only rootfs와 capability 0 상태에서
`/healthz`, 정적 파일, `/api`, SSE proxy를 실제 검증했다. 별도
`proxy_cache_path`는 없으며 SSE proxy cache와 buffering은 비활성이다.

## 7. 환경변수 인벤토리

실제 소비되는 변수는 다음 범주로 고정한다. 운영에서는 값이 아닌 이 목록을
배포 명세와 Secret store schema의 기준으로 사용한다.

- 실행·포트: `COMPOSE_PROJECT_NAME`, `SPRING_PROFILES_ACTIVE`, `TZ`,
  `SERVER_PORT`, `POSTGRES_PORT`, `BACKEND_PORT`, `FRONTEND_PORT`,
  `SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE`
- DB: `POSTGRES_DB`, `POSTGRES_ADMIN_USER`, `POSTGRES_ADMIN_PASSWORD`,
  `POSTGRES_FLYWAY_USER`, `POSTGRES_FLYWAY_PASSWORD`, `POSTGRES_APP_USER`,
  `POSTGRES_APP_PASSWORD`, `SPRING_DATASOURCE_URL`,
  `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`,
  `SPRING_FLYWAY_DEFAULT_SCHEMA`, `SPRING_FLYWAY_USER`,
  `SPRING_FLYWAY_PASSWORD`, `SPRING_JPA_HIBERNATE_DDL_AUTO`
- HTTP·보고서·저장소: `GILBOM_MAX_REQUEST_BODY_SIZE`,
  `GILBOM_MAX_REQUEST_HEADER_SIZE`, `GILBOM_MAX_UPLOAD_FILE_SIZE`,
  `GILBOM_REPORT_PDF_STORAGE_ROOT`,
  `GILBOM_REPORT_SSE_MAX_CONNECTIONS_PER_USER`,
  `GILBOM_REPORT_SSE_MAX_CONNECTIONS_TOTAL`,
  `GILBOM_STORAGE_LOCAL_ROOT_DIRECTORY`,
  `GILBOM_STORAGE_URL_SIGNING_KEY_BASE64`,
  `GILBOM_ROBOT_OFFLINE_TIMEOUT`, `GILBOM_ROBOT_OFFLINE_SCAN_INTERVAL`
- CORS·세션·인증: `GILBOM_CORS_ENABLED`,
  `GILBOM_CORS_ALLOWED_ORIGINS`, `GILBOM_CORS_ALLOW_CREDENTIALS`,
  `GILBOM_SESSION_IDLE_TIMEOUT`, `GILBOM_SESSION_ABSOLUTE_TIMEOUT`,
  `GILBOM_MFA_CHALLENGE_TIMEOUT`, `GILBOM_SESSION_MAX_CONCURRENT`,
  `GILBOM_MFA_CHALLENGE_MAX_FAILURES`,
  `GILBOM_SESSION_CLEANUP_INTERVAL`, `GILBOM_SESSION_CLEANUP_BATCH_SIZE`,
  `INITIAL_ADMIN_BOOTSTRAP_ENABLED`, `INITIAL_ADMIN_EMAIL`,
  `INITIAL_ADMIN_PASSWORD`, `INITIAL_ADMIN_DISPLAY_NAME`,
  `INITIAL_ADMIN_USER_CODE`
- Rate Limit·MFA key: `AUTH_RATE_LIMIT_HMAC_KEY_VERSION`,
  `AUTH_RATE_LIMIT_HMAC_KEY_BASE64`,
  `AUTH_CSRF_TOKEN_ISSUANCE_MAX_REQUESTS`,
  `AUTH_CSRF_TOKEN_ISSUANCE_WINDOW`,
  `AUTH_EMAIL_AVAILABILITY_MAX_REQUESTS`,
  `AUTH_EMAIL_AVAILABILITY_WINDOW`, `AUTH_REGISTRATION_MAX_REQUESTS`,
  `AUTH_REGISTRATION_WINDOW`, `AUTH_IP_MAX_REQUESTS`, `AUTH_IP_WINDOW`,
  `AUTH_PASSWORD_MAX_FAILURES`, `AUTH_PASSWORD_FAILURE_WINDOW`,
  `AUTH_MFA_MAX_FAILURES`, `AUTH_MFA_FAILURE_WINDOW`,
  `AUTH_TEMPORARY_LOCK_DURATION`, `AUTH_RATE_LIMIT_CLEANUP_INTERVAL`,
  `AUTH_RATE_LIMIT_CLEANUP_BATCH_SIZE`,
  `MFA_TOTP_SECRET_ENCRYPTION_ENABLED`,
  `MFA_TOTP_SECRET_ACTIVE_KEY_VERSION`, `MFA_TOTP_SECRET_KEYRING`
- MQTT 연결·계약: `MQTT_BROKER`, `MQTT_CLIENT_ID`, `MQTT_USERNAME`,
  `MQTT_PASSWORD`, `MQTT_SUBSCRIBE_TOPIC`, `MQTT_PUBLISH_TOPIC`,
  `MQTT_QOS`, `MQTT_RECONNECT_MAX_DELAY`,
  `MQTT_CONNECT_COMPLETION_TIMEOUT`, `MQTT_DISCONNECT_COMPLETION_TIMEOUT`,
  `MQTT_LOG_PAYLOAD`,
  `MQTT_TELEMETRY_MAX_DELAY`, `MQTT_STATE_MAX_DELAY`,
  `MQTT_COMMAND_RESULT_MAX_DELAY`, `MQTT_FUTURE_TIMESTAMP_TOLERANCE`
- MQTT TLS·credential: `MQTT_TRUST_CERTIFICATE`,
  `MQTT_CLIENT_CERTIFICATE`, `MQTT_CLIENT_PRIVATE_KEY`,
  `MQTT_PUBLIC_BROKER_URI`, `MQTT_CREDENTIAL_PROVISIONING_ENABLED`,
  `MQTT_CREDENTIAL_PASSWORD_FILE`, `MQTT_CREDENTIAL_PASSWORD_COMMAND`,
  `MQTT_CREDENTIAL_PROCESS_TIMEOUT`, `MQTT_CREDENTIAL_ACTIVATION_TIMEOUT`,
  `MQTT_CREDENTIAL_VERIFICATION_INTERVAL`,
  `MQTT_CREDENTIAL_REGISTRY_SCAN_INTERVAL`, `MQTT_TLS_PORT`, `MQTT_BIND_ADDRESS`,
  `MQTT_LAN_IP`, `MQTT_PUBLIC_HOSTNAME`, `MOSQUITTO_CERT_DIR`
- 선택 LLM: `GILBOM_LLM_ENABLED`, `GILBOM_LLM_PROVIDER`,
  `GILBOM_LLM_BASE_URL`, `GILBOM_LLM_MODEL`, `GILBOM_LLM_API_KEY`,
  `GILBOM_LLM_CONNECT_TIMEOUT`, `GILBOM_LLM_READ_TIMEOUT`,
  `GILBOM_LLM_MAX_OUTPUT_TOKENS`, `GILBOM_LLM_DAILY_REQUEST_LIMIT`,
  `GILBOM_LLM_DAILY_BUDGET`, `GILBOM_LLM_MAX_CONCURRENT_REQUESTS`,
  `GILBOM_LLM_PROMPT_VERSION`, `GILBOM_LLM_POLICY_APPROVED`,
  `GILBOM_LLM_TRAINING_EXCLUSION_APPROVED`,
  `GILBOM_LLM_DATA_RETENTION_APPROVED`,
  `GILBOM_LLM_OUTBOUND_ALLOWLIST_APPLIED`,
  `GILBOM_LLM_IMAGE_INPUT_ENABLED`,
  `GILBOM_LLM_IMAGE_DEIDENTIFICATION_APPROVED`
- Frontend build-time 공개 설정: `VITE_NAVER_MAP_CLIENT_ID`

ignored 로컬 `.env`에는 구 이름인 `GILBOM_AUTH_CHALLENGE_TIMEOUT`과
`GILBOM_AUTH_CHALLENGE_MAX_FAILURES`가 남아 있고 실제 애플리케이션·Compose는
`GILBOM_MFA_CHALLENGE_TIMEOUT`과 `GILBOM_MFA_CHALLENGE_MAX_FAILURES`를 사용한다.
현재 로컬 실행에는 기본값이 적용된다. Secret 값은 조사하지 않았다.

Day 7 작업 2부터 위 목록의 Secret 변수는 직접 값 대신 대응하는 `*_FILE`로
주입한다. 정확한 파일명과 컨테이너 경로는
[`day7-secret-injection.md`](day7-secret-injection.md)를 기준으로 한다.
`VITE_NAVER_MAP_CLIENT_ID`는 브라우저 bundle에 포함되는 공개 Client ID이므로
Secret으로 취급하거나 비밀 주입 경로를 사용하지 않는다.

## 8. 확정 Secret 목록

| Secret | 필수도 | 소비자·주입 원칙 |
|---|---|---|
| `POSTGRES_ADMIN_PASSWORD` | 필수 | DB bootstrap 전용. Backend에는 전달하지 않는다. |
| `POSTGRES_FLYWAY_PASSWORD` | 필수 | Backend의 Flyway migration 계정에만 전달한다. |
| `POSTGRES_APP_PASSWORD` | 필수 | Backend runtime datasource 계정에만 전달한다. |
| `AUTH_RATE_LIMIT_HMAC_KEY_BASE64` | 운영 필수 | 재시작·다중 인스턴스에서 고정된 외부 Secret |
| `MFA_TOTP_SECRET_KEYRING` | 운영 필수 | AES-256-GCM keyring. 버전과 함께 회전하며 원문 로그 금지 |
| `GILBOM_STORAGE_URL_SIGNING_KEY_BASE64` | 운영 필수 | 이미지 URL 서명용 32-byte 이상 고정 key |
| `INITIAL_ADMIN_PASSWORD` | 최초 bootstrap만 | 빈 DB 생성 시 일시 주입하고 bootstrap 종료 후 폐기 |
| `MQTT_PASSWORD` | 로봇 기능 사용 시 필수 | Backend Broker 계정 Secret |
| Mosquitto `passwd` 파일 | MQTT 필수 | 해시 파일도 민감정보로 취급. Backend RW/Broker RO, mode `0660` |
| Broker TLS private key | MQTT TLS 필수 | Broker RO. 이미지·Git·환경변수에 포함하지 않고 파일 Secret으로 mount |
| 로컬 CA private key | 로컬 발급만 | `mqtt-init` 외 서비스에 mount하지 않고 mode `0600` |
| `MQTT_CLIENT_PRIVATE_KEY` | mTLS 사용 시 | Backend 파일 Secret. 대응 인증서와 별도 권한 관리 |
| `GILBOM_LLM_API_KEY` | LLM 활성 시만 | Backend에만 주입. LLM 비활성 시 생성·주입하지 않는다. |

인증서 공개부, CA 인증서, MQTT username, DB username, LLM model·base URL,
`VITE_NAVER_MAP_CLIENT_ID`는 Secret이 아니다. `INITIAL_ADMIN_EMAIL`과 user code는
비밀번호는 아니지만 개인정보·bootstrap 자료로 보호한다.

운영 Secret은 이미지의 `ARG`, `ENV`, `COPY`, Compose 파일, Git 추적 파일에
기록하지 않는다. Compose에는 `/run/secrets/*` 경로만 전달하고 실제 값은 실행
시점의 read-only file Secret으로 mount한다.

## 9. Day 7 시작 시 확인된 배포 위험

1. ~~`compose.prod.yaml`이 Mosquitto만 포함해 전체 운영 배포를 기술하지 못함~~:
   Day 7 작업 5에서 DB·MQTT·Backend·Frontend, 4개 network, named volume,
   health dependency와 resource/log 상한을 포함해 해소했다.
2. ~~Frontend Nginx runtime이 root이며 port 80, `/run`, `/var/cache/nginx`에 씀~~:
   Day 7 작업 4에서 UID 101, port 8080, `/tmp` tmpfs로 해소했다.
3. ~~두 build context가 `.env`와 `node_modules` 등을 포함할 수 있음~~:
   Day 7 작업 2의 `.dockerignore` 보강 및 실제 context 크기 검증으로 해소했다.
4. ~~Backend·Frontend 서비스에 read-only root filesystem과 `cap_drop: ALL`이
   없음~~: Backend는 작업 3, Frontend는 작업 4에서 해소했다.
5. ~~MQTT 장애가 운영 Backend 시작 순서에 어떻게 반영되는지 미확정~~:
   로봇 통합 운영 배포에서는 DB와 MQTT health를 모두 Backend 선행 조건으로
   강제하는 것으로 확정했다.
6. Frontend 주 진입 청크가 minified 500 kB 경고 기준을 넘는다.
7. Backend 테스트 종료 scheduler 로그가 테스트 완료를 지연시키고 오류처럼 보인다.

위 항목은 신규 기능이 아니라 운영 패키징·보안·성능·배포 안정화 범위에서만
처리한다. Day 6 회귀 통과, Compose 문법 검증, 쓰기 경로·Secret 확정이 모두
완료됐으므로 이 기준선 확정 작업은 완료로 판정한다. 전체 운영 배포 명세는
`scripts/verify-production-compose.sh`를 Jenkins의 image build 선행 gate로
적용하고 실제 임시 운영 스택 검증 대상으로 유지한다.
