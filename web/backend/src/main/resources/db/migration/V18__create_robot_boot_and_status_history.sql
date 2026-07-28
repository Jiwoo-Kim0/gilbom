-- Presence·State·Telemetry가 공유하는 Boot Session과 append-only 상태 이력이다.
CREATE TABLE telemetry.robot_boot_session (
    boot_id UUID NOT NULL,
    robot_id UUID NOT NULL,
    schema_version VARCHAR(10) NOT NULL,
    session_status VARCHAR(10) NOT NULL,
    robot_connected_at TIMESTAMPTZ,
    first_received_at TIMESTAMPTZ NOT NULL,
    last_received_at TIMESTAMPTZ NOT NULL,
    offline_received_at TIMESTAMPTZ,
    offline_reason VARCHAR(50),
    last_seen_seq BIGINT,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT pk_robot_boot_session PRIMARY KEY (boot_id),
    CONSTRAINT uq_robot_boot_session_robot_boot UNIQUE (robot_id, boot_id),
    CONSTRAINT ck_robot_boot_session_schema CHECK (schema_version = '2.0'),
    CONSTRAINT ck_robot_boot_session_status CHECK (
        session_status IN ('ONLINE', 'OFFLINE')
    ),
    CONSTRAINT ck_robot_boot_session_reason CHECK (
        offline_reason IS NULL OR offline_reason IN (
            'GRACEFUL_SHUTDOWN',
            'CRITICAL_BATTERY_SHUTDOWN',
            'SYSTEM_FAULT_SHUTDOWN',
            'UNGRACEFUL_DISCONNECT',
            'HEARTBEAT_TIMEOUT',
            'REPLACED_BY_NEW_BOOT',
            'MIGRATED_HISTORY'
        )
    ),
    CONSTRAINT ck_robot_boot_session_sequence CHECK (
        last_seen_seq IS NULL OR last_seen_seq >= 1
    ),
    CONSTRAINT ck_robot_boot_session_times CHECK (
        last_received_at >= first_received_at
        AND updated_at >= created_at
    ),
    CONSTRAINT ck_robot_boot_session_lifecycle CHECK (
        (
            session_status = 'ONLINE'
            AND offline_received_at IS NULL
            AND offline_reason IS NULL
        )
        OR
        (
            session_status = 'OFFLINE'
            AND offline_received_at IS NOT NULL
            AND offline_reason IS NOT NULL
        )
    ),
    CONSTRAINT fk_robot_boot_session_robot
        FOREIGN KEY (robot_id)
        REFERENCES robot.robot (robot_id)
        ON DELETE RESTRICT
);

CREATE INDEX ix_robot_boot_session_robot_received
    ON telemetry.robot_boot_session (robot_id, first_received_at DESC);

CREATE INDEX ix_robot_boot_session_online_timeout
    ON telemetry.robot_boot_session (last_received_at)
    WHERE session_status = 'ONLINE';

-- V11~V17에서 이미 저장한 Telemetry를 보존한 채 Boot FK를 연결한다.
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
)
SELECT
    boot_id,
    robot_id,
    '2.0',
    'OFFLINE',
    MIN(received_at),
    MAX(received_at),
    MAX(received_at),
    'MIGRATED_HISTORY',
    MAX(seq)
FROM telemetry.robot_telemetry
GROUP BY boot_id, robot_id;

