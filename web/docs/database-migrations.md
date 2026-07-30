# Database migrations

## 원칙

- PostgreSQL schema 변경은 `backend/src/main/resources/db/migration`의 Flyway version migration으로만 수행한다.
- 이미 적용된 `V*.sql`은 수정하지 않고 다음 version 파일을 추가한다.
- Hibernate `ddl-auto`는 `none`, Spring Session 자동 schema 초기화는 `never`로 유지한다.
- 모든 시각은 `TIMESTAMPTZ` 또는 Spring Session 표준 epoch milliseconds로 저장한다.
- 실제 비밀번호, TOTP secret, 복구 코드 원문은 migration과 seed에 넣지 않는다.

## DB 역할 분리

| 역할 | 용도 | 허용 범위 |
|---|---|---|
| `POSTGRES_ADMIN_USER` | 빈 DB bootstrap | 로컬 DB 초기화에만 사용, 백엔드에 전달하지 않음 |
| `POSTGRES_FLYWAY_USER` | schema owner·migration | schema/table/function 생성과 Flyway 이력 관리 |
| `POSTGRES_APP_USER` | Spring runtime | 명시적으로 부여된 업무 DML |

`audit.audit_log`의 runtime 권한은 `SELECT`, `INSERT`뿐이다. 보고서 version도
`SELECT`, `INSERT`만 허용하며 두 테이블 모두 DB trigger로 `UPDATE`, `DELETE`를
추가 차단한다. 인증 schema는 상태 컬럼으로 폐기 이력을 남기므로 runtime role에
물리 `DELETE` 권한을 부여하지 않는다.

## 초기 migration

