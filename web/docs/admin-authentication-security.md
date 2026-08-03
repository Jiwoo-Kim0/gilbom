# 관리자 인증·세션·CSRF·TOTP 계약

- 문서 상태: 확정
- 구현 상태: 계정·Argon2id·회원가입·세션/쿠키/CSRF·Rate Limit/잠금/감사 기반 완료, 로그인·MFA API 진행 예정
- OpenAPI 정본: [`openapi-v1.yaml`](./openapi-v1.yaml)

## 1. 인증 원칙

- 관리자 브라우저는 Nginx를 통해 API와 동일 출처로 서비스한다.
- 관리자 인증은 서버 세션을 사용한다. 브라우저에는 세션 ID만 둔다.
- `ADMIN`, `MANAGER`, `OPERATOR`와 사용자 정의 관리자 역할 모두 TOTP MFA가 필수다.
- 비밀번호 검증 성공만으로 인증된 `SecurityContext`나 업무 권한을 만들지 않는다.
- 인증 전·초기 비밀번호 변경·MFA 대기·인증 완료 세션을 서버 상태로 구분한다.
- 인증과 인가 판단은 프론트엔드가 아니라 서버가 수행한다.
- 로그인 ID는 이메일이며 형식과 중복만 검증한다. DNS·MX 조회로 가입을 차단하지 않는다.
- 공개 회원가입 계정은 `PENDING_APPROVAL` 상태와 역할 없음으로 생성하며 승인 전에는
  로그인할 수 없다. 공개 회원가입으로 `ADMIN` 또는 다른 업무 권한을 자동 부여하지 않는다.
- 초기 관리자만 외부 Secret을 사용하는 서버 bootstrap으로 생성한다.
- 초기 관리자 생성·dummy 검증·부하 테스트의 상세 기준은
  [`password-and-initial-admin.md`](./password-and-initial-admin.md)를 따른다.
- 공개 회원가입·중복 경쟁·Rate Limit 구현 기준은
  [`public-registration.md`](./public-registration.md)를 따른다.
- 세션 수명·폐기·쿠키·CSRF 구현 기준은
  [`session-cookie-csrf.md`](./session-cookie-csrf.md)를 따른다.
- Rate Limit·계정 잠금·감사 실패 정책은
  [`authentication-rate-limit-lock-audit.md`](./authentication-rate-limit-lock-audit.md)를 따른다.

### 1.1 이메일 ID 계약

- 입력은 최대 254자이며 ASCII mailbox와 ASCII/Punycode domain 이메일 문법만 검증한다.
- 앞뒤 공백이 있으면 조용히 제거하지 않고 validation 오류로 거부한다.
- 검증을 통과한 이메일 전체를 `Locale.ROOT` 기준 소문자로 변환해 조회·중복 검사용
  `normalized_email`로 사용한다. 원래 입력은 표시용 이메일로 별도 보관할 수 있다.
- `+tag` 제거, 점(`.`) 제거처럼 메일 사업자별 별칭을 합치는 정규화는 하지 않는다.
- DNS·MX 조회, 실제 메일함 존재 확인은 형식 검증과 분리하며 회원가입 요청을 동기 차단하는
  조건으로 사용하지 않는다.
- 중복 확인 API는 UX 보조 수단이며 최종 중복 방지는 DB의
  `UNIQUE(normalized_email)` 제약으로 처리한다.

계정·MFA·세션 컬럼과 DB 제약의 상세 기준은
[`account-authentication-storage.md`](./account-authentication-storage.md)를 따른다.

## 2. 인증 상태

| 상태 | 의미 | 허용 API |
|---|---|---|
| `ANONYMOUS` | 인증 전. CSRF 발급을 위해 익명 세션은 존재할 수 있음 | 공개 API, CSRF 토큰 발급, 로그인 |
| `PASSWORD_CHANGE_REQUIRED` | 초기 비밀번호 검증 성공, 새 비밀번호 설정 대기 | 초기 비밀번호 변경, 로그아웃 |
| `MFA_ENROLLMENT_REQUIRED` | 비밀번호 성공, 활성 TOTP 없음 | TOTP 등록·확인, 로그아웃 |
| `MFA_REQUIRED` | 비밀번호 성공, TOTP 또는 복구 코드 대기 | TOTP 검증, 복구 코드 검증, 로그아웃 |
| `AUTHENTICATED` | 비밀번호와 두 번째 요소 검증 완료 | 보유 권한과 객체 범위에 따른 API |

