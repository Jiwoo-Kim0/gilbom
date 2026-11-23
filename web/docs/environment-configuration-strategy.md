# 환경별 설정 전략

- 문서 상태: 확정
- 적용 범위: Spring Boot, Nginx, PostgreSQL, MQTT Adapter·Broker, Private S3, LLM, CI/CD
- 관련 보안 정책: [`threat-model.md`](./threat-model.md)

## 1. 원칙

- 설정값과 Secret을 분리한다.
- 동일한 애플리케이션 이미지를 환경별 설정만 바꾸어 배포한다.
- 환경 이름은 `local`, `test`, `docker`, `staging`, `prod` 중 정확히 하나를 사용한다.
- `docker`는 로컬 통합 개발용이며 운영 환경을 뜻하지 않는다.
- 운영 Secret은 Git, `.env`, Compose YAML, Docker image, 프론트 번들에 넣지 않는다.
- 운영은 AWS workload IAM role, Secrets Manager, KMS를 사용한다.
- 안전하지 않은 운영 설정은 기본값으로 보완하지 않고 시작 단계에서 실패시킨다.
- 설정 변경과 Secret 회전은 재빌드보다 재배포·재시작으로 반영한다.

## 2. 설정 우선순위

낮은 우선순위부터 다음 순서로 적용한다.

1. `application.yml`의 비민감 공통 기본값
2. `application-{profile}.yml`의 환경별 비민감 설정
3. 배포 환경변수의 비민감 override
4. Secrets Manager·KMS에서 런타임에 주입한 Secret

운영 Secret을 command line argument나 `JAVA_TOOL_OPTIONS`로 전달하지 않는다. 프로세스 목록, 빌드 로그와 장애 수집 파일에 노출될 수 있기 때문이다.

Spring profile은 `SPRING_PROFILES_ACTIVE`로 선택하되 운영 배포 주체만 값을 설정한다. 여러 base profile을 동시에 활성화하지 않는다.

## 3. 환경 매트릭스

| 항목 | local | test | docker | staging | prod |
|---|---|---|---|---|---|
| 목적 | IDE 개발 | 자동 테스트 | 로컬 전체 구동 | 운영 전 검증 | 실제 운영 |
| Spring profile | `local` | `test` | `docker` | `staging` | `prod` |
| DB | localhost PostgreSQL | Testcontainers PostgreSQL | Compose PostgreSQL | 격리 PostgreSQL | 운영 PostgreSQL |
| DB schema | 개발 migration | 테스트마다 초기화 | migration 적용 | 운영과 동일 migration | 승인 migration |
| Session | 인메모리 허용 | 인메모리·테스트 저장소 | JDBC 권장 | Spring Session JDBC | Spring Session JDBC |
| Cookie Secure | `false` | 테스트별 | `false` | `true` | `true` |
| Cookie name | `GILBOM_SESSION` | `GILBOM_SESSION` | `GILBOM_SESSION` | `__Host-GILBOM_SESSION` | `__Host-GILBOM_SESSION` |
| HTTPS | 선택 | 테스트 | localhost HTTP | 필수 | 필수 |
| S3 | 비활성 또는 개발 bucket | fake/mock | 비활성 또는 개발 bucket | 전용 Private bucket | 운영 Private bucket |
| MQTT | 로컬 Broker 선택 | fake broker/container | Compose·개발 Broker | 전용 TLS Broker | EC2 Mosquitto TLS |
| LLM | 기본 비활성 | fake client | 기본 비활성 | 승인된 비운영 Key | 승인 시 활성 |
| Secret 출처 | 로컬 `.env`, Git 제외 | test fixture·임시값 | 로컬 `.env`, Git 제외 | Secrets Manager·KMS | Secrets Manager·KMS |
| 로그 | DEBUG, body 금지 | 필요 최소 | INFO | INFO | INFO/WARN |
| health 상세 | localhost만 허용 | 테스트 | 내부 접근 | 내부 운영망 | 내부 운영망 |
| 외부 데이터 | 사용 금지 | synthetic | 사용 금지 | 비식별 샘플 | 운영 데이터 |

staging은 prod와 같은 인증·쿠키·IAM·네트워크 정책을 사용하지만 DB, bucket, MQTT credential, LLM Key와 도메인은 완전히 분리한다.

