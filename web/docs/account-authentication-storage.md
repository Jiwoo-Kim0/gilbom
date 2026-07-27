# 계정·인증 저장 구조

## 1. 범위와 원칙

이 문서는 로그인·회원가입 API보다 먼저 확정하는 계정, MFA, 세션, 감사 저장 계약이다.

- 로그인 ID는 이메일이며 중복의 최종 판정자는 DB다.
- 비밀번호와 복구 코드는 단방향 Argon2id encoded hash만 저장한다.
- TOTP secret은 검증에 원문이 필요하므로 외부 key로 AES-256-GCM 암호화한다.
- 계정 생명주기와 일시적 로그인 잠금을 분리한다.
- MFA 등록 여부처럼 다른 테이블에서 정확히 계산할 수 있는 값은 중복 저장하지 않는다.
- 세션 ID, CSRF, 비밀번호, TOTP secret, 복구 코드 원문을 감사 로그에 저장하지 않는다.

## 2. 계정

기준 테이블은 `auth.admin_user`다.

| 컬럼 | 규칙 |
|---|---|
| `email` | 사용자가 제출한 표시용 이메일, 최대 254자 |
| `normalized_email` | DB가 `LOWER(email)`로 생성하는 저장 generated column |
| `password_hash` | 전체 `{argon2}$argon2id$...` encoded hash, `VARCHAR(512)` |
| `password_change_required` | 초기 비밀번호 변경 전 `true` |
| `account_status` | `PENDING_APPROVAL`, `ACTIVE`, `DISABLED`, `DELETED` |
| `account_origin` | `PUBLIC_REGISTRATION`, `BOOTSTRAP`, `ADMIN_CREATED`, `MIGRATED` |
| `is_active` | 기존 조회 호환용. `account_status=ACTIVE`일 때만 `true` |
| `failed_login_count` | 최근 성공 이후 연속 비밀번호 실패 횟수 |
| `last_failed_login_at` | 마지막 비밀번호 실패 시각 |
| `locked_until` | 현재 시각보다 미래이면 임시 잠금 |
| `deleted_at` | `DELETED` 계정의 소프트 삭제 시각 |

`normalized_email`에는 UNIQUE 제약을 둔다. 중복 확인 API가 동시에 성공하더라도 실제
INSERT 중 하나만 성공하므로 경쟁 조건에서 중복 계정이 생기지 않는다.

이메일 v1 형식은 ASCII mailbox와 ASCII/Punycode domain을 사용한다. 앞뒤 공백을
제거하지 않고 거부하며, `+tag` 또는 점을 제거하는 사업자별 별칭 정규화와 DNS·MX
조회는 하지 않는다.

기존 `username`은 데이터 손실 없이 `legacy_username`으로 이름을 바꾸고 신규 계정의
필수값과 로그인 ID에서 제외한다.

`account_origin=BOOTSTRAP`은 최대 한 계정만 허용한다. 같은 이메일의 공개 가입 계정이
이미 있더라도 bootstrap 코드가 그 계정을 관리자 계정으로 전환해서는 안 된다.

## 3. 계정 생명주기와 임시 잠금

`account_status`는 장기 생명주기다.

| 상태 | 인증 시도 | 의미 |
|---|:---:|---|
| `PENDING_APPROVAL` | 불가 | 공개 회원가입 직후, 역할 없음 |
| `ACTIVE` | 가능 | 승인 또는 초기 관리자 bootstrap 완료 |
| `DISABLED` | 불가 | 관리자 비활성화 |
| `DELETED` | 불가 | 소프트 삭제 |

임시 잠금은 `ACTIVE` 계정에만 적용하고 `account_status`를 `LOCKED`로 변경하지 않는다.
`locked_until > now`이면 잠금으로 판단한다. 15분 안의 다섯 번째 비밀번호 실패에서
`failed_login_count=5`와 `locked_until=last_failed_login_at + 15 minutes`를
DB 제약으로 강제한다. 잠금 만료 뒤 첫 실패는 새 창의 1회로 시작한다. 비밀번호 검증
성공 시 실패 횟수와 두 시각을 모두 초기화한다. 승인 대기·비활성·삭제 계정에는
실패·잠금 상태를 남기지 않는 DB 제약을 둔다.

`auth.authentication_rate_limit`은 별도 역할이다. `admin_user`의 값은 존재하는 계정의
연속 실패와 잠금 상태이고, rate-limit bucket은 존재하지 않는 이메일과 IP까지 포함한
고정 시간 창 제한이다. bucket key에는 정규화 이메일·IP 원문 대신 외부 pepper로 만든
HMAC-SHA-256만 저장한다.