| Version | 범위 | 설계 이유 |
|---|---|---|
| V1 | 관리자, Role, 관리자-Role, TOTP factor, 복구 코드 | 로그인 계정과 MFA 암호화·hash 구조 및 재사용 방지 metadata를 먼저 확정 |
| V2 | 보고서, 보고서 version, 감사 로그 | 변경 가능한 header와 불변 이력을 분리하고 감사 데이터를 append-only로 보호 |
| V3 | Spring Session JDBC, session security state | 재시작·다중 인스턴스에서도 세션을 공유하고 MFA 상태·절대 만료를 조회 가능하게 저장 |
| V4 | Permission/RBAC seed, 로그인 제한, 멱등 요청 | 세부 권한 매트릭스를 seed하고 인증 공격·중복 상태 변경을 DB에서 원자적으로 제어 |
| V5 | Argon2id·초기 비밀번호 상태 제약 | 비밀번호 hash를 Argon2id로 고정하고 `PASSWORD_CHANGE_REQUIRED` challenge를 세션 상태에 추가 |
| V6 | 계정·인증 저장 구조 완성 | 정규화 이메일 UNIQUE, 계정 생명주기·잠금, MFA/복구 코드와 challenge/session 정합성 제약 추가 |
| V7 | 계정 생성 경로 | 공개 가입 계정을 bootstrap 관리자로 오인하지 않도록 생성 origin과 단일 bootstrap 제약 추가 |
| V8 | 공개 인증 Rate Limit | 이메일 중복 확인과 회원가입 IP bucket을 로그인 제한과 분리 |
| V9 | 세션 수명 정책 | 유휴 30분·challenge 5분·절대 8시간 상한과 만료 조회 index 강제 |
| V10 | 인증 공격 방어 정책 | 비밀번호 5회·15분 계정 잠금, MFA 계정 bucket, 감사 action 조회 index 강제 |
| V11 | Robot, MQTT Credential, Telemetry 이력·현재 상태 | 로봇 통신에 필요한 기준정보·Credential 수명주기와 최신 데이터 보호 기반을 우선 생성 |
| V12 | defect 스키마 | 점자블록 결함·관측·처리 이력·병합 이력을 추가하고 처리 이력을 append-only로 보호. 상세는 [defect-schema.md](defect-schema.md) 참고 |
| V13 | 사용자 권한 예외·로봇 객체 범위 | ALLOW/DENY와 만료, VIEWER/OPERATOR/OWNER 배정, 제한 권한 및 마지막 활성 ADMIN 보호를 DB 제약과 trigger로 강제 |
| V14 | 권한 변경 세션 폐기 | 역할·권한·사용자 override·로봇 배정·계정 상태 변경과 같은 transaction에서 대상 사용자의 기존 session을 폐기 |
| V15 | Robot 외부 식별자 `mqtt_id` 통일 | DB UUID `robot_id`와 Topic·username·Payload의 외부 `mqtt_id`를 명확히 분리 |
| V16 | 가입 거절 상태 | 공개 가입 요청의 `REJECTED` 상태 전이와 관리자 처리 계약 지원 |
| V17 | MQTT Sequence 시작값 | State·Telemetry Sequence를 모든 계층에서 `1` 이상으로 강제 |
| V18 | Robot Boot Session·Status History | Presence·State·Telemetry의 Boot 수명주기, append-only 상태 이력, 현재 Boot FK와 Replay 보호 기반 추가 |
| V19 | 결함-로봇 연결·내보내기 job | `defect_observation.robot_id`로 결함을 로봇까지 추적해 `user_robot_assignment` 객체 범위와 `robotId` 필터를 SQL에서 적용하고, `POST /api/v1/defects/export`의 202 접수를 기록. 상세는 [defect-schema.md](defect-schema.md) 참고 |
| V20 | 런타임 세션 폐기 함수 실행 권한 | `PUBLIC`의 실행 권한은 계속 차단하고 `${applicationRole}`에 `auth.revoke_user_authorization_sessions(UUID)` 함수 하나의 `EXECUTE`만 부여해 관리자 MFA 초기화의 대상 세션 폐기를 허용 |
| V21 | Robot Command·Result 생명주기 | 명령 생성·MQTT 발행·ACK·완료·실패·거절·만료 상태와 append-only Result 저장 기반 추가 |
| V22 | 순찰·Mission·Event·Robot Image 정본 | Patrol Area/Route, 문자열 Mission 코드와 UUID PK 매핑, Telemetry Mission FK, 공통 Robot Event 멱등성, Mission 상태 이력, private JPEG 메타데이터와 통계 인덱스 추가 |
| V23 | Command·Defect의 Mission 정본 FK 연결 | Command의 Mission/Area와 Defect의 Area/Route, Observation의 Event/Image UUID를 실제 정본에 `NOT VALID` FK로 연결해 신규 데이터 무결성을 적용 |
| V24 | 시작 전 Mission 실패 시간 제약 보정 | `READY → FAILED` 준비 단계 실패에서 실제 주행 시작 시각인 `started_at`은 `NULL`을 허용하고 `ended_at`은 필수로 유지 |
| V25 | Robot별 활성 Mission 단일화 | mission_id가 없는 PAUSE·RESUME·CANCEL 명령도 대상을 결정적으로 찾도록 Robot마다 `READY`, `RUNNING`, `PAUSED` Mission을 하나로 제한 |
| V26 | 결함 통계 Query 인덱스 | 병합되지 않은 현재 결함의 전체 기간 및 지역·기간 통계를 위해 `last_seen_at`과 `patrol_area_id, last_seen_at` 기반 partial covering index 추가 |
| V27 | V23 제약 검증 승격 | V23이 `NOT VALID`로 걸어둔 Command·Defect·Observation의 FK 6개와 CHECK 1개를 `VALIDATE`해 기존 행까지 무결성을 보장하고 플래너가 제약을 신뢰하게 함. 근거는 [defect-schema.md](defect-schema.md) 3.0.1 |
| V28 | Incident 수집 무결성 보강 | `INC-YYYYMMDD-NNNNNN` 일자별 Counter와 신규 쓰기 형식 CHECK, GPS·Patrol Area 미확정 Incident 검토 대기열 및 Event·Mission·Image FK를 추가 |
| V29 | CSRF token 발급 Rate Limit bucket | 쿠키를 유지하지 않는 공개 `/csrf-token` 반복 호출이 익명 Spring Session 생성을 증폭시키지 못하도록 `IP_CSRF_TOKEN_ISSUANCE` HMAC IP bucket을 인증 Rate Limit 허용 타입에 추가 |
| V30 | 보고서 상태·버전·승인 불변성 보강 | 버전별 생성 방식·근거 snapshot, 승인 PDF 결합 정보, 생성 멱등 결과 추적을 추가하고 순차 버전·현재 버전 포인터·상태 전이를 DB trigger로 강제 |
| V31 | LLM 호출 quota·credit ledger | 사용자별 UTC 일일 호출 수와 전체 일일 credit을 원자적으로 예약하고 token·안전 실패 코드만 기록해 다중 인스턴스의 rate·budget 우회를 차단 |
| V32 | 보고서 LLM fallback 사유 | LLM 요청이 template으로 낮아진 원인을 고정 코드로 버전에 보존하되 Prompt·응답·예외 원문 저장을 차단 |

### V26 결함 통계 Query 인덱스

