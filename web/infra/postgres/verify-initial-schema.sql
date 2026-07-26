\set ON_ERROR_STOP on

-- Runtime role의 실제 DML 권한과 schema 관계를 검증하되 테스트 데이터는 남기지 않는다.
BEGIN;

INSERT INTO auth.admin_user (
    user_id,
    user_code,
    username,
    password_hash,
    display_name,
    email
) VALUES (
    '10000000-0000-4000-8000-000000000001',
    'ADM-SMOKE',
    'migration_smoke',
    '{argon2}integration-test-hash',
    'Migration Smoke',
    'migration-smoke@example.invalid'
);

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
    '{argon2}integration-test-recovery-hash'
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
    'migration_smoke'
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

-- 모두 1이어야 하며 runtime role에는 schema 생성·감사 수정 권한이 없어야 한다.
SELECT
    (SELECT COUNT(*) FROM auth.user_role WHERE user_id = '10000000-0000-4000-8000-000000000001') AS user_role_count,
    (SELECT COUNT(*) FROM auth.mfa_factor WHERE user_id = '10000000-0000-4000-8000-000000000001') AS mfa_factor_count,
    (SELECT COUNT(*) FROM auth.mfa_recovery_code WHERE factor_id = '20000000-0000-4000-8000-000000000001') AS recovery_code_count,
    (SELECT COUNT(*) FROM report.report_version WHERE report_id = '40000000-0000-4000-8000-000000000001') AS report_version_count,
    (SELECT COUNT(*) FROM audit.audit_log WHERE request_id = '60000000-0000-4000-8000-000000000001') AS audit_count,
    (SELECT COUNT(*) FROM session_store.session_security_state WHERE session_primary_id = '70000000-0000-4000-8000-000000000001') AS session_state_count,
    HAS_SCHEMA_PRIVILEGE(CURRENT_USER, 'auth', 'CREATE') AS can_create_auth_object,
    HAS_TABLE_PRIVILEGE(CURRENT_USER, 'audit.audit_log', 'UPDATE') AS can_update_audit;

ROLLBACK;