-- State만 저장돼 Telemetry 이력이 없는 기존 Current Boot도 누락하지 않는다.
INSERT INTO telemetry.robot_boot_session (
    boot_id,
    robot_id,
    schema_version,
    session_status,
    robot_connected_at,
    first_received_at,
    last_received_at,
    offline_received_at,
    offline_reason,
    last_seen_seq
)
SELECT
    state.current_boot_id,
    state.robot_id,
    '2.0',
    state.connectivity_status,
    CASE
        WHEN state.connectivity_status = 'ONLINE'
            THEN state.connection_changed_at
        ELSE NULL
    END,
    COALESCE(
        state.telemetry_received_at,
        state.state_received_at,
        state.last_received_at,
        state.updated_at
    ),
    COALESCE(
        state.last_received_at,
        state.telemetry_received_at,
        state.state_received_at,
        state.updated_at
    ),
    CASE
        WHEN state.connectivity_status = 'OFFLINE'
            THEN COALESCE(state.connection_changed_at, state.updated_at)
        ELSE NULL
    END,
    CASE
        WHEN state.connectivity_status = 'OFFLINE'
            THEN 'MIGRATED_HISTORY'
        ELSE NULL
    END,
    GREATEST(
        state.last_seen_seq,
        state.state_seq,
        state.telemetry_seq
    )
FROM telemetry.robot_current_state state
WHERE state.current_boot_id IS NOT NULL
ON CONFLICT (boot_id) DO NOTHING;

ALTER TABLE telemetry.robot_telemetry
    ADD CONSTRAINT fk_robot_telemetry_boot_session
        FOREIGN KEY (robot_id, boot_id)
        REFERENCES telemetry.robot_boot_session (robot_id, boot_id)
        ON DELETE RESTRICT;

ALTER TABLE telemetry.robot_current_state
    ADD CONSTRAINT fk_robot_current_boot_session
        FOREIGN KEY (robot_id, current_boot_id)
        REFERENCES telemetry.robot_boot_session (robot_id, boot_id)
        ON DELETE RESTRICT;

