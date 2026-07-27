-- 적용된 V1/V3를 수정하지 않고 비밀번호 알고리즘과 초기 비밀번호 변경 상태를 확장한다.
ALTER TABLE auth.admin_user
    ADD CONSTRAINT ck_admin_password_hash_argon2id
        CHECK (password_hash LIKE '{argon2}$argon2id$%');

COMMENT ON COLUMN auth.admin_user.password_hash IS
    '{argon2}$argon2id$ 형식만 허용하는 Argon2id password hash';

-- MFA 전용 이름을 인증 전 challenge 전체를 표현하는 이름으로 일반화한다.
ALTER TABLE session_store.session_security_state
    RENAME COLUMN mfa_challenge_expires_at TO authentication_challenge_expires_at;

ALTER TABLE session_store.session_security_state
    DROP CONSTRAINT ck_session_authentication_state,
    DROP CONSTRAINT ck_session_security_context;

ALTER TABLE session_store.session_security_state
    ADD CONSTRAINT ck_session_authentication_state CHECK (
        authentication_state IN (
            'ANONYMOUS',
            'PASSWORD_CHANGE_REQUIRED',
            'MFA_ENROLLMENT_REQUIRED',
            'MFA_REQUIRED',
            'AUTHENTICATED'
        )
    ),
    ADD CONSTRAINT ck_session_security_context CHECK (
        (
            authentication_state = 'ANONYMOUS'
            AND user_id IS NULL
            AND authentication_challenge_expires_at IS NULL
        )
        OR
        (
            authentication_state IN (
                'PASSWORD_CHANGE_REQUIRED',
                'MFA_ENROLLMENT_REQUIRED',
                'MFA_REQUIRED'
            )
            AND user_id IS NOT NULL
            AND absolute_expiry_time IS NOT NULL
            AND authentication_challenge_expires_at IS NOT NULL
        )
        OR
        (
            authentication_state = 'AUTHENTICATED'
            AND user_id IS NOT NULL
            AND absolute_expiry_time IS NOT NULL
            AND authentication_challenge_expires_at IS NULL
        )
    );

COMMENT ON TABLE session_store.session_security_state IS
    '초기 비밀번호 변경·MFA 진행 상태와 인증 세션 절대 만료 metadata';
COMMENT ON COLUMN session_store.session_security_state.authentication_challenge_expires_at IS
    '초기 비밀번호 변경 또는 MFA challenge 만료 epoch milliseconds';