`PASSWORD_CHANGE_REQUIRED`와 MFA 대기 상태는 인증 완료가 아니며 `AUTHENTICATED` 전용
API에 접근하면 `401 AUTH_UNAUTHENTICATED`를 반환한다.

## 3. 로그인 흐름

```text
GET /api/v1/auth/csrf-token
  └─ 익명 세션 쿠키 + CSRF 토큰 발급

POST /api/v1/auth/login + X-CSRF-TOKEN
  ├─ 실패 → 401 또는 시도 제한 429
  ├─ 초기 비밀번호 변경 필요 → 202 PASSWORD_CHANGE_REQUIRED
  ├─ TOTP 미등록 → 202 MFA_ENROLLMENT_REQUIRED
  └─ TOTP 등록됨 → 202 MFA_REQUIRED

PASSWORD_CHANGE_REQUIRED
  └─ 초기 비밀번호 변경 → 202 MFA_ENROLLMENT_REQUIRED

MFA_ENROLLMENT_REQUIRED
  └─ enroll → confirm → 세션 ID·CSRF 교체 → AUTHENTICATED

MFA_REQUIRED
  ├─ TOTP verify → 세션 ID·CSRF 교체 → AUTHENTICATED
  └─ recovery → 복구 코드 소모 → 세션 ID·CSRF 교체 → AUTHENTICATED
```

### 3.1 CSRF 준비

`GET /api/v1/auth/csrf-token`은 공개 API다. 세션에 결합된 CSRF 토큰을 만들고 다음을 반환한다.

```json
{
  "headerName": "X-CSRF-TOKEN",
  "token": "opaque-random-value"
}
```

- 응답에는 `Cache-Control: no-store`를 적용한다.
- 토큰은 프론트엔드 메모리에 보관하며 Local Storage, Session Storage, URL, 로그에 저장하지 않는다.
- 로그인 완료와 로그아웃 후에는 기존 토큰을 폐기한다.

`GET /api/v1/auth/session-state`는 SPA 새로고침 후 쿠키에 결합된 인증 진행 상태를
복원한다. `ANONYMOUS`, `PASSWORD_CHANGE_REQUIRED`, `MFA_ENROLLMENT_REQUIRED`,
`MFA_REQUIRED`, `AUTHENTICATED`와 pre-auth challenge 만료 시각만 반환하며 사용자 ID,
이메일, 역할과 권한은 포함하지 않는다. 비밀번호를 다시 검증한 로그인은 기존 pre-auth
challenge를 새 challenge로 교체할 수 있지만 완전 인증 세션을 낮추지는 않는다.

### 3.2 비밀번호 검증

`POST /api/v1/auth/login`은 `email`, `password`를 받고 `X-CSRF-TOKEN`을 요구한다.

- 성공 시에도 완전한 인증 세션을 만들지 않고 `202 Accepted`로 다음 단계만 알린다.
- `email`이 없거나 비밀번호가 틀린 경우를 같은 `401 AUTH_UNAUTHENTICATED`와 고정 메시지로 반환한다.
- 비활성·삭제·잠금 계정도 외부에는 동일한 인증 실패로 반환한다.
- 로그인 응답은 계정 존재 여부, 등록 역할, TOTP 등록 여부를 비밀번호 검증 전에 노출하지 않는다.
- 존재하지 않는 계정은 애플리케이션 시작 시 생성한 동일 파라미터 dummy Argon2id hash를 검증한다.
- 지원하지 않거나 손상된 저장 hash도 외부에는 같은 `401`을 반환하고 내부에는
  `requestId`와 고정 오류 분류만 기록한다.
- 비밀번호 단계 성공은 `AUTH_PASSWORD_VERIFIED`로 기록하지만 인증 완료 성공인
  `AUTH_LOGIN_SUCCEEDED`는 MFA 성공 시점에만 기록한다.

```json
{
  "loginState": "MFA_REQUIRED",
  "challengeExpiresAt": "2026-07-25T10:35:00Z"
}
```

### 3.3 초기 비밀번호 변경

서버 bootstrap으로 만든 초기 관리자는 비밀번호 검증 성공 후
`PASSWORD_CHANGE_REQUIRED`가 된다.