CREATE TABLE telemetry.robot_status_history (
    status_history_id UUID DEFAULT gen_random_uuid() NOT NULL,
    robot_id UUID NOT NULL,
    boot_id UUID NOT NULL,
    event_kind VARCHAR(20) NOT NULL,
    seq BIGINT,
    connectivity_status VARCHAR(10),
    robot_mode VARCHAR(20),
    reason_code VARCHAR(50),
    mission_status VARCHAR(20),
    battery_valid BOOLEAN,
    battery_percent NUMERIC(5, 2),
    battery_state VARCHAR(20),
    camera_health VARCHAR(20),
    lidar_health VARCHAR(20),
    gps_health VARCHAR(20),
    battery_health VARCHAR(20),
    motor_health VARCHAR(20),
    storage_health VARCHAR(20),
    observed_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    raw_payload JSONB NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT pk_robot_status_history PRIMARY KEY (status_history_id),
    CONSTRAINT ck_robot_status_history_kind CHECK (
        event_kind IN ('PRESENCE', 'STATE', 'TIMEOUT')
    ),
    CONSTRAINT ck_robot_status_history_sequence CHECK (
        seq IS NULL OR seq >= 1
    ),
    CONSTRAINT ck_robot_status_history_connectivity CHECK (
        connectivity_status IS NULL
        OR connectivity_status IN ('ONLINE', 'OFFLINE')
    ),
    CONSTRAINT ck_robot_status_history_mode CHECK (
        robot_mode IS NULL OR robot_mode IN (
            'BOOTING',
            'READY',
            'PATROLLING',
            'PAUSED',
            'SAFE_STOP',
            'ERROR',
            'SHUTTING_DOWN'
        )
    ),
    CONSTRAINT ck_robot_status_history_reason CHECK (
        reason_code IS NULL OR reason_code IN (
            'NONE',
            'REMOTE_PAUSE',
            'REMOTE_ESTOP',
            'OBSTACLE',
            'BOUNDARY_LOST',
            'LOW_BATTERY',
            'CRITICAL_BATTERY',
            'LIDAR_FAULT',
            'MOTOR_FAULT',
            'INTERNAL_ERROR',
            'GRACEFUL_SHUTDOWN',
            'CRITICAL_BATTERY_SHUTDOWN',
            'SYSTEM_FAULT_SHUTDOWN',
            'UNGRACEFUL_DISCONNECT',
            'HEARTBEAT_TIMEOUT',
            'REPLACED_BY_NEW_BOOT'
        )
    ),
    CONSTRAINT ck_robot_status_history_mission CHECK (
        mission_status IS NULL OR mission_status IN (
            'READY',
            'RUNNING',
            'PAUSED',
            'COMPLETED',
            'CANCELLED',
            'FAILED'
        )
    ),
    CONSTRAINT ck_robot_status_history_battery CHECK (
        battery_valid IS NULL
        OR (
            battery_valid = TRUE
            AND battery_percent IS NOT NULL
            AND battery_percent BETWEEN 0.0 AND 100.0
            AND battery_state IN ('NORMAL', 'LOW', 'CRITICAL')
        )
        OR (
            battery_valid = FALSE
            AND battery_percent IS NULL
            AND battery_state = 'UNKNOWN'
        )
    ),
    CONSTRAINT ck_robot_status_history_health CHECK (
        (camera_health IS NULL OR camera_health IN ('OK', 'DEGRADED', 'FAULT', 'UNKNOWN'))
        AND (lidar_health IS NULL OR lidar_health IN ('OK', 'DEGRADED', 'FAULT', 'UNKNOWN'))
        AND (gps_health IS NULL OR gps_health IN ('OK', 'DEGRADED', 'FAULT', 'UNKNOWN'))
        AND (battery_health IS NULL OR battery_health IN ('OK', 'DEGRADED', 'FAULT', 'UNKNOWN'))
        AND (motor_health IS NULL OR motor_health IN ('OK', 'DEGRADED', 'FAULT', 'UNKNOWN'))
        AND (storage_health IS NULL OR storage_health IN ('OK', 'DEGRADED', 'FAULT', 'UNKNOWN'))
    ),
    CONSTRAINT ck_robot_status_history_shape CHECK (
        (
            event_kind IN ('PRESENCE', 'TIMEOUT')
            AND seq IS NULL
            AND connectivity_status IS NOT NULL
            AND robot_mode IS NULL
        )
        OR
        (
            event_kind = 'STATE'
            AND seq IS NOT NULL
            AND connectivity_status IS NULL
            AND robot_mode IS NOT NULL
        )
    ),
    CONSTRAINT ck_robot_status_history_raw_payload CHECK (
        JSONB_TYPEOF(raw_payload) = 'object'
    ),
    CONSTRAINT fk_robot_status_history_robot
        FOREIGN KEY (robot_id)
        REFERENCES robot.robot (robot_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_robot_status_history_boot
        FOREIGN KEY (robot_id, boot_id)
        REFERENCES telemetry.robot_boot_session (robot_id, boot_id)
        ON DELETE RESTRICT
);

CREATE UNIQUE INDEX uq_robot_status_history_state_sequence
    ON telemetry.robot_status_history (robot_id, boot_id, seq)
    WHERE event_kind = 'STATE';

CREATE INDEX ix_robot_status_history_robot_observed
    ON telemetry.robot_status_history (robot_id, observed_at DESC);

CREATE FUNCTION telemetry.reject_robot_status_history_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, telemetry
AS $$
BEGIN
    RAISE EXCEPTION 'robot_status_history is append-only';
END;
$$;

REVOKE ALL
    ON FUNCTION telemetry.reject_robot_status_history_mutation()
    FROM PUBLIC;

CREATE TRIGGER tr_robot_status_history_append_only
    BEFORE UPDATE OR DELETE ON telemetry.robot_status_history
    FOR EACH ROW
    EXECUTE FUNCTION telemetry.reject_robot_status_history_mutation();

COMMENT ON TABLE telemetry.robot_boot_session IS
    '로봇 통신 프로그램의 boot_id별 연결·Sequence 수명주기';
COMMENT ON TABLE telemetry.robot_status_history IS
    'Presence·State·Timeout으로 확정된 append-only 로봇 상태 이력';

GRANT SELECT, INSERT, UPDATE
    ON telemetry.robot_boot_session
    TO "${applicationRole}";
GRANT SELECT, INSERT
    ON telemetry.robot_status_history
    TO "${applicationRole}";
