-- 비밀번호 실패 잠금 정책을 15분 내 5회, 15분 잠금으로 DB에서도 고정한다.
-- 로그인 API 도입 전이므로 기존의 만료됐거나 불완전한 실패 상태는 안전한 초기값으로 정리한다.
UPDATE auth.admin_user
SET failed_login_count = 0,
    last_failed_login_at = NULL,
    locked_until = NULL,
    updated_at = statement_timestamp()
WHERE locked_until IS NOT NULL
  AND locked_until <= statement_timestamp();

UPDATE auth.admin_user
SET failed_login_count = 5,
    last_failed_login_at = locked_until - INTERVAL '15 minutes',
    updated_at = statement_timestamp()
WHERE locked_until > statement_timestamp();

UPDATE auth.admin_user
SET failed_login_count = 0,
    last_failed_login_at = NULL,
    updated_at = statement_timestamp()
WHERE locked_until IS NULL
  AND failed_login_count >= 5;

ALTER TABLE auth.admin_user
    ADD CONSTRAINT ck_admin_temporary_login_lock_policy CHECK (
        failed_login_count BETWEEN 0 AND 5
        AND (
            (
                locked_until IS NULL
                AND failed_login_count BETWEEN 0 AND 4
            )
            OR
            (
                locked_until IS NOT NULL
                AND failed_login_count = 5
                AND locked_until
                    = last_failed_login_at + INTERVAL '15 minutes'
            )
        )
    );

-- 사용자 UUID HMAC bucket으로 MFA 실패 제한을 세션 challenge와 별도로 유지한다.
ALTER TABLE auth.authentication_rate_limit
    DROP CONSTRAINT ck_authentication_rate_limit_type,
    ADD CONSTRAINT ck_authentication_rate_limit_type CHECK (
        limit_type IN (
            'ACCOUNT_PASSWORD_FAILURE',
            'ACCOUNT_MFA_FAILURE',
            'IP_AUTHENTICATION_REQUEST',
            'IP_EMAIL_AVAILABILITY_REQUEST',
            'IP_PUBLIC_REGISTRATION_REQUEST'
        )
    );

CREATE INDEX ix_audit_log_action_created
    ON audit.audit_log (action_type, created_at DESC);

COMMENT ON COLUMN auth.admin_user.locked_until IS
    '15분 내 비밀번호 실패 5회 시 마지막 실패부터 정확히 15분간 적용하는 임시 잠금';
COMMENT ON TABLE auth.authentication_rate_limit IS
    '비밀번호·MFA 계정 실패와 인증·중복 확인·회원가입 IP 요청의 HMAC Rate Limit bucket';