1. `POST /api/v1/auth/password/initial-change`는 `PASSWORD_CHANGE_REQUIRED` 세션과
   `X-CSRF-TOKEN`을 요구한다.
2. 새 비밀번호는 원문 그대로 10~24 Unicode code point인지 검증하며 trim·정규화·
   잘라내기를 하지 않는다.
3. 기존 초기 비밀번호와 같으면 변경을 거부한다.
4. Argon2id로 새 hash를 만든 뒤 `password_change_required=false`와
   `password_changed_at`을 같은 트랜잭션에서 갱신한다.
5. 현재 challenge를 제외한 해당 사용자의 다른 세션을 모두 폐기한다.
6. 성공 감사와 상태 전이를 같은 트랜잭션으로 확정한다.
7. 성공해도 인증 완료로 승격하지 않고 `202 MFA_ENROLLMENT_REQUIRED`를 반환한다.

초기 비밀번호 변경 실패 응답과 로그에는 비밀번호 원문, hash, salt, 해싱 파라미터,
해싱 소요 시간을 포함하지 않는다.

### 3.4 TOTP 등록

최초 로그인 또는 관리자가 MFA를 초기화한 계정은 `MFA_ENROLLMENT_REQUIRED`가 된다.

1. `POST /api/v1/auth/mfa/totp/enroll`에서 사용자 전용 secret을 생성한다.
2. 응답에서 `otpauthUri`와 수동 입력키를 한 번만 전달한다.
3. `POST /api/v1/auth/mfa/totp/confirm`에서 첫 코드를 검증한 뒤 factor를 활성화한다.
4. 일회용 복구 코드 10개를 한 번만 반환한다.
5. 등록 확인 성공 후 세션 ID와 CSRF 토큰을 교체하고 인증을 완료한다.

등록 응답과 복구 코드 응답에는 `Cache-Control: no-store`를 적용한다. secret, `otpauthUri`, QR 내용과 복구 코드 원문은 애플리케이션 로그·감사 로그·분산 추적에 기록하지 않는다.

### 3.5 TOTP 검증과 복구

- RFC 6238, 6자리, 30초 time-step을 사용한다.
- 서버 현재 time-step과 앞뒤 1개 time-step만 허용한다.
- 사용자별 secret은 암호학적으로 안전한 난수 160bit 이상으로 생성한다.
- 같은 사용자에게 이미 성공한 time-step은 다시 허용하지 않는다.
- MFA 챌린지는 5분 후 만료한다.
- 챌린지당 실패 5회가 되면 해당 챌린지를 폐기하고 `429 COMMON_RATE_LIMIT_EXCEEDED`를 반환한다.
- 복구 코드는 10개를 발급하고 한 번 사용하면 즉시 폐기한다.
- 복구 코드 사용은 사용자 알림과 감사 로그를 생성한다.
- “이 기기 기억하기”는 MVP에서 지원하지 않는다.

TOTP secret은 검증에 원문이 필요하므로 비밀번호처럼 해시하지 않는다. 애플리케이션 외부의 마스터 키로 AES-GCM 암호화하여 별도 MFA factor 저장소에 보관한다. 복구 코드는 원문 대신 해시를 저장한다.

### 3.6 인증 완료

TOTP 또는 복구 코드 성공 시 다음을 한 트랜잭션 흐름으로 처리한다.

1. MFA challenge와 이미 사용한 time-step 또는 복구 코드를 소비한다.
2. 세션 ID를 교체한다.
3. 사용자 ID, 역할, 유효 권한을 인증 컨텍스트에 저장한다.
4. 새로운 CSRF 토큰을 만든다.
5. 로그인 성공 감사 로그와 `last_login_at`을 기록한다.
6. 사용자 정보, 권한, 새 CSRF 토큰과 세션 절대 만료 시각을 반환한다.

### 3.7 MFA 교체·관리자 초기화

