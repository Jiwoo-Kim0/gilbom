# 인증 Rate Limit·잠금·감사 로그 구현

- 구현 상태: 공통 기반 완료
- 적용 migration: V10, V29
- 후속 연결: 로그인·초기 비밀번호 변경·MFA·로그아웃 API

## 1. 방어 계층

로그인과 MFA API는 다음 순서를 유지한다.

1. 요청 body를 읽기 전에 IP 인증 요청 Rate Limit을 소비한다.
2. 이메일 형식·크기 검증 후 정규화 이메일 HMAC bucket의 비밀번호 실패 제한을 확인한다.
3. 계정 존재 여부와 관계없이 실제 또는 dummy Argon2id hash를 검증한다.
4. 실패하면 HMAC bucket과 존재하는 `ACTIVE` 계정의 실패 상태를 같은 트랜잭션에서 갱신한다.
5. 비밀번호가 맞으면 비밀번호 실패 bucket과 계정 실패 상태를 초기화하고 pre-auth challenge만 만든다.
6. MFA 전에 사용자 UUID HMAC bucket과 현재 5분 challenge를 모두 확인한다.
7. MFA 실패는 challenge와 계정 MFA bucket을 함께 증가시킨다.
8. 최종 MFA 성공 감사가 저장된 뒤에만 인증 세션으로 승격하고 세션 ID·CSRF를 교체한다.

Rate Limit 저장소 장애는 인증 요청을 계속 허용하지 않고 `503 DB_UNAVAILABLE`로
fail-closed 처리한다. 429 응답은 `Cache-Control: no-store`와 정수 초 단위
`Retry-After`를 포함한다.

## 2. 고정 정책

| 대상 | 상한 | 창·제한 |
|---|---:|---:|
| IP 인증 요청 | 20회 | 1분 |
| 정규화 이메일 비밀번호 실패 | 5회 | 15분 창, 15분 제한 |
| 사용자 MFA 실패 | 5회 | 15분 창, 15분 제한 |
| 현재 pre-auth challenge | 5회 | challenge 시작부터 5분 |
| 새 익명 세션의 CSRF 발급 | 60회 | IP별 1분 |
| 이메일 중복 확인 | 20회 | IP별 1분 |
| 공개 회원가입 | 5회 | IP별 1시간 |

정책 값은 `AuthenticationRateLimitProperties`의 시작 검증과 V10 DB 제약으로
고정한다. 애플리케이션 설정만 바꿔 더 약한 값으로 기동할 수 없다.

## 3. 식별자와 저장

`auth.authentication_rate_limit`에는 이메일, IP, 사용자 UUID 원문을 넣지 않는다.
외부 Secret을 key로 한 HMAC-SHA-256 결과와 key version만 저장한다.

bucket은 다음처럼 분리한다.

- `IP_AUTHENTICATION_REQUEST`
- `ACCOUNT_PASSWORD_FAILURE`
- `ACCOUNT_MFA_FAILURE`
- `IP_EMAIL_AVAILABILITY_REQUEST`
- `IP_PUBLIC_REGISTRATION_REQUEST`
- `IP_CSRF_TOKEN_ISSUANCE`

실제 계정은 `auth.admin_user.failed_login_count`, `last_failed_login_at`,
`locked_until`도 함께 갱신한다. 15분 안의 다섯 번째 실패에서
`failed_login_count=5`, `locked_until=last_failed_login_at + 15 minutes`가 된다.
잠금이 만료된 뒤 첫 실패는 새 창의 1회로 시작한다.

존재하지 않는 이메일도 같은 HMAC bucket을 소비한다. 실제 계정 row가 없다는 이유로
계정별 제한을 생략하지 않으며 외부 응답은 실제 계정과 같은 인증 실패 계약을 사용한다.

### 3.1 CSRF 발급과 세션 저장소 보호

`GET /api/v1/auth/csrf-token`은 토큰을 읽는 시점에 Spring Session을 만들 수 있다.
쿠키를 유지하지 않는 클라이언트가 이를 반복하면 인증을 우회하지 않고도
`session_store.spring_session`과 attribute row 생성을 증폭시킬 수 있다.

다음 두 계층을 적용한다.

1. Nginx는 IP별 `2r/s`, `burst=60`으로 순간 flood를 애플리케이션 전에 `429` 처리한다.
2. 애플리케이션은 현재 유효한 `HttpSession`이 없는 요청만
   `IP_CSRF_TOKEN_ISSUANCE` HMAC bucket으로 IP별 정확히 60회/분 허용한다.