## 4. Profile별 계약

### 4.1 `local`

- 개발자가 Backend를 IDE에서 직접 실행하는 환경이다.
- `.env`는 로컬 편의용이며 `.gitignore` 상태를 유지한다.
- `.env.example`에는 동작 가능한 Secret이나 실제 endpoint를 넣지 않는다.
- LLM, S3, MQTT가 없어도 해당 기능을 명시적으로 비활성화해 나머지 API를 실행할 수 있다.
- 운영 관리자·로봇 계정과 실제 데이터를 사용하지 않는다.

### 4.2 `test`

- 외부 네트워크 호출을 기본 차단한다.
- PostgreSQL 호환성이 필요한 테스트는 Testcontainers PostgreSQL을 사용한다.
- S3, MQTT, LLM은 fake 또는 container로 대체하며 실제 AWS·LLM credential을 읽지 않는다.
- 테스트 Secret은 실행마다 생성하고 테스트 종료 후 폐기한다.
- 시간·UUID·TOTP clock은 테스트에서 제어 가능하게 주입한다.

### 4.3 `docker`

- 현재 `compose.yaml`을 이용한 로컬 통합 환경이다.
- DB·Backend·Nginx는 loopback에만 bind하며 운영 노출을 가정하지 않는다.
- `SPRING_PROFILES_ACTIVE=docker`를 사용한다.
- `.env`에는 로컬 DB 비밀번호만 두며 팀 공용·운영 Secret을 넣지 않는다.
- 빈 `compose.prod.yaml`은 운영 배포 정본이 아니다.

### 4.4 `staging`

- prod와 동일한 Secure cookie, HTTPS, 세션 저장소, CSRF, MFA, IAM·Secret 주입 방식으로 검증한다.
- 별도 AWS resource prefix·bucket·DB·MQTT principal·LLM Key를 사용한다.
- 운영 데이터를 복사하지 않고 synthetic 또는 비식별화된 데이터를 사용한다.
- 배포 전 migration, 권한 매트릭스, Presigned URL, Secret scan, LLM 금지 데이터 테스트를 수행한다.

### 4.5 `prod`

- HTTPS와 `__Host-GILBOM_SESSION`의 `Secure=true`를 강제한다.
- 정확한 관리자 Origin만 허용하고 CORS wildcard와 credential 조합을 금지한다.
- DB·Backend·Actuator 관리 포트는 인터넷에 공개하지 않는다.
- AWS 정적 Access Key가 존재하면 시작을 거부한다.
- LLM은 공급자 보존·학습 정책과 budget이 승인된 경우에만 활성화한다.
- debug, SQL parameter, request/response body logging을 금지한다.
- Secret·권한·배포 변경은 CloudTrail·Jenkins·감사 로그로 추적한다.

## 5. 설정 카탈로그

환경변수는 Spring relaxed binding을 고려해 대문자 `UPPER_SNAKE_CASE`를 사용한다. Secret 여부가 `Secret`인 값은 운영 환경변수에 원문으로 두지 않고 Secret 참조 또는 런타임 주입을 사용한다.

### 5.1 공통·웹

| 환경변수 | Secret | 기본·규칙 |
|---|:---:|---|
| `SPRING_PROFILES_ACTIVE` |  | 허용된 환경 하나, 기본 운영값 없음 |
| `SERVER_PORT` |  | 기본 `8080`, 외부 직접 공개 금지 |
| `GILBOM_PUBLIC_BASE_URL` |  | 환경별 HTTPS 외부 URL |
| `GILBOM_FRONTEND_ORIGIN` |  | 정확한 단일 Origin 또는 승인 목록 |
| `GILBOM_CORS_ENABLED` |  | 기본 `false`; Nginx same-origin이면 비활성 유지 |
| `GILBOM_CORS_ALLOWED_ORIGINS` |  | 활성화 시 exact `http(s)` Origin 목록 필수, wildcard 금지 |
| `GILBOM_CORS_ALLOW_CREDENTIALS` |  | 세션 cookie 사용 시 `true`, wildcard와 조합 불가 |
| `GILBOM_REQUEST_ID_HEADER` |  | `X-Request-Id` 고정 |
| `GILBOM_MAX_REQUEST_BODY_SIZE` |  | 전체 HTTP 요청 본문 상한, 기본 `1MB` |
| `GILBOM_MAX_REQUEST_HEADER_SIZE` |  | Cookie를 포함한 요청 헤더 상한, 기본 `16KB` |
| `GILBOM_MAX_UPLOAD_FILE_SIZE` |  | multipart 파일 1개 상한, 기본 `500KB` |
| `GILBOM_MAX_JSON_BYTES` |  | API별 상한의 공통 방어값 |

