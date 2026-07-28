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
| V19 | 결함-로봇 연결·내보내기 job | `defect_observation.robot_id`로 결함을 로봇까지 추적해 `user_robot_assignment` 객체 범위와 `robotId` 필터를 SQL에서 적용하고, `POST /api/v1/defects/export`의 202 접수를 기록. 상세는 [defect-schema.md](defect-schema.md) 참고 |


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


Flyway가 정상 적용되면 `flyway_history.flyway_schema_history`에 V1~V19가
`success=true`로 기록된다.

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