이미 세션이 있는 새로고침·다중 탭 요청은 새 JDBC 세션 row를 만들지 않으므로
애플리케이션 bucket을 소비하지 않는다. Nginx는 서버 세션의 유효성을 판단할 수 없어
모든 CSRF token 경로에 넉넉한 burst 상한만 적용하고, 영속 정책의 정본은
애플리케이션과 PostgreSQL에 둔다.

애플리케이션 제한은 다중 인스턴스에서도 같은 HMAC bucket을 공유하고, 거부 시
공통 JSON `429`, `Retry-After`, `Cache-Control: no-store`를 반환한다. 저장소 장애는
새 세션 생성을 허용하지 않고 `503 DB_UNAVAILABLE`로 fail-closed 처리한다.
Nginx가 먼저 거부한 `429`는 애플리케이션 JSON body나 동적 `Retry-After`가 없을 수 있다.

IP 제한은 Nginx와 애플리케이션이 실제 클라이언트 IP를 식별한다는 전제에 의존한다.
운영 환경에서 Nginx 앞에 LB·WAF·CDN이 있으면 신뢰할 프록시 대역만
`set_real_ip_from`으로 지정하고 `real_ip_header`를 구성해야 한다. 이를 생략하면 모든
사용자가 프록시 IP 하나로 집계되거나, 반대로 신뢰하지 않은 전달 헤더를 허용할 경우
공격자가 IP bucket을 우회할 수 있다.

## 4. MFA 제한

세션 challenge와 계정 MFA bucket은 목적이 다르므로 둘 다 사용한다.

- 세션 challenge: 현재 로그인 흐름에서 5분·5회
- 계정 MFA bucket: challenge를 다시 발급받아 우회하는 공격을 15분 동안 제한
- 다섯 번째 challenge 실패: 현재 pre-auth 세션 즉시 폐기
- MFA 성공: 계정 MFA 실패 bucket 초기화

## 5. 감사 이벤트

감사 저장소는 기존 append-only `audit.audit_log`를 사용한다. runtime 계정은
`SELECT`, `INSERT`만 가능하며 UPDATE·DELETE는 권한과 trigger로 모두 차단한다.

| 이벤트 | result | actor/target |
|---|---|---|
| `AUTH_LOGIN_SUCCEEDED` | `SUCCESS` | 인증 사용자 / 사용자 UUID |
| `AUTH_LOGIN_FAILED` | `FAILURE` | 미인증 사용자 / 알 수 있을 때만 사용자 UUID |
| `AUTH_PASSWORD_VERIFIED` | `SUCCESS` | 미인증 사용자 / 사용자 UUID |
| `AUTH_ACCOUNT_TEMPORARILY_LOCKED` | `DENIED` | 시스템 / 사용자 UUID |
| `AUTH_MFA_CHALLENGE_FAILED` | `FAILURE` | 미인증 사용자 / 사용자 UUID |
| `AUTH_MFA_ENROLLMENT_STARTED` | `SUCCESS` | 미인증 사용자 / 사용자 UUID |
| `AUTH_MFA_ENROLLED` | `SUCCESS` | 미인증 사용자 / 사용자 UUID |
| `AUTH_RECOVERY_CODE_USED` | `SUCCESS` | 미인증 사용자 / 사용자 UUID |
| `AUTH_PASSWORD_CHANGED` | `SUCCESS` | 인증 사용자 / 사용자 UUID |
| `AUTH_PASSWORD_CHANGE_FAILED` | `FAILURE` | 인증 사용자 / 사용자 UUID |
| `AUTH_SESSION_REVOKED` | `SUCCESS` | 인증 사용자 또는 권한 변경 DB Trigger의 SYSTEM / 사용자·역할·권한 UUID |
| `AUTH_RATE_LIMITED` | `DENIED` | 시스템 / 알 수 있을 때만 사용자 UUID |
| `AUTH_LOGOUT` | `SUCCESS` | 인증 사용자 / 세션 ID는 기록하지 않음 |

IP·CSRF 발급·중복 확인·회원가입 Rate Limit은 같은 차단 창의 첫 거부 요청에서만
`AUTH_RATE_LIMITED`를 기록해 차단 이후의 반복 요청이 감사 저장소를 증폭시키지 못하게
한다. 계정·MFA 제한은 필수 `AUTH_LOGIN_FAILED`,
`AUTH_ACCOUNT_TEMPORARILY_LOCKED`, `AUTH_MFA_CHALLENGE_FAILED`로 추적한다.