파일:
`backend/src/main/resources/db/migration/V26__add_statistics_query_indexes.sql`

V12의 `ix_defect_workflow_filter`는 `workflow_status`, `defect_type`,
`risk_level`이 선두라 기간만 지정하는 통계 Query에서 `last_seen_at` 범위를
효율적으로 좁히기 어렵고, `patrol_area_id`와 병합 여부가 Filter로 남는다.
V26은 실제 통계 SQL의 두 access path에 맞춰 다음 인덱스를 추가한다.

| 인덱스 | Key | INCLUDE | Partial 조건 | 대상 Query |
|---|---|---|---|---|
| `ix_defect_current_last_seen_stats` | `last_seen_at DESC, defect_id` | `patrol_area_id, defect_type, workflow_status` | `merged_into_defect_id IS NULL` | 상태·유형·UTC 날짜별 전체 기간 통계 |
| `ix_defect_current_area_last_seen_stats` | `patrol_area_id, last_seen_at DESC, defect_id` | `defect_type, workflow_status` | `merged_into_defect_id IS NULL` | 지역별 기간 통계 |

Partial 조건으로 통계에서 제외되는 병합 원본을 인덱스에 저장하지 않는다.
`defect_id`는 권한 범위 판정과 `COUNT(DISTINCT defect_id)`에 사용하고,
`defect_type`, `workflow_status`, `patrol_area_id`는 집계 시 heap 접근을 줄이기
위한 covering column이다. Index Only Scan 여부는 visibility map 상태에 따라
달라질 수 있다.