### 5.2 DB

| 환경변수 | Secret | 기본·규칙 |
|---|:---:|---|
| `SPRING_DATASOURCE_URL` |  | 환경별 PostgreSQL URL, 운영 TLS 적용 |
| `SPRING_DATASOURCE_USERNAME` |  | 애플리케이션 전용 사용자 |
| `SPRING_DATASOURCE_PASSWORD` | O | staging/prod Secrets Manager 주입 |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` |  | 모든 공유 환경 `none`, 자동 schema 변경 금지 |
| `GILBOM_DB_MAX_POOL_SIZE` |  | 환경 용량에 맞춰 제한 |
| `GILBOM_DB_STATEMENT_TIMEOUT` |  | 무제한 query 금지 |

운영 DB 계정은 schema owner나 superuser가 아니며 애플리케이션 CRUD 권한과 migration 권한을 분리한다.

### 5.3 세션·인증

| 환경변수 | Secret | 기본·규칙 |
|---|:---:|---|
| `GILBOM_SESSION_IDLE_TIMEOUT` |  | `30m` |
| `GILBOM_SESSION_ABSOLUTE_TIMEOUT` |  | `8h` |
| `GILBOM_SESSION_MAX_CONCURRENT` |  | `1` |
| `GILBOM_MFA_CHALLENGE_TIMEOUT` |  | `5m` |
| `GILBOM_MFA_CHALLENGE_MAX_FAILURES` |  | `5` |
| `GILBOM_SESSION_CLEANUP_INTERVAL` |  | 만료 보안 세션 정리 주기 `1m` |
| `GILBOM_SESSION_CLEANUP_BATCH_SIZE` |  | 한 번에 최대 `1000` |
| `GILBOM_TOTP_ISSUER` |  | 환경별 구분 가능한 표시명 |
| `GILBOM_TOTP_KMS_KEY_ARN` |  | staging/prod 전용 KMS key ARN |
| `AUTH_IP_MAX_REQUESTS` |  | 고정 `20` |
| `AUTH_IP_WINDOW` |  | 고정 `1m` |
| `AUTH_PASSWORD_MAX_FAILURES` |  | 고정 `5` |
| `AUTH_PASSWORD_FAILURE_WINDOW` |  | 고정 `15m` |
| `AUTH_MFA_MAX_FAILURES` |  | 고정 `5` |
| `AUTH_MFA_FAILURE_WINDOW` |  | 고정 `15m` |
| `AUTH_TEMPORARY_LOCK_DURATION` |  | 비밀번호·MFA 제한 모두 고정 `15m` |
| `INITIAL_ADMIN_BOOTSTRAP_ENABLED` |  | 기본 `false`, 빈 DB 최초 생성 때만 `true` |
| `INITIAL_ADMIN_EMAIL` | O | 초기 관리자 이메일, bootstrap 외 사용 금지 |
| `INITIAL_ADMIN_PASSWORD` | O | 10~24자 임시 비밀번호, 최초 생성 후 Secret 폐기 |
| `INITIAL_ADMIN_DISPLAY_NAME` |  | 앞뒤 공백 없는 최대 100자 |
| `INITIAL_ADMIN_USER_CODE` |  | 앞뒤 공백 없는 최대 30자 |
| `AUTH_RATE_LIMIT_HMAC_KEY_VERSION` |  | HMAC key 회전 버전, 양의 정수 |
| `AUTH_RATE_LIMIT_HMAC_KEY_BASE64` | O | 최소 32-byte key의 Base64, staging/prod 외부 Secret |
| `AUTH_CSRF_TOKEN_ISSUANCE_MAX_REQUESTS` |  | 새 익명 세션 생성 방어, 고정 `60` |
| `AUTH_CSRF_TOKEN_ISSUANCE_WINDOW` |  | 고정 `1m` |
| `AUTH_EMAIL_AVAILABILITY_MAX_REQUESTS` |  | 기본 1분당 `20` |
| `AUTH_EMAIL_AVAILABILITY_WINDOW` |  | 기본 `1m` |
| `AUTH_REGISTRATION_MAX_REQUESTS` |  | 기본 1시간당 `5` |
| `AUTH_REGISTRATION_WINDOW` |  | 기본 `1h` |
| `AUTH_RATE_LIMIT_CLEANUP_INTERVAL` |  | 만료 bucket 정리 주기, 기본 `10m` |
| `AUTH_RATE_LIMIT_CLEANUP_BATCH_SIZE` |  | 한 번에 정리할 최대 bucket 수, 기본 `1000` |

TOTP KMS key 자체를 환경변수에 넣지 않고 ARN만 설정한다.
초기 관리자 Secret은 최초 생성 후 `INITIAL_ADMIN_BOOTSTRAP_ENABLED=false`로 되돌리고
배포 환경에서 제거한다.
Rate Limit HMAC key는 인스턴스 간 동일하게 주입하고 회전할 때 version을 함께 올린다.

### 5.4 AWS·S3

| 환경변수 | Secret | 기본·규칙 |
|---|:---:|---|
| `GILBOM_REPORT_PDF_STORAGE_ROOT` |  | local Docker는 `/app/data/report-pdf` 영속 volume, IDE/test 기본은 OS 임시 경로 |
| `GILBOM_REPORT_SSE_MAX_CONNECTIONS_PER_USER` |  | 기본 `3`, 사용자·다중 탭 연결 상한 |
| `GILBOM_REPORT_SSE_MAX_CONNECTIONS_TOTAL` |  | 기본 `200`, 단일 Backend emitter 상한 |
| `AWS_REGION` |  | 배포 region |
| `GILBOM_S3_ENABLED` |  | local/test/docker 기본 `false` |
| `GILBOM_S3_BUCKET` |  | 환경별 Private bucket |
| `GILBOM_S3_OBJECT_PREFIX` |  | 환경·서비스별 prefix |
| `GILBOM_S3_KMS_KEY_ARN` |  | 필요 시 bucket 암호화 key |
| `GILBOM_S3_UPLOAD_URL_TTL` |  | 기본 `10m`, 최대 `15m` |
| `GILBOM_S3_DOWNLOAD_URL_TTL` |  | 기본 `5m`, 최대 `10m` |

staging/prod에서는 `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`를 애플리케이션 설정으로 사용하지 않는다. workload role의 단기 credential만 사용한다.
승인 PDF 저장은 `ApprovedReportPdfStorage` 포트 뒤에 두므로 현재 local volume 구현을
Private S3 adapter로 바꿔도 상태 전이·DB 결합 규칙은 바뀌지 않는다. DB에는 내부
storage key와 SHA-256만 저장하며 presigned URL은 저장하지 않는다.
보고서 SSE heartbeat는 계약상 15초로 고정하며 연결 상한만 환경별로 낮출 수 있다.

### 5.5 MQTT

| 환경변수 | Secret | 기본·규칙 |
|---|:---:|---|
| `GILBOM_MQTT_ENABLED` |  | test 기본 `false` |
| `GILBOM_MQTT_BROKER_URI` |  | 운영 `ssl://...:8883` |
| `GILBOM_MQTT_CLIENT_ID` |  | 환경·인스턴스에서 고유 |
| `GILBOM_MQTT_USERNAME` |  | Backend 전용 principal |
| `GILBOM_MQTT_PASSWORD` | O | staging/prod Secrets Manager 주입 |
| `GILBOM_MQTT_TRUSTSTORE_PATH` |  | image에 공개 CA만 포함 가능 |
| `GILBOM_MQTT_KEYSTORE_SECRET_ID` |  | client private key Secret 참조 |
| `GILBOM_MQTT_CONNECT_TIMEOUT` |  | 유한 timeout |
| `GILBOM_MQTT_COMMAND_TTL` |  | operation별 상한 적용 |
| `MQTT_TELEMETRY_MAX_DELAY` |  | 기본 `60s`, 오래된 Telemetry 수신 차단 |
| `MQTT_STATE_MAX_DELAY` |  | 기본 `300s`, 단절 후 State 복원 허용 범위 |
| `MQTT_COMMAND_RESULT_MAX_DELAY` |  | 기본 `60s`, Result 기준시각 허용 지연 |
| `MQTT_FUTURE_TIMESTAMP_TOLERANCE` |  | 기본 `5s`, 기기 시계 미래 오차 상한 |

