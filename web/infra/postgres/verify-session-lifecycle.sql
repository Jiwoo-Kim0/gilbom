\set ON_ERROR_STOP on

-- 세션 수명·동시 세션·부모 세션 폐기 cascade를 runtime role로 검증하고 rollback한다.
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
    '97000000-0000-4000-8000-000000000001',
    'USR-SESSION-SMOKE',
    '{argon2}$argon2id$v=19$m=19456,t=2,p=1$session-lifecycle-test',
    'Session Lifecycle Smoke',
    'session.lifecycle@example.invalid',
    'ACTIVE',
    TRUE,
    FALSE,
    NOW(),
    'PUBLIC_REGISTRATION'
);

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
        '97100000-0000-4000-8000-000000000001',
        '97200000-0000-4000-8000-000000000001',
        1800000000000,
        1800000000000,
        1800,
        1800001800000,
        NULL
    ),
    (
        '97100000-0000-4000-8000-000000000002',
        '97200000-0000-4000-8000-000000000002',
        1800000000000,
        1800000000000,
        1800,
        1800001800000,
        '97000000-0000-4000-8000-000000000001'
    );

INSERT INTO session_store.session_security_state (
    session_primary_id,
    user_id,
    authentication_state,
    absolute_expiry_time,
    authentication_challenge_expires_at,
    authentication_challenge_failure_count,
    created_time,
    updated_time,
    authentication_challenge_started_time
) VALUES (
    '97100000-0000-4000-8000-000000000001',
    '97000000-0000-4000-8000-000000000001',
    'MFA_REQUIRED',
    1800000300000,
    1800000300000,
    0,
    1800000000000,
    1800000000000,
    1800000000000
);

INSERT INTO session_store.session_security_state (
    session_primary_id,
    user_id,
    authentication_state,
    absolute_expiry_time,
    created_time,
    updated_time
) VALUES (
    '97100000-0000-4000-8000-000000000002',
    '97000000-0000-4000-8000-000000000001',
    'AUTHENTICATED',
    1800028800000,
    1800000000000,
    1800000000000
);

WITH incremented AS (
    UPDATE session_store.session_security_state security_state
    SET authentication_challenge_failure_count =
            authentication_challenge_failure_count + 1,
        updated_time = 1800000000001
    FROM session_store.spring_session session
    WHERE session.primary_id = security_state.session_primary_id
      AND session.session_id =
          '97200000-0000-4000-8000-000000000001'
      AND security_state.user_id =
          '97000000-0000-4000-8000-000000000001'
      AND security_state.authentication_state = 'MFA_REQUIRED'
      AND security_state.authentication_challenge_failure_count < 5
      AND security_state.authentication_challenge_expires_at
          > 1800000000001
    RETURNING authentication_challenge_failure_count
)
SELECT authentication_challenge_failure_count = 1
    AS challenge_failure_incremented
FROM incremented
\gset

DO $$
BEGIN
    BEGIN
        INSERT INTO session_store.spring_session (
            primary_id,
            session_id,
            creation_time,
            last_access_time,
            max_inactive_interval,
            expiry_time
        ) VALUES (
            '97100000-0000-4000-8000-000000000003',
            '97200000-0000-4000-8000-000000000003',
            1800000000000,
            1800000000000,
            3600,
            1800003600000
        );
        RAISE EXCEPTION 'a session idle timeout longer than 30 minutes was accepted'
            USING ERRCODE = 'ZX009';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        UPDATE session_store.session_security_state
        SET absolute_expiry_time = 1800000300001,
            authentication_challenge_expires_at = 1800000300001
        WHERE session_primary_id =
            '97100000-0000-4000-8000-000000000001';
        RAISE EXCEPTION 'a challenge longer than 5 minutes was accepted'
            USING ERRCODE = 'ZX010';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        UPDATE session_store.session_security_state
        SET absolute_expiry_time = 1800028800001
        WHERE session_primary_id =
            '97100000-0000-4000-8000-000000000002';
        RAISE EXCEPTION 'an authenticated session longer than 8 hours was accepted'
            USING ERRCODE = 'ZX011';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;
END;
$$;

DELETE FROM session_store.spring_session
WHERE primary_id = '97100000-0000-4000-8000-000000000001';

SELECT (
    :'challenge_failure_incremented'::BOOLEAN
    AND NOT EXISTS (
        SELECT 1
        FROM session_store.session_security_state
        WHERE session_primary_id =
            '97100000-0000-4000-8000-000000000001'
    )
    AND (
        SELECT COUNT(*)
        FROM session_store.session_security_state
        WHERE user_id = '97000000-0000-4000-8000-000000000001'
          AND authentication_state = 'AUTHENTICATED'
    ) = 1
    AND EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_session_security_lifetime_limits'
          AND conrelid =
              'session_store.session_security_state'::REGCLASS
    )
    AND EXISTS (
        SELECT 1
        FROM pg_indexes
        WHERE schemaname = 'session_store'
          AND tablename = 'session_security_state'
          AND indexname = 'uq_session_security_authenticated_user'
    )
) AS session_lifecycle_contract
\gset

\if :session_lifecycle_contract
    \echo 'Session idle/absolute/challenge/concurrency/invalidation contract: PASS'
\else
    \echo 'Session idle/absolute/challenge/concurrency/invalidation contract: FAIL'
    \quit 1
\endif

ROLLBACK;
