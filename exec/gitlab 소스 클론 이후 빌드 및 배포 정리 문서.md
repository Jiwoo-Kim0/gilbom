# 길봄 포팅 매뉴얼

기준: GitLab `master` 브랜치, 운영 배포 환경


## 0. 시스템 구성

```text
Browser/Robot
  ├─ HTTPS 443 ───────────────> Frontend Nginx
  │                               ├─ 정적 React 파일 제공
  │                               └─ /api/* ─HTTP─> Spring Backend:8080
  ├─ HTTP 80 ──308 redirect───> HTTPS 443
  ├─ MQTTS 8883 ──────────────> Mosquitto
  └─ HTTPS POST /api/v1/images > Nginx ─HTTP─> Backend

Backend ─JDBC─> PostgreSQL/PostGIS (내부 data network)
Backend ─MQTTS─> Mosquitto (내부 TLS)
Backend ─HTTPS─> GMS (정책 gate를 모두 통과한 경우)
Browser ─HTTPS─> Naver Maps JavaScript API
```

운영 Compose에서 외부로 publish되는 기본 포트는 `80`, `443`, `8883`이다. Backend `8080`과 PostgreSQL `5432`는 운영 호스트에 publish되지 않는다.

## 1. 제품 버전 상세 내용

### 1.1 Web/서버 

Local 열은 개발 PC에서 직접 실행하거나 `compose.yaml`로 실행할 때의 기준이고, Docker 열은 image 내부에 고정된 버전과 운영 구성을 나타낸다.

| 구분 | 제품 | Local 개발 환경 | Docker image·운영 환경 |
|---|---|---|---|
| JVM/JDK | Java 21 | Host에서 Backend를 직접 실행할 때 JDK 21 필요. Corretto·Temurin 등 배포판은 제한하지 않음. Local Docker만 사용하면 Host JDK 불필요 | Builder: Amazon Corretto `21.0.12+8`, Alpine 3.22 |
| Backend runtime | Java runtime | Host 직접 실행은 설치된 JDK 21 사용. Local Docker 실행은 image 내부 runtime 사용 | `jlink` Java 21 runtime, `MaxRAMPercentage=75.0` |
| Backend framework | Spring Boot | `3.5.16` | `3.5.16` |
| WAS | Embedded Apache Tomcat | `tomcat-embed-core 10.1.55` | `tomcat-embed-core 10.1.55` |
| Backend build tool | Gradle Wrapper | `8.14.3` | Builder에서 Wrapper `8.14.3` 사용 |
| Frontend framework | React / React DOM | `19.2.8` | build 결과 정적 파일 사용 |
| Router | React Router | `8.3.0` | build 결과 정적 파일에 포함 |
| Frontend builder | Node.js / npm | Node `24.18.0`, npm `11.16.0` | Node `24.18.0`, npm `11.16.0`, Alpine 3.23 |
| Web server/reverse proxy | Nginx | Local Compose에서 `http://localhost:3000` 제공 | `nginx:1.30.4-alpine`, non-root UID 101 |
| DBMS | PostgreSQL + PostGIS | Local Compose image `postgis/postgis:16-3.5` | `postgis/postgis:16-3.5` |
| MQTT broker | Eclipse Mosquitto | Local Compose image `2.1.2-alpine`, Local CA, port 8883 | `2.1.2-alpine`, 운영 TLS, port 8883 |
| TLS 발급·갱신 | Certbot | 사용하지 않음 | `certbot/certbot:v5.7.0` |
| CI | Jenkins LTS | Local 실행에는 사용하지 않음 | `2.555.3-lts-jdk21` |
| CI container tooling | Docker CLI/DinD | Local 실행에는 사용하지 않음 | Docker `29.6.2`, Alpine 3.24 |
| Docker 실행 도구 | Docker Engine / Compose | Docker Desktop 또는 Docker Engine, Compose v2(세부 버전 미고정) | 운영 host Docker Engine, Compose v2(세부 버전 미고정) |

### 참조 근거

  - Backend 빌드·런타임: `web/backend/Dockerfile`
  - IDE, Spring Boot 및 Java Toolchain: `web/backend/build.gradle`
  - Gradle Wrapper: `web/backend/gradle/wrapper/gradle-wrapper.properties`
  - Embedded Tomcat: Gradle `runtimeClasspath`의 BOM 해석 결과
  - Frontend 빌드·Nginx: `web/frontend/Dockerfile`
  - React·React Router·Node/npm 요구 버전: `web/frontend/gilbom/package.json`
  - Frontend 실제 의존성 해석 버전: `web/frontend/gilbom/package-lock.json`
  - npm 버전 검사 설정: `web/frontend/gilbom/.npmrc`
  - 로컬 DB·MQTT·Backend·Frontend 구성: `web/compose.yaml`
  - 운영 DB·MQTT·TLS·Certbot 구성: `web/compose.prod.yaml`
  - CI Jenkins·Docker DinD 구성: `web/compose.jenkins.yaml`
  - Jenkins 실행 이미지: `web/infra/jenkins/Dockerfile`
  - CI 이미지 빌드 절차: `web/Jenkinsfile`

  
 ### Tomcat 버전 확인하기(Gradle `runtimeClasspath`의  BOM 해석)
```bash
cd web/backend
./gradlew dependencyInsight --dependency tomcat-embed-core --configuration runtimeClasspath
```

### 1.2 Jetson/로봇

| 구분 | 버전 값 | 
|---|---|---|
| Board | NVIDIA Jetson Orin Nano |
| Kernel/architecture | `5.15.148-tegra`, `aarch64` | 
| JetPack/L4T | JetPack L4T `R36.4.7` | 
| Python | `3.10.12` | 
| MQTT client | paho-mqtt `2.1.0` | 
| 설정 | PyYAML `6.0.2` | 
| Vision | Ultralytics `8.4.95` | 
| OpenCV | `5.0.0.93` |
| NumPy | `1.26.4` | 
| PyTorch | `2.5.0a0+872d972e41.nv24.8` |
| TorchVision | `0.20.0a0+afc54f7` | 

Jetson의 현재 상태는 다음으로 다시 수집한다.

```bash
cat /etc/os-release
cat /etc/nv_tegra_release
uname -a
python3 --version
python3 scripts/hardware_inventory.py
```

## 1.3 주요 설정 파일 및 경로