## 4. 비밀번호와 복구 코드

`password_hash`와 `mfa_recovery_code.code_hash`는 `VARCHAR(512)`이며 다음 전체 문자열을
저장한다.

```text
{argon2}$argon2id$v=19$m=...,t=...,p=...$<salt>$<hash>
```

encoded hash 자체에 알고리즘, 버전, 파라미터, salt가 포함되므로 별도의 salt 또는
password algorithm 컬럼을 만들지 않는다. 비밀번호 원문이나 복호화 가능한 비밀번호
암호문도 저장하지 않는다. DB 제약은 `{argon2}$argon2id$` 이외의 값을 거부한다.

복구 코드는 factor마다 1~10번 위치를 갖고 `used_at`을 원자적으로 설정해 한 번만
사용한다.

## 5. MFA

기준 테이블은 `auth.mfa_factor`다.

- `factor_status=PENDING`: secret 생성과 암호화 저장 완료, 최초 TOTP 확인 전
- `factor_status=ACTIVE`: 등록 완료
- `factor_status=REVOKED`: 교체·초기화로 폐기
- 활성 TOTP factor 존재 여부가 MFA 등록 여부의 단일 기준이다.
- `secret_ciphertext`, `secret_nonce`, `secret_key_version`만 DB에 저장한다.
- `last_successful_totp_time_step`은 마지막 성공 step이며 감소하거나 NULL로 되돌릴 수 없다.

같은 time-step 소비 경쟁은 `UPDATE ... WHERE last_successful_totp_time_step IS NULL OR
last_successful_totp_time_step < :step` 형태의 조건부 갱신과 영향 행 수로 판정한다.

## 6. Challenge와 세션

Spring Session 표준 테이블과 보안 companion 테이블을 분리한다.

| 저장 위치 | 값 |
|---|---|
| `spring_session.creation_time` | 세션 생성 시각 |
| `spring_session.last_access_time` | 최근 접근 시각 |
| `spring_session.expiry_time` | 유휴 만료 계산 시각 |
| `session_security_state.absolute_expiry_time` | 갱신 불가능한 절대 만료 |
| `authentication_challenge_started_time` | 초기 비밀번호 변경/MFA challenge 시작 |
| `authentication_challenge_expires_at` | challenge 만료 |
| `authentication_challenge_failure_count` | challenge 실패 횟수, 0~5 |

익명·사전인증·인증 완료 상태별 NULL 가능 컬럼을 DB CHECK로 제한한다.
`AUTHENTICATED` 세션은 사용자당 하나만 존재하도록 partial UNIQUE index를 둔다.
`principal_name`에는 이메일 대신 내부 user UUID 문자열을 사용한다.
V9는 `max_inactive_interval=1800`, challenge 최대 300,000ms, 인증 세션 절대
28,800,000ms 상한을 DB CHECK로도 강제한다. challenge의 `absolute_expiry_time`은
challenge 만료와 같고, MFA 성공 시점에 8시간 값으로 새로 설정한다.

## 7. 감사 로그

`audit.audit_log`는 append-only trigger와 runtime `SELECT/INSERT` 권한만 사용한다.
로그인 성공·실패·잠금·비밀번호 변경·MFA·로그아웃 이벤트를 저장할 수 있지만 다음 값은
`before_value`, `after_value`, `reason`에도 넣지 않는다.

- 비밀번호 원문·hash·salt·파라미터·해싱 시간
- TOTP secret·암호문·nonce·QR URI
- TOTP/복구 코드 원문 또는 hash
- 세션 ID·CSRF 토큰
- rate-limit subject HMAC key와 외부 pepper

## 8. 마이그레이션

V6는 기존 V1~V5를 수정하지 않고 다음을 수행한다.

- 이메일 로그인 전환과 generated `normalized_email` UNIQUE
- 계정 생명주기·연속 실패·임시 잠금 상태
- 비밀번호·복구 코드 hash 컬럼 512자 및 Argon2id 제약
- TOTP time-step 단조 증가 trigger
- challenge 시작·실패 횟수와 세션 시각 제약
- 사용자당 인증 완료 세션 1개 제약

V9는 세션 유휴 30분·challenge 5분·절대 8시간 상한과 만료 정리 인덱스를 추가한다.
V10은 비밀번호 실패 5회·15분 잠금의 정확한 시각 관계와 MFA 계정 실패 bucket type,
감사 action 조회 index를 추가한다.

기존 이메일의 소문자 충돌이나 기존 비-Argon2id 복구 코드가 있으면 migration을
자동 우회하지 않고 실패시켜 데이터를 먼저 정리하도록 한다.
