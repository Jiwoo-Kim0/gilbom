\set ON_ERROR_STOP on

-- 빈 volume에서 PostGIS, Flyway V1~V35, 필수 account/MFA/session/RBAC/security schema를 검증한다.
-- flyway_role과 application_role은 psql --set으로 전달하며 비밀번호는 받거나 출력하지 않는다.
SELECT (
    :'flyway_role' <> :'application_role'
    AND EXISTS (
        SELECT 1
        FROM pg_extension
        WHERE extname = 'postgis'
    )
    AND (
        SELECT COUNT(*) = 35
               AND COUNT(*) FILTER (WHERE NOT success) = 0
               AND COUNT(*) FILTER (
                   WHERE version::INTEGER BETWEEN 1 AND 35
                     AND success
               ) = 35
        FROM flyway_history.flyway_schema_history
    )
    AND to_regclass('auth.admin_user') IS NOT NULL
    AND to_regclass('auth.mfa_factor') IS NOT NULL
    AND to_regclass('auth.mfa_recovery_code') IS NOT NULL
    AND to_regclass('auth.authentication_rate_limit') IS NOT NULL
    AND to_regclass('auth.permission') IS NOT NULL
    AND to_regclass('auth.role_permission') IS NOT NULL
    AND to_regclass('report.anomaly_report') IS NOT NULL
    AND to_regclass('audit.audit_log') IS NOT NULL
    AND to_regclass('session_store.spring_session') IS NOT NULL
    AND to_regclass('session_store.spring_session_attributes') IS NOT NULL
    AND to_regclass('session_store.session_security_state') IS NOT NULL
    AND to_regclass('request_control.idempotency_request') IS NOT NULL
    AND to_regclass('robot.robot') IS NOT NULL
    AND to_regclass('robot.robot_credential') IS NOT NULL
    AND to_regclass('telemetry.robot_telemetry') IS NOT NULL
    AND to_regclass('telemetry.robot_current_state') IS NOT NULL
    AND EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'auth'
          AND table_name = 'admin_user'
          AND column_name = 'password_change_required'
          AND is_nullable = 'NO'
    )
    AND EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'auth'
          AND table_name = 'admin_user'
          AND column_name = 'email'
          AND character_maximum_length = 254
          AND is_nullable = 'NO'
    )
    AND EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'auth'
          AND table_name = 'admin_user'
          AND column_name = 'normalized_email'
          AND character_maximum_length = 254
          AND is_generated = 'ALWAYS'
          AND is_nullable = 'NO'
    )
    AND EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'auth'
          AND table_name = 'admin_user'
          AND column_name = 'password_hash'
          AND character_maximum_length >= 255
          AND is_nullable = 'NO'
    )
    AND NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'auth'
          AND table_name = 'admin_user'
          AND column_name IN (
              'password',
              'password_plaintext',
              'encrypted_password',
              'password_salt',
              'password_algorithm'
          )
    )
    AND (
        SELECT COUNT(*) = 6
        FROM information_schema.columns
        WHERE table_schema = 'auth'
          AND table_name = 'admin_user'
          AND column_name IN (
              'account_status',
              'failed_login_count',
              'last_failed_login_at',
              'locked_until',
              'is_active',
              'account_origin'
          )
    )
    AND EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uq_admin_normalized_email'
          AND conrelid = 'auth.admin_user'::REGCLASS
          AND contype = 'u'
    )
    AND EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_admin_temporary_login_lock_policy'
          AND conrelid = 'auth.admin_user'::REGCLASS
          AND contype = 'c'
    )
    AND EXISTS (
        SELECT 1
        FROM pg_indexes
        WHERE schemaname = 'auth'
          AND tablename = 'admin_user'
          AND indexname = 'uq_single_bootstrap_admin'
    )
    AND EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'session_store'
          AND table_name = 'session_security_state'
          AND column_name = 'authentication_challenge_expires_at'
    )
    AND EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'session_store'
          AND table_name = 'session_security_state'
          AND column_name = 'authentication_challenge_started_time'
    )
    AND EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'session_store'
          AND table_name = 'session_security_state'
          AND column_name = 'authentication_challenge_failure_count'
    )
    AND EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_session_security_lifetime_limits'
          AND conrelid =
              'session_store.session_security_state'::REGCLASS
    )
    AND (
        SELECT COUNT(*) = 5
        FROM information_schema.columns
        WHERE table_schema = 'auth'
          AND table_name = 'mfa_factor'
          AND column_name IN (
              'secret_ciphertext',
              'secret_nonce',
              'secret_key_version',
              'last_successful_totp_time_step',
              'factor_type'
          )
    )
    AND EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'auth'
          AND table_name = 'mfa_recovery_code'
          AND column_name = 'code_hash'
          AND character_maximum_length >= 255
          AND is_nullable = 'NO'
    )
    AND EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'report'
          AND table_name = 'anomaly_report'
          AND column_name = 'deleted_at'
    )
    AND (
        SELECT ARRAY_AGG(role_code ORDER BY role_code)
        FROM auth.role
        WHERE is_system AND is_active
    ) = ARRAY['ADMIN', 'MANAGER', 'OPERATOR']::VARCHAR[]
    AND (
        SELECT COUNT(*)
        FROM auth.permission
        WHERE is_system AND is_active
    ) = 18
    AND (
        SELECT COUNT(*)
        FROM auth.role_permission role_permission
        JOIN auth.role role USING (role_id)
        WHERE role.role_code = 'ADMIN' AND role_permission.is_active
    ) = 18
    AND (
        SELECT COUNT(*)
        FROM auth.role_permission role_permission
        JOIN auth.role role USING (role_id)
        WHERE role.role_code = 'MANAGER' AND role_permission.is_active
    ) = 15
    AND (
        SELECT COUNT(*)
        FROM auth.role_permission role_permission
        JOIN auth.role role USING (role_id)
        WHERE role.role_code = 'OPERATOR' AND role_permission.is_active
    ) = 10
    AND (
        SELECT COUNT(*)
        FROM auth.role_permission role_permission
        JOIN auth.role role USING (role_id)
        JOIN auth.permission permission USING (permission_id)
        WHERE permission.permission_code = 'ADMIN_SETTINGS_MANAGE'
          AND role_permission.is_active
          AND role.role_code = 'ADMIN'
          AND role.is_system
    ) = 1
    AND HAS_SCHEMA_PRIVILEGE(:'application_role', 'auth', 'USAGE')
    AND NOT HAS_SCHEMA_PRIVILEGE(:'application_role', 'auth', 'CREATE')
    AND HAS_SCHEMA_PRIVILEGE(:'application_role', 'request_control', 'USAGE')
    AND NOT HAS_SCHEMA_PRIVILEGE(:'application_role', 'request_control', 'CREATE')
    AND HAS_SCHEMA_PRIVILEGE(:'application_role', 'robot', 'USAGE')
    AND NOT HAS_SCHEMA_PRIVILEGE(:'application_role', 'robot', 'CREATE')
    AND HAS_SCHEMA_PRIVILEGE(:'application_role', 'telemetry', 'USAGE')
    AND NOT HAS_SCHEMA_PRIVILEGE(:'application_role', 'telemetry', 'CREATE')
    AND HAS_TABLE_PRIVILEGE(:'application_role', 'audit.audit_log', 'SELECT')
    AND HAS_TABLE_PRIVILEGE(:'application_role', 'audit.audit_log', 'INSERT')
    AND NOT HAS_TABLE_PRIVILEGE(:'application_role', 'audit.audit_log', 'UPDATE')
    AND NOT HAS_TABLE_PRIVILEGE(:'application_role', 'audit.audit_log', 'DELETE')
    AND HAS_SEQUENCE_PRIVILEGE(
        :'application_role',
        'robot.mqtt_id_sequence',
        'USAGE'
    )
    AND HAS_TABLE_PRIVILEGE(:'application_role', 'robot.robot_credential', 'UPDATE')
    AND NOT HAS_TABLE_PRIVILEGE(:'application_role', 'robot.robot_credential', 'DELETE')
    AND HAS_TABLE_PRIVILEGE(:'application_role', 'telemetry.robot_telemetry', 'SELECT')
    AND HAS_TABLE_PRIVILEGE(:'application_role', 'telemetry.robot_telemetry', 'INSERT')
    AND NOT HAS_TABLE_PRIVILEGE(:'application_role', 'telemetry.robot_telemetry', 'UPDATE')
    AND NOT HAS_TABLE_PRIVILEGE(:'application_role', 'telemetry.robot_telemetry', 'DELETE')
    AND HAS_TABLE_PRIVILEGE(:'application_role', 'telemetry.robot_current_state', 'UPDATE')
    AND NOT HAS_TABLE_PRIVILEGE(:'application_role', 'telemetry.robot_current_state', 'DELETE')
    AND HAS_TABLE_PRIVILEGE(:'application_role', 'session_store.spring_session', 'DELETE')
    AND NOT HAS_TABLE_PRIVILEGE(:'application_role', 'auth.permission', 'UPDATE')
    AND NOT HAS_SCHEMA_PRIVILEGE(:'application_role', 'flyway_history', 'USAGE')
) AS empty_database_contract_ok
\gset

\if :empty_database_contract_ok
    \echo 'Empty database PostGIS/Flyway/account/MFA/session/RBAC/security contract: PASS'
\else
    \echo 'Empty database PostGIS/Flyway/account/MFA/session/RBAC/security contract: FAIL'
    \quit 1
\endif
