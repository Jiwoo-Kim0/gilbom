-- 이메일을 유일한 로그인 ID로 사용한다. 기존 username은 데이터 보존을 위해 선택적 legacy 값으로 남긴다.
ALTER TABLE auth.admin_user
    DROP CONSTRAINT uq_admin_username,
    DROP CONSTRAINT ck_admin_username_not_blank,
    DROP CONSTRAINT uq_admin_email,
    DROP CONSTRAINT ck_admin_soft_delete;

ALTER TABLE auth.admin_user
    RENAME COLUMN username TO legacy_username;

ALTER TABLE auth.admin_user
    ALTER COLUMN legacy_username DROP NOT NULL,
    ALTER COLUMN email TYPE VARCHAR(254),
    ALTER COLUMN password_hash TYPE VARCHAR(512),
    ALTER COLUMN is_active SET DEFAULT FALSE,
    ADD COLUMN normalized_email VARCHAR(254)
        GENERATED ALWAYS AS (LOWER(email)) STORED,
    ADD COLUMN account_status VARCHAR(24),
    ADD COLUMN failed_login_count INTEGER DEFAULT 0 NOT NULL,
    ADD COLUMN last_failed_login_at TIMESTAMPTZ,
    ADD COLUMN locked_until TIMESTAMPTZ;

-- 기존 계정의 활성·삭제 상태를 새 생명주기 상태로 손실 없이 변환한다.
UPDATE auth.admin_user
SET account_status = CASE
    WHEN deleted_at IS NOT NULL THEN 'DELETED'
    WHEN is_active THEN 'ACTIVE'
    ELSE 'DISABLED'
END;

ALTER TABLE auth.admin_user
    ALTER COLUMN normalized_email SET NOT NULL,
    ALTER COLUMN account_status SET DEFAULT 'PENDING_APPROVAL',
    ALTER COLUMN account_status SET NOT NULL,
    ADD CONSTRAINT uq_admin_normalized_email UNIQUE (normalized_email),
    ADD CONSTRAINT ck_admin_legacy_username CHECK (
        legacy_username IS NULL
        OR (legacy_username = BTRIM(legacy_username) AND legacy_username <> '')
    ),
    ADD CONSTRAINT ck_admin_account_status CHECK (
        account_status IN ('PENDING_APPROVAL', 'ACTIVE', 'DISABLED', 'DELETED')
    ),
    ADD CONSTRAINT ck_admin_account_lifecycle CHECK (
        (
            account_status = 'PENDING_APPROVAL'
            AND is_active = FALSE
            AND deleted_at IS NULL
        )
        OR
        (
            account_status = 'ACTIVE'
            AND is_active = TRUE
            AND deleted_at IS NULL
        )
        OR
        (
            account_status = 'DISABLED'
            AND is_active = FALSE
            AND deleted_at IS NULL
        )
        OR
        (
            account_status = 'DELETED'
            AND is_active = FALSE
            AND deleted_at IS NOT NULL
        )
    ),
    ADD CONSTRAINT ck_admin_login_failure_state CHECK (
        (
            account_status = 'ACTIVE'
            AND (
                (
                    failed_login_count = 0
                    AND last_failed_login_at IS NULL
                    AND locked_until IS NULL
                )
                OR
                (
                    failed_login_count > 0
                    AND last_failed_login_at IS NOT NULL
                    AND (
                        locked_until IS NULL
                        OR locked_until > last_failed_login_at
                    )
                )
            )
        )
        OR
        (
            account_status <> 'ACTIVE'
            AND failed_login_count = 0
            AND last_failed_login_at IS NULL
            AND locked_until IS NULL
        )
    ),
    ADD CONSTRAINT ck_admin_password_change_state CHECK (
        password_change_required = TRUE
        OR password_changed_at IS NOT NULL
    ),
    ADD CONSTRAINT ck_admin_timestamps CHECK (updated_at >= created_at);