PostGIS Testcontainer 검증에서는 작은 Fixture의 자연 계획이 정상적으로
Sequential Scan을 선택했다. `enable_seqscan=off` 검증에서 전체 기간과
지역·기간 인덱스가 각각 대표 Query를 서비스할 수 있고, 지역과 기간 조건이
`Index Cond`에 포함되는 것을 확인했다. 실행계획 전문과 해석은
[patrol-mission-statistics-fixture.md](patrol-mission-statistics-fixture.md#결함-통계-실행계획)에
기록한다.

운영 반영 전에는 실제 Cardinality와 분포를 가진 스테이징 DB에서 자연
`EXPLAIN (ANALYZE, BUFFERS)`, p95 응답시간, 인덱스 크기와 쓰기 비용을 다시
측정한다. V26은 일반 `CREATE INDEX`를 사용하므로 큰 운영 테이블에 적용할 때는
DDL lock 허용 시간과 배포 창도 확인해야 한다.

### V29 CSRF token 발급 Rate Limit bucket

파일:
`backend/src/main/resources/db/migration/V29__limit_csrf_token_session_creation.sql`

V29는 `auth.authentication_rate_limit.limit_type`의 기존 CHECK 제약을 교체하여
`IP_CSRF_TOKEN_ISSUANCE`를 허용한다. 별도 테이블을 만들지 않고 V8·V10에서 구축한
영속 Rate Limit 저장소를 재사용하므로 다중 애플리케이션 인스턴스가 같은 차단 창을
공유하며, 클라이언트 IP 원문 대신 HMAC 값과 key version만 저장한다.

애플리케이션은 유효한 기존 세션이 없는 `GET /api/v1/auth/csrf-token` 요청만 이
bucket에 기록한다. 기존 세션의 새로고침·다중 탭 요청은 새 JDBC session row를 만들지
않으므로 차감하지 않는다. 세부 한도와 Nginx 선제 제한은
[인증 Rate Limit·잠금·감사 로그 구현](authentication-rate-limit-lock-audit.md#31-csrf-발급과-세션-저장소-보호)에
기록한다.

### V30 보고서 도메인 불변성 보강

파일:
`backend/src/main/resources/db/migration/V30__harden_report_domain_invariants.sql`

V30은 기존 V2의 변경 가능한 보고서 header와 append-only version 구조를 유지하면서,
보고서 생성·수정·승인 API가 필요로 하는 재현성과 DB 방어선을 보강한다.

#### 저장 필드

| 대상 | 추가 필드 | 저장 규칙 |
|---|---|---|
| `report.report_version` | `generation_method` | `MANUAL`, `TEMPLATE`, `LLM`, `SYSTEM`만 허용. 기존 version은 header의 값을 backfill한 뒤 `NOT NULL`로 전환 |
| `report.report_version` | `evidence_snapshot` | `defectIds`, `missionIds`, `patrolAreaIds` 배열을 가진 JSON object. 원본 리소스가 정리된 뒤에도 버전별 근거 ID를 재현 |
| `report.anomaly_report` | `approved_pdf_version_no`, `approved_pdf_storage_key`, `approved_pdf_sha256`, `approved_pdf_created_at` | 네 필드는 모두 `NULL`이거나 승인된 정확한 version에 함께 결합. SHA-256은 32 bytes이고 생성 시각은 승인 시각 이후여야 함 |
| `request_control.idempotency_request` | `resource_type`, `resource_id` | 둘 다 `NULL`이거나 함께 존재. 보고서 생성 성공 시 `REPORT`와 생성된 report UUID를 저장 |

승인 PDF version은 `(report_id, version_no)` 복합 외래 키로 실제
`report.report_version`을 참조한다. 내부 object storage key는 DB에 저장하지만 외부
API에는 노출하지 않고, PDF version·SHA-256·생성 시각만 동일성 확인에 사용한다.

보고서 생성 멱등 행은 다음 partial index로 24시간 만료 정리 대상을 찾는다.

```sql
CREATE INDEX ix_idempotency_report_expiry
    ON request_control.idempotency_request (expires_at)
    WHERE request_method = 'POST'
      AND request_path = '/api/v1/reports';
```

#### DB trigger 불변성

| Trigger | 강제 규칙 |
|---|---|
| `tr_report_version_sequence` | 첫 version은 1이고, 이후 version은 잠근 header의 `current_version_no + 1`만 허용 |
| `tr_report_version_current_pointer` | 새 version을 INSERT한 transaction이 commit되기 전에 header의 `current_version_no`가 그 version을 가리켜야 함 |
| `tr_anomaly_report_lifecycle` | 계약에 없는 상태 전이, version 증가 없는 본문 metadata 변경, `IN_REVIEW` 수정, 승인본 변경·삭제, 삭제본 재변경, 승인 PDF 교체를 거부 |

현재 version 역방향 검사는 header와 version을 같은 transaction에서 생성·변경할 수
있도록 `DEFERRABLE INITIALLY DEFERRED` constraint trigger로 commit 시점에 수행한다.
`REJECTED → DRAFT`는 새 version이 함께 생성된 경우만 허용하고, version pointer는
한 번에 정확히 1만 증가할 수 있다.

DB가 알 수 없는 요청 문맥 규칙인 `expectedVersionNo` 일치와 최신 version 작성자의
자기 승인은 서비스 계층에서 추가 검사한다. 상세 상태·API 계약은
[report-status-contract.md](report-status-contract.md)를 함께 적용한다.

### V31 LLM 호출 quota·credit ledger

파일:
`backend/src/main/resources/db/migration/V31__add_llm_invocation_quota_ledger.sql`

`request_control.llm_invocation_usage`는 실제 외부 호출 시도마다 한 행을 추가한다.
호출 전에 최대 입력 8,000 token과 설정된 최대 출력 token의 모델별 credit을
`STARTED`로 예약하며, 성공 usage가 있으면 실제 credit으로 정산한다. 실패·재시도와
usage 미제공 응답은 비용을 과소 계산하지 않도록 예약액을 유지한다.

| 저장 | 저장 금지 |
|---|---|
| UTC 사용일, 사용자 UUID, `GMS`, 허용 모델 | Prompt·System/Developer Prompt |
| `STARTED`, `SUCCEEDED`, `FAILED` | Provider 응답·보고서 본문 |
| 예약·정산 credit, input/output/total token | 이미지 bytes·Base64·data URL |
| 고정 실패 코드, 시작·완료 시각 | API Key·Authorization·URL·예외 원문 |

동일 UTC 날짜에 대한 `pg_advisory_xact_lock` 아래 사용자별 행 수와 전체
`accounted_credits` 합계를 확인하고 INSERT한다. 따라서 서로 다른 인스턴스에서 동시에
budget 마지막 잔액을 확인해도 한 transaction만 예약에 성공한다. 사용자·날짜와
날짜·모델 access path를 위한 두 인덱스를 둔다.

### V32 보고서 LLM fallback 사유

파일:
`backend/src/main/resources/db/migration/V32__persist_report_generation_fallback.sql`

`report.report_version.generation_fallback_reason`은 LLM 요청이 정책·quota·장애·응답
검증 때문에 template으로 낮아진 경우에만 저장한다. CHECK 제약은
`generation_method = 'TEMPLATE'`와 고정된 안전 코드 조합만 허용한다.
Prompt·provider 응답·이미지·내부 예외 메시지는 이 필드나 다른 보고서 필드에
저장하지 않는다.

시스템 Role `ADMIN`, `MANAGER`, `OPERATOR`와 18개 세부 permission 및
role-permission 관계는 결정적인 UUID로 seed한다. 실제
관리자 계정은 password hash와 MFA 등록 절차가 필요하므로 migration에서 만들지 않는다.

## 로컬 최초 실행

`.env.example`을 `.env`로 복사하고 bootstrap, Flyway, runtime 계정에 서로 다른
로컬 비밀번호를 설정한 뒤 실행한다.

```bash
docker compose up --build --detach
docker compose logs backend
```


Flyway가 정상 적용되면 `flyway_history.flyway_schema_history`에 V1~V32가
순서대로 `success=true`로 기록된다.

## 재실행 가능한 검증

빈 DB 계약 검증은 bootstrap 계정으로 PostGIS, Flyway 이력, Spring Session,
RBAC seed와 runtime 최소 권한을 확인한다.

```bash
docker compose exec --no-TTY db sh -ec \
  'PGPASSWORD="$POSTGRES_PASSWORD" psql --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" --set "flyway_role=$POSTGRES_FLYWAY_USER" \
  --set "application_role=$POSTGRES_APP_USER"' \
  < infra/postgres/verify-empty-database.sql
```

다음 검증은 실제 runtime role로 모든 schema 관계를 삽입한 뒤 rollback하므로
업무 데이터를 남기지 않는다.

```bash
docker compose exec --no-TTY db sh -ec \
  'PGPASSWORD="$POSTGRES_APP_PASSWORD" psql --host 127.0.0.1 \
  --username "$POSTGRES_APP_USER" --dbname "$POSTGRES_DB"' \
  < infra/postgres/verify-initial-schema.sql
```

계정 이메일 중복, 비-Argon2id hash, 계정 상태 모순, TOTP step 회귀, 동시 인증
세션처럼 거부돼야 하는 입력은 다음 부정 테스트로 검증한다.

```bash
docker compose exec --no-TTY db sh -ec \
  'PGPASSWORD="$POSTGRES_APP_PASSWORD" psql --host 127.0.0.1 \
  --username "$POSTGRES_APP_USER" --dbname "$POSTGRES_DB"' \
  < infra/postgres/verify-account-authentication-constraints.sql
```

세션의 유휴·challenge·절대 만료 상한, 동시 인증 세션과 부모 세션 삭제 cascade는
다음 rollback 검증으로 확인한다.

```bash
docker compose exec --no-TTY db sh -ec \
  'PGPASSWORD="$POSTGRES_APP_PASSWORD" psql --host 127.0.0.1 \
  --username "$POSTGRES_APP_USER" --dbname "$POSTGRES_DB"' \
  < infra/postgres/verify-session-lifecycle.sql
```

공개 가입의 승인 대기·역할 없음 상태와 새 Rate Limit type은 다음 rollback 검증으로
확인한다.

```bash
docker compose exec --no-TTY db sh -ec \
  'PGPASSWORD="$POSTGRES_APP_PASSWORD" psql --host 127.0.0.1 \
  --username "$POSTGRES_APP_USER" --dbname "$POSTGRES_DB"' \
  < infra/postgres/verify-public-registration.sql
```

비밀번호 실패 5회 잠금, 인증·MFA bucket type, 인증 감사 이벤트와 append-only
권한은 다음 rollback 검증으로 확인한다.

```bash
docker compose exec --no-TTY db sh -ec \
  'PGPASSWORD="$POSTGRES_APP_PASSWORD" psql --host 127.0.0.1 \
  --username "$POSTGRES_APP_USER" --dbname "$POSTGRES_DB"' \
  < infra/postgres/verify-authentication-defense.sql
```

사용자별 ALLOW/DENY, 로봇 객체 범위, 제한 권한 우회 차단과 마지막 활성 ADMIN
보호는 다음 rollback 검증으로 확인한다.

```bash
docker compose exec --no-TTY db sh -ec \
  'PGPASSWORD="$POSTGRES_APP_PASSWORD" psql --host 127.0.0.1 \
  --username "$POSTGRES_APP_USER" --dbname "$POSTGRES_DB"' \
  < infra/postgres/verify-rbac-scope.sql
```

append-only trigger 검증은 Flyway owner로 수행한다.

```bash
docker compose exec --no-TTY db sh -ec \
  'PGPASSWORD="$POSTGRES_FLYWAY_PASSWORD" psql --host 127.0.0.1 \
  --username "$POSTGRES_FLYWAY_USER" --dbname "$POSTGRES_DB"' \
  < infra/postgres/verify-append-only.sql
```
