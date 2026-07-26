\set ON_ERROR_STOP on

-- Object owner인 Flyway role도 row mutation trigger를 우회하지 못하는지 검증한다.
-- 모든 검증 데이터는 transaction 마지막에 rollback한다.
BEGIN;

INSERT INTO audit.audit_log (
    request_id,
    actor_type,
    action_type,
    target_type,
    target_id,
    result
) VALUES (
    '90000000-0000-4000-8000-000000000001',
    'SYSTEM',
    'AUTH_RATE_LIMITED',
    'SESSION',
    '90000000-0000-4000-8000-000000000002',
    'SUCCESS'
);

DO $$
BEGIN
    BEGIN
        UPDATE audit.audit_log
        SET result = 'FAILURE'
        WHERE request_id = '90000000-0000-4000-8000-000000000001';

        RAISE EXCEPTION 'audit mutation was not blocked';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN
            NULL;
    END;
END;
$$;

INSERT INTO report.anomaly_report (
    report_id,
    report_code,
    title,
    report_type,
    generation_method,
    creator_type,
    period_from,
    period_to
) VALUES (
    '90000000-0000-4000-8000-000000000003',
    'RPT-APPEND-ONLY',
    'Append-only verification',
    'ISSUE',
    'SYSTEM',
    'SYSTEM',
    '2026-01-01T00:00:00Z',
    '2026-01-02T00:00:00Z'
);

INSERT INTO report.report_version (
    report_version_id,
    report_id,
    version_no,
    title,
    period_from,
    period_to,
    content,
    creator_type
) VALUES (
    '90000000-0000-4000-8000-000000000004',
    '90000000-0000-4000-8000-000000000003',
    1,
    'Append-only verification',
    '2026-01-01T00:00:00Z',
    '2026-01-02T00:00:00Z',
    'Immutable content',
    'SYSTEM'
);

SET CONSTRAINTS ALL IMMEDIATE;

DO $$
BEGIN
    BEGIN
        UPDATE report.report_version
        SET content = 'Mutation must fail'
        WHERE report_version_id = '90000000-0000-4000-8000-000000000004';

        RAISE EXCEPTION 'report version mutation was not blocked';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN
            NULL;
    END;
END;
$$;

SELECT
    'blocked' AS audit_update,
    'blocked' AS report_version_update;

ROLLBACK;
