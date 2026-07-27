-- Flyway V11: 로봇 통신의 첫 저장 경로에 필요한 최소 schema다.
-- Mission, Command, Event, Boot Session 테이블은 후속 migration에서 추가한다.
CREATE SCHEMA robot;
CREATE SCHEMA telemetry;

-- 공개 robot_code를 동시 등록에도 충돌 없이 순차 생성한다.
CREATE SEQUENCE robot.robot_code_sequence
    AS BIGINT
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 20;

-- MQTT Topic과 username에서 사용하는 robot_code는 서버만 생성한다.
CREATE TABLE robot.robot (
    robot_id UUID DEFAULT gen_random_uuid() NOT NULL,
    robot_code VARCHAR(128) NOT NULL,
    robot_name VARCHAR(100) NOT NULL,
    serial_number VARCHAR(100) NOT NULL,
    operational_status VARCHAR(20) DEFAULT 'PROVISIONING' NOT NULL,
    registered_by UUID NOT NULL,
    registered_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT pk_robot PRIMARY KEY (robot_id),
    CONSTRAINT uq_robot_code UNIQUE (robot_code),
    CONSTRAINT uq_robot_serial_number UNIQUE (serial_number),
    CONSTRAINT ck_robot_code_not_blank CHECK (
        robot_code = BTRIM(robot_code) AND robot_code <> ''
    ),
    CONSTRAINT ck_robot_name_not_blank CHECK (
        robot_name = BTRIM(robot_name) AND robot_name <> ''
    ),
    CONSTRAINT ck_robot_serial_number_not_blank CHECK (
        serial_number = BTRIM(serial_number) AND serial_number <> ''
    ),
    CONSTRAINT ck_robot_operational_status CHECK (
        operational_status IN (
            'PROVISIONING',
            'ACTIVE',
            'INACTIVE',
            'MAINTENANCE',
            'DISABLED'
        )
    ),
    CONSTRAINT ck_robot_timestamps CHECK (updated_at >= registered_at),
    CONSTRAINT fk_robot_registered_by
        FOREIGN KEY (registered_by)
        REFERENCES auth.admin_user (user_id)
        ON DELETE RESTRICT
);

CREATE INDEX ix_robot_operational_status
    ON robot.robot (operational_status, robot_code);

COMMENT ON TABLE robot.robot IS '관리자가 등록한 로봇 기준정보. MQTT username은 robot_code와 동일하다.';
COMMENT ON COLUMN robot.robot.robot_id IS '내부 FK에 사용하는 서버 생성 UUID';
COMMENT ON COLUMN robot.robot.robot_code IS 'Topic, Payload, MQTT username에 사용하는 서버 생성 공개 코드';
COMMENT ON COLUMN robot.robot.robot_name IS '관리자 관제 화면 표시명';
COMMENT ON COLUMN robot.robot.serial_number IS '물리 로봇의 고유 Serial Number';
COMMENT ON COLUMN robot.robot.operational_status IS 'PROVISIONING, ACTIVE, INACTIVE, MAINTENANCE, DISABLED';
COMMENT ON COLUMN robot.robot.registered_by IS '인증 세션에서 결정한 등록 관리자';