CREATE UNIQUE INDEX uq_admin_legacy_username
    ON auth.admin_user (legacy_username)
    WHERE legacy_username IS NOT NULL;
CREATE INDEX ix_admin_account_status
    ON auth.admin_user (account_status, created_at);
CREATE INDEX ix_admin_locked_until
    ON auth.admin_user (locked_until)
    WHERE locked_until IS NOT NULL;

COMMENT ON COLUMN auth.admin_user.legacy_username IS
    '이메일 로그인 전환 전 식별자. 신규 계정에는 사용하거나 요구하지 않는다.';
COMMENT ON COLUMN auth.admin_user.email IS
    '표시용 원본 이메일. 앞뒤 공백 없이 최대 254자';
COMMENT ON COLUMN auth.admin_user.normalized_email IS
    'LOWER(email)로 DB가 생성하는 로그인·중복 판정용 이메일';
COMMENT ON COLUMN auth.admin_user.account_status IS
    'PENDING_APPROVAL, ACTIVE, DISABLED, DELETED 계정 생명주기';
COMMENT ON COLUMN auth.admin_user.is_active IS
    '기존 조회 호환용 값. account_status=ACTIVE일 때만 TRUE';
COMMENT ON COLUMN auth.admin_user.password_hash IS
    'salt와 파라미터를 포함한 전체 {argon2}$argon2id$ encoded hash. 원문·암호문 저장 금지';
COMMENT ON COLUMN auth.admin_user.failed_login_count IS
    '최근 성공 이후 연속 비밀번호 로그인 실패 횟수';
COMMENT ON COLUMN auth.admin_user.last_failed_login_at IS
    '마지막 비밀번호 로그인 실패 시각';
COMMENT ON COLUMN auth.admin_user.locked_until IS
    '현재 시각보다 미래이면 임시 잠금. 생명주기 account_status와 분리한다.';

COMMENT ON COLUMN auth.authentication_rate_limit.subject_key_hash IS
    '정규화 이메일 또는 IP 원문 대신 외부 pepper로 만든 HMAC-SHA-256';

-- 복구 코드도 전체 Argon2id encoded hash만 저장하고 salt·알고리즘 컬럼은 만들지 않는다.
ALTER TABLE auth.mfa_recovery_code
    DROP CONSTRAINT ck_mfa_recovery_code_hash,
    ALTER COLUMN code_hash TYPE VARCHAR(512),
    ADD CONSTRAINT ck_mfa_recovery_code_hash CHECK (
        code_hash LIKE '{argon2}$argon2id$%'
    );

COMMENT ON COLUMN auth.mfa_recovery_code.code_hash IS
    'salt와 파라미터를 포함한 전체 Argon2id encoded hash. 복구 코드 원문 저장 금지';

-- 마지막 성공 step은 감소·초기화할 수 없게 해 같은 factor에서 과거 step이 다시 유효해지는 것을 막는다.
CREATE FUNCTION auth.reject_totp_time_step_regression()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.last_successful_totp_time_step IS NOT NULL
       AND NEW.last_successful_totp_time_step
           IS DISTINCT FROM OLD.last_successful_totp_time_step
       AND (
           NEW.last_successful_totp_time_step IS NULL
           OR NEW.last_successful_totp_time_step
               <= OLD.last_successful_totp_time_step
       ) THEN
        RAISE EXCEPTION 'TOTP time-step cannot move backwards'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION auth.reject_totp_time_step_regression() FROM PUBLIC;

CREATE TRIGGER tr_mfa_factor_totp_time_step_monotonic
    BEFORE UPDATE OF last_successful_totp_time_step ON auth.mfa_factor
    FOR EACH ROW EXECUTE FUNCTION auth.reject_totp_time_step_regression();

-- pre-auth challenge의 시작·만료·실패 횟수와 세션 시각 관계를 명시한다.
ALTER TABLE session_store.session_security_state
    RENAME COLUMN mfa_failure_count TO authentication_challenge_failure_count;

