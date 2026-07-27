# 현재 사용자·마이페이지·비밀번호 변경·로그아웃

## 1. 조회 API 분리

`GET /api/v1/auth/me`는 SPA가 새로고침 후 인증 상태와 권한을 복원하기 위한 최소
스냅샷이다. 내부 사용자 UUID, 표시 이름, 활성 역할과 permission만 반환한다.

`GET /api/v1/my/profile`은 마이페이지 표시용이며 다음 정보만 반환한다.

- 이메일과 표시 이름
- 부서, 직책, 사무실
- 활성 역할과 permission
- MFA 등록 상태
- 비밀번호 변경·최근 로그인 시각

두 API 모두 `AUTHENTICATED` 세션과 내부 UUID principal이 일치해야 하며
`Cache-Control: no-store`를 사용한다. 비밀번호 hash·salt, TOTP secret·암호문,
복구 코드, 세션 ID와 CSRF는 DTO에 필드 자체를 만들지 않았다.

## 2. 본인 비밀번호 변경

`PATCH /api/v1/my/password`는 CSRF가 필수이며 현재 비밀번호와 새 비밀번호를 받는다.
두 값 모두 trim·정규화·대소문자 변경 없이 Unicode code point 기준 10~24자를
허용한다.

처리 순서는 다음과 같다.

1. 현재 `AUTHENTICATED` 세션과 내부 UUID principal 소유권을 확인한다.
2. 활성 계정과 현재 Argon2id encoded hash를 조회한다.
3. 현재 비밀번호를 중앙 `PasswordHashingService`로 재검증한다.
4. 새 비밀번호가 기존 hash와 일치하면 `422 AUTH_PASSWORD_REUSE_NOT_ALLOWED`로 거부한다.
5. 중앙 Argon2id encoder로 새 hash를 먼저 생성한다.
6. 기존 hash가 조회 이후 바뀌지 않았다는 조건으로 새 hash를 원자적으로 교체한다.
7. 필수 비밀번호 변경 감사와 사용자 전체 세션 삭제를 같은 DB 트랜잭션으로 커밋한다.
8. 현재 Servlet 세션과 브라우저 쿠키를 만료하고 204를 반환한다.

Argon2id encoder는 호출할 때마다 새로운 16-byte random salt를 생성하고 전체
`{argon2}$argon2id$...` 문자열에 salt와 파라미터를 포함한다. hash 생성이나 필수 감사,
전체 세션 삭제가 실패하면 기존 hash를 유지하거나 트랜잭션을 rollback한다.

현재 비밀번호 불일치와 저장 hash 오류는 외부에서 구별할 수 없는 동일한 401을 사용한다.
비밀번호 원문·hash·salt·해싱 시간과 예외 메시지는 로그·감사·응답에 기록하지 않는다.

## 3. 로그아웃

`POST /api/v1/auth/logout`은 CSRF가 필수이며 인증 완료 세션과 pre-auth challenge
세션에서 모두 사용할 수 있다.

1. 현재 세션의 서버 저장 row를 삭제한다.
2. Servlet 세션과 SecurityContext를 폐기한다.
3. 로그아웃 감사를 별도 best-effort 트랜잭션으로 기록한다.
4. 로컬에서는 `GILBOM_SESSION`, 운영에서는 `__Host-GILBOM_SESSION`을 동일 Path에서
   `Max-Age=0`, `HttpOnly`, 설정된 `Secure`·`SameSite` 속성으로 만료한다.

서버 row 삭제 후에는 이전 쿠키 값을 다시 전송해도 세션을 복원할 수 없다. 감사 저장
장애는 이미 성공한 로그아웃을 되돌리지 않는다.

## 4. 감사와 실패 정책

| 이벤트 | 정책 |
|---|---|
| `AUTH_PASSWORD_CHANGED` | 필수, hash 변경과 같은 트랜잭션 |
| `AUTH_PASSWORD_CHANGE_FAILED` | 필수 |
| `AUTH_SESSION_REVOKED` | 필수, 전체 세션 삭제와 같은 트랜잭션 |
| `AUTH_LOGOUT` | 서버 세션 폐기 후 best-effort |

## 5. 검증 범위

테스트는 profile secret 비노출, pre-auth 조회 거부, 현재 비밀번호 재검증, 동일 비밀번호
거부, hash 생성 후 교체 순서, 낙관적 hash 경쟁 조건, 전체 세션 폐기, 감사 실패 시
미완료, pre-auth 로그아웃, CSRF 필수, `no-store`, 비밀번호 검증 오류 비반사와
`Max-Age=0` 쿠키를 검증한다.