-- password 원문은 저장하지 않는다. secret_hash도 인증에 악용 가능한 민감정보이므로
-- API 응답과 감사 로그에 포함하지 않고 Credential Provisioner 처리에만 사용한다.
CREATE TABLE robot.robot_credential (
    credential_id UUID DEFAULT gen_random_uuid() NOT NULL,
    robot_id UUID NOT NULL,
    credential_type VARCHAR(30) DEFAULT 'MQTT_PASSWORD' NOT NULL,
    credential_version INTEGER NOT NULL,
    secret_hash TEXT NOT NULL,
    secret_fingerprint CHAR(64) NOT NULL,
    credential_status VARCHAR(20) DEFAULT 'PENDING' NOT NULL,
    issued_by UUID NOT NULL,
    issued_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    activated_at TIMESTAMPTZ,
    revoked_by UUID,
    revoked_at TIMESTAMPTZ,
    revocation_reason VARCHAR(100),
    last_provision_error_code VARCHAR(100),
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT pk_robot_credential PRIMARY KEY (credential_id),
    CONSTRAINT uq_robot_credential_version UNIQUE (
        robot_id,
        credential_type,
        credential_version
    ),
    CONSTRAINT ck_robot_credential_type CHECK (
        credential_type = 'MQTT_PASSWORD'
    ),
    CONSTRAINT ck_robot_credential_version CHECK (credential_version > 0),
    CONSTRAINT ck_robot_credential_hash_not_blank CHECK (
        BTRIM(secret_hash) <> ''
    ),
    CONSTRAINT ck_robot_credential_fingerprint CHECK (
        secret_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_robot_credential_status CHECK (
        credential_status IN (
            'PENDING',
            'ACTIVE',
            'REVOKED',
            'EXPIRED',
            'FAILED'
        )
    ),
    CONSTRAINT ck_robot_credential_expiry CHECK (
        expires_at > issued_at
        AND expires_at <= issued_at + INTERVAL '180 days'
    ),
    CONSTRAINT ck_robot_credential_activation CHECK (
        credential_status <> 'ACTIVE' OR activated_at IS NOT NULL
    ),
    CONSTRAINT ck_robot_credential_revocation CHECK (
        (
            credential_status = 'REVOKED'
            AND revoked_at IS NOT NULL
            AND revoked_by IS NOT NULL
            AND revocation_reason IS NOT NULL
            AND BTRIM(revocation_reason) <> ''
        )
        OR (
            credential_status <> 'REVOKED'
            AND revoked_at IS NULL
            AND revoked_by IS NULL
            AND revocation_reason IS NULL
        )
    ),
    CONSTRAINT ck_robot_credential_failure CHECK (
        (
            credential_status = 'FAILED'
            AND last_provision_error_code IS NOT NULL
            AND BTRIM(last_provision_error_code) <> ''
        )
        OR (
            credential_status <> 'FAILED'
            AND last_provision_error_code IS NULL
        )
    ),
    CONSTRAINT ck_robot_credential_timestamps CHECK (
        created_at >= issued_at
        AND updated_at >= created_at
        AND (activated_at IS NULL OR activated_at >= issued_at)
        AND (revoked_at IS NULL OR revoked_at >= issued_at)
    ),
    CONSTRAINT fk_robot_credential_robot
        FOREIGN KEY (robot_id)
        REFERENCES robot.robot (robot_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_robot_credential_issued_by
        FOREIGN KEY (issued_by)
        REFERENCES auth.admin_user (user_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_robot_credential_revoked_by
        FOREIGN KEY (revoked_by)
        REFERENCES auth.admin_user (user_id)
        ON DELETE RESTRICT
);

-- 같은 로봇에는 활성 MQTT 비밀번호를 하나만 허용한다.
CREATE UNIQUE INDEX uq_robot_active_mqtt_password
    ON robot.robot_credential (robot_id, credential_type)
    WHERE credential_status = 'ACTIVE';

CREATE INDEX ix_robot_credential_robot_issued
    ON robot.robot_credential (robot_id, issued_at DESC);

CREATE INDEX ix_robot_credential_expiry
    ON robot.robot_credential (expires_at)
    WHERE credential_status = 'ACTIVE';

COMMENT ON TABLE robot.robot_credential IS '로봇별 MQTT Password Credential의 발급·활성·폐기 수명주기';
COMMENT ON COLUMN robot.robot_credential.credential_type IS '현재는 MQTT_PASSWORD만 허용';
COMMENT ON COLUMN robot.robot_credential.credential_version IS '로봇·Credential 종류별 1부터 증가하는 버전';
COMMENT ON COLUMN robot.robot_credential.secret_hash IS 'Mosquitto password-file 호환 verifier. API·감사 로그 반환 금지';
COMMENT ON COLUMN robot.robot_credential.secret_fingerprint IS '원문 Secret의 SHA-256 lowercase hex. API 반환 금지';
COMMENT ON COLUMN robot.robot_credential.credential_status IS 'PENDING, ACTIVE, REVOKED, EXPIRED, FAILED';
COMMENT ON COLUMN robot.robot_credential.last_provision_error_code IS 'Secret·예외 원문을 제외한 제한된 Provisioning 오류 코드';

-- MQTT Telemetry 이력과 실제 이동 궤적의 원본이다.
-- boot_id는 Wire 식별자를 먼저 보존하고 Boot Session 테이블 추가 시 FK를 연결한다.
CREATE TABLE telemetry.robot_telemetry (
    message_id UUID NOT NULL,
    schema_version VARCHAR(10) NOT NULL,
    robot_id UUID NOT NULL,
    boot_id UUID NOT NULL,
    seq BIGINT NOT NULL,
    sampled_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    position_valid BOOLEAN NOT NULL,
    position GEOMETRY(POINT, 4326),
    heading_deg DOUBLE PRECISION,
    moving BOOLEAN NOT NULL,
    speed_mps DOUBLE PRECISION,
    battery_valid BOOLEAN NOT NULL,
    battery_percent NUMERIC(5, 2),
    battery_state VARCHAR(20) NOT NULL,
    battery_source VARCHAR(20),
    battery_voltage_v DOUBLE PRECISION,
    battery_current_a DOUBLE PRECISION,
    charging BOOLEAN,
    battery_temperature_c DOUBLE PRECISION,
    estimated_runtime_sec INTEGER,
    jetson_temp_c DOUBLE PRECISION,
    mission_state VARCHAR(20),
    mission_elapsed_sec INTEGER,
    mission_incident_count INTEGER,
    raw_payload JSONB NOT NULL,
    CONSTRAINT pk_robot_telemetry PRIMARY KEY (message_id),
    CONSTRAINT uq_robot_telemetry_sequence UNIQUE (robot_id, boot_id, seq),
    CONSTRAINT ck_robot_telemetry_schema_version CHECK (schema_version = '2.0'),
    CONSTRAINT ck_robot_telemetry_seq CHECK (seq >= 0),
    CONSTRAINT ck_robot_telemetry_position CHECK (
        (
            position_valid = TRUE
            AND position IS NOT NULL
        )
        OR (
            position_valid = FALSE
            AND position IS NULL
            AND heading_deg IS NULL
        )
    ),
    CONSTRAINT ck_robot_telemetry_heading CHECK (
        heading_deg IS NULL OR (heading_deg >= 0.0 AND heading_deg < 360.0)
    ),
    CONSTRAINT ck_robot_telemetry_speed CHECK (
        speed_mps IS NULL OR speed_mps >= 0.0
    ),
    CONSTRAINT ck_robot_telemetry_battery CHECK (
        (
            battery_valid = TRUE
            AND battery_percent IS NOT NULL
            AND battery_percent BETWEEN 0.0 AND 100.0
            AND battery_state IN ('NORMAL', 'LOW', 'CRITICAL')
            AND battery_source IN ('MEASURED', 'ESTIMATED')
        )
        OR (
            battery_valid = FALSE
            AND battery_percent IS NULL
            AND battery_state = 'UNKNOWN'
            AND battery_source IS NULL
        )
    ),
    CONSTRAINT ck_robot_telemetry_estimated_runtime CHECK (
        estimated_runtime_sec IS NULL OR estimated_runtime_sec >= 0
    ),
    CONSTRAINT ck_robot_telemetry_mission_state CHECK (
        mission_state IS NULL OR mission_state IN (
            'READY',
            'RUNNING',
            'PAUSED',
            'COMPLETED',
            'CANCELLED',
            'FAILED'
        )
    ),
    CONSTRAINT ck_robot_telemetry_mission_metrics CHECK (
        (mission_elapsed_sec IS NULL OR mission_elapsed_sec >= 0)
        AND (mission_incident_count IS NULL OR mission_incident_count >= 0)
    ),
    CONSTRAINT ck_robot_telemetry_raw_payload CHECK (
        JSONB_TYPEOF(raw_payload) = 'object'
    ),
    CONSTRAINT fk_robot_telemetry_robot
        FOREIGN KEY (robot_id)
        REFERENCES robot.robot (robot_id)
        ON DELETE RESTRICT
);

CREATE INDEX ix_robot_telemetry_robot_sampled
    ON telemetry.robot_telemetry (robot_id, sampled_at DESC);

CREATE INDEX ix_robot_telemetry_boot_sequence
    ON telemetry.robot_telemetry (boot_id, seq);

CREATE INDEX ix_robot_telemetry_position
    ON telemetry.robot_telemetry
    USING GIST (position)
    WHERE position_valid = TRUE;

COMMENT ON TABLE telemetry.robot_telemetry IS '검증을 통과한 MQTT v2 Telemetry 이력과 실제 이동 궤적 원본';
COMMENT ON COLUMN telemetry.robot_telemetry.message_id IS 'MQTT Payload가 생성한 메시지 멱등 UUID';
COMMENT ON COLUMN telemetry.robot_telemetry.boot_id IS '로봇 부팅 단위 UUID. Boot Session FK는 후속 migration에서 연결';
COMMENT ON COLUMN telemetry.robot_telemetry.seq IS '로봇이 boot 안에서 생성한 0 이상의 공용 순번';
COMMENT ON COLUMN telemetry.robot_telemetry.sampled_at IS '로봇 측정 시각';
COMMENT ON COLUMN telemetry.robot_telemetry.received_at IS 'Backend 수신 시각';
COMMENT ON COLUMN telemetry.robot_telemetry.position IS 'position_valid가 true일 때의 WGS84 Point';
COMMENT ON COLUMN telemetry.robot_telemetry.raw_payload IS '검증된 Secret 없는 MQTT Payload JSON';

-- 로봇마다 한 행만 유지하는 실시간 관제 read model이다.
-- 지연 메시지가 최신 상태를 덮지 않도록 State와 Telemetry 기준시각·seq를 분리한다.
CREATE TABLE telemetry.robot_current_state (
    robot_id UUID NOT NULL,
    current_boot_id UUID,
    connectivity_status VARCHAR(10) DEFAULT 'OFFLINE' NOT NULL,
    connection_changed_at TIMESTAMPTZ,
    last_received_at TIMESTAMPTZ,
    last_seen_seq BIGINT,
    state_schema_version VARCHAR(10),
    state_seq BIGINT,
    state_observed_at TIMESTAMPTZ,
    state_received_at TIMESTAMPTZ,
    robot_mode VARCHAR(20),
    state_reason_code VARCHAR(50),
    mission_status VARCHAR(20),
    battery_valid BOOLEAN,
    battery_percent NUMERIC(5, 2),
    battery_state VARCHAR(20),
    battery_source VARCHAR(20),
    battery_voltage_v DOUBLE PRECISION,
    battery_current_a DOUBLE PRECISION,
    charging BOOLEAN,
    battery_temperature_c DOUBLE PRECISION,
    estimated_runtime_sec INTEGER,
    battery_observed_at TIMESTAMPTZ,
    camera_health VARCHAR(20),
    lidar_health VARCHAR(20),
    gps_health VARCHAR(20),
    battery_health VARCHAR(20),
    motor_health VARCHAR(20),
    storage_health VARCHAR(20),
    telemetry_seq BIGINT,
    telemetry_sampled_at TIMESTAMPTZ,
    telemetry_received_at TIMESTAMPTZ,
    position_valid BOOLEAN,
    position GEOMETRY(POINT, 4326),
    heading_deg DOUBLE PRECISION,
    moving BOOLEAN,
    speed_mps DOUBLE PRECISION,
    jetson_temp_c DOUBLE PRECISION,
    overall_health_status VARCHAR(20) DEFAULT 'UNKNOWN' NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT pk_robot_current_state PRIMARY KEY (robot_id),
    CONSTRAINT ck_robot_current_connectivity CHECK (
        connectivity_status IN ('ONLINE', 'OFFLINE')
    ),
    CONSTRAINT ck_robot_current_last_seen_seq CHECK (
        last_seen_seq IS NULL OR last_seen_seq >= 0
    ),
    CONSTRAINT ck_robot_current_state_version CHECK (
        state_schema_version IS NULL OR state_schema_version = '2.0'
    ),
    CONSTRAINT ck_robot_current_state_seq CHECK (
        state_seq IS NULL OR state_seq >= 0
    ),
    CONSTRAINT ck_robot_current_mode CHECK (
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
    CONSTRAINT ck_robot_current_reason CHECK (
        state_reason_code IS NULL OR state_reason_code IN (
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
    CONSTRAINT ck_robot_current_mission_status CHECK (
        mission_status IS NULL OR mission_status IN (
            'READY',
            'RUNNING',
            'PAUSED',
            'COMPLETED',
            'CANCELLED',
            'FAILED'
        )
    ),
    CONSTRAINT ck_robot_current_battery CHECK (
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
            AND battery_source IS NULL
        )
    ),
    CONSTRAINT ck_robot_current_battery_source CHECK (
        battery_source IS NULL OR battery_source IN ('MEASURED', 'ESTIMATED')
    ),
    CONSTRAINT ck_robot_current_estimated_runtime CHECK (
        estimated_runtime_sec IS NULL OR estimated_runtime_sec >= 0
    ),
    CONSTRAINT ck_robot_current_health CHECK (
        (camera_health IS NULL OR camera_health IN ('OK', 'DEGRADED', 'FAULT', 'UNKNOWN'))
        AND (lidar_health IS NULL OR lidar_health IN ('OK', 'DEGRADED', 'FAULT', 'UNKNOWN'))
        AND (gps_health IS NULL OR gps_health IN ('OK', 'DEGRADED', 'FAULT', 'UNKNOWN'))
        AND (battery_health IS NULL OR battery_health IN ('OK', 'DEGRADED', 'FAULT', 'UNKNOWN'))
        AND (motor_health IS NULL OR motor_health IN ('OK', 'DEGRADED', 'FAULT', 'UNKNOWN'))
        AND (storage_health IS NULL OR storage_health IN ('OK', 'DEGRADED', 'FAULT', 'UNKNOWN'))
    ),
    CONSTRAINT ck_robot_current_telemetry_seq CHECK (
        telemetry_seq IS NULL OR telemetry_seq >= 0
    ),
    CONSTRAINT ck_robot_current_position CHECK (
        position_valid IS NULL
        OR position_valid = FALSE
        OR position IS NOT NULL
    ),
    CONSTRAINT ck_robot_current_heading CHECK (
        heading_deg IS NULL OR (heading_deg >= 0.0 AND heading_deg < 360.0)
    ),
    CONSTRAINT ck_robot_current_speed CHECK (
        speed_mps IS NULL OR speed_mps >= 0.0
    ),
    CONSTRAINT ck_robot_current_overall_health CHECK (
        overall_health_status IN ('OK', 'WARNING', 'CRITICAL', 'UNKNOWN')
    ),
    CONSTRAINT fk_robot_current_state_robot
        FOREIGN KEY (robot_id)
        REFERENCES robot.robot (robot_id)
        ON DELETE RESTRICT
);

CREATE INDEX ix_robot_current_connectivity_health
    ON telemetry.robot_current_state (
        connectivity_status,
        overall_health_status
    );

-- 마지막 유효 위치를 유지하므로 최신 position_valid가 false여도 position은 남을 수 있다.
CREATE INDEX ix_robot_current_position
    ON telemetry.robot_current_state
    USING GIST (position)
    WHERE position IS NOT NULL;

COMMENT ON TABLE telemetry.robot_current_state IS '로봇별 실시간 관제 최신 상태 read model';
COMMENT ON COLUMN telemetry.robot_current_state.current_boot_id IS '현재 부팅 UUID. Boot Session FK는 후속 migration에서 연결';
COMMENT ON COLUMN telemetry.robot_current_state.last_seen_seq IS '현재 boot에서 수신한 State·Telemetry의 최댓값';
COMMENT ON COLUMN telemetry.robot_current_state.state_observed_at IS 'State Payload의 updated_at';
COMMENT ON COLUMN telemetry.robot_current_state.telemetry_sampled_at IS '최신으로 채택한 Telemetry의 sampled_at';
COMMENT ON COLUMN telemetry.robot_current_state.position IS '마지막 유효 GPS 위치';
COMMENT ON COLUMN telemetry.robot_current_state.position_valid IS '가장 최근 Telemetry의 위치 유효성';

-- 업무 테이블은 lifecycle 상태 변경으로 보존하고 Telemetry 이력은 append-only로 취급한다.
GRANT USAGE ON SCHEMA robot, telemetry TO "${applicationRole}";
GRANT USAGE, SELECT ON SEQUENCE robot.robot_code_sequence TO "${applicationRole}";
GRANT SELECT, INSERT, UPDATE ON robot.robot TO "${applicationRole}";
GRANT SELECT, INSERT, UPDATE ON robot.robot_credential TO "${applicationRole}";
GRANT SELECT, INSERT ON telemetry.robot_telemetry TO "${applicationRole}";
GRANT SELECT, INSERT, UPDATE ON telemetry.robot_current_state TO "${applicationRole}";
