# 관리자 프론트 권한과 API 연결

## 권한 정본

프론트는 인증 완료 응답과 `GET /api/v1/auth/me`의 `permissions`를 현재 세션의 권한
정본으로 사용한다. 메뉴와 라우트는 같은 permission code를 검사한다.

| 화면 | 경로 | required permission |
|---|---|---|
| 가입 요청 승인 | `/admin/approvals` | `ADMIN_SETTINGS_MANAGE` |
| 역할·권한 관리 | `/admin/roles` | `ADMIN_SETTINGS_MANAGE` |
| MFA 관리자 초기화 | `/admin/mfa-reset` | `ADMIN_SETTINGS_MANAGE` |
| 감사 로그 | `/audit-logs` | `AUDIT_LOG_VIEW` |

메뉴에서 권한 없는 항목을 숨기지만 이것을 보안 경계로 간주하지 않는다. 직접 URL
접근은 `RequirePermission`이 403 화면으로 처리하고, 모든 API는 서버의
`@PreAuthorize`와 서비스 정책을 다시 통과해야 한다.

## 화면별 변경 경계

- 가입 승인은 활성 역할과 사유를 함께 전송한다. 거절도 사유를 필수로 받는다.
- 역할 정보 변경과 role-permission 변경은 서로 다른 서버 트랜잭션·감사 이벤트이므로
  화면에서도 별도 저장 동작으로 분리한다.
- `ADMIN_SETTINGS_MANAGE`는 사용자 정의 역할에 선택할 수 없게 표시하고 서버가 최종
  불변식을 검증한다.
- MFA 초기화는 자기 자신을 선택할 수 없고 작업자의 현재 비밀번호, 6자리 TOTP,
  사유를 모두 입력해야 한다. 성공 시 대상 factor와 모든 세션이 폐기된다.
- 감사 로그 필터는 서버 계약의 action, target, result, 기간만 전달한다. 변경 전·후
  값은 서버 allowlist와 마스킹이 적용된 응답만 표시한다.

CSRF 토큰과 session cookie는 각 화면이 직접 취급하지 않는다. 공통 `apiFetch`가 상태
변경 메서드에 현재 세션의 CSRF 헤더와 credential을 자동으로 첨부한다.
