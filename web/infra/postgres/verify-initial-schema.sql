\set ON_ERROR_STOP on

-- Runtime role의 실제 DML 권한과 schema 관계를 검증하되 테스트 데이터는 남기지 않는다.
BEGIN;

INSERT INTO auth.admin_user (
    user_id,
    user_code,
    legacy_username,
    password_hash,
    display_name,
    email,
    account_status,
    is_active,
    account_origin
) VALUES (
    '10000000-0000-4000-8000-000000000001',
    'ADM-SMOKE',
    'migration_smoke',
    '{argon2}$argon2id$v=19$m=19456,t=2,p=1$integration-test-hash',
    'Migration Smoke',
    'Migration-Smoke@Example.Invalid',
    'ACTIVE',
    TRUE,
    'ADMIN_CREATED'
);

UPDATE auth.admin_user
SET failed_login_count = 5,
    last_failed_login_at = '2026-07-26T00:05:00Z',
    locked_until = '2026-07-26T00:20:00Z'
WHERE user_id = '10000000-0000-4000-8000-000000000001';

INSERT INTO auth.user_role (
    user_id,
    role_id,
    is_primary,
    assigned_by
) VALUES (
    '10000000-0000-4000-8000-000000000001',
    '00000000-0000-4000-8000-000000000001',
    TRUE,
    '10000000-0000-4000-8000-000000000001'
);

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
    '20000000-0000-4000-8000-000000000001',
    '10000000-0000-4000-8000-000000000001',
    'ACTIVE',
    DECODE(REPEAT('ab', 32), 'hex'),
    DECODE(REPEAT('cd', 12), 'hex'),
    1,
    123456,
    NOW()
);

INSERT INTO auth.mfa_recovery_code (
    recovery_code_id,
    factor_id,
    code_position,
    code_hash
) VALUES (
    '30000000-0000-4000-8000-000000000001',
    '20000000-0000-4000-8000-000000000001',
    1,
    '{argon2}$argon2id$v=19$m=19456,t=2,p=1$integration-test-recovery-hash'
);

INSERT INTO auth.authentication_rate_limit (
    rate_limit_id,
    limit_type,
    subject_key_hash,
    hash_key_version,
    window_started_at,
    last_attempt_at,
    request_count,
    failure_count,
    blocked_until,
    expires_at
) VALUES (
    '35000000-0000-4000-8000-000000000001',
    'ACCOUNT_PASSWORD_FAILURE',
    DECODE(REPEAT('ef', 32), 'hex'),
    1,
    '2026-07-26T00:00:00Z',
    '2026-07-26T00:05:00Z',
    5,
    5,
    '2026-07-26T00:20:00Z',
    '2026-07-26T01:00:00Z'
);

INSERT INTO robot.robot (
    robot_id,
    mqtt_id,
    robot_name,
    serial_number,
    registered_by
) VALUES (
    '36000000-0000-4000-8000-000000000001',
    'RBT-SMOKE',
    'Migration Smoke Robot',
    'SN-MIGRATION-SMOKE',
    '10000000-0000-4000-8000-000000000001'
);

INSERT INTO robot.robot_credential (
    credential_id,
    robot_id,
    credential_version,
    secret_hash,
    secret_fingerprint,
    issued_by,
    expires_at
) VALUES (
    '37000000-0000-4000-8000-000000000001',
    '36000000-0000-4000-8000-000000000001',
    1,
    '$7$101$migration-smoke-verifier',
    REPEAT('ab', 32),
    '10000000-0000-4000-8000-000000000001',
    NOW() + INTERVAL '90 days'
);

-- Telemetry는 V18부터 (robot_id, boot_id) Boot Session FK를 가지므로
-- 동일한 부팅 세션을 먼저 생성한다.
INSERT INTO telemetry.robot_boot_session (
    boot_id,
    robot_id,
    schema_version,
    session_status,
    robot_connected_at,
    first_received_at,
    last_received_at,
    last_seen_seq
) VALUES (
    '39000000-0000-4000-8000-000000000001',
    '36000000-0000-4000-8000-000000000001',
    '2.0',
    'ONLINE',
    NOW(),
    NOW(),
    NOW(),
    1
);

