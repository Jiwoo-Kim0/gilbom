# 길봄(GILBOM) 핵심 실행·배포·시연 안내

길봄은 자율주행 로봇이 보행로를 순찰하면서 점자블록 파손·미설치 구역과 보행 장애물을 탐지하고, 관제 웹에서 위치·사진·이동 궤적·로봇 상태를 실시간으로 확인하는 서비스입니다.

이 문서는 다음 자료의 핵심만 요약합니다.

- [시연 시나리오 및 기기 세팅](<exec/시연시나리오_화면안내 및 기기 세팅.docx>)
- [외부 서비스 정보](exec/2_외부_서비스_정보.md)
- [GitLab clone 이후 빌드 및 배포](<exec/gitlab 소스 클론 이후 빌드 및 배포 정리 문서.md>)
- [DB dump](exec/3_DB_dump.sql)

> 계정, 비밀번호, API Key, Webhook URL, 인증서 Private Key는 이 문서나 Git에 기록하지 않습니다.

## 1. 시스템 구성

```text
Browser ──HTTPS 443──> Nginx ──/api──> Spring Backend ──JDBC──> PostgreSQL/PostGIS
Robot   ──MQTTS 8883─────────────────> Mosquitto ─────────────> Backend
Robot   ──HTTPS 이미지 업로드─────────> Nginx ────────────────> Backend
Backend ──HTTPS──> SSAFY GMS(승인된 경우)
Browser ──HTTPS──> Naver Maps API
```

- 운영 외부 공개 포트: `80`(HTTPS 전환/ACME), `443`, `8883`
- Backend `8080`과 DB `5432`는 운영 호스트에 직접 공개하지 않습니다.
- DB, 로봇 이미지, 보고서 PDF, MQTT 데이터는 Docker named volume에 보존됩니다.

### 주요 버전

| 구분 | 버전 |
|---|---|
| Backend | Java 21, Spring Boot 3.5.16, Gradle 8.14.3 |
| Frontend | React 19.2.8, Node 24.18.0, npm 11.16.0 |
| Web/DB/MQTT | Nginx 1.30.4, PostgreSQL 16 + PostGIS 3.5, Mosquitto 2.1.2 |
| Robot | Jetson Orin Nano, JetPack L4T R36.4.7, Python 3.10.12 |

## 2. 로컬 실행

Docker Desktop(또는 Docker Engine)과 Docker Compose v2가 필요합니다. Git Bash/Linux 기준으로 실행합니다.

```bash
cd web
cp .env.example .env
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

docker compose up --build -d
```

`.env`의 `VITE_NAVER_MAP_CLIENT_ID`를 설정하면 지도 기능을 사용할 수 있습니다. 기본 접속 주소는 `http://localhost:3000`입니다.

```bash
docker compose ps
docker compose logs -f backend frontend
docker compose down
```

`docker compose down`은 volume을 유지하지만, `down -v`는 DB·이미지·보고서 데이터를 삭제하므로 주의합니다.

## 3. 운영 빌드 및 배포

### 3.1 소스 준비

```bash
git clone https://lab.ssafy.com/s15-webmobile3-sub1/S15P11D101.git
cd S15P11D101
git checkout master
git pull --ff-only origin master
git rev-parse HEAD
```

운영 서버에는 Docker Engine/Compose v2, Git, OpenSSL, `sha256sum`, POSIX `sh`가 필요합니다. DNS와 `80/443` 인바운드를 준비하고, `8883`은 로봇 출발지 CIDR에만 허용합니다.

### 3.2 환경 및 Secret

```bash
cd web
cp .env.prod.example .env.prod
chmod 0600 .env.prod
```

- `.env.prod`에는 Secret 원문이 아니라 `*_SECRET_FILE` 경로만 기록합니다.
- `CERTBOT_EMAIL`, 운영 도메인, image tag, Naver Maps Client ID 등을 실제 환경에 맞게 설정합니다.
- 운영 Secret은 `infra/scripts/generate-production-secrets.sh`로 준비합니다.
- `VITE_NAVER_MAP_CLIENT_ID`는 빌드 시 번들에 포함되므로 변경 후 Frontend image를 다시 빌드해야 합니다.

