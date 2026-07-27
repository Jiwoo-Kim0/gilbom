# 세션·쿠키·CSRF 구현

## 1. 저장 경계

브라우저 인증은 JWT가 아니라 PostgreSQL 기반 Spring Session JDBC를 사용한다.

| 저장 위치 | 내용 |
|---|---|
| 브라우저 session cookie | 추측 불가능한 Spring Session ID |
| `session_store.spring_session` | 생성·최근 접근·유휴 만료·내부 사용자 UUID |
| `session_store.session_security_state` | challenge 상태·절대 만료·실패 횟수 |
| Spring Session attribute | SecurityContext와 서버 측 CSRF 토큰 |
| 프론트 모듈 메모리 | API 응답으로 받은 현재 CSRF 토큰 |

세션 ID, CSRF 토큰, SecurityContext 직렬화 값은 감사 로그나 일반 로그에 기록하지 않는다.

## 2. 상태와 수명

`GET /api/v1/auth/csrf-token`이 먼저 익명 Spring Session을 만든다. 비밀번호 검증
성공 시 같은 세션의 companion row를 다음 challenge 중 하나로 만든다.

```text
PASSWORD_CHANGE_REQUIRED
MFA_ENROLLMENT_REQUIRED
MFA_REQUIRED
```

challenge는 시작 시점부터 5분이며 초기 비밀번호 변경 후에도 다시 5분을 부여하지 않는다.
challenge 세션에는 인증된 SecurityContext나 업무 권한을 저장하지 않는다.
challenge 실패 횟수는 원자적으로 증가시키고 5회에 도달하면 현재 세션을 즉시 폐기한다.

MFA 성공 시점에만 다음 작업을 수행한다.

1. 현재 세션을 제외한 해당 사용자의 인증·pre-auth 세션을 모두 DB에서 폐기한다.
2. 현재 challenge를 `AUTHENTICATED`로 원자 전환한다.
3. Servlet/Spring Session의 세션 ID를 교체한다.
4. 내부 사용자 UUID principal을 가진 SecurityContext를 저장한다.
5. 기존 CSRF를 제거하고 새 토큰을 생성한다.
6. 새 CSRF와 MFA 성공 시점부터 8시간인 절대 만료 시각을 응답 DTO에 제공한다.

MFA 완료 중 세션 또는 CSRF 교체가 실패하면 현재 세션을 폐기하고 인증 완료를 반환하지
않는다.

## 3. 만료와 동시 세션

- Spring Session의 `last_access_time`과 `expiry_time`이 요청마다 30분 유휴 만료를 갱신한다.
- `absolute_expiry_time`은 갱신하지 않으며 MFA 성공 후 최대 8시간이다.
- 보안 필터는 요청마다 companion row와 SecurityContext의 상태·내부 UUID를 대조한다.
- challenge 또는 절대 만료, 상태 불일치, 소유자 불일치는 세션을 폐기하고
  `401 AUTH_UNAUTHENTICATED`를 반환한다.
- 사용자 row 잠금과 partial UNIQUE index로 인증 완료 세션은 사용자당 하나만 허용한다.
- 접근이 없는 만료 challenge와 절대 만료 세션은 1분 주기, 최대 1,000개씩 정리한다.

Spring Session 자체의 유휴 만료와 별도로 절대 만료를 검사하므로 정상 요청을 계속 보내도
8시간을 넘겨 인증을 유지할 수 없다.

## 4. 폐기 API

세션 서비스는 다음 내부 연산을 제공한다.

- 현재 세션 폐기: 로그아웃
- 세션 ID 기준 개별 폐기: 강제 로그아웃
- 사용자 기준 전체 폐기: 비밀번호·MFA·계정·권한 변경

비밀번호 또는 MFA 변경 API는 반드시 현재 요청을 함께 전달하는 전체 폐기 메서드를
사용한다. 그래야 요청 처리 종료 시 현재 Spring Session이 다시 저장되는 것을 막을 수 있다.

초기 비밀번호 변경은 아직 인증 완료 세션이 아니므로 예외적으로 현재 5분 pre-auth
challenge만 유지하고 같은 사용자의 다른 pre-auth·인증 세션을 모두 폐기한다. 유지한
challenge는 새 5분을 부여받지 않고 기존 만료 시각 안에서 TOTP 등록으로만 진행한다.

본인 비밀번호 변경은 새 Argon2id hash 생성이 끝난 뒤 DB 트랜잭션에서 hash 교체,
필수 감사와 사용자 전체 세션 삭제를 함께 커밋한다. 커밋 후 현재 Servlet 세션을
invalidate하고 동일한 cookie 이름·Path에 `Max-Age=0`을 명시해 다시 로그인을 요구한다.

로그아웃은 인증 완료 세션뿐 아니라 비밀번호 변경·MFA 대기 challenge에서도 호출할 수
있다. 서버 세션 삭제를 먼저 확정하고 로그아웃 감사는 best-effort로 기록한 뒤 쿠키를
즉시 만료한다.

## 5. 쿠키

| 속성 | 로컬 HTTP | 운영 HTTPS |
|---|---|---|
| 이름 | `GILBOM_SESSION` | `__Host-GILBOM_SESSION` |
| HttpOnly | `true` | `true` |
| Secure | `false` | `true` |
| SameSite | `Lax` | `Lax` |
| Path | `/` | `/` |
| Domain | 미설정 | 미설정 |
| Max-Age/Expires | 미설정 | 미설정 |

브라우저에는 영속 쿠키가 아니라 세션 쿠키로 저장한다. 서버의 30분·8시간 만료가
보안 정본이며, 폐기된 쿠키 값을 다시 보내도 DB 세션이 없어 인증할 수 없다.

## 6. 프론트 CSRF

`csrfTokenStore.ts`는 토큰을 module-level 변수에만 저장한다. 안전하지 않은 HTTP 메서드는
`apiFetch`가 `credentials: include`와 `X-CSRF-TOKEN`을 자동 적용한다.

- `localStorage`, `sessionStorage`, IndexedDB, cookie에 CSRF를 복제하지 않는다.
- MFA 성공 응답은 `replaceCsrfToken`으로 즉시 교체한다.
- 로그아웃과 공통 401 처리는 `clearCsrfToken`으로 메모리 값을 제거한다.
- 새로고침으로 메모리가 사라지면 공개 CSRF API에서 다시 발급받는다.