INSERT INTO telemetry.robot_current_state (robot_id)
VALUES ('36000000-0000-4000-8000-000000000001');

INSERT INTO telemetry.robot_telemetry (
    message_id,
    schema_version,
    robot_id,
    boot_id,
    seq,
    sampled_at,
    position_valid,
    position,
    heading_deg,
    moving,
    speed_mps,
    battery_valid,
    battery_percent,
    battery_state,
    battery_source,
    jetson_temp_c,
    raw_payload
) VALUES (
    '38000000-0000-4000-8000-000000000001',
    '2.0',
    '36000000-0000-4000-8000-000000000001',
    '39000000-0000-4000-8000-000000000001',
    1,
    NOW(),
    TRUE,
    ST_SetSRID(ST_MakePoint(127.0396, 37.5013), 4326),
    90.0,
    TRUE,
    1.2,
    TRUE,
    87.0,
    'NORMAL',
    'MEASURED',
    41.5,
    '{"schema_version":"2.0","robot_id":"RBT-SMOKE"}'::JSONB
);

-- current_version FK는 transaction commit 시점까지 지연되므로 header와 첫 version을 원자적으로 만든다.
INSERT INTO report.anomaly_report (
    report_id,
    report_code,
    title,
    report_type,
    period_from,
    period_to,
    current_version_no,
    created_by
) VALUES (
    '40000000-0000-4000-8000-000000000001',
    'RPT-SMOKE',
    'Migration smoke report',
    'ISSUE',
    '2026-01-01T00:00:00Z',
    '2026-01-02T00:00:00Z',
    1,
    '10000000-0000-4000-8000-000000000001'
);

INSERT INTO report.report_version (
    report_version_id,
    report_id,
    version_no,
    title,
    period_from,
    period_to,
    content,
    statistics,
    generation_method,
    created_by
) VALUES (
    '50000000-0000-4000-8000-000000000001',
    '40000000-0000-4000-8000-000000000001',
    1,
    'Migration smoke report',
    '2026-01-01T00:00:00Z',
    '2026-01-02T00:00:00Z',
    'Integration verification content',
    '{"defectCount": 1}'::JSONB,
    'MANUAL',
    '10000000-0000-4000-8000-000000000001'
);

SET CONSTRAINTS ALL IMMEDIATE;

INSERT INTO audit.audit_log (
    request_id,
    actor_user_id,
    actor_type,
    action_type,
    target_type,
    target_id,
    after_value,
    result
) VALUES (
    '60000000-0000-4000-8000-000000000001',
    '10000000-0000-4000-8000-000000000001',
    'USER',
    'REPORT_CREATED',
    'REPORT',
    '40000000-0000-4000-8000-000000000001',
    '{"status": "DRAFT", "versionNo": 1}'::JSONB,
    'SUCCESS'
);

INSERT INTO session_store.spring_session (
    primary_id,
    session_id,
    creation_time,
    last_access_time,
    max_inactive_interval,
    expiry_time,
    principal_name
) VALUES (
    '70000000-0000-4000-8000-000000000001',
    '80000000-0000-4000-8000-000000000001',
    1800000000000,
    1800000000000,
    1800,
    1800001800000,
    '10000000-0000-4000-8000-000000000001'
);

INSERT INTO session_store.session_security_state (
    session_primary_id,
    user_id,
    authentication_state,
    absolute_expiry_time,
    created_time,
    updated_time
) VALUES (
    '70000000-0000-4000-8000-000000000001',
    '10000000-0000-4000-8000-000000000001',
    'AUTHENTICATED',
    1800028800000,
    1800000000000,
    1800000000000
);