| 구분 | Local 설정 파일·경로 | Docker·운영 설정 파일·경로 | 역할 |
|---|---|---|---|
| Compose | `web/compose.yaml` | `web/compose.prod.yaml` | Local 전체 서비스와 운영 전체 서비스 구성 |
| 환경 파일 | `web/.env.example` → `web/.env` | `web/.env.prod.example` → `web/.env.prod` | Compose 변수 입력. Secret 원문 대신 Secret 파일 경로 기록 |
| image build | `web/compose.yaml`의 `build` 설정 | `web/compose.build.yaml`, `web/Jenkinsfile` | Local image 직접 생성과 운영 Commit SHA image 생성 |
| Backend 공통 설정 | `web/backend/src/main/resources/application.yml` | Local과 동일 | Spring/JDBC/Flyway/MQTT/인증/LLM 공통 프로퍼티 |
| Backend 환경 profile | `web/backend/src/main/resources/application-local.yml`, `application-docker.yml` | `web/backend/src/main/resources/application-ec2.yml`, `application-docker.yml` | Local 및 운영 profile별 설정 |
| Backend image | `web/backend/Dockerfile` | Local과 동일 | Java 21 build, `bootJar`, `jlink` runtime 생성 |
| Frontend image | `web/frontend/Dockerfile` | Local과 동일 | Node build와 Nginx runtime 생성 |
| Nginx | `web/frontend/nginx/`의 Local HTTP 구성 | `web/frontend/nginx/`의 ACME bootstrap·HTTPS 구성 | React 정적 파일, `/api` proxy, redirect, TLS 설정 |
| MQTT | `web/infra/mosquitto/local/`, `web/infra/mosquitto/acl/` | `web/infra/mosquitto/mosquitto.conf`, `run-production-mosquitto.sh`, `acl/` | Local CA·credential 생성과 운영 TLS Broker 구성 |
| PostgreSQL 초기화 | `web/infra/postgres/init/20-create-application-roles.sh` | Local과 동일 | DB admin 외 Flyway/runtime role 생성 |
| Secret | `web/secrets/` | `web/infra/scripts/generate-production-secrets.sh`, 운영 host의 Secret 경로 | Local Secret 파일과 운영 Secret 생성·주입 |
| DB migration | `web/backend/src/main/resources/db/migration/` | Local과 동일 | Flyway schema 정본(V1~V44) |
| DB backup | 필요 시 `exec/3_DB_덤프_생성_및_복구.md` 사용 | `web/infra/scripts/backup-db.sh` | DB dump 생성·복구와 운영 암호화 backup |
| DB 참고 자료 | `docs/길봄_ERD.sql`, `docs/길봄_DB_DDL.sql` | Local과 동일 | ERD와 통합 DDL 참고 자료. 실제 적용 정본은 Flyway migration |
| Jetson 주행 설정 | `embedded/jetson_main/config.yaml` | Robot 장치에 배포한 동일 파일 | 주행 장치·카메라·AI·안전 임계값 |
| Jetson 통신 설정 | `embedded/jetson_main/comms.yaml` | Robot 장치에 배포한 동일 파일 | MQTT/TLS/이미지 업로드 설정 템플릿 |
| Jetson dependency | `embedded/jetson_main/requirements.txt` | Robot 장치에 배포한 동일 파일 | Jetson Python dependency 기준 |


## 2. 빌드 시 사용되는 운영 환경변수

실제 값은 `web/.env.prod.example`을 복사한 `.env.prod`에 기록한다. `.env.prod`는 Compose가 `${변수}`를 치환할 때 읽는 운영 설정 파일이다. `compose.prod.yaml`의 `environment`에 연결된 값은 컨테이너 환경변수로 전달되고, `image`, `ports`, `volumes`, `cpus` 등에 연결된 값은 Docker 배포 구성을 바꾼다.

`*_SECRET_FILE` 변수에는 Secret 원문이 아니라 **운영 host의 Secret 파일 경로**만 기록한다. Compose가 그 파일을 `/run/secrets/...`에 read-only로 mount하고, 컨테이너가 기동할 때 내용을 읽는다.

- `.env.prod`만 편집해도 실행 중인 컨테이너에는 즉시 반영되지 않는다. Compose로 재생성해야 한다.
- `VITE_NAVER_MAP_CLIENT_ID`는 build-time 값이므로 Frontend image를 다시 빌드해야 한다.
- DB명·role·초기 비밀번호는 빈 `postgres_data` volume의 최초 초기화 때만 자동 생성된다.
- Secret 원문은 `.env.prod`, Git, shell history, Jenkins parameter 또는 Mattermost에 기록하지 않는다.

### 2.1 필수 또는 운영자가 결정해야 하는 값

