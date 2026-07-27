\set ON_ERROR_STOP on

-- 공개 가입 계정과 V8 Rate Limit type을 실제 runtime role로 검증하고 모두 rollback한다.
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
    '96000000-0000-4000-8000-000000000001',
    'USR-PUBLIC-SMOKE',
    '{argon2}$argon2id$v=19$m=19456,t=2,p=1$public-registration-test',
    'Public Registration Smoke',
    'Public.User@Example.Invalid',
    'PENDING_APPROVAL',
    FALSE,
    FALSE,
    NOW(),
    'PUBLIC_REGISTRATION'
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
            '96000000-0000-4000-8000-000000000002',
            'USR-PUBLIC-DUPLICATE',
            '{argon2}$argon2id$v=19$m=19456,t=2,p=1$duplicate-test',
            'Duplicate Public Registration',
            'public.user@example.invalid'
        );
        RAISE EXCEPTION 'case-insensitive public registration duplicate was accepted'
            USING ERRCODE = 'ZX008';
    EXCEPTION
        WHEN unique_violation THEN NULL;
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
        'IP_EMAIL_AVAILABILITY_REQUEST',
        DECODE(REPEAT('41', 32), 'hex'),
        1,
        NOW(),
        NOW(),
        1,
        0,
        NULL,
        NOW() + INTERVAL '1 minute'
    ),
    (
        'IP_PUBLIC_REGISTRATION_REQUEST',
        DECODE(REPEAT('42', 32), 'hex'),
        1,
        NOW(),
        NOW(),
        1,
        0,
        NULL,
        NOW() + INTERVAL '1 hour'
    );

INSERT INTO audit.audit_log (
    actor_type,
    action_type,
    target_type,
    target_id,
    after_value,
    result
) VALUES (
    'USER',
    'AUTH_PUBLIC_REGISTRATION_CREATED',
    'USER',
    '96000000-0000-4000-8000-000000000001',
    jsonb_build_object(
        'accountStatus', 'PENDING_APPROVAL',
        'accountOrigin', 'PUBLIC_REGISTRATION',
        'roleAssigned', FALSE
    ),
    'SUCCESS'
);

SELECT (
    account_status = 'PENDING_APPROVAL'
    AND is_active = FALSE
    AND password_change_required = FALSE
    AND password_changed_at IS NOT NULL
    AND account_origin = 'PUBLIC_REGISTRATION'
    AND normalized_email = 'public.user@example.invalid'
    AND NOT EXISTS (
        SELECT 1
        FROM auth.user_role
        WHERE user_id = admin_user.user_id
    )
    AND (
        SELECT COUNT(*)
        FROM auth.authentication_rate_limit
        WHERE limit_type IN (
            'IP_EMAIL_AVAILABILITY_REQUEST',
            'IP_PUBLIC_REGISTRATION_REQUEST'
        )
    ) = 2
    AND (
        SELECT COUNT(*)
        FROM audit.audit_log
        WHERE target_id = admin_user.user_id
          AND action_type = 'AUTH_PUBLIC_REGISTRATION_CREATED'
          AND after_value = jsonb_build_object(
              'accountStatus', 'PENDING_APPROVAL',
              'accountOrigin', 'PUBLIC_REGISTRATION',
              'roleAssigned', FALSE
          )
    ) = 1
) AS public_registration_contract
FROM auth.admin_user
WHERE user_id = '96000000-0000-4000-8000-000000000001'
\gset

\if :public_registration_contract
    \echo 'Public registration and Rate Limit contract: PASS'
\else
    \echo 'Public registration and Rate Limit contract: FAIL'
    \quit 1
\endif

ROLLBACK;
