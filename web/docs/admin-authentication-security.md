# 관리자 인증·세션·CSRF·TOTP 계약

- 문서 상태: 확정
- 구현 예정: Day 3 로그인·세션·공격 방어, Day 4 CSRF
- OpenAPI 정본: [`openapi-v1.yaml`](./openapi-v1.yaml)

## 1. 인증 원칙

- 관리자 브라우저는 Nginx를 통해 API와 동일 출처로 서비스한다.
- 관리자 인증은 서버 세션을 사용한다. 브라우저에는 세션 ID만 둔다.
- `ADMIN`, `MANAGER`, `OPERATOR`와 사용자 정의 관리자 역할 모두 TOTP MFA가 필수다.
- 비밀번호 검증 성공만으로 인증된 `SecurityContext`나 업무 권한을 만들지 않는다.
- 인증 전·MFA 대기·인증 완료 세션을 서버 상태로 구분한다.
- 인증과 인가 판단은 프론트엔드가 아니라 서버가 수행한다.

## 2. 인증 상태

| 상태 | 의미 | 허용 API |
|---|---|---|
| `ANONYMOUS` | 인증 전. CSRF 발급을 위해 익명 세션은 존재할 수 있음 | 공개 API, CSRF 토큰 발급, 로그인 |
| `MFA_ENROLLMENT_REQUIRED` | 비밀번호 성공, 활성 TOTP 없음 | TOTP 등록·확인, 로그아웃 |
| `MFA_REQUIRED` | 비밀번호 성공, TOTP 또는 복구 코드 대기 | TOTP 검증, 복구 코드 검증, 로그아웃 |
| `AUTHENTICATED` | 비밀번호와 두 번째 요소 검증 완료 | 보유 권한과 객체 범위에 따른 API |

MFA 대기 상태는 인증 완료가 아니며 `AUTHENTICATED` 전용 API에 접근하면 `401 AUTH_UNAUTHENTICATED`를 반환한다.

## 3. 로그인 흐름