| 변수 | 실제 사용 위치 | 역할·입력 내용 | 값을 변경했을 때의 효과·주의점 | 템플릿/작성값 |
|---|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | Backend Spring profile | `ec2`는 Secure `__Host-` Cookie, 외부 rate-limit Key, MFA 암호화와 운영 로그 정책을 강제하고 `docker`는 컨테이너 설정을 적용한다. | 다른 profile로 바꾸면 운영 보안 강제가 빠질 수 있으므로 `ec2,docker`를 유지한다. | `ec2,docker` |
| `SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE` | Backend graceful shutdown | HTTP 요청·DB pool·MQTT 연결을 정리할 단계별 최대 시간이다. | 너무 짧으면 요청이 끊기고, 너무 길면 Compose의 30초 종료 상한에서 강제 종료될 수 있다. | `20s` |
| `GILBOM_HEALTH_READINESS_COMPONENTS` | `/actuator/health/readiness`, Compose healthcheck | Backend가 트래픽을 받을 준비가 되었는지 검사할 구성요소 목록이다. | `mqttConnection`을 추가하면 MQTT 장애도 Backend unhealthy와 Frontend 기동 대기로 이어진다. LLM은 fallback 대상이라 포함하지 않는다. | `readinessState,db,flywayReadiness,storageReadiness` |
| `TZ` | DB·Backend | DB 함수, 로그와 시간 변환의 기본 timezone이다. | 환경별 timezone이 다르면 운영 기록 비교가 어려워진다. 특별한 요구가 없으면 UTC를 유지한다. | `UTC` |
| `BACKEND_IMAGE` | `backend.image` | 운영에서 실행할 CI 검증 완료 Backend image tag/digest다. | 변경 후 재배포하면 Backend 코드·dependency·migration이 바뀐다. 승인된 full SHA tag 또는 digest를 사용한다. | `________________` |
| `FRONTEND_IMAGE` | `frontend.image` | 운영에서 실행할 Frontend/Nginx image tag/digest다. | 브라우저 bundle과 Nginx 설정이 바뀐다. Backend와 같은 release SHA를 사용한다. | `________________` |
| `FRONTEND_BIND_ADDRESS` | Frontend port publish | Nginx를 host의 어느 interface에 공개할지 정한다. | `0.0.0.0`은 모든 interface, `127.0.0.1`은 host 내부에서만 접근 가능하다. | `0.0.0.0` |
| `FRONTEND_HTTP_PORT` | host → Nginx `8080` | HTTP-01 ACME challenge와 HTTPS redirect 외부 port다. | 80이 아니면 방화벽·DNS 앞단·Certbot challenge 경로를 함께 조정해야 한다. | `80` |
| `FRONTEND_HTTPS_PORT` | host → Nginx `8443` | 사용자용 외부 HTTPS port다. | 443이 아니면 URL에 port가 필요하고 공개 URL·방화벽도 함께 바꿔야 한다. | `443` |
| `CERTBOT_DOMAIN` | Certbot과 `certificate.sh` | 인증서를 발급할 FQDN이며 DNS가 운영 host를 가리켜야 한다. | 변경하면 새 domain 인증서를 발급하고 Nginx·MQTT 공개 주소를 함께 점검해야 한다. | `________________` |
| `CERTBOT_EMAIL` | Certbot `--email` | 인증서 만료·복구 알림을 받을 담당자 email이다. | 빈 값이면 Compose 필수값 검사 또는 발급이 실패한다. | `________________` |
| `CERTBOT_CONF_DIR` | host → `/etc/letsencrypt` | Certbot 계정·갱신 설정·원본 인증서의 영속 directory다. | 빈 경로로 변경하면 기존 발급 이력이 보이지 않아 갱신이 끊길 수 있다. | `/opt/gilbom/certbot/conf` |
| `CERTBOT_WWW_DIR` | host → Frontend/Certbot `/var/www/certbot` | Nginx와 Certbot이 공유하는 HTTP-01 challenge directory다. | 두 서비스가 다른 directory를 보면 domain 검증이 실패한다. | `/opt/gilbom/certbot/www` |
| `NGINX_CERT_DIR` | host → Frontend `/etc/nginx/certs` | Certbot 원본에서 복사한 Nginx용 `fullchain.pem`, `privkey.pem`을 둔다. | 비어 있거나 권한이 틀리면 Frontend는 HTTPS 앱 대신 bootstrap 모드에 머문다. | `/opt/gilbom/certbot/nginx` |
| `NGINX_CERT_GID` | 인증서 sync script | Nginx가 private key를 읽게 할 파일 group ID다. | Frontend nginx group과 다르면 reload·HTTPS 기동이 실패한다. | `101` |
| `POSTGRES_DB` | DB 초기화·Backend JDBC URL | 생성하고 접속할 database 이름이다. | 최초 기동에는 생성되지만 기존 volume에서는 값만 바꿔도 DB가 rename되지 않아 연결이 실패한다. | `gilbom` |
| `POSTGRES_ADMIN_USER` | DB bootstrap·healthcheck | DB와 role을 최초 생성하는 관리 계정이며 애플리케이션에는 전달하지 않는다. | 기존 DB에서 이름만 바꾸면 새 role이 생기지 않고 healthcheck가 실패한다. | `gilbom_db_admin` |
| `POSTGRES_FLYWAY_USER` | DB 초기화·Backend Flyway | schema/table migration 전용 role이다. | 기존 DB에서는 role·권한·객체 소유권을 먼저 이관하지 않으면 migration이 실패한다. | `gilbom_flyway` |
| `POSTGRES_APP_USER` | Backend datasource | 업무 데이터에 최소 DML 권한으로 접근하는 runtime role이다. | 기존 DB에서 새 role·권한 없이 바꾸면 Backend가 DB에 접속하지 못한다. | `gilbom_app` |
| `MQTT_TLS_SERVER_NAME` | Broker healthcheck·Backend Broker URL·내부 DNS alias | Broker 인증서 DNS SAN과 일치할 TLS hostname이다. Compose 내부에서도 이 이름으로 hostname 검증한다. | 인증서 SAN과 다르면 TLS handshake와 healthcheck가 실패한다. | `________________` |
| `MQTT_PUBLIC_BROKER_URI` | Robot MQTT credential 응답 | Robot이 외부에서 접속할 공개 MQTTS 주소다. Backend 내부 Broker 주소와는 별개다. | 변경 후 Robot에 제공되는 주소가 바뀌므로 DNS·port·인증서 SAN과 맞아야 한다. | `ssl://________________:8883` |
| `MQTT_BIND_ADDRESS` | Mosquitto port publish | Broker를 host의 어느 interface에 공개할지 정한다. | `0.0.0.0`이면 외부에 노출되므로 방화벽에서 Robot source CIDR을 제한한다. | `0.0.0.0` |
| `MQTT_TLS_PORT` | host → Mosquitto `8883` | Robot용 외부 MQTTS port다. | 변경하면 방화벽과 `MQTT_PUBLIC_BROKER_URI` port도 같이 바꿔야 한다. | `8883` |
| `MQTT_CLIENT_ID` | Backend Paho MQTT client | Broker가 Backend 연결을 구분하는 client identifier다. | 다른 활성 client와 중복되면 기존 연결이 끊길 수 있어 인스턴스별로 고유해야 한다. | `gilbom-backend-prod` |
| `MQTT_USERNAME` | Backend MQTT 인증 | Broker ACL과 `mosquitto_passwd`에 존재하는 Backend 계정명이다. | 이름만 바꾸면 인증·topic 권한이 실패하므로 ACL과 verifier를 함께 변경한다. | `gilbom` |
| `GILBOM_STORAGE_UPLOAD_URL` | Robot 이미지 credential | Jetson이 결함 이미지를 업로드할 공개 HTTPS API 전체 주소다. | Nginx domain/route와 다르면 Robot 이미지 업로드가 실패한다. | `https://________________/api/v1/images` |
| `VITE_NAVER_MAP_CLIENT_ID` | Frontend build ARG → Vite bundle | Naver Maps 공개 Client ID다. Client Secret은 넣지 않는다. | 정적 bundle에 포함되므로 값 변경 후 Frontend image 재빌드가 필요하다. | `________________` |
| `INITIAL_ADMIN_BOOTSTRAP_ENABLED` | Backend initial-admin runner | 빈 DB에 최초 관리자 1명을 만드는 일회성 gate다. | `true`일 때 아래 정보와 password Secret으로 생성하고, 완료 후 반드시 `false`로 되돌린다. | 평상시 `false` |
| `INITIAL_ADMIN_EMAIL` | 최초 관리자 계정 | 생성할 관리자 로그인 email이다. | 같은 email의 일반 가입 계정이 있으면 안전을 위해 Backend 기동이 중단된다. 생성 후 값 변경은 기존 계정에 반영되지 않는다. | `________________` |
| `INITIAL_ADMIN_DISPLAY_NAME` | 최초 관리자 계정 | 화면과 감사 기록에 사용할 표시 이름이다. | bootstrap 시에만 반영되며 이후 변경은 사용자 관리 절차로 수행한다. | `________________` |
| `INITIAL_ADMIN_USER_CODE` | 최초 관리자 계정 | 조직 내 사번/사용자 코드다. | bootstrap 시에만 반영되며 최대 30자 검증을 받는다. | `________________` |

### 2.2 Secret 파일 경로 변수

아래 변수에는 Secret 원문이 아니라 host 파일 경로를 입력한다.