비밀번호와 private key는 property dump, health, actuator env, 로그에 노출하지 않는다.

### 5.6 LLM

| 환경변수 | Secret | 기본·규칙 |
|---|:---:|---|
| `GILBOM_LLM_ENABLED` |  | 기본 `false`; 정책 gate 평가 요청 |
| `GILBOM_LLM_PROVIDER` |  | `GMS`만 허용 |
| `GILBOM_LLM_BASE_URL` |  | `https://gms.ssafy.io/gmsapi/api.openai.com/v1`만 허용 |
| `GILBOM_LLM_MODEL` |  | 보고서 생성은 `gpt-5.4-mini`, `gpt-5.4`만 허용 |
| `GILBOM_LLM_API_KEY` | O | staging/prod Secrets Manager 주입 |
| `GILBOM_LLM_CONNECT_TIMEOUT` |  | 기본 `3s`, 최대 `10s` |
| `GILBOM_LLM_READ_TIMEOUT` |  | 기본 `30s`, 최대 `60s` |
| `GILBOM_LLM_MAX_OUTPUT_TOKENS` |  | `1..1500`, 보고서별 출력 상한 |
| `GILBOM_LLM_DAILY_REQUEST_LIMIT` |  | 양수인 환경별 요청 상한 |
| `GILBOM_LLM_DAILY_BUDGET` |  | 양수인 일별 크레딧 승인 예산 |
| `GILBOM_LLM_MAX_CONCURRENT_REQUESTS` |  | 기본 `5`, 인스턴스별 `1..20` 동시 외부 호출 상한 |
| `GILBOM_LLM_PROMPT_VERSION` |  | 운영 `report-v3`; 롤백용 `report-v1`, `report-v2`도 허용 |
| `GILBOM_LLM_POLICY_APPROVED` |  | 전체 LLM 운영 정책 승인 여부 |
| `GILBOM_LLM_TRAINING_EXCLUSION_APPROVED` |  | 입력·출력 학습 제외 확인 여부 |
| `GILBOM_LLM_DATA_RETENTION_APPROVED` |  | 보존 기간·삭제 정책 승인 여부 |
| `GILBOM_LLM_OUTBOUND_ALLOWLIST_APPLIED` |  | `gms.ssafy.io:443` egress 적용 확인 여부 |
| `GILBOM_LLM_IMAGE_INPUT_ENABLED` |  | 운영 `true`; 대표 결함 JPEG 전송 기능 flag |
| `GILBOM_LLM_IMAGE_DEIDENTIFICATION_APPROVED` |  | 운영 `true`; 얼굴·차량번호·EXIF 등 이미지 비식별화 검증 승인 |

