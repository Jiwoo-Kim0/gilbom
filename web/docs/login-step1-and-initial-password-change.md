# 로그인 1단계·초기 비밀번호 변경 구현

- 구현 상태: 완료
- API: `POST /api/v1/auth/login`,
  `POST /api/v1/auth/password/initial-change`
- DB migration: 없음. V6·V9·V10의 계정, 세션, Rate Limit, 감사 구조 사용

## 1. 로그인 1단계

처리 순서는 다음과 같다.

1. Servlet 필터가 요청 body를 읽기 전에 IP별 인증 요청 한도
   `20회/1분`을 소비한다.
2. Bean Validation으로 이메일 형식·최대 254자와 비밀번호
   `10~24 Unicode code point`를 확인한다.
3. 이메일을 `Locale.ROOT` 소문자로 정규화해 계정과 활성 TOTP factor 존재 여부를
   조회한다.
4. 정규화 이메일 HMAC 실패 bucket의 15분 제한을 확인한다.
5. 계정이 있으면 저장된 전체 Argon2id encoded hash, 없으면 프로세스 시작 시 한 번 만든
   dummy Argon2id hash를 검증한다.
6. hash 검증 후에만 계정 활성·임시 잠금 상태를 판단한다.
7. 실패하면 HMAC bucket, 실제 `ACTIVE` 계정의 실패 횟수·잠금, 필수 실패 감사를 한
   트랜잭션에서 갱신한다.
8. 성공하면 실패 상태 초기화, `AUTH_PASSWORD_VERIFIED` 감사, 5분 pre-auth challenge
   생성을 한 트랜잭션에서 처리한다.

IP 제한은 네트워크 주소 단위의 대량 공격을 줄이고, 정규화 이메일 HMAC 제한과 실제 계정
잠금은 IP를 바꾸는 분산 공격을 줄인다. 실제 이메일 원문은 Rate Limit 저장소에 넣지 않는다.

## 2. 계정 열거와 hash 오류 방어

외부 실패 계약은 다음 네 경우에 모두 동일하다.

| 내부 원인 | 외부 응답 |
|---|---|
| 계정 없음 | `401 AUTH_UNAUTHENTICATED` |
| 비밀번호 불일치 | `401 AUTH_UNAUTHENTICATED` |
| 비활성·삭제·승인 대기 계정 | `401 AUTH_UNAUTHENTICATED` |
| 실제 계정 임시 잠금 | `401 AUTH_UNAUTHENTICATED` |

고정 메시지는 `인증 정보가 올바르지 않습니다.`다. 존재하지 않는 계정도 실제 계정과 같은
Argon2id encoder와 파라미터를 사용하는 재사용 dummy hash를 검증하므로 빠른 조회 실패가
계정 존재 신호가 되지 않는다.

저장 hash의 prefix가 Argon2id가 아니거나 encoded 값이 손상되어 encoder가 예외를
발생시켜도 같은 `401`로 변환한다. 운영 로그에는 비밀번호, 이메일, hash, salt,
파라미터, 예외 원문을 남기지 않고 `requestId`와
`PASSWORD_HASH_CONFIGURATION` 분류만 남긴다.

계정 HMAC bucket이 이미 차단된 경우에는 고비용 Argon2id 검증 전에 `429`를 반환한다.
응답에는 `Retry-After`와 `Cache-Control: no-store`가 포함된다. 동일 이메일에 대한
bucket 동작은 실제 계정 존재 여부와 무관하다.

## 3. 상태 반환

비밀번호가 맞아도 SecurityContext나 업무 권한을 만들지 않는다.

| 계정 상태 | HTTP | `loginState` |
|---|---:|---|
| `password_change_required=true` | 202 | `PASSWORD_CHANGE_REQUIRED` |
| 비밀번호 변경 완료, 활성 TOTP 없음 | 202 | `MFA_ENROLLMENT_REQUIRED` |
| 비밀번호 변경 완료, 활성 TOTP 있음 | 202 | `MFA_REQUIRED` |

`LoginChallengeResponse`는 `loginState`, `challengeExpiresAt`만 반환한다. 세션 ID, CSRF,
인증 여부, 역할은 반환하지 않으며 모든 응답은 `Cache-Control: no-store`다.
`AUTH_LOGIN_SUCCEEDED`는 이 단계가 아니라 MFA 성공으로 `AUTHENTICATED`가 되는 시점에만
기록한다.

## 4. 초기 비밀번호 변경

초기 변경 API는 Spring Security 관점에서는 아직 익명 상태이므로 permit 목록에 있지만,
서비스가 서버 세션의 정확한 `PASSWORD_CHANGE_REQUIRED` 상태, 사용자 소유권, 5분 만료와
계정의 `ACTIVE + password_change_required=true`를 모두 다시 검증한다. CSRF 헤더도
필수다.

새 비밀번호는 입력 원문 그대로 10~24자인지 검증하고 trim, 대소문자 변경, Unicode
정규화, 잘라내기를 하지 않는다. 현재 hash와 Argon2id 검증해 같은 비밀번호이면
`422 AUTH_PASSWORD_REUSE_NOT_ALLOWED`로 거부한다.

Argon2id 계산은 DB 트랜잭션 밖에서 수행한다. 이후 다음 작업은 한 트랜잭션으로 처리한다.

1. 예상한 기존 hash와 `password_change_required=true`를 조건으로 새 전체 Argon2id
   encoded hash와 `password_changed_at`을 갱신한다.
2. 현재 pre-auth challenge를 제외한 같은 사용자의 모든 세션을 폐기한다.
3. `AUTH_INITIAL_PASSWORD_CHANGED` 필수 감사를 저장한다.
4. 현재 상태를 `MFA_ENROLLMENT_REQUIRED`로 전이한다.

조건부 UPDATE는 같은 challenge를 이용한 동시 변경에서 한 요청만 성공하게 한다. 어느
단계든 실패하면 계정 변경, 세션 폐기, 성공 감사와 상태 전이가 함께 rollback된다.
현재 challenge는 TOTP 등록을 이어가기 위해서만 남기며 만료 시각을 연장하지 않는다.
일반 마이페이지 비밀번호 변경은 이 예외 없이 현재 세션까지 전부 폐기하고 재로그인을
요구한다.

## 5. 감사 실패 정책

- 비밀번호 실패, 실제 계정 잠금, 비밀번호 검증 성공, 초기 비밀번호 변경 성공·실패는
  필수 감사다.
- 필수 감사 저장이 실패하면 관련 실패 카운터·challenge·비밀번호 변경도 확정하지 않는다.
- IP·계정 Rate Limit로 이미 거부한 요청의 감사는 best-effort이며 감사 장애가 거부를
  허용으로 바꾸지 않는다.
- MFA 후 최종 로그인 성공과 로그아웃 감사는 기존 단계 6 정책을 그대로 사용한다.

## 6. 검증 범위

자동 테스트는 다음을 고정한다.

- 세 가지 202 상태와 응답의 인증정보 부재
- 미존재 계정의 dummy hash 검증
- 잠금 계정도 Argon2id 검증 후 공통 401 처리
- 손상·비지원 hash의 공통 401과 로그 민감정보 부재
- 계정별 429와 `Retry-After`
- CSRF 필수, 입력 크기 검증과 비밀번호 반사 금지
- 정확한 pre-auth 상태에서만 초기 변경 허용
- 동일 비밀번호 422, hash 오류 401
- 조건부 계정 갱신, 다른 세션 폐기, 필수 감사, 상태 전이 순서
- 초기 변경 현재 challenge 유지와 다른 세션 폐기