INSERT INTO request_control.idempotency_request (
    idempotency_request_id,
    principal_type,
    principal_key_hash,
    idempotency_key_hash,
    request_fingerprint,
    request_method,
    request_path,
    lease_expires_at,
    expires_at
) VALUES (
    '85000000-0000-4000-8000-000000000001',
    'USER',
    DECODE(REPEAT('11', 32), 'hex'),
    DECODE(REPEAT('22', 32), 'hex'),
    DECODE(REPEAT('33', 32), 'hex'),
    'POST',
    '/api/v1/robots/robot-smoke/commands',
    '2030-01-01T00:01:00Z',
    '2030-01-02T00:00:00Z'
);

UPDATE request_control.idempotency_request
SET processing_status = 'SUCCEEDED',
    response_status = 202,
    response_headers = '{"contentType": "application/json"}'::JSONB,
    response_body = '{"commandId": "smoke-command"}'::JSONB,
    updated_at = NOW()
WHERE idempotency_request_id = '85000000-0000-4000-8000-000000000001';

-- 모두 1이어야 하며 runtime role에는 schema 생성·감사 수정 권한이 없어야 한다.
SELECT
    (SELECT password_change_required FROM auth.admin_user WHERE user_id = '10000000-0000-4000-8000-000000000001') AS password_change_required,
    (SELECT normalized_email FROM auth.admin_user WHERE user_id = '10000000-0000-4000-8000-000000000001') AS normalized_email,
    (SELECT account_status FROM auth.admin_user WHERE user_id = '10000000-0000-4000-8000-000000000001') AS account_status,
    (SELECT failed_login_count FROM auth.admin_user WHERE user_id = '10000000-0000-4000-8000-000000000001') AS failed_login_count,
    (SELECT locked_until IS NOT NULL FROM auth.admin_user WHERE user_id = '10000000-0000-4000-8000-000000000001') AS account_locked,
    (SELECT COUNT(*) FROM auth.user_role WHERE user_id = '10000000-0000-4000-8000-000000000001') AS user_role_count,
    (SELECT COUNT(*) FROM auth.mfa_factor WHERE user_id = '10000000-0000-4000-8000-000000000001') AS mfa_factor_count,
    (SELECT COUNT(*) FROM auth.mfa_recovery_code WHERE factor_id = '20000000-0000-4000-8000-000000000001') AS recovery_code_count,
    (SELECT COUNT(*) FROM auth.permission WHERE is_system AND is_active) AS permission_count,
    (SELECT COUNT(*) FROM auth.role_permission role_permission
      JOIN auth.role role USING (role_id)
     WHERE role.role_code = 'ADMIN' AND role_permission.is_active) AS admin_permission_count,
    (SELECT COUNT(*) FROM auth.role_permission role_permission
      JOIN auth.role role USING (role_id)
     WHERE role.role_code = 'MANAGER' AND role_permission.is_active) AS manager_permission_count,
    (SELECT COUNT(*) FROM auth.role_permission role_permission
      JOIN auth.role role USING (role_id)
     WHERE role.role_code = 'OPERATOR' AND role_permission.is_active) AS operator_permission_count,
    (SELECT COUNT(*) FROM auth.authentication_rate_limit WHERE rate_limit_id = '35000000-0000-4000-8000-000000000001') AS rate_limit_count,
    (SELECT COUNT(*) FROM robot.robot WHERE robot_id = '36000000-0000-4000-8000-000000000001') AS robot_count,
    (SELECT COUNT(*) FROM robot.robot_credential WHERE credential_id = '37000000-0000-4000-8000-000000000001') AS credential_count,
    (SELECT COUNT(*) FROM telemetry.robot_boot_session WHERE boot_id = '39000000-0000-4000-8000-000000000001') AS boot_session_count,
    (SELECT COUNT(*) FROM telemetry.robot_current_state WHERE robot_id = '36000000-0000-4000-8000-000000000001') AS current_state_count,
    (SELECT COUNT(*) FROM telemetry.robot_telemetry WHERE message_id = '38000000-0000-4000-8000-000000000001') AS telemetry_count,
    (SELECT COUNT(*) FROM report.report_version WHERE report_id = '40000000-0000-4000-8000-000000000001') AS report_version_count,
    (SELECT deleted_at IS NULL FROM report.anomaly_report WHERE report_id = '40000000-0000-4000-8000-000000000001') AS report_not_deleted,
    (SELECT COUNT(*) FROM audit.audit_log WHERE request_id = '60000000-0000-4000-8000-000000000001') AS audit_count,
    (SELECT COUNT(*) FROM session_store.session_security_state WHERE session_primary_id = '70000000-0000-4000-8000-000000000001') AS session_state_count,
    (SELECT COUNT(*) FROM request_control.idempotency_request WHERE idempotency_request_id = '85000000-0000-4000-8000-000000000001') AS idempotency_count,
    HAS_SCHEMA_PRIVILEGE(CURRENT_USER, 'auth', 'CREATE') AS can_create_auth_object,
    HAS_SCHEMA_PRIVILEGE(CURRENT_USER, 'request_control', 'CREATE') AS can_create_request_control_object,
    HAS_SCHEMA_PRIVILEGE(CURRENT_USER, 'robot', 'CREATE') AS can_create_robot_object,
    HAS_SCHEMA_PRIVILEGE(CURRENT_USER, 'telemetry', 'CREATE') AS can_create_telemetry_object,
    HAS_TABLE_PRIVILEGE(CURRENT_USER, 'audit.audit_log', 'SELECT') AS can_select_audit,
    HAS_TABLE_PRIVILEGE(CURRENT_USER, 'audit.audit_log', 'INSERT') AS can_insert_audit,
    HAS_TABLE_PRIVILEGE(CURRENT_USER, 'audit.audit_log', 'UPDATE') AS can_update_audit,
    HAS_TABLE_PRIVILEGE(CURRENT_USER, 'audit.audit_log', 'DELETE') AS can_delete_audit,
    HAS_TABLE_PRIVILEGE(CURRENT_USER, 'auth.permission', 'UPDATE') AS can_update_permission,
    HAS_TABLE_PRIVILEGE(CURRENT_USER, 'robot.robot_credential', 'UPDATE') AS can_update_credential,
    HAS_TABLE_PRIVILEGE(CURRENT_USER, 'robot.robot_credential', 'DELETE') AS can_delete_credential,
    HAS_TABLE_PRIVILEGE(CURRENT_USER, 'telemetry.robot_boot_session', 'UPDATE') AS can_update_boot_session,
    HAS_TABLE_PRIVILEGE(CURRENT_USER, 'telemetry.robot_boot_session', 'DELETE') AS can_delete_boot_session,
    HAS_TABLE_PRIVILEGE(CURRENT_USER, 'telemetry.robot_telemetry', 'UPDATE') AS can_update_telemetry,
    HAS_TABLE_PRIVILEGE(CURRENT_USER, 'telemetry.robot_current_state', 'UPDATE') AS can_update_current_state;

