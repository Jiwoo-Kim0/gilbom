-- 공개 중복 확인과 회원가입은 로그인 제한과 분리된 IP bucket을 사용한다.
ALTER TABLE auth.authentication_rate_limit
    DROP CONSTRAINT ck_authentication_rate_limit_type,
    ADD CONSTRAINT ck_authentication_rate_limit_type CHECK (
        limit_type IN (
            'ACCOUNT_PASSWORD_FAILURE',
            'IP_AUTHENTICATION_REQUEST',
            'IP_EMAIL_AVAILABILITY_REQUEST',
            'IP_PUBLIC_REGISTRATION_REQUEST'
        )
    );

COMMENT ON TABLE auth.authentication_rate_limit IS
    '계정 비밀번호 실패와 로그인·이메일 중복 확인·회원가입 IP 요청 제한 bucket';
