CREATE TABLE robot.robot_command (
    command_id UUID NOT NULL,
    schema_version VARCHAR(10) NOT NULL,
    robot_id UUID NOT NULL,
    mission_id UUID,
    patrol_area_id UUID,
    command_type VARCHAR(40) NOT NULL,
    command_status VARCHAR(20) NOT NULL,
    parameters JSONB NOT NULL,
    requested_by_type VARCHAR(10) NOT NULL,
    requested_by_user_id UUID,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ,
    accepted_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    publish_attempt_count INTEGER DEFAULT 0 NOT NULL,
    last_error_code VARCHAR(100),
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT pk_robot_command PRIMARY KEY (command_id),
    CONSTRAINT ck_robot_command_schema CHECK (schema_version = '2.0'),
    CONSTRAINT ck_robot_command_type CHECK (
        command_type IN (
            'START_MISSION',
            'PAUSE_MISSION',
            'RESUME_MISSION',
            'CANCEL_MISSION',
            'EMERGENCY_STOP',
            'CLEAR_EMERGENCY_STOP'
        )
    ),
    CONSTRAINT ck_robot_command_status CHECK (
        command_status IN (
            'CREATED',
            'SENT',
            'ACCEPTED',
            'COMPLETED',
            'FAILED',
            'REJECTED',
            'EXPIRED',
            'PUBLISH_FAILED'
        )
    ),
    CONSTRAINT ck_robot_command_parameters CHECK (
        JSONB_TYPEOF(parameters) = 'object'
    ),
    CONSTRAINT ck_robot_command_requester CHECK (
        (
            requested_by_type = 'ADMIN'
            AND requested_by_user_id IS NOT NULL
        )
        OR (
            requested_by_type = 'SYSTEM'
            AND requested_by_user_id IS NULL
        )
    ),
    CONSTRAINT ck_robot_command_expiry CHECK (expires_at > issued_at),
    CONSTRAINT ck_robot_command_publish_attempts CHECK (
        publish_attempt_count >= 0
    ),
    CONSTRAINT ck_robot_command_lifecycle CHECK (
        (command_status = 'CREATED' AND sent_at IS NULL)
        OR (command_status <> 'CREATED')
    ),
    CONSTRAINT ck_robot_command_failure CHECK (
        (
            command_status = 'PUBLISH_FAILED'
            AND last_error_code IS NOT NULL
            AND BTRIM(last_error_code) <> ''
        )
        OR command_status <> 'PUBLISH_FAILED'
    ),
    CONSTRAINT ck_robot_command_timestamps CHECK (
        updated_at >= created_at
        AND expires_at > issued_at
        AND (sent_at IS NULL OR sent_at >= issued_at)
        AND (accepted_at IS NULL OR accepted_at >= issued_at)
        AND (completed_at IS NULL OR completed_at >= issued_at)
    ),
    CONSTRAINT fk_robot_command_robot
        FOREIGN KEY (robot_id)
        REFERENCES robot.robot (robot_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_robot_command_requester
        FOREIGN KEY (requested_by_user_id)
        REFERENCES auth.admin_user (user_id)
        ON DELETE RESTRICT
);

CREATE INDEX ix_robot_command_robot_issued
    ON robot.robot_command (robot_id, issued_at DESC, command_id DESC);

CREATE INDEX ix_robot_command_expiry
    ON robot.robot_command (expires_at)
    WHERE command_status IN ('CREATED', 'SENT', 'ACCEPTED');

CREATE TABLE robot.robot_command_result (
    result_id UUID NOT NULL,
    command_id UUID NOT NULL,
    robot_id UUID NOT NULL,
    schema_version VARCHAR(10) NOT NULL,
    result_status VARCHAR(20) NOT NULL,
    robot_received_at TIMESTAMPTZ NOT NULL,
    robot_completed_at TIMESTAMPTZ,
    reason_code VARCHAR(100) NOT NULL,
    resulting_mode VARCHAR(20) NOT NULL,
    resulting_reason_code VARCHAR(50) NOT NULL,
    server_received_at TIMESTAMPTZ NOT NULL,
    raw_payload JSONB NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT pk_robot_command_result PRIMARY KEY (result_id),
    CONSTRAINT ck_robot_command_result_schema CHECK (
        schema_version = '2.0'
    ),
    CONSTRAINT ck_robot_command_result_status CHECK (
        result_status IN (
            'ACCEPTED',
            'COMPLETED',
            'FAILED',
            'REJECTED',
            'EXPIRED'
        )
    ),
    CONSTRAINT ck_robot_command_result_completion CHECK (
        (
            result_status = 'ACCEPTED'
            AND robot_completed_at IS NULL
        )
        OR (
            result_status <> 'ACCEPTED'
            AND robot_completed_at IS NOT NULL
        )
    ),
    CONSTRAINT ck_robot_command_result_times CHECK (
        robot_completed_at IS NULL
        OR robot_completed_at >= robot_received_at
    ),
    CONSTRAINT ck_robot_command_result_reason CHECK (
        BTRIM(reason_code) <> ''
    ),
    CONSTRAINT ck_robot_command_result_mode CHECK (
        resulting_mode IN (
            'BOOTING',
            'READY',
            'PATROLLING',
            'PAUSED',
            'SAFE_STOP',
            'ERROR',
            'SHUTTING_DOWN'
        )
    ),
    CONSTRAINT ck_robot_command_result_state_reason CHECK (
        resulting_reason_code IN (
            'NONE',
            'REMOTE_PAUSE',
            'REMOTE_ESTOP',
            'OBSTACLE',
            'BOUNDARY_LOST',
            'LOW_BATTERY',
            'CRITICAL_BATTERY',
            'LIDAR_FAULT',
            'MOTOR_FAULT',
            'INTERNAL_ERROR'
        )
    ),
    CONSTRAINT ck_robot_command_result_payload CHECK (
        JSONB_TYPEOF(raw_payload) = 'object'
    ),
    CONSTRAINT fk_robot_command_result_command
        FOREIGN KEY (command_id)
        REFERENCES robot.robot_command (command_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_robot_command_result_robot
        FOREIGN KEY (robot_id)
        REFERENCES robot.robot (robot_id)
        ON DELETE RESTRICT
);

CREATE INDEX ix_robot_command_result_command_received
    ON robot.robot_command_result (
        command_id,
        server_received_at ASC,
        result_id ASC
    );

CREATE FUNCTION robot.reject_robot_command_result_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, robot
AS $$
BEGIN
    RAISE EXCEPTION 'robot_command_result is append-only';
END;
$$;

REVOKE ALL
    ON FUNCTION robot.reject_robot_command_result_mutation()
    FROM PUBLIC;

CREATE TRIGGER tr_robot_command_result_append_only
    BEFORE UPDATE OR DELETE ON robot.robot_command_result
    FOR EACH ROW
    EXECUTE FUNCTION robot.reject_robot_command_result_mutation();

COMMENT ON TABLE robot.robot_command IS
    'REST 접수부터 MQTT 발행, ACK, 완료, 만료까지 관리하는 로봇 명령';
COMMENT ON TABLE robot.robot_command_result IS
    'MQTT commands/result로 수신한 append-only 명령 처리 결과';

GRANT SELECT, INSERT, UPDATE
    ON robot.robot_command
    TO "${applicationRole}";
GRANT SELECT, INSERT
    ON robot.robot_command_result
    TO "${applicationRole}";