| 경로 변수 | 파일에 넣는 내용 | 사용하는 곳·역할 | 변경 효과·주의점 |
|---|---|---|---|
| `POSTGRES_ADMIN_PASSWORD_SECRET_FILE` | DB 관리 role 비밀번호 한 줄 | DB 최초 초기화와 healthcheck가 `/run/secrets/postgres_admin_password`에서 읽는다. | 기존 DB에서 파일만 바꾸면 실제 role 비밀번호와 달라져 healthcheck가 실패한다. `ALTER ROLE`과 Secret 교체를 함께 수행한다. |
| `POSTGRES_FLYWAY_PASSWORD_SECRET_FILE` | Flyway role 비밀번호 한 줄 | DB role 생성과 Backend Flyway 연결에서 읽는다. | 기존 role 비밀번호와 불일치하면 migration 단계에서 Backend가 기동하지 못한다. |
| `POSTGRES_APP_PASSWORD_SECRET_FILE` | runtime app role 비밀번호 한 줄 | DB role 생성과 Backend datasource에서 읽는다. | 불일치하면 DB 연결과 readiness가 실패한다. 기존 DB에서는 role 비밀번호도 동시에 rotation한다. |
| `MQTT_PASSWORD_SECRET_FILE` | Backend MQTT 계정 평문 비밀번호 한 줄 | Broker healthcheck와 Backend MQTT client가 읽는다. | `mosquitto_passwd`의 `gilbom` hash와 같아야 하며 한쪽만 변경하면 인증이 실패한다. |
| `MQTT_CA_CERTIFICATE_FILE` | Broker CA PEM certificate | Broker healthcheck와 Backend TLS trust에 사용한다. | CA를 바꾸면 그 CA가 서명한 Broker fullchain도 함께 배포해야 한다. 잘못된 CA는 TLS handshake를 실패시킨다. |
| `MOSQUITTO_PASSWORD_FILE_SECRET_FILE` | `mosquitto_passwd` hash 파일 | `mosquitto-config-init`이 최초 기동 시 credential named volume에 복사한다. | volume이 이미 있으면 host 파일만 바꿔도 재복사되지 않는다. credential rotation/provisioning 절차를 사용한다. |
| `MOSQUITTO_TLS_FULLCHAIN_FILE` | Broker PEM certificate chain | Mosquitto TLS listener의 서버 인증서다. | private key와 쌍이 맞고 CA 검증 및 `MQTT_TLS_SERVER_NAME` SAN 검사를 통과해야 한다. 교체 후 Broker 재시작이 필요하다. |
| `MOSQUITTO_TLS_PRIVATE_KEY_SECRET_FILE` | Broker certificate private key | Mosquitto TLS listener가 읽는다. | 인증서와 불일치하거나 권한이 틀리면 Broker가 기동하지 않는다. 절대 환경변수·Git에 넣지 않는다. |
| `GILBOM_STORAGE_SIGNING_KEY_SECRET_FILE` | 무작위 Base64 URL 서명 Key | Backend의 보호된 이미지 URL 서명·검증에 사용한다. | 모든 Backend가 같은 Key를 써야 한다. 교체하면 이전 Key로 발급한 유효 URL이 무효화될 수 있다. |
| `AUTH_RATE_LIMIT_HMAC_KEY_SECRET_FILE` | 최소 32-byte 무작위 Base64 HMAC Key | IP 원문 대신 안정적인 rate-limit bucket을 만드는 데 사용한다. | 인스턴스·재시작 간 동일해야 한다. Key/version 변경 시 기존 bucket 누적이 이어지지 않는다. |
| `MFA_TOTP_KEYRING_SECRET_FILE` | `version=base64-32-byte-key` 형식 keyring | DB의 TOTP Secret을 AES-GCM으로 암·복호화한다. | 새 active version을 추가해도 기존 Key를 삭제하면 기존 MFA Secret을 복호화하지 못한다. |
| `INITIAL_ADMIN_PASSWORD_SECRET_FILE` | 최초 관리자 임시 비밀번호 한 줄 | bootstrap 활성 시 읽고 hash만 DB에 저장한다. | bootstrap이 꺼져 있으면 빈 파일 가능하다. 계정 생성 후 파일을 바꿔도 기존 비밀번호는 바뀌지 않는다. |
| `GILBOM_LLM_API_KEY_SECRET_FILE` | 승인받은 GMS API Key 한 줄 | Backend가 GMS Authorization에만 사용한다. | 현재 운영 Compose는 LLM enabled를 `true`로 고정하므로 파일이 비었거나 읽히지 않으면 Backend 기동이 실패한다. |

`web/infra/scripts/generate-production-secrets.sh`는 기본 `web/secrets`에 파일을 만들고 directory `0700`, 파일 `0444`를 적용하며 기존 파일을 덮어쓰지 않는다. 운영 중 임의 재생성하면 DB·MQTT·MFA·서명 상태가 기존 데이터와 불일치할 수 있으므로 rotation 계획 없이 재실행하지 않는다.

### 2.3 선택 기능 및 제한값

#### LLM/GMS

| 변수 | 역할 | 값에 따른 효과·조건 |
|---|---|---|
| `GILBOM_LLM_ENABLED` | 외부 LLM 기능의 1차 gate | 현재 `compose.prod.yaml`이 `true`를 직접 주입하므로 `.env.prod`의 `false`는 무시된다. |
| `GILBOM_LLM_PROVIDER` | 외부 provider 선택 | 현재 `GMS`만 허용하며 다른 값·빈 값은 template fallback으로 동작한다. |
| `GILBOM_LLM_BASE_URL` | GMS API base URL | HTTPS `gms.ssafy.io:443/gmsapi/api.openai.com/v1`만 허용한다. 다른 host·port·path는 거부한다. |
| `GILBOM_LLM_MODEL` | 보고서 생성 모델 | `gpt-5.4-mini`, `gpt-5.4`만 허용한다. 빈 값·미승인 모델은 fallback 조건이다. |
| `GILBOM_LLM_CONNECT_TIMEOUT` | 연결 최대 대기시간 | 0초 초과·10초 이하여야 한다. 짧으면 network 지연에 민감하고 범위를 넘으면 policy gate가 실패한다. |
| `GILBOM_LLM_READ_TIMEOUT` | 응답 최대 대기시간 | 0초 초과·60초 이하여야 한다. 짧으면 긴 생성이 timeout된다. |
| `GILBOM_LLM_MAX_OUTPUT_TOKENS` | 요청당 출력 token 상한 | `1~1500`만 허용한다. 낮추면 응답이 짧아질 수 있다. |
| `GILBOM_LLM_DAILY_REQUEST_LIMIT` | 사용자별 UTC 일일 요청 상한 | 반드시 1 이상이어야 한다. `0`은 무제한이 아니라 LLM 비활성 조건이다. |
| `GILBOM_LLM_DAILY_BUDGET` | 전체 일일 credit 예산 | 0보다 커야 하며 예약 합계가 넘으면 외부 호출을 거부한다. `0`은 비활성 조건이다. |
| `GILBOM_LLM_MAX_CONCURRENT_REQUESTS` | Backend 인스턴스별 동시 호출 상한 | `1~20`만 허용한다. 높이면 처리량·비용이 늘고 낮추면 과부하를 줄인다. |
| `GILBOM_LLM_PROMPT_VERSION` | 검증된 prompt/schema 선택 | 운영 Compose가 `report-v3`를 고정하므로 `.env.prod` 값은 무시된다. 코드 허용값은 `report-v1~v3`다. |
| `GILBOM_LLM_POLICY_APPROVED` | 조직 LLM 정책 승인 여부 | 실제 승인 뒤 `true`로 한다. `false`이면 외부 client를 활성화하지 않는다. |
| `GILBOM_LLM_TRAINING_EXCLUSION_APPROVED` | 학습 제외 조건 승인 | `false`이면 외부 호출을 차단한다. 실제 계약 확인 없이 값만 `true`로 쓰지 않는다. |
| `GILBOM_LLM_DATA_RETENTION_APPROVED` | 데이터 보존 정책 승인 | `false`이면 외부 호출을 차단한다. |
| `GILBOM_LLM_OUTBOUND_ALLOWLIST_APPLIED` | GMS egress allowlist 적용 확인 | 실제 방화벽 정책 적용 후 `true`로 한다. 값 자체가 network 정책을 만들지는 않는다. |
| `GILBOM_LLM_IMAGE_INPUT_ENABLED`, `GILBOM_LLM_IMAGE_DEIDENTIFICATION_APPROVED` | 이미지 입력과 비식별 승인 gate | 둘 다 `true`일 때만 이미지를 포함한다. 현재 운영 Compose가 두 값을 `true`로 고정한다. |

