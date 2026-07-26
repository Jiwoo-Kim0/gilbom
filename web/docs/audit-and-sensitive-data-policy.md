# 감사 로그·민감정보 처리 정책

- 문서 상태: 확정
- 구현 예정: Day 3 인증 감사 이벤트, Day 4 RBAC·관리자·업무 감사 이벤트
- DB 기준: `audit.audit_log`

## 1. 목적과 구분

감사 로그는 “누가, 언제, 어떤 대상에, 무엇을 시도했고, 결과가 무엇인지”를 사후 검증하기 위한 추가 전용 기록이다. 장애 분석용 애플리케이션 로그와 목적을 구분한다.

| 구분 | 감사 로그 | 애플리케이션 로그 |
|---|---|---|
| 목적 | 관리자·시스템 행위 추적 | 장애·성능·운영 분석 |
| 주요 키 | actor, action, target, result, requestId | level, logger, message, requestId |
| 변경·삭제 | 애플리케이션에서 수정·삭제 금지 | 보존 정책에 따라 순환·삭제 |
| 민감값 | 저장 금지 | 저장 금지 |
| 조회 권한 | `AUDIT_LOG_VIEW` | `SYSTEM_LOG_VIEW` 및 운영 인프라 권한 |

## 2. 원문 저장·기록 금지 기준

“저장 금지”는 업무 DB, 감사 로그, 애플리케이션 로그, 프록시 access log, APM·분산 추적, 오류 응답, 메트릭 label, 이벤트 payload 전체에 적용한다.

| 값 | 영구 저장 | 로그·추적 | 응답 |
|---|---|---|---|
| 비밀번호·임시 비밀번호 | 원문 금지, adaptive hash만 허용 | 항상 금지 | 요청에서만 수신, 다시 반환 금지 |
| API Key·MQTT 비밀번호 | 원문 금지, 검증용 hash와 Key ID만 허용 | 항상 금지 | 발급 직후 한 번만 허용 |
| 세션 ID·Cookie 헤더 | 지정 Spring Session 저장소만 예외 | 항상 금지 | `Set-Cookie`로만 전달 |
| CSRF Token | 세션 저장소만 허용 | 항상 금지 | 발급·인증 완료 응답에서 허용 |
| TOTP secret·`otpauthUri` | MFA 저장소에 암호화만 허용 | 항상 금지 | 등록 시 한 번만 허용 |
| TOTP·복구 코드 | 복구 코드 hash 외 원문 금지 | 항상 금지 | 입력 또는 최초 복구 코드 발급 시만 허용 |
| `Authorization`, `X-API-Key` 헤더 | 금지 | 항상 금지 | 반사 금지 |
| 인증서 private key | 애플리케이션 DB 저장 금지 | 항상 금지 | 반환 금지 |
| Presigned URL | 영구 저장 금지 | query string 포함 로그 금지 | 짧은 만료 응답만 허용 |

세션 ID는 서버 세션을 찾기 위해 Spring Session 전용 저장소에는 존재할 수 있다. 이를 사용자·감사·통계 테이블에 복제하거나 평문 로그에 기록하는 것은 금지한다.

API Key는 충분한 엔트로피의 난수로 생성하고, 조회용 Key ID와 검증용 hash를 분리한다. 새 키 원문은 `Cache-Control: no-store` 응답에서 한 번만 보여주며 다시 조회할 수 없다.

## 3. 공통 로그 필터링

다음 이름과 대소문자·구분자 변형은 값 전체를 `[REDACTED]`로 치환한다.

```text
password, passwd, passcode, token, access_token, refresh_token,
secret, api_key, x-api-key, authorization, credential,
cookie, set-cookie, session, session_id, csrf,
totp, otp, recovery_code, private_key, presigned_url
```

추가 규칙은 다음과 같다.

- 요청·응답 body 전체를 기본 로깅하지 않는다.
- 필요한 업무 필드는 명시적인 allowlist로 구조화해 기록한다.
- query string은 기본 제외한다. 검색어 등 개인정보 가능 값은 원문 대신 필터 존재 여부만 남긴다.
- `before_value`, `after_value`, `reason`에도 같은 재귀 필터를 적용한다.
- 예외 객체를 문자열로 통째로 기록하지 않는다. 안전한 예외 분류와 `requestId`만 구조화한다.
- SQL 문과 bind parameter는 운영 로그에 기록하지 않는다.
- 파일 바이트, Base64, 얼굴·차량번호 등 이미지 분석 원문을 기록하지 않는다.
- IP와 User-Agent는 보안 추적 목적으로 감사 로그에 저장할 수 있지만 화면에는 필요한 권한이 있는 사용자에게만 노출한다.

