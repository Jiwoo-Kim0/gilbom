-- 공개 CSRF token 조회가 쿠키를 유지하지 않는 요청마다 새 JDBC 세션을 만들 수 있으므로
-- 이메일 중복 확인·회원가입과 분리된 IP HMAC bucket을 추가한다.
ALTER TABLE auth.authentication_rate_limit
    DROP CONSTRAINT ck_authentication_rate_limit_type,
    ADD CONSTRAINT ck_authentication_rate_limit_type CHECK (
        limit_type IN (
            'ACCOUNT_PASSWORD_FAILURE',
            'ACCOUNT_MFA_FAILURE',
            'IP_AUTHENTICATION_REQUEST',
            'IP_CSRF_TOKEN_ISSUANCE',
            'IP_EMAIL_AVAILABILITY_REQUEST',
            'IP_PUBLIC_REGISTRATION_REQUEST'
        )
    );

COMMENT ON TABLE auth.authentication_rate_limit IS
    '비밀번호·MFA 계정 실패와 인증·CSRF 발급·중복 확인·회원가입 IP 요청의 HMAC Rate Limit bucket';
