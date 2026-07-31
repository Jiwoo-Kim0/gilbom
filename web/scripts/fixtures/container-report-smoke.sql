\set ON_ERROR_STOP on

BEGIN;

DO $$
BEGIN
    IF (
        SELECT COUNT(*)
        FROM auth.admin_user
        WHERE account_origin = 'BOOTSTRAP'
          AND normalized_email = 'report-smoke-author@example.test'
          AND account_status = 'ACTIVE'
    ) <> 1 THEN
        RAISE EXCEPTION 'report smoke bootstrap author is unavailable';
    END IF;
END;
$$;

-- 검토자는 bootstrap 비밀번호 hash를 복제해 로그인 API를 통과하되, 별도 계정과
-- MANAGER role을 사용한다. 원문 비밀번호나 hash는 fixture 출력에 노출하지 않는다.
INSERT INTO auth.admin_user (
    user_id,
    user_code,
    password_hash,
    display_name,
    email,
    is_active,
    account_status,
    password_change_required,
    password_changed_at,
    account_origin
)
SELECT
    '00000000-0000-0000-0000-000000000002'::UUID,
    'REPORT-SMOKE-REVIEWER',
    password_hash,
    '보고서 Smoke 검토자',
    'report-smoke-reviewer@example.test',
    TRUE,
    'ACTIVE',
    FALSE,
    statement_timestamp(),
    'ADMIN_CREATED'
FROM auth.admin_user
WHERE normalized_email = 'report-smoke-author@example.test';

INSERT INTO auth.user_role (
    user_id,
    role_id,
    is_primary,
    is_active,
    assigned_by
)
SELECT
    '00000000-0000-0000-0000-000000000002'::UUID,
    role.role_id,
    TRUE,
    TRUE,
    author.user_id
FROM auth.role role
CROSS JOIN auth.admin_user author
WHERE role.role_code = 'MANAGER'
  AND author.normalized_email =
      'report-smoke-author@example.test';

INSERT INTO robot.robot (
    robot_id,
    mqtt_id,
    robot_name,
    serial_number,
    operational_status,
    registered_by
)
SELECT
    '10000000-0000-0000-0000-000000000001'::UUID,
    'report-smoke-robot',
    '보고서 Smoke 로봇',
    'SN-REPORT-SMOKE-001',
    'ACTIVE',
    user_id
FROM auth.admin_user
WHERE normalized_email = 'report-smoke-author@example.test';

INSERT INTO robot.patrol_area (
    patrol_area_id,
    area_code,
    area_name,
    boundary,
    lifecycle_status,
    created_by,
    updated_by,
    created_at,
    updated_at
)
SELECT
    '20000000-0000-0000-0000-000000000001'::UUID,
    'SMOKE-A-01',
    '보고서 Smoke 순찰 구역',
    ST_GeomFromText(
        'POLYGON(('
        || '127.00 37.50,'
        || '127.02 37.50,'
        || '127.02 37.52,'
        || '127.00 37.52,'
        || '127.00 37.50))',
        4326
    ),
    'ACTIVE',
    user_id,
    user_id,
    '2026-07-01T00:00:00Z',
    '2026-07-01T00:00:00Z'
FROM auth.admin_user
WHERE normalized_email = 'report-smoke-author@example.test';

INSERT INTO robot.patrol_route (
    patrol_route_id,
    patrol_area_id,
    route_code,
    route_name,
    route_version,
    route_geometry,
    lifecycle_status,
    created_by,
    updated_by,
    created_at,
    updated_at
)
SELECT
    '30000000-0000-0000-0000-000000000001'::UUID,
    '20000000-0000-0000-0000-000000000001'::UUID,
    'SMOKE-A-01-MAIN',
    '보고서 Smoke 주 경로',
    1,
    ST_GeomFromText(
        'LINESTRING(127.005 37.505,127.015 37.515)',
        4326
    ),
    'ACTIVE',
    user_id,
    user_id,
    '2026-07-01T00:00:00Z',
    '2026-07-01T00:00:00Z'
FROM auth.admin_user
WHERE normalized_email = 'report-smoke-author@example.test';