- 본인 교체는 `POST /api/v1/my/mfa/totp/reset`을 사용한다.
- 본인의 현재 비밀번호와 현재 TOTP 또는 미사용 복구 코드를 다시 검증한다.
- 성공하면 모든 인증 세션과 기존 factor·복구 코드를 폐기하고 현재 세션을 `MFA_ENROLLMENT_REQUIRED`로 낮춘다.
- 다른 사용자의 긴급 초기화는 `POST /api/v1/admin/users/{userId}/mfa/reset`을 사용한다.
- 관리자 초기화에는 `ADMIN_SETTINGS_MANAGE`, CSRF, 작업자의 현재 비밀번호·TOTP 재검증, 1~500자의 사유가 모두 필요하다.
- 관리자는 자기 MFA를 관리자 초기화 API로 우회할 수 없다.
- 초기화된 사용자는 다음 로그인에서 TOTP를 다시 등록해야 한다.
- 교체·초기화 결과는 대상 사용자에게 알리고 `AUTH_MFA_RESET` 감사 로그를 남긴다.

### 3.8 현재 사용자·비밀번호 변경·로그아웃

- `GET /api/v1/auth/me`는 인증 복원용 사용자·역할·permission만 반환한다.
- `GET /api/v1/my/profile`은 이메일·표시 이름·조직 정보·MFA 상태를 반환하되
  비밀번호 hash, TOTP secret, 복구 코드와 세션·CSRF는 반환하지 않는다.
- `PATCH /api/v1/my/password`는 현재 비밀번호를 Argon2id로 재검증하고 새 hash를
  생성한 뒤에만 기존 hash를 교체한다.
- 본인 비밀번호 변경의 hash 교체, 필수 감사와 사용자 전체 세션 삭제는 같은 DB
  트랜잭션으로 처리한다.
- 비밀번호 변경 성공과 `POST /api/v1/auth/logout`은 현재 쿠키도 `Max-Age=0`으로
  만료한다. 이전 세션 ID를 다시 보내도 서버 저장 row가 없어 인증할 수 없다.

상세 트랜잭션과 응답 계약은
[`current-user-password-logout.md`](./current-user-password-logout.md)를 따른다.

## 4. 세션 계약

### 4.1 저장소

- 운영은 PostgreSQL 기반 Spring Session JDBC를 사용한다.
- 세션 저장소는 인증 컨텍스트, 생성 시각, 최근 접근 시각, 절대 만료 시각,
  초기 비밀번호 변경·MFA 대기 상태를 보관한다.
- 세션 저장소 레코드는 업무 테이블이나 감사 로그가 아니며 만료·로그아웃 시 제거할 수 있다.
- 로컬과 운영 모두 JDBC 세션을 사용해 재시작·다중 인스턴스 동작을 같은 방식으로 검증한다.

### 4.2 쿠키

| 속성 | 운영값 |
|---|---|
| 이름 | `__Host-GILBOM_SESSION` |
| 값 | 추측 불가능한 세션 식별자 |
| `Secure` | `true` |
| `HttpOnly` | `true` |
| `SameSite` | `Lax` |
| `Path` | `/` |
| `Domain` | 설정하지 않음 |
| 브라우저 만료 | 세션 쿠키 |

로컬 HTTP 프로필은 브라우저의 `__Host-` 제약 때문에 `GILBOM_SESSION`, `Secure=false`를 사용할 수 있다. 프론트엔드는 쿠키 이름이나 값을 읽지 않으며 `credentials: include`로만 전송한다.

### 4.3 수명과 폐기

| 항목 | 정책 |
|---|---|
| 초기 비밀번호 변경·MFA 대기 만료 | 비밀번호 성공 후 5분 |
| 인증 세션 유휴 만료 | 마지막 요청 후 30분 |
| 인증 세션 절대 만료 | MFA 성공 후 8시간 |
| 동시 인증 세션 | 사용자당 1개 |

다음 상황에서는 기존 인증 세션을 모두 폐기한다.

- 로그아웃
- 비밀번호 변경 또는 계정 비활성화·삭제
- TOTP 등록·교체·초기화
- 역할, 권한 예외, 로봇 배정 변경
- 관리자의 강제 로그아웃

권한 변경은 다음 요청부터가 아니라 세션 폐기 즉시 효력이 발생해야 한다. 만료되거나 폐기된 세션은 `401 AUTH_UNAUTHENTICATED`를 반환한다.

## 5. CSRF 계약

- 토큰 저장소는 서버 세션이다.
- 헤더 이름은 `X-CSRF-TOKEN`으로 고정한다.
- `POST`, `PUT`, `PATCH`, `DELETE` 전체와 로그인·로그아웃·MFA API에 검증한다.
- GET과 HEAD는 상태를 변경하지 않는다.
- 쿠키의 SameSite 설정만으로 CSRF 검증을 대체하지 않는다.
- CSRF 누락·불일치는 `403 AUTH_FORBIDDEN`과 “요청 검증에 실패했습니다.”라는 고정 메시지를 반환한다.
- CORS를 열어야 하는 환경에서는 허용 Origin을 정확한 관리자 프론트엔드 Origin 목록으로 제한하고 wildcard와 credential 조합을 금지한다.