`GILBOM_LLM_ENABLED=true`는 외부 호출을 강제하는 값이 아니다. API Key, provider, 정확한 base URL, 모델, timeout, 출력·동시성 상한, 일별 요청 한도·예산, Prompt 버전, 학습 제외·보존·outbound·전체 정책 승인을 모두 통과해야 `GmsLlmClient`를 만든다. 하나라도 빠지면 서버 기동과 일반 보고서 API는 유지하고 `DisabledLlmClient`와 템플릿 생성만 사용한다. 일별 요청 한도는 사용자별 실제 외부 호출 수에 적용되고, 일별 budget은 환경 전체 호출에 적용된다.

API Key 원문, `Authorization` 헤더, Prompt와 응답 원문은 로그하지 않는다. 사용자 요청은 모델, base URL, System Prompt를 받지 않으며 서버 설정과 `LlmPromptCatalog`만 이 값을 결정한다. 상세 설계와 판정표는 [LLM Client·정책 게이트](./llm-client-policy.md)를 따른다.

이미지 입력은 별도 하위 gate다. 두 이미지 flag가 모두 `true`일 때만
`DefectReportImageService`가 사용자 접근 범위 안에서 선택한 비식별화 완료 JPEG
한 장을 메모리에서 Base64로 변환한다. 어느 하나라도 `false`이거나 조회 결과가
빈 목록이면 텍스트 LLM 생성은 유지하고 이미지 전송만 생략한다.