ALTER TABLE session_store.session_security_state
    DROP CONSTRAINT ck_session_mfa_failure_count,
    DROP CONSTRAINT ck_session_security_timestamps,
    DROP CONSTRAINT ck_session_security_context,
    ADD COLUMN authentication_challenge_started_time BIGINT;

UPDATE session_store.session_security_state
SET authentication_challenge_started_time = GREATEST(
    created_time,
    LEAST(updated_time, authentication_challenge_expires_at - 1)
)
WHERE authentication_state IN (
    'PASSWORD_CHANGE_REQUIRED',
    'MFA_ENROLLMENT_REQUIRED',
    'MFA_REQUIRED'
);

ALTER TABLE session_store.spring_session
    ADD CONSTRAINT ck_spring_session_inactive_interval CHECK (
        max_inactive_interval > 0
    ),
    ADD CONSTRAINT ck_spring_session_timestamps CHECK (
        creation_time >= 0
        AND last_access_time >= creation_time
        AND expiry_time > last_access_time
    );

ALTER TABLE session_store.session_security_state
    ADD CONSTRAINT ck_session_challenge_failure_count CHECK (
        authentication_challenge_failure_count BETWEEN 0 AND 5
    ),
    ADD CONSTRAINT ck_session_security_timestamps CHECK (
        created_time >= 0
        AND updated_time >= created_time
        AND (
            absolute_expiry_time IS NULL
            OR absolute_expiry_time > created_time
        )
        AND (
            authentication_challenge_started_time IS NULL
            OR authentication_challenge_started_time >= created_time
        )
        AND (
            authentication_challenge_expires_at IS NULL
            OR (
                authentication_challenge_started_time IS NOT NULL
                AND authentication_challenge_expires_at
                    > authentication_challenge_started_time
            )
        )
    ),
    ADD CONSTRAINT ck_session_security_context CHECK (
        (
            authentication_state = 'ANONYMOUS'
            AND user_id IS NULL
            AND absolute_expiry_time IS NULL
            AND authentication_challenge_started_time IS NULL
            AND authentication_challenge_expires_at IS NULL
            AND authentication_challenge_failure_count = 0
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
            AND authentication_challenge_started_time IS NOT NULL
            AND authentication_challenge_expires_at IS NOT NULL
        )
        OR
        (
            authentication_state = 'AUTHENTICATED'
            AND user_id IS NOT NULL
            AND absolute_expiry_time IS NOT NULL
            AND authentication_challenge_started_time IS NULL
            AND authentication_challenge_expires_at IS NULL
            AND authentication_challenge_failure_count = 0
        )
    );

CREATE UNIQUE INDEX uq_session_security_authenticated_user
    ON session_store.session_security_state (user_id)
    WHERE authentication_state = 'AUTHENTICATED';

COMMENT ON COLUMN session_store.spring_session.creation_time IS
    '세션 생성 epoch milliseconds';
COMMENT ON COLUMN session_store.spring_session.last_access_time IS
    '유휴 만료 갱신에 사용하는 최근 접근 epoch milliseconds';
COMMENT ON COLUMN session_store.spring_session.principal_name IS
    '이메일이 아닌 내부 user UUID 문자열';
COMMENT ON COLUMN session_store.session_security_state.absolute_expiry_time IS
    '갱신할 수 없는 세션 절대 만료 epoch milliseconds';
COMMENT ON COLUMN session_store.session_security_state.authentication_challenge_started_time IS
    '초기 비밀번호 변경 또는 MFA challenge 시작 epoch milliseconds';
COMMENT ON COLUMN session_store.session_security_state.authentication_challenge_failure_count IS
    'challenge별 실패 횟수. 5회 도달 시 challenge를 폐기한다.';

COMMENT ON TABLE audit.audit_log IS
    '인증·권한·업무 행위를 민감 인증정보 없이 추가 전용으로 보존하는 감사 로그';