## 6. 비밀번호와 로그인 공격 방어 계약

- 회원가입·초기 관리자·비밀번호 변경의 새 비밀번호는 10~24 Unicode code point로 받는다.
- 비밀번호는 대문자·특수문자·숫자 연속 등 조합 규칙을 추가하지 않고 길이만 검증한다.
- 비밀번호의 앞뒤 공백 제거, 대소문자 변환, Unicode 정규화, 최대 길이 잘라내기를 하지 않는다.
- 모든 비밀번호 hash는 `{argon2}$argon2id$...` 형식의 Argon2id로만 생성·검증한다.
- DB에는 salt와 파라미터를 포함한 전체 encoded hash를 `VARCHAR(512)`로 저장하고,
  password salt·algorithm 컬럼이나 복호화 가능한 비밀번호 암호문을 별도로 만들지 않는다.
- 허용 password encoder는 Argon2id 하나이며 BCrypt를 포함한 fallback은 제공하지 않는다.
- Argon2id 파라미터는 salt 16byte, hash 32byte, parallelism 1, memory 19MiB,
  iterations 2로 중앙 고정하고 변경 시 부하·보안 검토를 수행한다.
- 존재하지 않는 계정도 실제 계정과 같은 파라미터의 재사용 가능한 dummy Argon2id hash를
  검증해 계정 존재 여부에 따른 timing 차이를 줄인다.
- 관리자 또는 운영자가 임시 비밀번호 원문을 조회할 수 없다.
- 비밀번호 원문, hash, salt, 해싱 파라미터와 해싱 소요 시간은 응답·일반 로그·감사 로그에 기록하지 않는다.
- 비밀번호 변경 후 기존 세션을 모두 폐기하고 새 로그인을 요구한다.
- 계정별 15분 동안 비밀번호 실패 5회 또는 MFA 챌린지 실패 5회 시 15분간 새 시도를 제한한다.
- IP별 1분 동안 인증 요청 20회를 초과하면 `429`와 `Retry-After`를 반환한다.
- 계정 제한과 IP 제한을 함께 사용하고 정상 계정을 잠그기 위한 단일 IP 공격이 전체 서비스 장애로 번지지 않게 분리한다.
- 성공·실패 응답 시간 차이로 계정 존재 여부를 쉽게 추론하지 못하도록 사용자 조회 실패도 비밀번호 검증에 준하는 비용을 사용한다.

오류 응답은 SQL, 제약조건명, 테이블·컬럼명, 스택 트레이스, 라이브러리 예외 메시지, 사용자 입력값을 포함하지 않는다.

## 7. 인증 오류 매핑

| 상황 | HTTP | 코드 | 외부 메시지 |
|---|---:|---|---|
| 이메일·비밀번호·MFA·복구 코드 불일치 | 401 | `AUTH_UNAUTHENTICATED` | 인증 정보가 올바르지 않습니다. |
| 이메일 형식·입력 길이 오류 | 400 | `COMMON_VALIDATION_FAILED` | 요청 값이 올바르지 않습니다. |
| 초기 비밀번호를 현재 비밀번호와 같게 변경 | 422 | `AUTH_PASSWORD_REUSE_NOT_ALLOWED` | 새 비밀번호는 현재 비밀번호와 달라야 합니다. |
| 만료·폐기 세션 또는 MFA 미완료 상태 | 401 | `AUTH_UNAUTHENTICATED` | 인증이 필요합니다. |
| 권한 코드 부족 | 403 | `AUTH_FORBIDDEN` | 요청을 수행할 권한이 없습니다. |
| 단건 객체가 사용자 범위 밖 | 404 | 해당 `{DOMAIN}_NOT_FOUND` | 요청한 리소스를 찾을 수 없습니다. |
| CSRF 누락·불일치 | 403 | `AUTH_FORBIDDEN` | 요청 검증에 실패했습니다. |
| 로그인·MFA 시도 제한 | 429 | `COMMON_RATE_LIMIT_EXCEEDED` | 잠시 후 다시 시도해 주세요. |
| TOTP 암호화 keyring·검증 기반 사용 불가 | 503 | `AUTH_MFA_UNAVAILABLE` | MFA 서비스를 일시적으로 사용할 수 없습니다. |
| 예상하지 못한 인증 내부 오류 | 500 | `COMMON_INTERNAL_ERROR` | 서버 내부 오류가 발생했습니다. |