API Key Secret은 기동 필수 검사 대상이다. API Key가 유효하지만 provider/model/예산/승인 조건이 빠진 경우에는 외부 GMS client 대신 template fallback을 사용한다.

#### CORS·MFA·MQTT

| 변수 | 역할 | 값에 따른 효과·주의점 |
|---|---|---|
| `GILBOM_CORS_ENABLED` | cross-origin API 허용 gate | 기본 same-origin에서는 `false`를 유지한다. `true`이면 허용 origin만 CORS 응답을 받는다. |
| `GILBOM_CORS_ALLOWED_ORIGINS` | 허용할 정확한 Origin 목록 | scheme·host·port를 정확히 지정하고 credential 사용 시 `*`를 쓰지 않는다. |
| `GILBOM_CORS_ALLOW_CREDENTIALS` | cross-origin Session Cookie 허용 | `true`이면 origin을 엄격히 제한한다. `false`이면 cross-origin Session 인증이 동작하지 않는다. |
| `AUTH_RATE_LIMIT_HMAC_KEY_VERSION` | HMAC Key 운영 version | Key rotation 때 함께 올리고 모든 Backend 인스턴스에 동시에 적용한다. |
| `MFA_TOTP_SECRET_ENCRYPTION_ENABLED` | TOTP Secret DB 암호화 | 운영 `ec2` profile은 `true`와 유효 keyring을 강제하며 누락 시 기동 실패한다. |
| `MFA_TOTP_SECRET_ACTIVE_KEY_VERSION` | 새 TOTP Secret 암호화 Key 선택 | keyring에 존재해야 한다. rotation은 새 Key 추가 → active version 변경 → 기존 Key 보존 순서다. |
| `MQTT_CREDENTIAL_PROVISIONING_ENABLED` | Robot MQTT 계정 발급·회전·폐기 provisioner | `false`이면 관리 API가 `MQTT_CREDENTIAL_PROVISIONING_UNAVAILABLE`로 실패한다. 쓰기 경로·동기화 검증 후에만 `true`로 한다. |
| `MQTT_CONNECT_COMPLETION_TIMEOUT` | Backend Broker 연결 완료 대기 | 너무 짧으면 정상 TLS 연결도 실패하고 너무 길면 장애 감지가 늦어진다. 기본 `10s`다. |
| `MQTT_DISCONNECT_COMPLETION_TIMEOUT` | 종료 시 MQTT 연결 정리 대기 | graceful shutdown 상한 안에서 정리가 끝나도록 설정한다. 기본 `5s`다. |

### 2.4 컨테이너 자원·로그 제한

| 변수 묶음 | 적용 대상·역할 | 변경 효과 |
|---|---|---|
| `POSTGRES_CPUS`, `POSTGRES_MEMORY_LIMIT`, `POSTGRES_MEMORY_RESERVATION`, `POSTGRES_PIDS_LIMIT` | PostgreSQL CPU·memory·process 제한 | 너무 낮으면 query 지연, connection 실패, OOM 종료가 발생할 수 있고 높이면 host 자원을 더 사용한다. |
| `POSTGRES_SHM_SIZE` | PostgreSQL `/dev/shm` | 병렬 query와 shared memory 여유를 정한다. 지나치게 작으면 일부 query가 실패할 수 있다. |
| `BACKEND_CPUS`, `BACKEND_MEMORY_LIMIT`, `BACKEND_MEMORY_RESERVATION`, `BACKEND_PIDS_LIMIT` | Spring/JVM 제한 | 요청·PDF·LLM·MQTT 처리량에 영향을 준다. JVM heap은 container memory의 `MaxRAMPercentage=75.0`도 적용받는다. |
| `FRONTEND_CPUS`, `FRONTEND_MEMORY_LIMIT`, `FRONTEND_MEMORY_RESERVATION`, `FRONTEND_PIDS_LIMIT` | Nginx 제한 | 너무 낮으면 동시 연결·proxy·healthcheck가 지연될 수 있다. |
| `MQTT_CPUS`, `MQTT_MEMORY_LIMIT`, `MQTT_MEMORY_RESERVATION`, `MQTT_PIDS_LIMIT` | Mosquitto Broker 제한 | Robot 연결 수, TLS와 inflight message 처리 여유에 영향을 준다. |
| `MQTT_INIT_*` | 일회성 Broker 설정 초기화 | 너무 낮아 init이 실패하면 Broker가 시작되지 않는다. |
| `CERTBOT_*` 자원 제한 | 인증서 issue/renew container | 인증서 작업에만 적용되고 평상시 Frontend 처리량에는 영향이 없다. |
| `LOG_MAX_SIZE`, `LOG_MAX_FILES` | Docker `json-file` rotation | 값을 늘리면 로그 보존량과 disk 사용량이 함께 늘어난다. 기본은 파일당 `10m`, 3개다. |

### 2.5 변경값 적용

```bash
cd web
docker compose --env-file .env.prod -f compose.prod.yaml config --quiet
docker compose --env-file .env.prod -f compose.prod.yaml config --services
```


## 3. DB 접속 계정 및 프로퍼티

### 3.1 운영 DB 연결

| 항목 | 운영 기준 |
|---|---|
| Host | 도커 Compose 내부 DNS `db` |
| Port | `5432` |
| Database | `gilbom` 기본값 (`POSTGRES_DB`) |
| Encoding/locale | UTF-8, `C` locale |
| Admin role | `gilbom_db_admin` (`POSTGRES_ADMIN_USER`) |
| Migration role | `gilbom_flyway` (`POSTGRES_FLYWAY_USER`) |
| Runtime role | `gilbom_app` (`POSTGRES_APP_USER`) |
| JDBC URL | `jdbc:postgresql://db:5432/${POSTGRES_DB}` |
| Hibernate DDL | `none`; Flyway만 스키마 변경 |
| Flyway history schema | `flyway_history` |

비밀번호는 secrets 폴더 안 다음 파일로 각각 한 줄로 저장하고 `Git에 추가하지 않는다.`

```text
web/secrets/postgres_admin_password
web/secrets/postgres_flyway_password
web/secrets/postgres_app_password
```

주요 schema는 `auth`, `report`, `audit`, `session_store`, `request_control`, `robot`, `telemetry`, `defect`, `storage`, `flyway_history`다. 테이블과 제약의 실제 정본은 `web/backend/src/main/resources/db/migration/`이다.

### 3.2 DB 관련 파일 목록

| 파일 | 정의 내용 |
|---|---|
| `.env.prod` | DB명, 세 DB role 이름, Secret host 경로 |
| `compose.prod.yaml` | 컨테이너 환경변수, Secret mount, healthcheck, volume/network |
| `application.yml` | JDBC URL/user/password, Flyway user/password/schema, JPA 정책 |
| `20-create-application-roles.sh` | Flyway/runtime role 생성과 비밀번호 주입 |
| `db/migration/V*.sql` | schema/table/index/constraint/seed/권한 변경 |
| `infra/scripts/backup-db.sh` | admin 계정을 사용한 암호화 backup |
| `exec/3_DB_덤프_생성_및_복구.md` | 제출용 최신 dump 생성 및 복구 절차 |