## 4. 감사 로그 필드 계약

기존 `audit.audit_log`의 필드를 다음 의미로 사용한다.

| 필드 | 필수 | 규칙 |
|---|:---:|---|
| `audit_id` | O | 서버 생성 증가 ID |
| `request_id` | 조건부 | HTTP 요청에서 발생하면 공통 `X-Request-Id`와 같은 UUID |
| `actor_user_id` | 조건부 | 인증된 관리자. 로그인 실패·시스템 작업처럼 알 수 없으면 `null` |
| `actor_type` | O | `USER`, `SYSTEM`, `ROBOT` |
| `action_type` | O | 아래 확정 action code |
| `target_type` | O | `USER`, `ROLE`, `ROBOT`, `MISSION`, `DEFECT`, `REPORT`, `SETTING`, `SESSION` 등 |
| `target_id` | 조건부 | 대상 내부 UUID. 대상이 없거나 식별 전 실패면 `null` |
| `before_value` | 조건부 | 변경 전 안전 필드의 최소 JSON snapshot |
| `after_value` | 조건부 | 변경 후 안전 필드의 최소 JSON snapshot |
| `reason` | 조건부 | 승인·반려·병합·권한 예외 등 업무 사유. 민감정보 필터 적용 |
| `result` | O | `SUCCESS`, `FAILURE`, `DENIED` |
| `ip_address` | 조건부 | 신뢰하는 프록시 체인에서 확정한 원격 IP |
| `user_agent` | 조건부 | 최대 500자, 제어문자 제거 |
| `created_at` | O | 서버 UTC 시각 |

`actor_user_id`는 행위자이고 `target_id`는 변경 대상이다. 다른 사용자의 권한을 변경할 때 두 값을 바꾸어 기록하지 않는다.

## 5. action code

action code는 `UPPER_SNAKE_CASE`로 고정하며 화면 문구로 재사용하지 않는다.

### 5.1 인증·세션

| action | 기록 시점 |
|---|---|
| `AUTH_LOGIN_SUCCEEDED` | 비밀번호와 MFA를 모두 통과한 때 |
| `AUTH_LOGIN_FAILED` | 비밀번호 검증 실패 또는 계정 사용 불가 |
| `AUTH_MFA_CHALLENGE_FAILED` | TOTP·복구 코드 검증 실패 |
| `AUTH_MFA_ENROLLED` | TOTP 등록 확인 완료 |
| `AUTH_MFA_RESET` | 사용자 또는 관리자가 MFA 초기화 |
| `AUTH_RECOVERY_CODE_USED` | 복구 코드 사용 |
| `AUTH_LOGOUT` | 사용자 로그아웃 |
| `AUTH_SESSION_REVOKED` | 비밀번호·권한·계정 변경 또는 관리자 조치로 세션 폐기 |
| `AUTH_RATE_LIMITED` | 로그인 또는 MFA 시도 제한 적용 |

실패 로그인에서 제출된 username, password, TOTP 값은 기록하지 않는다. 존재하는 사용자를 안전하게 식별할 수 있을 때만 대상 사용자 UUID를 `target_id`로 기록한다.

### 5.2 관리자·권한

| action | 기록 시점 |
|---|---|
| `ADMIN_USER_CREATED` | 관리자 계정 생성 |
| `ADMIN_USER_UPDATED` | 조직정보·활성 상태 변경 |
| `ADMIN_USER_DISABLED` | 계정 비활성화·소프트 삭제 |
| `RBAC_ROLE_CREATED` | 역할 생성 |
| `RBAC_ROLE_UPDATED` | 역할 속성 변경 |
| `RBAC_ROLE_DISABLED` | 역할 비활성화 |
| `RBAC_ROLE_PERMISSIONS_CHANGED` | 역할 권한 일괄 변경 |
| `RBAC_USER_ROLES_CHANGED` | 사용자 역할 일괄 변경 |
| `RBAC_USER_OVERRIDES_CHANGED` | 사용자 ALLOW/DENY 예외 변경 |
| `RBAC_ROBOT_ASSIGNMENTS_CHANGED` | 사용자 담당 로봇 변경 |
| `ADMIN_SETTING_CHANGED` | 운영 설정 변경 |

권한 변경의 `before_value`와 `after_value`에는 role ID, permission code, effect, expiresAt만 허용한다. 대상 사용자의 프로필·이메일을 중복 저장하지 않는다.

### 5.3 운영 업무