인증 필터 단계의 오류도 [`common-api-response-and-errors.md`](./common-api-response-and-errors.md)의 `ErrorResponse` 형식과 `X-Request-Id`를 사용한다.

## 8. 구현 인수 조건

Day 3·4 구현에서는 최소한 다음을 자동 테스트한다.

- 운영 쿠키 속성 및 응답·로그에 세션 ID가 없는지
- 비밀번호 성공만으로 업무 API에 접근할 수 없는지
- 초기 비밀번호 변경 성공이 `MFA_ENROLLMENT_REQUIRED`로만 전이되는지
- 신규·초기·변경 비밀번호 hash가 모두 `{argon2}$argon2id$`이고 BCrypt가 거부되는지
- 같은 비밀번호의 hash가 무작위 salt로 서로 다르며 24자 Unicode 입력 전체를 검증하는지
- MFA challenge 만료·시도 제한·time-step 재사용 차단
- 로그인·MFA 성공 시 세션 ID와 CSRF 토큰 교체
- 유휴·절대 만료 및 동시 세션 1개 제한
- 로그아웃·비밀번호·권한·MFA 변경 시 세션 폐기
- 로그인, 로그아웃, MFA API의 CSRF 누락·불일치 거부
- 계정 존재 여부와 내부 예외가 오류 응답에 노출되지 않는지
- 필터 단계 401·403·429가 공통 오류 형식인지

## 9. 프론트 인증 상태

SPA의 인증 상태, 메모리 CSRF 저장, 공통 API 오류 처리, 라우트 가드와 화면별 API
연결은 [`frontend-authentication-foundation.md`](./frontend-authentication-foundation.md)를
기준으로 한다. 프론트 상태는 서버 세션을 대체하지 않으며 새로고침 시
`GET /api/v1/auth/me`로 다시 검증한다.

## 10. 통합 보안 검증

인증 변경 후에는 실제 PostgreSQL과 임시 Backend를 사용하는 다음 하네스를 실행한다.

```bash
python3 scripts/auth-security-integration-test.py
```

하네스는 무작위 이름의 전용 DB와 Backend 컨테이너를 만들고 정상 개발 DB에는 접근하지
않는다. 종료 성공·실패와 관계없이 임시 컨테이너와 DB를 제거하며, 생성한 비밀번호,
TOTP secret, 복구 코드, 세션 ID와 CSRF 토큰은 출력하지 않는다.

Jenkins TLS DinD에서는 공개 port의 소유자가 Jenkins container가 아니라
`docker-builder`이므로 Pipeline이 다음 경계를 설정한다.

```text
GILBOM_SECURITY_TEST_BIND_ADDRESS=0.0.0.0
GILBOM_SECURITY_TEST_PUBLISHED_HOST=docker-builder
GILBOM_SECURITY_TEST_OUTPUT=.artifacts/security-regression/auth-security-integration.json
```

JSON에는 민감값 대신 검사명, 임시 Backend·DB 삭제 여부와 PASS/FAIL만 기록한다.
로컬 host Docker 실행은 두 network 값을 생략해 `127.0.0.1`만 사용한다.

검증 범위는 이메일 UNIQUE 동시 요청, 계정 열거 방지, pre-auth 보호 API 차단,
TOTP·복구 코드 재사용 차단, 세션 ID·CSRF 교체, 동시 인증 세션 1개, 비밀번호 변경·
로그아웃·유휴/절대 만료 후 재사용 차단, IP·계정 Rate Limit, Argon2id 전용 저장과
감사/애플리케이션 로그의 비밀값 부재다.

Argon2id 설정의 별도 자원 측정은 다음 명령으로 수행한다.

```bash
bash ./gradlew passwordHashLoadTest
```

이 부하 테스트는 일반 단위 테스트와 분리되어 있으며, 운영 인스턴스의 CPU·메모리
등급이나 Argon2id 파라미터를 바꿀 때 다시 측정한다.