## 4. 배포 특이사항

이 절은 일반적인 Docker Compose 배포와 비교하여 이 프로젝트에서 추가로 지켜야 하는 조건을 정리한다.

1. **운영 서버에서는 애플리케이션 image를 빌드하지 않는다.**  
   Jenkins가 Git full Commit SHA를 기준으로 Backend·Frontend image를 생성한다. 운영 서버는 해당 image를 전달받아 `compose.prod.yaml`에서 `--no-build`로 실행한다. 운영 host에서 임의로 `docker compose build`를 실행하면 CI가 만든 image와 다른 산출물이 생길 수 있다.

2. **서비스는 정해진 의존 순서로 기동한다.**  
   PostgreSQL과 Mosquitto가 정상 상태가 되어야 Backend가 기동하고, Backend가 준비된 뒤 Frontend가 기동한다. DB 비밀번호, MQTT 인증서, CA 또는 MQTT 비밀번호가 잘못되면 Frontend 기동까지 대기·실패할 수 있다.

3. **운영 Frontend는 인증서 유무에 따라 실행 모드가 달라진다.**  
   인증서가 없으면 Nginx는 HTTP-01 challenge를 위한 ACME bootstrap만 제공하고 애플리케이션을 평문 HTTP로 공개하지 않는다. Certbot 발급, Nginx 전용 인증서 directory 동기화, Nginx reload가 완료된 뒤에만 HTTPS 애플리케이션과 HTTP→HTTPS redirect가 활성화된다.

4. **MQTT 내부 접속도 인증서 hostname과 일치해야 한다.**  
   `MQTT_TLS_SERVER_NAME`은 Broker 인증서의 DNS SAN과 일치해야 한다. Compose는 같은 이름을 내부 data network alias로 등록하여 Backend가 Docker 내부에서도 동일한 TLS 이름으로 접속하게 한다. 외부 `8883/tcp`는 전체 공개하지 않고 Robot source CIDR로 제한한다.

5. **Secret 원문은 `.env.prod`에 넣지 않는다.**  
   `.env.prod`에는 `*_SECRET_FILE` 형식의 운영 host 파일 경로만 기록한다. Compose가 해당 파일을 `/run/secrets`에 read-only로 mount하고 각 컨테이너가 기동 시 읽는다. Secret 파일 변경 후에는 해당 프로세스가 값을 다시 읽도록 재생성·rotation 절차를 수행한다.

6. **DB 초기 설정은 기존 volume에 자동 반영되지 않는다.**  
   DB명, admin/Flyway/runtime role과 초기 비밀번호는 `postgres_data`가 비어 있는 최초 기동 때만 자동 생성된다. 기존 DB에서는 `.env.prod` 값만 바꾸지 말고 `ALTER ROLE`, 권한·객체 소유권 migration과 Secret 교체를 하나의 rotation 절차로 수행한다.

7. **MQTT 평문 비밀번호와 password verifier를 함께 관리한다.**  
   Backend가 읽는 `mqtt_backend_password`와 Mosquitto가 사용하는 `mosquitto_passwd`의 `gilbom` hash는 같은 비밀번호를 나타내야 한다. 한쪽만 바꾸면 Mosquitto 또는 Backend의 MQTT 인증이 실패한다. 기존 `mosquitto_credentials` volume이 있으면 host의 초기 verifier 파일만 교체해도 자동 반영되지 않는다.

8. **Naver Maps Client ID는 Frontend build-time 설정이다.**  
   `VITE_NAVER_MAP_CLIENT_ID`는 Vite production bundle에 포함된다. `.env.prod` 수정과 컨테이너 재시작만으로 바뀌지 않으며, 값을 변경하면 Frontend image를 다시 빌드하여 배포해야 한다.

9. **일부 LLM 설정은 현재 운영 Compose에 직접 고정되어 있다.**  
   `GILBOM_LLM_ENABLED=true`, `GILBOM_LLM_PROMPT_VERSION=report-v3`, 이미지 입력 관련 두 flag는 `compose.prod.yaml` 값이 `.env.prod`보다 우선한다. API Key Secret 파일이 비어 있으면 Backend가 기동하지 못한다. Key는 유효하지만 provider·model·예산·정책 승인 조건이 부족하면 외부 GMS 대신 template fallback을 사용한다.

10. **영속 데이터는 image 재배포와 별도로 보존·백업한다.**  
    PostgreSQL 데이터, Mosquitto credential/data, 보고서 PDF와 Robot 이미지는 Docker named volume에 저장된다. 일반 `docker compose down` 뒤에는 유지되지만 `docker compose down -v`는 volume을 삭제하므로 운영에서 실행하지 않는다. image rollback만으로 DB migration이나 volume 데이터가 이전 상태로 돌아가지는 않는다.

## 4.1 운영 서버 사전 조건

1. 전용 Linux host에 Docker Engine과 Docker Compose v2가 설치되어 있어야 한다.
2. Git, OpenSSL, `sha256sum`, POSIX `sh`가 필요하다.
3. DNS A/AAAA record가 운영 host를 가리켜야 한다.
4. 외부 inbound `80/tcp`, `443/tcp`가 열려야 한다.
5. `8883/tcp`는 Robot source CIDR에만 허용한다.
6. GMS를 쓸 경우 `gms.ssafy.io:443` egress 정책을 별도로 적용한다.
7. 운영 volume과 새 image layer를 저장할 disk 공간을 준비한다.

## 4.2 GitLab clone

사용할 remote와 기준 branch는 다음과 같다.

```text
Repository: https://lab.ssafy.com/s15-webmobile3-sub1/S15P11D101.git
Branch: master
```

새 directory에 clone한다.

```bash
git clone https://lab.ssafy.com/s15-webmobile3-sub1/S15P11D101.git
cd S15P11D101
git checkout master
git pull --ff-only origin master
```



## 4.3 Web image 빌드

### 4.3.1 환경 파일 준비

```bash
cd web
cp .env.prod.example .env.prod
chmod 0600 .env.prod
```

`.env.prod.example`에서 등호(`=`) 뒤가 비어 있는 항목은 다음과 같다.

| 환경변수 | 입력할 값 | 작성 조건 |
|---|---|---|
| `CERTBOT_EMAIL` | Let's Encrypt 인증서 만료 안내를 받을 운영자 이메일 | 필수 |
| `INITIAL_ADMIN_EMAIL` | 최초 관리자 로그인 이메일 | 빈 DB에서 `INITIAL_ADMIN_BOOTSTRAP_ENABLED=true`로 최초 관리자를 만들 때만 입력 |
| `INITIAL_ADMIN_DISPLAY_NAME` | 최초 관리자 표시 이름 | 최초 관리자 생성 시에만 입력 |
| `INITIAL_ADMIN_USER_CODE` | 최초 관리자 사번 또는 사용자 코드 | 최초 관리자 생성 시에만 입력 |
| `GILBOM_LLM_MODEL` | `gpt-5.4-mini` 또는 `gpt-5.4` | GMS 보고서 생성을 사용할 때 입력 |
| `GILBOM_CORS_ALLOWED_ORIGINS` | 허용할 Frontend origin 목록(예: `https://i15d101.p.ssafy.io`) | Frontend와 Backend가 다른 origin이고 `GILBOM_CORS_ENABLED=true`일 때만 입력 |
| `VITE_NAVER_MAP_CLIENT_ID` | Naver Cloud Platform에서 발급한 Maps Client ID | 지도 기능을 포함한 Frontend image를 빌드할 때 입력 |

