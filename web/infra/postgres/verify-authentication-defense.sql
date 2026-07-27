\set ON_ERROR_STOP on

-- 비밀번호 실패 잠금, 인증 Rate Limit type, 감사 이벤트의 DB 계약을 runtime role로 검증한다.
BEGIN;

INSERT INTO auth.admin_user (
    user_id,
    user_code,
    password_hash,
    display_name,
    email,
    account_status,
    is_active,
    password_change_required,
    password_changed_at,
    account_origin
) VALUES (
    '98000000-0000-4000-8000-000000000001',
    'USR-AUTH-DEFENSE',
    '{argon2}$argon2id$v=19$m=19456,t=2,p=1$auth-defense-test',
    'Authentication Defense Smoke',
    'auth.defense@example.invalid',
    'ACTIVE',
    TRUE,
    FALSE,
    NOW(),
    'ADMIN_CREATED'
);

DO $$
DECLARE
    attempt INTEGER;
BEGIN
    FOR attempt IN 1..5 LOOP
        UPDATE auth.admin_user
        SET failed_login_count = CASE
                WHEN last_failed_login_at IS NULL
                    OR last_failed_login_at
                        <= statement_timestamp() - INTERVAL '15 minutes'
                    THEN 1
                ELSE failed_login_count + 1
            END,
            last_failed_login_at = statement_timestamp(),
            locked_until = CASE
                WHEN (
                    CASE
                        WHEN last_failed_login_at IS NULL
                            OR last_failed_login_at
                                <= statement_timestamp()
                                    - INTERVAL '15 minutes'
                            THEN 1
                        ELSE failed_login_count + 1
                    END
                ) >= 5
                    THEN statement_timestamp() + INTERVAL '15 minutes'
                ELSE NULL
            END,
            updated_at = statement_timestamp()
        WHERE user_id = '98000000-0000-4000-8000-000000000001'
          AND account_status = 'ACTIVE'
          AND (
            locked_until IS NULL
            OR locked_until <= statement_timestamp()
          );
    END LOOP;

    BEGIN
        UPDATE auth.admin_user
        SET locked_until = last_failed_login_at + INTERVAL '1 minute'
        WHERE user_id = '98000000-0000-4000-8000-000000000001';
        RAISE EXCEPTION 'a non-15-minute account lock was accepted'
            USING ERRCODE = 'ZX012';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;
END;
$$;

INSERT INTO auth.authentication_rate_limit (
    limit_type,
    subject_key_hash,
    hash_key_version,
    window_started_at,
    last_attempt_at,
    request_count,
    failure_count,
    blocked_until,
    expires_at
) VALUES
    (
        'IP_AUTHENTICATION_REQUEST',
        DECODE(REPEAT('51', 32), 'hex'),
        1,
        NOW(),
        NOW(),
        20,
        0,
        NULL,
        NOW() + INTERVAL '1 minute'
    ),
    (
        'ACCOUNT_PASSWORD_FAILURE',
        DECODE(REPEAT('52', 32), 'hex'),
        1,
        NOW(),
        NOW(),
        5,
        5,
        NOW() + INTERVAL '15 minutes',
        NOW() + INTERVAL '15 minutes'
    ),
    (
        'ACCOUNT_MFA_FAILURE',
        DECODE(REPEAT('53', 32), 'hex'),
        1,
        NOW(),
        NOW(),
        5,
        5,
        NOW() + INTERVAL '15 minutes',
        NOW() + INTERVAL '15 minutes'
    );

INSERT INTO audit.audit_log (
    request_id,
    actor_user_id,
    actor_type,
    action_type,
    target_type,
    target_id,
    result,
    ip_address,
    user_agent
) VALUES
    (
        '98100000-0000-4000-8000-000000000001',
        NULL,
        'USER',
        'AUTH_LOGIN_FAILED',
        'USER',
        '98000000-0000-4000-8000-000000000001',
        'FAILURE',
        '203.0.113.20',
        'verification-agent'
    ),
    (
        '98100000-0000-4000-8000-000000000002',
        NULL,
        'SYSTEM',
        'AUTH_ACCOUNT_TEMPORARILY_LOCKED',
        'USER',
        '98000000-0000-4000-8000-000000000001',
        'DENIED',
        '203.0.113.20',
        'verification-agent'
    ),
    (
        '98100000-0000-4000-8000-000000000003',
        '98000000-0000-4000-8000-000000000001',
        'USER',
        'AUTH_LOGIN_SUCCEEDED',
        'USER',
        '98000000-0000-4000-8000-000000000001',
        'SUCCESS',
        '203.0.113.20',
        'verification-agent'
    ),
    (
        '98100000-0000-4000-8000-000000000004',
        '98000000-0000-4000-8000-000000000001',
        'USER',
        'AUTH_LOGOUT',
        'SESSION',
        NULL,
        'SUCCESS',
        '203.0.113.20',
        'verification-agent'
    );

DO $$
BEGIN
    BEGIN
        UPDATE audit.audit_log
        SET result = 'FAILURE'
        WHERE request_id =
            '98100000-0000-4000-8000-000000000003';
        RAISE EXCEPTION 'an audit log update was accepted'
            USING ERRCODE = 'ZX013';
    EXCEPTION
        WHEN object_not_in_prerequisite_state
            OR insufficient_privilege THEN NULL;
    END;
END;
$$;

SELECT (
    (
        SELECT failed_login_count = 5
            AND locked_until
                = last_failed_login_at + INTERVAL '15 minutes'
        FROM auth.admin_user
        WHERE user_id = '98000000-0000-4000-8000-000000000001'
    )
    AND (
        SELECT COUNT(*) = 3
        FROM auth.authentication_rate_limit
        WHERE limit_type IN (
            'IP_AUTHENTICATION_REQUEST',
            'ACCOUNT_PASSWORD_FAILURE',
            'ACCOUNT_MFA_FAILURE'
        )
    )
    AND (
        SELECT COUNT(*) = 4
        FROM audit.audit_log
        WHERE request_id IN (
            '98100000-0000-4000-8000-000000000001',
            '98100000-0000-4000-8000-000000000002',
            '98100000-0000-4000-8000-000000000003',
            '98100000-0000-4000-8000-000000000004'
        )
          AND before_value IS NULL
          AND after_value IS NULL
          AND reason IS NULL
    )
    AND EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_admin_temporary_login_lock_policy'
          AND conrelid = 'auth.admin_user'::REGCLASS
    )
    AND EXISTS (
        SELECT 1
        FROM pg_indexes
        WHERE schemaname = 'audit'
          AND tablename = 'audit_log'
          AND indexname = 'ix_audit_log_action_created'
    )
) AS authentication_defense_contract
\gset

\if :authentication_defense_contract
    \echo 'Authentication Rate Limit/lock/audit contract: PASS'
\else
    \echo 'Authentication Rate Limit/lock/audit contract: FAIL'
    \quit 1
\endif

ROLLBACK;