### 3.3 빌드와 기동

빌드 서버/Jenkins에서 Git Commit SHA를 고정 tag로 사용합니다.

```bash
export RELEASE_SHA="$(git rev-parse HEAD)"
export BACKEND_IMAGE="gilbom-backend:$RELEASE_SHA"
export FRONTEND_IMAGE="gilbom-frontend:$RELEASE_SHA"

docker compose --env-file .env.prod -f compose.build.yaml build backend frontend
```

운영 서버는 전달받은 동일 image tag를 `.env.prod`에 기록한 뒤 빌드 없이 기동합니다.

```bash
docker compose --env-file .env.prod -f compose.prod.yaml config --quiet
docker compose --env-file .env.prod -f compose.prod.yaml \
  up --detach --no-build --wait --wait-timeout 240
```

최초 인증서 발급:

```bash
sudo env DEPLOY_DIR="$PWD" COMPOSE_FILE="$PWD/compose.prod.yaml" \
  ENV_FILE="$PWD/.env.prod" sh infra/certbot/certificate.sh issue
```

핵심 주의사항:

- 운영 서버에서 임의로 image를 다시 빌드하지 않습니다.
- 기동 순서는 DB·Mosquitto → Backend → Frontend입니다.
- 빈 DB에서만 최초 관리자 bootstrap을 사용하고, 첫 로그인·MFA 등록 후 다시 비활성화합니다.
- Flyway migration 뒤에는 새 스키마와 호환되는 Backend만 사용합니다.
- 재배포 전에 DB/PDF/image/MQTT 데이터와 이전 image digest를 백업합니다.

## 4. 외부 서비스

| 서비스 | 용도 | 핵심 설정 |
|---|---|---|
| SSAFY GitLab | 형상 관리·clone | `master` 기준, 계정 또는 승인된 Token 사용 |
| Naver Cloud Maps | 로봇·결함·순찰 구역 지도 | `VITE_NAVER_MAP_CLIENT_ID`; 운영 도메인 등록 필요 |
| SSAFY GMS | LLM 결함 보고서 생성 | Backend에서만 호출; Key·모델·예산·정책 승인 필요 |
| Let's Encrypt | 운영 HTTPS 인증서 | 도메인 DNS, 80 포트, Certbot 이메일 필요 |
| Mattermost | Jenkins/Alertmanager 알림 | Incoming Webhook은 Secret으로 관리 |
| Maven/npm/OCI Registry | 의존성 및 image 다운로드 | 운영 프록시·Registry 정책 확인 |

GMS 조건이 충족되지 않으면 일반 보고서 API는 유지되고 템플릿 보고서로 대체됩니다. 로봇 이미지와 보고서 PDF는 현재 외부 스토리지가 아니라 local named volume에 저장됩니다.

## 5. 시연 순서

핵심 기능만 보여주면 약 3~4분, 전체 발표 큐시트는 약 7분입니다.

### 사전 점검

- Frontend, Backend, PostgreSQL, Mosquitto가 정상인지 확인합니다.
- 로봇이 `ONLINE`이고 Heartbeat가 최신인지 확인합니다.
- GPS, 카메라, AI 모델, 이미지 업로드 경로를 확인합니다.
- 시연용 순찰 구역·임무·장애물을 미리 준비합니다.
- 브라우저 배율을 80~90%로 맞추고 필요한 화면을 탭으로 열어 둡니다.
- MQTT 시뮬레이터, 테스트 탐지 이벤트, 녹화 영상을 대체 수단으로 준비합니다.

### 권장 흐름