INSERT INTO robot.mission (
    mission_id,
    mission_code,
    robot_id,
    patrol_area_id,
    patrol_route_id,
    status,
    scheduled_start_at,
    started_at,
    ended_at,
    progress_percent,
    completion_reason,
    result_duration_sec,
    result_incident_count,
    result_normal_detection_count,
    result_payload,
    planned_duration_sec,
    created_by,
    updated_by,
    created_at,
    updated_at
)
SELECT
    '40000000-0000-0000-0000-000000000001'::UUID,
    'MIS-REPORT-SMOKE-001',
    '10000000-0000-0000-0000-000000000001'::UUID,
    '20000000-0000-0000-0000-000000000001'::UUID,
    '30000000-0000-0000-0000-000000000001'::UUID,
    'COMPLETED',
    '2026-07-02T00:00:00Z',
    '2026-07-02T00:00:00Z',
    '2026-07-02T00:30:00Z',
    100,
    'SMOKE_COMPLETED',
    1800,
    1,
    12,
    '{"source":"container-report-smoke"}'::JSONB,
    1800,
    user_id,
    user_id,
    '2026-07-01T00:00:00Z',
    '2026-07-02T00:30:00Z'
FROM auth.admin_user
WHERE normalized_email = 'report-smoke-author@example.test';

INSERT INTO telemetry.robot_boot_session (
    boot_id,
    robot_id,
    schema_version,
    session_status,
    first_received_at,
    last_received_at,
    offline_received_at,
    offline_reason,
    last_seen_seq
) VALUES (
    '50000000-0000-0000-0000-000000000001'::UUID,
    '10000000-0000-0000-0000-000000000001'::UUID,
    '2.0',
    'OFFLINE',
    '2026-07-02T00:00:00Z',
    '2026-07-02T00:10:01Z',
    '2026-07-02T00:10:01Z',
    'GRACEFUL_SHUTDOWN',
    1
);

INSERT INTO telemetry.robot_event (
    event_id,
    schema_version,
    robot_id,
    boot_id,
    seq,
    mission_id,
    event_type,
    source_component,
    occurred_at,
    sent_at,
    raw_payload
) VALUES (
    '60000000-0000-0000-0000-000000000001'::UUID,
    '2.0',
    '10000000-0000-0000-0000-000000000001'::UUID,
    '50000000-0000-0000-0000-000000000001'::UUID,
    1,
    '40000000-0000-0000-0000-000000000001'::UUID,
    'BRAILLE_BLOCK_INCIDENT_DETECTED',
    'ai',
    '2026-07-02T00:10:00Z',
    '2026-07-02T00:10:01Z',
    '{"source":"container-report-smoke"}'::JSONB
);

INSERT INTO defect.defect (
    defect_id,
    incident_code,
    block_type,
    defect_type,
    risk_level,
    workflow_status,
    location,
    zone_id,
    patrol_area_id,
    patrol_route_id,
    location_label,
    first_seen_at,
    last_seen_at,
    observation_count,
    latest_confidence
) VALUES (
    '70000000-0000-0000-0000-000000000001'::UUID,
    'INC-20260702-900001',
    'LINEAR',
    'DAMAGED',
    'HIGH',
    'NEW',
    ST_SetSRID(ST_MakePoint(127.01, 37.51), 4326),
    'A-01',
    '20000000-0000-0000-0000-000000000001'::UUID,
    '30000000-0000-0000-0000-000000000001'::UUID,
    '보고서 Smoke 순찰 구역',
    '2026-07-02T00:10:00Z',
    '2026-07-02T00:10:00Z',
    1,
    0.910
);

INSERT INTO defect.defect_observation (
    event_id,
    robot_id,
    defect_id,
    location_valid,
    location,
    zone_id,
    asset_type,
    block_type,
    detected_defect_type,
    confidence,
    model_name,
    model_version,
    is_representative,
    created_at
) VALUES (
    '60000000-0000-0000-0000-000000000001'::UUID,
    '10000000-0000-0000-0000-000000000001'::UUID,
    '70000000-0000-0000-0000-000000000001'::UUID,
    TRUE,
    ST_SetSRID(ST_MakePoint(127.01, 37.51), 4326),
    'A-01',
    'BRAILLE_BLOCK',
    'LINEAR',
    'DAMAGED',
    0.910,
    'report-smoke-model',
    '1.0.0',
    TRUE,
    '2026-07-02T00:10:02Z'
);

DO $$
BEGIN
    IF (
        SELECT COUNT(*) FROM robot.mission
        WHERE mission_id =
            '40000000-0000-0000-0000-000000000001'::UUID
    ) <> 1 OR (
        SELECT COUNT(*) FROM defect.defect
        WHERE defect_id =
            '70000000-0000-0000-0000-000000000001'::UUID
    ) <> 1 THEN
        RAISE EXCEPTION 'report smoke fixture was not stored';
    END IF;
END;
$$;

COMMIT;
