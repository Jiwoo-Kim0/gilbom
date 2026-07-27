\set ON_ERROR_STOP on

-- 계정·MFA·세션의 보안 제약이 잘못된 저장을 실제로 거부하는지 검증하고 모두 rollback한다.
BEGIN;

INSERT INTO auth.admin_user (
    user_id,
    user_code,
    password_hash,
    display_name,
    email,
    account_status,
    is_active,
    account_origin
) VALUES (
    '91000000-0000-4000-8000-000000000001',
    'AUTH-CONSTRAINT-1',
    '{argon2}$argon2id$v=19$m=19456,t=2,p=1$constraint-test-hash',
    'Auth Constraint',
    'Constraint.User@Example.Invalid',
    'ACTIVE',
    TRUE,
    'ADMIN_CREATED'
);

DO $$
BEGIN
    BEGIN
        INSERT INTO auth.admin_user (
            user_id,
            user_code,
            password_hash,
            display_name,
            email
        ) VALUES (
            '91000000-0000-4000-8000-000000000002',
            'AUTH-CONSTRAINT-2',
            '{argon2}$argon2id$v=19$m=19456,t=2,p=1$duplicate-email-test',
            'Duplicate Email',
            'constraint.user@example.invalid'
        );
        RAISE EXCEPTION 'case-insensitive duplicate email was accepted'
            USING ERRCODE = 'ZX001';
    EXCEPTION
        WHEN unique_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO auth.admin_user (
            user_id,
            user_code,
            password_hash,
            display_name,
            email
        ) VALUES (
            '91000000-0000-4000-8000-000000000003',
            'AUTH-CONSTRAINT-3',
            '{bcrypt}$2a$10$not-allowed',
            'Invalid Password Hash',
            'invalid-hash@example.invalid'
        );
        RAISE EXCEPTION 'non-Argon2id password hash was accepted'
            USING ERRCODE = 'ZX002';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO auth.admin_user (
            user_id,
            user_code,
            password_hash,
            display_name,
            email,
            account_status,
            is_active
        ) VALUES (
            '91000000-0000-4000-8000-000000000004',
            'AUTH-CONSTRAINT-4',
            '{argon2}$argon2id$v=19$m=19456,t=2,p=1$invalid-state-test',
            'Invalid Account State',
            'invalid-state@example.invalid',
            'PENDING_APPROVAL',
            TRUE
        );
        RAISE EXCEPTION 'inconsistent account lifecycle was accepted'
            USING ERRCODE = 'ZX003';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;
END;
$$;

INSERT INTO auth.mfa_factor (
    factor_id,
    user_id,
    factor_status,
    secret_ciphertext,
    secret_nonce,
    secret_key_version,
    last_successful_totp_time_step,
    activated_at
) VALUES (
    '92000000-0000-4000-8000-000000000001',
    '91000000-0000-4000-8000-000000000001',
    'ACTIVE',
    DECODE(REPEAT('ab', 32), 'hex'),
    DECODE(REPEAT('cd', 12), 'hex'),
    1,
    200,
    NOW()
);

DO $$
BEGIN
    BEGIN
        INSERT INTO auth.mfa_recovery_code (
            recovery_code_id,
            factor_id,
            code_position,
            code_hash
        ) VALUES (
            '93000000-0000-4000-8000-000000000001',
            '92000000-0000-4000-8000-000000000001',
            1,
            'recoverable-plaintext-is-not-allowed'
        );
        RAISE EXCEPTION 'non-Argon2id recovery code hash was accepted'
            USING ERRCODE = 'ZX004';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        UPDATE auth.mfa_factor
        SET last_successful_totp_time_step = 199
        WHERE factor_id = '92000000-0000-4000-8000-000000000001';
        RAISE EXCEPTION 'TOTP time-step regression was accepted'
            USING ERRCODE = 'ZX005';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;
END;
$$;

INSERT INTO session_store.spring_session (
    primary_id,
    session_id,
    creation_time,
    last_access_time,
    max_inactive_interval,
    expiry_time,
    principal_name
) VALUES
    (
        '94000000-0000-4000-8000-000000000001',
        '95000000-0000-4000-8000-000000000001',
        1800000000000,
        1800000000000,
        1800,
        1800001800000,
        '91000000-0000-4000-8000-000000000001'
    ),
    (
        '94000000-0000-4000-8000-000000000002',
        '95000000-0000-4000-8000-000000000002',
        1800000000000,
        1800000000000,
        1800,
        1800001800000,
        '91000000-0000-4000-8000-000000000001'
    ),
    (
        '94000000-0000-4000-8000-000000000003',
        '95000000-0000-4000-8000-000000000003',
        1800000000000,
        1800000000000,
        1800,
        1800001800000,
        '91000000-0000-4000-8000-000000000001'
    );

INSERT INTO session_store.session_security_state (
    session_primary_id,
    user_id,
    authentication_state,
    absolute_expiry_time,
    created_time,
    updated_time
) VALUES (
    '94000000-0000-4000-8000-000000000001',
    '91000000-0000-4000-8000-000000000001',
    'AUTHENTICATED',
    1800028800000,
    1800000000000,
    1800000000000
);

DO $$
BEGIN
    BEGIN
        INSERT INTO session_store.session_security_state (
            session_primary_id,
            user_id,
            authentication_state,
            absolute_expiry_time,
            created_time,
            updated_time
        ) VALUES (
            '94000000-0000-4000-8000-000000000002',
            '91000000-0000-4000-8000-000000000001',
            'AUTHENTICATED',
            1800028800000,
            1800000000000,
            1800000000000
        );
        RAISE EXCEPTION 'a second authenticated session was accepted'
            USING ERRCODE = 'ZX006';
    EXCEPTION
        WHEN unique_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO session_store.session_security_state (
            session_primary_id,
            user_id,
            authentication_state,
            absolute_expiry_time,
            authentication_challenge_expires_at,
            created_time,
            updated_time
        ) VALUES (
            '94000000-0000-4000-8000-000000000003',
            '91000000-0000-4000-8000-000000000001',
            'MFA_REQUIRED',
            1800028800000,
            1800000300000,
            1800000000000,
            1800000000000
        );
        RAISE EXCEPTION 'a challenge without a start time was accepted'
            USING ERRCODE = 'ZX007';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;
END;
$$;

\echo 'Account/MFA/session negative constraint contract: PASS'
ROLLBACK;