1. 지도에서 순찰 구역을 조회합니다. 등록을 보여줄 경우 경계점 3개 이상으로 다각형을 만듭니다.
2. 로봇 위치, `ONLINE`, Heartbeat, 배터리, Jetson 온도를 확인합니다.
3. 온라인 로봇과 순찰 구역을 선택해 임무를 생성하고 시작합니다.
4. 지도에서 현재 위치와 GPS 이동 궤적이 누적되는지 확인합니다.
5. 일시 정지·재개·긴급 정지 상태 전이와 Telemetry 갱신을 확인합니다.
6. 장애물을 탐지해 사진·GPS·시각·로봇/임무 ID가 서버로 전송되는지 확인합니다.
7. 신규 알림·결함 건수·지도 마커를 확인하고 결함 상세와 보고서를 엽니다.
8. 임무 완료 후 최종 궤적, 탐지 기록, 배터리·온도·GPS 통계를 확인합니다.

시연 중에는 로봇 Credential 재발급 버튼을 누르지 않습니다. 실제 모터를 구동하는 `scripts/drive.sh --live`는 주행 공간·카메라·비상 정지·조향 한계를 확인한 뒤에만 실행합니다.

## 6. DB dump 요약

`exec/3_DB_dump.sql`은 PostgreSQL 16.9에서 생성된 plain SQL dump입니다.

- 앱 스키마: `audit`, `auth`, `defect`, `report`, `request_control`, `robot`, `session_store`, `storage`, `telemetry`
- 이력 스키마: `flyway_history`
- 확장: `postgis`, `postgis_topology`, `postgis_tiger_geocoder`, `fuzzystrmatch`, `pgcrypto`
- 역할: `gilbom_db_admin`(관리), `gilbom_flyway`(객체 소유/migration), `gilbom_app`(제한된 runtime DML)
- 앱/Flyway 기준 41개 테이블, 총 16,336행이 포함되어 있습니다.

### 주요 데이터 규모

| 데이터 | 행 수 |
|---|---:|
| 감사 로그 | 1,001 |
| 관리자 / 권한 / 역할 | 관리자 8, 권한 18, 역할 3 |
| 결함 / 관측 / 수집 오류 | 41 / 47 / 99 |
| 보고서 / 보고서 버전 | 19 / 20 |
| 임무 / 상태 이력 | 49 / 256 |
| 로봇 명령 / 명령 결과 | 190 / 324 |
| 저장 이미지 | 72 |
| 로봇 이벤트 / 상태 이력 / Telemetry | 307 / 3,148 / 10,337 |
| Flyway 적용 이력 | 43 |

### 복구 원칙

1. 기존 운영 데이터가 있다면 먼저 백업하고 Backend를 중지합니다.
2. PostGIS 3.5가 설치된 PostgreSQL 16 환경에 세 역할을 먼저 생성합니다.
3. 기존 객체가 없는 빈 DB(`template0` 기반 권장)에 관리자 계정으로 복구합니다.
4. 루트 디렉터리에서 다음과 같이 오류 발생 즉시 중단되도록 실행합니다.

```bash
psql -v ON_ERROR_STOP=1 -U gilbom_db_admin -d gilbom -f exec/3_DB_dump.sql
```

5. 확장 설치, Flyway 이력 43건, 주요 테이블 행 수와 권한을 확인한 뒤 Backend를 기동합니다.

dump에는 관리자 정보, 인증 해시·암호화 값, 로봇 Credential 해시, 감사 기록이 포함될 수 있습니다. 평문 비밀번호가 아니더라도 민감 데이터로 취급하고 외부 공유와 로그 노출을 금지합니다.

## 7. 최종 체크

- `docker compose ... config --quiet`가 성공합니다.
- 운영 URL에서 유효한 HTTPS 인증서를 확인합니다.
- 로봇이 MQTTS로 연결되고 Heartbeat가 갱신됩니다.
- 지도, 임무, 실시간 궤적, 결함 마커, 보고서가 정상 동작합니다.
- DB·이미지·PDF·MQTT volume 백업과 rollback image digest를 확보했습니다.
- 문서와 저장소에 Secret 원문이 없습니다.