SELECT (
    (SELECT password_change_required FROM auth.admin_user WHERE user_id = '10000000-0000-4000-8000-000000000001')
    AND (
        SELECT normalized_email = 'migration-smoke@example.invalid'
        FROM auth.admin_user
        WHERE user_id = '10000000-0000-4000-8000-000000000001'
    )
    AND (
        SELECT account_status = 'ACTIVE'
               AND is_active
               AND failed_login_count = 5
               AND locked_until = '2026-07-26T00:20:00Z'::TIMESTAMPTZ
        FROM auth.admin_user
        WHERE user_id = '10000000-0000-4000-8000-000000000001'
    )
    AND (SELECT COUNT(*) FROM auth.user_role WHERE user_id = '10000000-0000-4000-8000-000000000001') = 1
    AND (SELECT COUNT(*) FROM auth.mfa_factor WHERE user_id = '10000000-0000-4000-8000-000000000001') = 1
    AND (SELECT COUNT(*) FROM auth.mfa_recovery_code WHERE factor_id = '20000000-0000-4000-8000-000000000001') = 1
    AND (SELECT COUNT(*) FROM auth.permission WHERE is_system AND is_active) = 18
    AND (SELECT COUNT(*) FROM auth.authentication_rate_limit WHERE rate_limit_id = '35000000-0000-4000-8000-000000000001') = 1
    AND (SELECT COUNT(*) FROM robot.robot WHERE robot_id = '36000000-0000-4000-8000-000000000001') = 1
    AND (SELECT COUNT(*) FROM robot.robot_credential WHERE credential_id = '37000000-0000-4000-8000-000000000001') = 1
    AND (SELECT COUNT(*) FROM telemetry.robot_boot_session WHERE boot_id = '39000000-0000-4000-8000-000000000001') = 1
    AND (SELECT COUNT(*) FROM telemetry.robot_current_state WHERE robot_id = '36000000-0000-4000-8000-000000000001') = 1
    AND (SELECT COUNT(*) FROM telemetry.robot_telemetry WHERE message_id = '38000000-0000-4000-8000-000000000001') = 1
    AND (SELECT COUNT(*) FROM report.report_version WHERE report_id = '40000000-0000-4000-8000-000000000001') = 1
    AND (SELECT deleted_at IS NULL FROM report.anomaly_report WHERE report_id = '40000000-0000-4000-8000-000000000001')
    AND (SELECT COUNT(*) FROM audit.audit_log WHERE request_id = '60000000-0000-4000-8000-000000000001') = 1
    AND (SELECT COUNT(*) FROM session_store.session_security_state WHERE session_primary_id = '70000000-0000-4000-8000-000000000001') = 1
    AND (
        SELECT processing_status = 'SUCCEEDED'
               AND response_status = 202
               AND response_body = '{"commandId": "smoke-command"}'::JSONB
        FROM request_control.idempotency_request
        WHERE idempotency_request_id = '85000000-0000-4000-8000-000000000001'
    )
    AND NOT HAS_SCHEMA_PRIVILEGE(CURRENT_USER, 'auth', 'CREATE')
    AND NOT HAS_SCHEMA_PRIVILEGE(CURRENT_USER, 'request_control', 'CREATE')
    AND NOT HAS_SCHEMA_PRIVILEGE(CURRENT_USER, 'robot', 'CREATE')
    AND NOT HAS_SCHEMA_PRIVILEGE(CURRENT_USER, 'telemetry', 'CREATE')
    AND HAS_TABLE_PRIVILEGE(CURRENT_USER, 'audit.audit_log', 'SELECT')
    AND HAS_TABLE_PRIVILEGE(CURRENT_USER, 'audit.audit_log', 'INSERT')
    AND NOT HAS_TABLE_PRIVILEGE(CURRENT_USER, 'audit.audit_log', 'UPDATE')
    AND NOT HAS_TABLE_PRIVILEGE(CURRENT_USER, 'audit.audit_log', 'DELETE')
    AND NOT HAS_TABLE_PRIVILEGE(CURRENT_USER, 'auth.permission', 'UPDATE')
    AND HAS_TABLE_PRIVILEGE(CURRENT_USER, 'robot.robot_credential', 'UPDATE')
    AND NOT HAS_TABLE_PRIVILEGE(CURRENT_USER, 'robot.robot_credential', 'DELETE')
    AND HAS_TABLE_PRIVILEGE(CURRENT_USER, 'telemetry.robot_boot_session', 'UPDATE')
    AND NOT HAS_TABLE_PRIVILEGE(CURRENT_USER, 'telemetry.robot_boot_session', 'DELETE')
    AND NOT HAS_TABLE_PRIVILEGE(CURRENT_USER, 'telemetry.robot_telemetry', 'UPDATE')
    AND HAS_TABLE_PRIVILEGE(CURRENT_USER, 'telemetry.robot_current_state', 'UPDATE')
) AS runtime_contract_ok
\gset

\if :runtime_contract_ok
    \echo 'Runtime DML and least-privilege contract: PASS'
\else
    \echo 'Runtime DML and least-privilege contract: FAIL'
    \quit 1
\endif

ROLLBACK;