`BACKEND_IMAGE`와 `FRONTEND_IMAGE`는 빈 값은 아니지만 기본값 `:local`을 배포할 Commit SHA tag로 바꾼다. 운영 domain이 `i15d101.p.ssafy.io`와 다르면 `CERTBOT_DOMAIN`, `MQTT_TLS_SERVER_NAME`, `MQTT_PUBLIC_BROKER_URI`, `GILBOM_STORAGE_UPLOAD_URL`도 해당 domain으로 바꾼다. `*_SECRET_FILE`에는 Secret 원문이 아니라 다음 절에서 생성할 Secret 파일의 host 경로를 입력한다.

### 4.3.2 운영 Secret 생성

아래 세 MQTT 인증서 source는 먼저 발급받아 안전한 host 경로에 둔다.

```text
MQTT CA certificate source: ________________________________
MQTT fullchain source:      ________________________________
MQTT private key source:    ________________________________
최초 관리자 password source: ______________________________
GMS API Key source:   ________________________________
```

```bash
MQTT_CA_CERTIFICATE_SOURCE=/secure/pki/mqtt-ca.pem \
MOSQUITTO_TLS_FULLCHAIN_SOURCE=/secure/pki/mqtt-fullchain.pem \
MOSQUITTO_TLS_PRIVATE_KEY_SOURCE=/secure/pki/mqtt-private-key.pem \
INITIAL_ADMIN_PASSWORD_SOURCE=/secure/bootstrap/initial-admin-password \
GILBOM_LLM_API_KEY_SOURCE=/secure/gms/gilbom-api-key \
SECRET_DIR="$PWD/secrets" \
  sh infra/scripts/generate-production-secrets.sh
```

GMS를 사용하지 않으면 `GILBOM_LLM_API_KEY_SOURCE` 한 줄을 생략한다. 스크립트는 기존 Secret 파일을 덮어쓰지 않는다.

### 4.3.3 image build

현재 Commit SHA를 immutable tag로 사용한다.

```bash
export RELEASE_SHA="$(git rev-parse HEAD)"
export BACKEND_IMAGE="gilbom-backend:$RELEASE_SHA"
export FRONTEND_IMAGE="gilbom-frontend:$RELEASE_SHA"
export VITE_NAVER_MAP_CLIENT_ID="________________"

docker compose --env-file .env.prod -f compose.build.yaml \
  build backend frontend
```

`BACKEND_IMAGE`, `FRONTEND_IMAGE`의 동일한 값을 `.env.prod`에도 기록한다.

## 4.4 최초 배포

### 4.4.1 최초 관리자 정책

- 기존 DB dump를 복구하여 관리자 계정이 있으면 `INITIAL_ADMIN_BOOTSTRAP_ENABLED=false`로 둔다.
- 빈 DB를 최초 구성할 때만 `true`로 두고 관리자 email/name/code/password 파일을 준비한다.
- 첫 로그인에서 password 변경과 MFA 등록 후 다시 `false`로 바꾸고 Backend를 재생성한다.

### 4.4.2 host directory

```bash
sudo install -d -m 0755 /opt/gilbom/certbot/conf /opt/gilbom/certbot/www
sudo install -d -m 0750 -o root -g 101 /opt/gilbom/certbot/nginx
```

### 4.4.3 Compose 기동

```bash
docker compose --env-file .env.prod -f compose.prod.yaml \
  up --detach --no-build --wait --wait-timeout 240
```

기동 순서는 PostgreSQL·Mosquitto → Backend → Frontend다. 인증서가 아직 없으면 Frontend는 ACME bootstrap만 제공하고 애플리케이션을 HTTP로 공개하지 않는다.

## 4.5 HTTPS 인증서 발급

사전 조건:

- `CERTBOT_DOMAIN` DNS가 이 host를 가리킨다.
- 외부 `80/tcp`가 열려 있다.
- port 80은 Certbot과 Frontend가 사용한다.

현재 clone의 `web` directory에서 다음처럼 프로젝트 경로와 단일 env 파일을 명시한다.

```bash
sudo env \
  DEPLOY_DIR="$PWD" \
  COMPOSE_FILE="$PWD/compose.prod.yaml" \
  ENV_FILE="$PWD/.env.prod" \
  sh infra/certbot/certificate.sh issue
```

발급 후 Nginx는 HTTP 요청을 HTTPS로 308 redirect하고 HTTPS 443에서만 앱을 제공한다.

정기 갱신 systemd timer 설치는 `web/docs/production-deployment-runbook.md` 6.1절을 따른다.

## 4.6 Jetson 배포 및 서버 연결

### 4.6.1 dependency 준비

Python 3.10 기반 virtual environment를 생성하고 JetPack system package를 읽을 수 있게 만든다.

```bash
python3.10 -m venv .venv --system-site-packages
. .venv/bin/activate
python3 -m pip install -r requirements.txt
```

PyTorch, TorchVision, TensorRT, pycuda는 Jetson/JetPack 전용 package이므로 일반 PyPI package로 임의 교체하지 않는다.

### 4.6.2 소스 전송

개발 PC의 `embedded/jetson_main`에서 실행한다. Windows라면 MobaXterm이 아니라 Git Bash처럼 `bash`, `tar`, `ssh`가 있는 shell에서 실행한다.

```bash
cd embedded/jetson_main
scripts/deploy.sh ubuntu@{젯슨 도메인}
```

기본 원격 directory는 `~/jetson_final`이다.

### 4.6.3 Robot credential 환경변수

관리자 화면에서 Robot 등록 후 MQTT/Image credential을 발급한다. 다음 실제 값을 Jetson의 root-readable 환경 파일 또는 Secret 주입 경로에 저장한다. 값 자체는 이 문서에 적지 않는다.

(Credencial 회전 혹은 발급 시 할당 받고 다음 값들을 로봇에 삽입)
```bash
export MQTT_ID="________________"
export MQTT_HOST="________________"(브로커 URL)
export MQTT_PORT="8883"
export MQTT_USERNAME="________________"
export MQTT_CLIENT_ID="________________"
export MQTT_PASSWORD="________________"
export MQTT_CA_CERT="system"
export ROBOT_UUID="________________"
export UPLOAD_URL="https://{EC2도메인}/api/v1/images"
export UPLOAD_API_KEY="________________"
```

환경변수 작성 규칙:

- `MQTT_USERNAME`은 `MQTT_ID`와 같아야 Broker ACL과 일치한다.
- `ROBOT_UUID`는 DB `robot.robot_id` UUID이며 `MQTT_ID`가 아니다.
- `UPLOAD_URL`은 공개 HTTPS 전체 URL이다.
- `UPLOAD_API_KEY`는 해당 Robot에 발급된 기기별 Key다.
- `comms.yaml`의 `incident.model_version`은 현재 `0.0.0-stub`이므로 실제 export 버전으로 교체해야 한다.