### 5.7 로그·관리 endpoint

| 환경변수 | Secret | 기본·규칙 |
|---|:---:|---|
| `LOGGING_LEVEL_COM_GILBOM` |  | local `DEBUG`, 그 외 `INFO` |
| `GILBOM_HTTP_BODY_LOGGING_ENABLED` |  | 모든 환경 `false` |
| `MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS` |  | 외부 노출은 `never` |
| `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` |  | `health,info,prometheus`만 |
| `GILBOM_AUDIT_RETENTION_DAYS` |  | 기본 `365` |

Actuator `env`, `configprops`, heap dump, thread dump는 공개하지 않는다.
`prometheus`는 Prometheus 수집용으로만 열려 있다. Nginx가 `/actuator`를 프록시하지 않고
Backend는 port를 publish하지 않으므로 internal `app` network 밖에서는 닿지 않는다.

## 6. 시작 시 Fail-fast 검증

애플리케이션은 시작 시 다음을 검사한다.

- profile이 허용 목록 중 정확히 하나인지
- staging/prod에서 HTTPS base URL, Secure cookie와 정확한 Origin이 설정됐는지
- prod에서 session store가 JDBC인지
- prod에서 기본·placeholder DB password가 아닌지
- AWS 정적 Access Key가 staging/prod 설정에 존재하지 않는지
- S3 활성 시 bucket, region, TTL과 workload role 권한이 유효한지
- MQTT 활성 시 TLS URI, client ID, credential과 topic 설정이 유효한지
- LLM 활성 요청 시 승인 provider·model·Secret·timeout·budget·데이터 정책·outbound 적용이 모두 있는지
- Secret 값이 public config나 로그 출력 대상에 포함되지 않았는지

필수 통합 기능이 활성화됐는데 설정이 잘못된 경우 기능을 묵시적으로 우회하지 않고 시작을 실패시킨다. LLM은 보고서 조회·수정과 분리된 선택 기능이므로 설정이 잘못되어도 서버를 실패시키지 않고 외부 호출을 닫은 뒤 `TEMPLATE` 방식으로 제한한다. 그 밖의 선택 기능이 명시적으로 비활성화된 경우에는 해당 API를 `503` 또는 기능 미제공 응답으로 닫고 나머지 서비스는 시작할 수 있다.

## 7. 배포·회전

- 배포 산출물은 환경 중립적으로 한 번 빌드하고 동일 image digest를 staging에서 검증한 뒤 prod로 승격한다.
- DB migration은 애플리케이션 시작과 분리된 승인 작업으로 수행한다.
- Secret 값은 Jenkins parameter나 build log로 전달하지 않고 배포 대상의 Secret 참조만 바꾼다.
- Secret 회전 후 관련 connection pool·MQTT·LLM client를 안전하게 재생성하거나 rolling restart한다.
- 설정 변경자는 변경 이유와 대상 환경을 남기고 staging/prod 변경은 최소 1인의 검토를 거친다.
- rollback은 이전 image digest와 호환 가능한 설정·migration 범위에서 수행한다.

## 8. 환경별 인수 조건

- local/test/docker가 운영 AWS·LLM Secret 없이 실행·테스트됨
- test가 실제 외부 네트워크를 호출하지 않음
- staging에서 prod와 동일한 Secure cookie·CSRF·MFA·JDBC session이 검증됨
- staging/prod AWS resource와 Secret이 완전히 분리됨
- prod 시작 시 안전하지 않은 cookie·CORS·profile·Secret 설정이 fail-fast됨
- Git, `.env.example`, image, 프론트 bundle, Jenkins log에서 Secret scan 통과
- Actuator·DB·Backend 포트가 외부에 노출되지 않음
- 운영 LLM 활성화 전에 데이터 보존·학습 제외·비용 상한이 승인됨

이 기준을 만족해야 환경 설정 작업을 완료로 본다.