HTTP 감사 context에는 `requestId`, 신뢰된 proxy 처리 후의 원격 IP, 제어문자를 제거한
최대 500자 User-Agent만 허용한다. 요청 body, query string, Cookie, Authorization,
CSRF, 이메일, 비밀번호, TOTP, 복구 코드와 세션 ID를 감사 event 객체가 받을 수 없게
분리했다.

권한 변경 DB Trigger가 실제 Session을 하나 이상 삭제하면 같은 transaction에서
`AUTH_SESSION_REVOKED`를 추가한다. 이 행은 `actor_type=SYSTEM`이고 HTTP context가
없으므로 `requestId`, IP, User-Agent는 `NULL`이다. `after_value`의 allowlist 필드
`source`, `trigger`, `operation`, `deletedSessionCount`로 어떤 DB 경계가 발동했는지
확인한다. Session ID와 Cookie는 기록하지 않으며 삭제 건수가 0이면 감사 행도 만들지
않는다. 관리자 API 경로라면 같은 시각·대상의 `RBAC_*` 행에서 실제 요청자와
`requestId`를 대조한다.

## 6. 감사 저장 실패 정책

| 이벤트 | 정책 | 이유 |
|---|---|---|
| 비밀번호 검증 성공 | 필수, 실패 시 pre-auth challenge 생성 금지 | 감사 없는 인증 단계 진행 방지 |
| 로그인 성공 | 필수, 실패 시 인증 세션 승격 금지 | 감사 없는 인증 성공 방지 |
| 로그인 실패 | 필수, 실패 상태와 같은 트랜잭션 | 실패 횟수와 감사의 불일치 방지 |
| 계정 임시 잠금 | 필수, 잠금과 같은 트랜잭션 | 잠금 원인 추적 보장 |
| MFA 실패 | 필수, challenge·계정 제한과 같은 트랜잭션 | MFA 공격 이력과 카운터 정합성 |
| MFA 등록 시작·완료 | 필수, factor 변경과 같은 트랜잭션 | 감사 없는 MFA secret 등록 방지 |
| 복구 코드 사용 | 필수, 코드 소비와 같은 트랜잭션 | 사용 이력과 일회성 상태 정합성 |
| 본인 비밀번호 변경·세션 폐기 | 필수, hash 변경과 같은 트랜잭션 | 감사 없는 비밀번호 변경과 부분 세션 폐기 방지 |
| 권한 변경 | 필수, 업무 변경과 같은 트랜잭션 | 감사 없는 권한 변경 방지 |
| Rate Limit 거부 | best-effort 독립 트랜잭션 | 감사 장애가 이미 결정된 거부를 허용으로 바꾸지 않음 |
| 로그아웃 | 세션 폐기 후 best-effort | 감사 장애가 로그아웃을 되돌리지 못하게 함 |

best-effort 저장 실패 시 애플리케이션 로그에는 예외·SQL·입력값 대신 `requestId`와
고정된 안전 메시지만 기록한다.

## 7. 로그인·MFA API 연결 규칙

- 로그인 필터에서 IP 제한을 먼저 소비한다.
- 계정 조회 후에도 `inspectPasswordAttempt` 결과와 `AccountAuthenticationState`를 함께 확인한다.
- 비밀번호 실패 시 `recordPasswordFailure`만 사용해 카운터와 감사를 원자적으로 처리한다.
- 비밀번호 성공 시 `completePasswordVerification`만 호출해 실패 상태 초기화,
  `AUTH_PASSWORD_VERIFIED` 감사와 challenge 생성을 같은 트랜잭션으로 처리한다.
- TOTP·복구 코드 실패는 같은 challenge 실패 메시지와 카운터를 사용하며 5회째에
  challenge를 폐기한다.
- MFA 성공은 성공 step 또는 복구 코드를 먼저 원자적으로 소비한 뒤에만 로그인 성공
  감사와 세션 승격을 수행한다.
- MFA 검증 전 `inspectMfaAttempt`, 실패 시 `recordMfaFailure`를 사용한다.
- MFA 검증 성공 후 일반 `completeMfa`를 직접 호출하지 않고
  `completeMfaAndRecordLogin`을 호출한다.
- 로그아웃은 `AuthenticationDefenseService.logout`을 사용해 세션 폐기를 먼저 확정한다.

로그인 API는 계정 없음·비밀번호 불일치·비활성·잠금을 외부 메시지로 구분하지 않는다.