### 4.6.4 실행

```bash
ssh ubuntu@{젯슨 도메인}
cd ~/jetson_final
scripts/drive.sh --live --comms-config comms.yaml
```

`--live`는 실제 모터를 움직인다. 카메라·주행 공간·비상 정지·조향 한계를 현장에서 확인하기 전 실행하지 않는다. `scripts/drive.sh` 없이 `main.py`를 직접 실행하면 Jetson의 `libcusparseLt.so.0` 경로가 빠질 수 있다.

## 4.7 DB migration과 dump 복구

- Backend 시작 시 Flyway가 migration을 실행한다.
- `hibernate.ddl-auto`는 `none`이다.
- migration 시작 뒤 자동 image rollback을 수행하지 않는다.
- 운영 dump를 복구할 때는 먼저 DB만 기동하고 dump를 복구한 뒤 Backend를 시작한다.
- 제출용 최신 dump 생성과 복구는 `exec/3_DB_덤프_생성_및_복구.md`를 따른다.

## 4.8 재배포와 rollback

재배포는 새 Commit SHA image를 build하고 `.env.prod`의 image tag를 갱신한 뒤 수행한다.

```bash
docker compose --env-file .env.prod -f compose.prod.yaml \
  up --detach --no-build --wait --wait-timeout 240
```

중요 사항:

- `docker compose down --volumes`와 `docker volume rm`을 운영에서 사용하지 않는다.
- `postgres_data`, `robot_images`, `report_pdf_data`, `mosquitto_data`, credential volume을 보존한다.
- Flyway migration 후에는 새 schema와 호환되는 Backend image만 사용한다.
- 배포 전에 DB/PDF/image/MQTT 상태 backup과 이전 image digest를 확보한다.
- 상세 rollback은 `web/docs/production-conditional-rollback.md`를 따른다.

## 5. Local Docker 실행

Local Docker는 개발 PC에서 Web 전체 구성을 실행하기 위한 환경이다. 운영 배포와 환경 파일 및 Compose 파일을 공유하지 않는다.

### 5.1 운영 환경과의 구분

| 구분 | Local Docker | 운영 배포 |
|---|---|---|
| 실행 위치 | 개발 PC의 `web` directory | 운영 Linux host의 `web` directory |
| Compose 파일 | `compose.yaml` | `compose.prod.yaml` |
| 환경 파일 | `.env.example`을 복사한 `.env` | `.env.prod.example`을 복사한 `.env.prod` |
| Backend profile | `local,docker` | `ec2,docker` |
| image 준비 | 개발 PC에서 `--build`로 생성 | CI가 생성한 Commit SHA image 사용 |
| Frontend 주소 | `http://localhost:3000` | `https://{EC2도메인}` |
| Backend 직접 주소 | `http://localhost:8080` | 외부에 직접 공개하지 않고 Nginx의 `/api`로 연결 |
| PostgreSQL | `127.0.0.1:5432` | Compose 내부 DNS `db:5432` |
| MQTT TLS | `127.0.0.1:8883`, Local CA 사용 | 운영 domain의 `8883`, 운영 인증서 사용 |
| HTTPS 인증서 | 사용하지 않음 | Certbot으로 발급 |

### 5.2 실행 위치와 환경 파일

Docker Desktop과 Docker Compose v2를 설치한다. 모든 명령은 저장소의 `web` directory에서 실행한다.

PowerShell에서 이동할 경우:

```powershell
cd C:\ssafy\I15D101T\web
Copy-Item .env.example .env
```

Git Bash에서 이동할 경우:

```bash
cd /c/ssafy/I15D101T/web
cp .env.example .env
```

`.env`에서 `VITE_NAVER_MAP_CLIENT_ID`에 Naver Cloud Platform에서 발급받은 Maps Client ID를 입력한다. Local 기본 포트는 다음과 같으며 이미 사용 중인 포트가 있으면 `.env`의 값을 변경한다.

| 환경변수 | 기본값 | 용도 |
|---|---:|---|
| `FRONTEND_PORT` | `3000` | 브라우저 접속 및 `/api` proxy |
| `BACKEND_PORT` | `8080` | Backend 직접 접속 |
| `POSTGRES_PORT` | `5432` | Local DB 접속 |
| `MQTT_TLS_PORT` | `8883` | Local MQTT TLS 접속 |

### 5.3 Local Secret 파일 생성

`.env`에는 비밀번호나 Key 원문을 넣지 않는다. Git Bash에서 다음과 같이 Compose가 읽을 Local 전용 Secret 파일을 생성한다.

```bash
mkdir -p secrets

openssl rand -hex 32 > secrets/postgres_admin_password
openssl rand -hex 32 > secrets/postgres_flyway_password
openssl rand -hex 32 > secrets/postgres_app_password
openssl rand -hex 32 > secrets/mqtt_backend_password
openssl rand -base64 48 > secrets/gilbom_storage_signing_key
openssl rand -base64 48 > secrets/gilbom_auth_rate_limit_hmac_key
printf '1=%s\n' "$(openssl rand -base64 32 | tr -d '\n')" > secrets/gilbom_mfa_totp_keyring
openssl rand -base64 18 | tr -d '\n' > secrets/gilbom_initial_admin_password
: > secrets/gilbom_llm_api_key
```

`INITIAL_ADMIN_BOOTSTRAP_ENABLED=false`이면 관리자 email, 표시 이름, 사용자 코드는 비워 둔다. GMS를 사용하지 않는 Local 기본 구성에서는 `GILBOM_LLM_ENABLED=false`, `GILBOM_LLM_MODEL=`을 유지하고 `gilbom_llm_api_key`도 빈 파일로 둔다.

### 5.4 전체 서비스 실행

`web` directory에서 Local 전용 `compose.yaml`을 사용하여 PostgreSQL, Mosquitto, Backend, Frontend를 함께 실행한다.

```bash
docker compose up --build -d
```

실행 후 브라우저 접속 주소는 `http://localhost:3000`이다. Frontend Nginx가 `/api` 요청을 Backend로 전달하므로 일반 화면 사용 시에는 `8080` 포트로 직접 접속할 필요가 없다.

특정 애플리케이션 image만 다시 만들 때는 서비스명을 마지막에 지정한다.

```bash
docker compose up --build -d frontend
docker compose up --build -d backend
```

`frontend`는 Backend에 의존하고 Backend는 PostgreSQL과 Mosquitto에 의존하므로 필요한 서비스가 함께 기동된다. 위 명령은 사용자 home(`~`)이 아니라 `compose.yaml`이 있는 `web` directory에서 실행해야 한다.

### 5.5 Local 서비스 중지와 재실행

컨테이너만 중지할 때:

```bash
docker compose stop
```

컨테이너와 Local network를 내릴 때:

```bash
docker compose down
```

`docker compose down`은 PostgreSQL, 보고서 PDF, Robot image를 저장하는 named volume을 유지한다. `.env`, Backend·Frontend 소스 또는 image build 설정을 변경한 뒤에는 `docker compose up --build -d`를 다시 실행한다.