```text
GET /api/v1/auth/csrf-token
  └─ 익명 세션 쿠키 + CSRF 토큰 발급

POST /api/v1/auth/login + X-CSRF-TOKEN
  ├─ 실패 → 401 또는 시도 제한 429
  ├─ TOTP 미등록 → 202 MFA_ENROLLMENT_REQUIRED
  └─ TOTP 등록됨 → 202 MFA_REQUIRED

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

### 3.2 비밀번호 검증

`POST /api/v1/auth/login`은 `username`, `password`를 받고 `X-CSRF-TOKEN`을 요구한다.

- 성공 시에도 완전한 인증 세션을 만들지 않고 `202 Accepted`로 다음 단계만 알린다.
- `username`이 없거나 비밀번호가 틀린 경우를 같은 `401 AUTH_UNAUTHENTICATED`와 고정 메시지로 반환한다.
- 비활성·삭제·잠금 계정도 외부에는 동일한 인증 실패로 반환한다.
- 로그인 응답은 계정 존재 여부, 등록 역할, TOTP 등록 여부를 비밀번호 검증 전에 노출하지 않는다.

```json
{
  "loginState": "MFA_REQUIRED",
  "challengeExpiresAt": "2026-07-25T10:35:00Z"
}
```

### 3.3 TOTP 등록

최초 로그인 또는 관리자가 MFA를 초기화한 계정은 `MFA_ENROLLMENT_REQUIRED`가 된다.

1. `POST /api/v1/auth/mfa/totp/enroll`에서 사용자 전용 secret을 생성한다.
2. 응답에서 `otpauthUri`와 수동 입력키를 한 번만 전달한다.
3. `POST /api/v1/auth/mfa/totp/confirm`에서 첫 코드를 검증한 뒤 factor를 활성화한다.
4. 일회용 복구 코드 10개를 한 번만 반환한다.
5. 등록 확인 성공 후 세션 ID와 CSRF 토큰을 교체하고 인증을 완료한다.

등록 응답과 복구 코드 응답에는 `Cache-Control: no-store`를 적용한다. secret, `otpauthUri`, QR 내용과 복구 코드 원문은 애플리케이션 로그·감사 로그·분산 추적에 기록하지 않는다.

### 3.4 TOTP 검증과 복구

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

### 3.5 인증 완료

TOTP 또는 복구 코드 성공 시 다음을 한 트랜잭션 흐름으로 처리한다.

1. MFA challenge와 이미 사용한 time-step 또는 복구 코드를 소비한다.
2. 세션 ID를 교체한다.
3. 사용자 ID, 역할, 유효 권한을 인증 컨텍스트에 저장한다.
4. 새로운 CSRF 토큰을 만든다.
5. 로그인 성공 감사 로그와 `last_login_at`을 기록한다.
6. 사용자 정보, 권한, 새 CSRF 토큰과 세션 절대 만료 시각을 반환한다.

### 3.6 MFA 교체·관리자 초기화

- 본인 교체는 `POST /api/v1/my/mfa/totp/reset`을 사용한다.
- 본인의 현재 비밀번호와 현재 TOTP 또는 미사용 복구 코드를 다시 검증한다.
- 성공하면 모든 인증 세션과 기존 factor·복구 코드를 폐기하고 현재 세션을 `MFA_ENROLLMENT_REQUIRED`로 낮춘다.
- 다른 사용자의 긴급 초기화는 `POST /api/v1/admin/users/{userId}/mfa/reset`을 사용한다.
- 관리자 초기화에는 `ADMIN_SETTINGS_MANAGE`, CSRF, 작업자의 현재 비밀번호·TOTP 재검증, 1~500자의 사유가 모두 필요하다.
- 관리자는 자기 MFA를 관리자 초기화 API로 우회할 수 없다.
- 초기화된 사용자는 다음 로그인에서 TOTP를 다시 등록해야 한다.
- 교체·초기화 결과는 대상 사용자에게 알리고 `AUTH_MFA_RESET` 감사 로그를 남긴다.

## 4. 세션 계약

### 4.1 저장소

- 운영은 PostgreSQL 기반 Spring Session JDBC를 사용한다.
- 세션 저장소는 인증 컨텍스트, 생성 시각, 최근 접근 시각, 절대 만료 시각, MFA 대기 상태를 보관한다.
- 세션 저장소 레코드는 업무 테이블이나 감사 로그가 아니며 만료·로그아웃 시 제거할 수 있다.
- 로컬 단일 인스턴스 개발에서는 인메모리 세션을 허용하지만 운영 프로필에는 사용할 수 없다.

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
| MFA 대기 만료 | 비밀번호 성공 후 5분 |
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

- 새 비밀번호는 12자 이상 128자 이하로 받으며 앞뒤 공백을 임의로 제거하지 않는다.
- 비밀번호는 `{id}encoded` 형식의 adaptive hash로 저장하고 신규 비밀번호 기본은 Argon2id로 한다.
- 기존 해시 알고리즘은 로그인 성공 시 필요하면 최신 정책으로 재해시한다.
- 관리자 또는 운영자가 임시 비밀번호 원문을 조회할 수 없다.
- 비밀번호 변경 후 기존 세션을 모두 폐기하고 새 로그인을 요구한다.
- 계정별 15분 동안 비밀번호 실패 5회 또는 MFA 챌린지 실패 5회 시 15분간 새 시도를 제한한다.
- IP별 1분 동안 인증 요청 20회를 초과하면 `429`와 `Retry-After`를 반환한다.
- 계정 제한과 IP 제한을 함께 사용하고 정상 계정을 잠그기 위한 단일 IP 공격이 전체 서비스 장애로 번지지 않게 분리한다.
- 성공·실패 응답 시간 차이로 계정 존재 여부를 쉽게 추론하지 못하도록 사용자 조회 실패도 비밀번호 검증에 준하는 비용을 사용한다.

오류 응답은 SQL, 제약조건명, 테이블·컬럼명, 스택 트레이스, 라이브러리 예외 메시지, 사용자 입력값을 포함하지 않는다.

## 7. 인증 오류 매핑

| 상황 | HTTP | 코드 | 외부 메시지 |
|---|---:|---|---|
| 아이디·비밀번호·MFA·복구 코드 불일치 | 401 | `AUTH_UNAUTHENTICATED` | 인증 정보가 올바르지 않습니다. |
| 만료·폐기 세션 또는 MFA 미완료 상태 | 401 | `AUTH_UNAUTHENTICATED` | 인증이 필요합니다. |
| 권한 코드 부족 | 403 | `AUTH_FORBIDDEN` | 요청을 수행할 권한이 없습니다. |
| 단건 객체가 사용자 범위 밖 | 404 | 해당 `{DOMAIN}_NOT_FOUND` | 요청한 리소스를 찾을 수 없습니다. |
| CSRF 누락·불일치 | 403 | `AUTH_FORBIDDEN` | 요청 검증에 실패했습니다. |
| 로그인·MFA 시도 제한 | 429 | `COMMON_RATE_LIMIT_EXCEEDED` | 잠시 후 다시 시도해 주세요. |
| 예상하지 못한 인증 내부 오류 | 500 | `COMMON_INTERNAL_ERROR` | 서버 내부 오류가 발생했습니다. |

인증 필터 단계의 오류도 [`common-api-response-and-errors.md`](./common-api-response-and-errors.md)의 `ErrorResponse` 형식과 `X-Request-Id`를 사용한다.

## 8. 구현 인수 조건

Day 3·4 구현에서는 최소한 다음을 자동 테스트한다.

- 운영 쿠키 속성 및 응답·로그에 세션 ID가 없는지
- 비밀번호 성공만으로 업무 API에 접근할 수 없는지
- MFA challenge 만료·시도 제한·time-step 재사용 차단
- 로그인·MFA 성공 시 세션 ID와 CSRF 토큰 교체
- 유휴·절대 만료 및 동시 세션 1개 제한
- 로그아웃·비밀번호·권한·MFA 변경 시 세션 폐기
- 로그인, 로그아웃, MFA API의 CSRF 누락·불일치 거부
- 계정 존재 여부와 내부 예외가 오류 응답에 노출되지 않는지
- 필터 단계 401·403·429가 공통 오류 형식인지
