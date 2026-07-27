-- Day 3 세션 정책을 DB에서도 상한으로 고정해 설정 drift를 즉시 거부한다.
UPDATE session_store.spring_session
SET max_inactive_interval = 1800,
    expiry_time = last_access_time + 1800000;

UPDATE session_store.session_security_state
SET absolute_expiry_time = authentication_challenge_expires_at
WHERE authentication_state IN (
    'PASSWORD_CHANGE_REQUIRED',
    'MFA_ENROLLMENT_REQUIRED',
    'MFA_REQUIRED'
);

UPDATE session_store.session_security_state
SET absolute_expiry_time = LEAST(
    absolute_expiry_time,
    updated_time + 28800000
)
WHERE authentication_state = 'AUTHENTICATED';

ALTER TABLE session_store.spring_session
    DROP CONSTRAINT ck_spring_session_inactive_interval,
    ADD CONSTRAINT ck_spring_session_inactive_interval CHECK (
        max_inactive_interval = 1800
    );

ALTER TABLE session_store.session_security_state
    ADD CONSTRAINT ck_session_security_lifetime_limits CHECK (
        (
            authentication_state = 'ANONYMOUS'
            AND absolute_expiry_time IS NULL
        )
        OR
        (
            authentication_state IN (
                'PASSWORD_CHANGE_REQUIRED',
                'MFA_ENROLLMENT_REQUIRED',
                'MFA_REQUIRED'
            )
            AND authentication_challenge_expires_at
                <= authentication_challenge_started_time + 300000
            AND absolute_expiry_time
                = authentication_challenge_expires_at
        )
        OR
        (
            authentication_state = 'AUTHENTICATED'
            AND absolute_expiry_time <= updated_time + 28800000
        )
    );

CREATE INDEX ix_session_security_absolute_expiry
    ON session_store.session_security_state (absolute_expiry_time)
    WHERE absolute_expiry_time IS NOT NULL;

CREATE INDEX ix_session_security_challenge_expiry
    ON session_store.session_security_state (
        authentication_challenge_expires_at
    )
    WHERE authentication_challenge_expires_at IS NOT NULL;

COMMENT ON CONSTRAINT ck_spring_session_inactive_interval
    ON session_store.spring_session IS
    '모든 브라우저 세션의 유휴 만료는 30분으로 고정한다.';

COMMENT ON CONSTRAINT ck_session_security_lifetime_limits
    ON session_store.session_security_state IS
    '인증 전 challenge 5분, MFA 성공 후 인증 세션 절대 8시간 상한';