| action | 기록 시점 |
|---|---|
| `ROBOT_REGISTERED`, `ROBOT_UPDATED`, `ROBOT_DISABLED` | 로봇 관리 |
| `ROBOT_CREDENTIAL_ISSUED`, `ROBOT_CREDENTIAL_REVOKED` | 기기 인증정보 수명주기 |
| `ROBOT_COMMAND_ISSUED` | 관리자 명령 접수·저장 |
| `ROBOT_EVENT_ACKNOWLEDGED` | 경고 확인 |
| `MISSION_CREATED`, `MISSION_UPDATED` | 임무 생성·수정 |
| `DEFECT_UPDATED`, `DEFECT_STATUS_CHANGED` | 결함 정보·상태 변경 |
| `DEFECTS_MERGED`, `DEFECT_MERGE_REVERTED` | 결함 병합·취소 |
| `DEFECT_EXPORT_REQUESTED` | 결함 내보내기 요청 |
| `REPORT_CREATED`, `REPORT_UPDATED`, `REPORT_DELETED` | 보고서 수명주기 |
| `REPORT_SUBMITTED`, `REPORT_APPROVED`, `REPORT_REJECTED` | 보고서 상태 전이 |
| `REPORT_SCHEDULE_CHANGED` | 보고서 일정 생성·변경·중지 |
| `AUDIT_LOG_VIEWED` | 감사 로그 목록·상세 조회 |

`ROBOT_CREDENTIAL_ISSUED`에는 credential ID, type, 만료 시각과 fingerprint만 기록하고 secret 원문을 넣지 않는다. Presigned URL 발급 기록에는 file ID와 만료 시각만 넣고 URL을 넣지 않는다.

## 6. 결과와 트랜잭션

- 인가 단계에서 차단된 중요 변경 요청은 `DENIED`로 기록한다.
- 검증을 통과했지만 처리 중 실패한 중요 작업은 `FAILURE`로 기록한다.
- DB 상태 변경과 감사 로그는 가능한 한 같은 DB 트랜잭션에 저장한다.
- 감사 로그 저장에 실패하면 보안·권한·승인·명령 등 중요 변경은 함께 실패시킨다.
- 조회 API의 감사 로그 실패는 가용성을 위해 조회를 실패시키지 않을 수 있지만 별도 운영 경보를 생성한다.
- 비동기 작업의 `SUCCESS`는 “요청 접수·영속화 성공”을 뜻한다. MQTT 실행·파일 생성 같은 최종 결과는 별도 이벤트로 기록한다.
- 감사 로그 자체는 애플리케이션 API로 수정하거나 삭제할 수 없다.

## 7. 조회·보존

- 감사 로그 조회에는 `AUDIT_LOG_VIEW`가 필요하다.
- 감사 로그 조회 자체도 `AUDIT_LOG_VIEWED`로 기록하되 검색어 원문과 전체 결과를 저장하지 않는다.
- 기본 온라인 보존 기간은 365일로 한다.
- 보존 기간 변경과 법적 보존 요구는 운영 정책 변경으로 별도 승인한다.
- 백업에도 동일 접근 제어와 암호화를 적용한다.
- 사용자 계정 소프트 삭제 후에도 업무 추적을 위해 기존 감사 로그의 사용자 참조를 보존한다.

## 8. 오류 응답 안전성

- `SQLException`, ORM 예외, DB constraint 이름, SQL 구문과 bind 값은 외부에 반환하지 않는다.
- 예상하지 못한 예외는 `500 COMMON_INTERNAL_ERROR`와 고정 메시지만 반환한다.
- 인증 실패 메시지는 사용자 존재, 잠금 여부, 해시 알고리즘, MFA secret 상태를 구분하지 않는다.
- Validation의 민감 입력은 `rejectedValue: null`을 반환한다.
- 클라이언트가 장애 문의에 사용할 값은 `requestId`뿐이다.

따라서 오류 메시지나 감사 로그를 통해 SQL Injection에 유용한 SQL 구조·테이블·컬럼·입력 반사 정보가 노출되지 않아야 한다.

## 9. 구현 인수 조건

- 민감 키 변형을 포함한 재귀 redaction 테스트
- 요청·응답·예외·SQL·프록시 로그에 금지값이 없는지 테스트
- 중요 변경과 감사 로그의 트랜잭션 원자성 테스트
- 인가 거부·업무 실패·성공 결과 구분 테스트
- action code, actor, target, requestId 정합성 테스트
- API Key 발급 응답 1회 노출과 저장 hash 검증
- 감사 로그 수정·삭제 API 부재와 DB 계정 권한 테스트

