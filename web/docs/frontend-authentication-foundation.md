# 프론트 공통 인증 기반

## 1. 목적과 경계

관리자 SPA는 서버 세션을 인증의 정본으로 사용한다. 프론트 상태는 화면 전이를 위한
일시적인 투영이며 인증 근거가 아니다. 브라우저는 세션 쿠키 값을 읽지 않고 모든 API에
`credentials: include`를 적용한다.

다음 정보는 React state 또는 모듈 메모리에만 존재한다.

- 현재 사용자와 프론트 인증 상태
- pre-auth challenge 만료 시각
- CSRF 토큰
- TOTP 등록 URI·수동 입력키
- 최초 발급 복구 코드

위 값을 `localStorage`, `sessionStorage`, IndexedDB 또는 JS가 만드는 별도 인증 쿠키에
저장하지 않는다. 새로고침하면 메모리 값은 사라지고 서버 세션은
`GET /api/v1/auth/me`로 다시 확인한다.

## 2. API Client

`apiFetch`는 다음 정책을 중앙 적용한다.

- `credentials: include`
- 상태 변경 메서드의 `X-CSRF-TOKEN`
- `Accept: application/json`, JSON 요청의 `Content-Type`
- `Cache-Control: no-store`에 맞춘 브라우저 요청 캐시 미사용
- 공통 `ErrorResponse` 파싱과 `X-Request-Id` 보존
- 네트워크 오류의 안전한 사용자 메시지

CSRF 토큰이 없을 때 `GET /api/v1/auth/csrf-token`을 한 번만 호출한다. 같은 시점의
여러 변경 요청은 진행 중인 토큰 Promise를 공유한다. MFA 성공 응답의 새 토큰은 기존
메모리 토큰과 즉시 교체한다.

### 오류 처리

| 응답 | 처리 |
|---|---|
| 보호 API `401` | CSRF와 사용자 메모리 상태 삭제, 로그인 경로로 전환 |
| 로그인·MFA 불일치 `401` | 같은 단계에서 재시도 가능, 공통 로그아웃 알림은 억제 |
| 변경 요청 `403` | CSRF 메모리 폐기, 사용자에게 재시도 또는 권한 확인 안내 |
| `413` | 허용 크기 초과 안내 |
| `429` | `Retry-After`가 있으면 남은 초를 포함해 안내 |

서버가 권한 부족과 CSRF 거부를 모두 `403 AUTH_FORBIDDEN`으로 공개하므로 클라이언트가
둘을 확정적으로 구별하지 않는다. 403 이후 CSRF는 폐기하지만 변경 요청을 자동
재전송하지 않는다. 처리 여부가 불명확한 요청을 자동 재전송해 중복 변경을 만드는 것보다
사용자의 명시적인 재시도가 안전하다.

현재 비밀번호 불일치도 세션 만료와 같은 401 계약을 사용한다. 마이페이지는 이 응답을
받으면 `/auth/me`를 다시 조회한다. 세션이 유효하면 비밀번호 오류만 표시하고, 세션이
만료되었으면 공통 익명 상태로 전환한다.

## 3. 프론트 인증 상태 머신

```text
BOOTSTRAPPING
  ├─ /auth/me 200 ───────────────────────────────> AUTHENTICATED
  └─ /auth/me 401 또는 복원 실패 ───────────────> ANONYMOUS

ANONYMOUS
  └─ 로그인 202 ─> PASSWORD_CHANGE_REQUIRED
                  | MFA_ENROLLMENT_REQUIRED
                  | MFA_REQUIRED

PASSWORD_CHANGE_REQUIRED
  └─ 변경 202 ───────────────────────────────────> MFA_ENROLLMENT_REQUIRED

MFA_ENROLLMENT_REQUIRED
  └─ 등록 확인 200 + CSRF 교체 ─────────────────> AUTHENTICATED

MFA_REQUIRED
  └─ TOTP/복구 코드 200 + CSRF 교체 ───────────> AUTHENTICATED

AUTHENTICATED
  └─ 로그아웃·세션 만료·비밀번호 변경 ─────────> ANONYMOUS
```

보호 라우트는 `AUTHENTICATED`만 허용한다. 각 pre-auth 화면도 대응하는 상태에서만
접근할 수 있다. URL을 직접 입력해 상태 머신을 건너뛸 수 없고, 서버 역시 모든 API에서
세션 상태를 다시 검증한다.

challenge 만료 시각은 메모리 timer로 관리한다. 새로고침 중 pre-auth 상태를 조회하는
별도 서버 API는 제공하지 않으므로 `/auth/me`가 401이면 로그인부터 다시 시작한다.
인증 완료 세션은 새로고침 후 정상 복원된다.

## 4. 화면과 API 연결

| 화면 | 경로 | API |
|---|---|---|
| 회원가입·중복 확인 | `/register` | `POST /auth/email-availability`, `POST /auth/registrations` |
| 로그인 | `/login` | `POST /auth/login` |
| 초기 비밀번호 변경 | `/auth/password-change` | `POST /auth/password/initial-change` |
| MFA 등록 | `/auth/mfa-enrollment` | `POST /auth/mfa/totp/enroll`, `POST /auth/mfa/totp/confirm` |
| MFA 인증 | `/auth/mfa` | `POST /auth/mfa/totp/verify`, `POST /auth/mfa/recovery` |
| 마이페이지 | `/my` | `GET /my/profile` |
| 비밀번호 변경 | `/my` | `PATCH /my/password` |
| 로그아웃 | `/my` | `POST /auth/logout` |

회원가입은 승인 대기 상태를 안내한 후 로그인 화면으로 이동하며 자동 인증하지 않는다.
MFA 등록 성공 시 복구 코드 10개를 한 번만 화면에 표시하고 사용자가 보관 완료를
확인해야 업무 화면으로 이동한다. TOTP secret과 복구 코드는 컴포넌트가 사라지면 함께
사라진다.

비밀번호 변경 성공 시 서버가 모든 세션과 현재 쿠키를 폐기하므로 프론트도 즉시
사용자·CSRF 상태를 삭제하고 다시 로그인을 요구한다. 로그아웃 요청이 네트워크 또는
서버 오류로 실패하면 서버 세션이 살아 있을 수 있으므로 성공으로 가장해 로컬 상태만
삭제하지 않는다.

## 5. 개발·운영 연결

운영 이미지는 Nginx의 `/api/` reverse proxy를 사용한다. Vite 개발 서버도 `/api`를
로컬 백엔드 `127.0.0.1:8080`으로 proxy한다. 따라서 애플리케이션 코드는 환경별 API
Origin이나 세션 쿠키 값을 다루지 않고 같은 origin의 상대 경로만 사용한다.

## 6. 검증 기준

- TypeScript production build 성공
- ESLint 성공
- 소스에서 인증정보의 Web Storage 접근 없음
- 모든 인증 변경 요청에 메모리 CSRF 헤더 적용
- MFA 성공 응답의 CSRF 교체
- 보호 API 401에서 익명 상태 전환
- 로그인·MFA 실패 401은 해당 화면에서 재시도
- 403·413·429 공통 안내
- 새로고침 시 `/auth/me` 복원
- 보호 라우트와 pre-auth 라우트의 상태별 접근 제한
- 비밀번호 변경과 로그아웃 후 이전 화면 재진입 차단

