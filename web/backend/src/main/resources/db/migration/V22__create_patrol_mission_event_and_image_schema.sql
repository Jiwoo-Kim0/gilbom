-- 팀원 B의 순찰·Mission 정본과 팀원 C가 Incident 통계에서 참조할 FK 대상이다.
-- MQTT 문자열 mission_id는 mission_code로 조회하고 내부 FK는 mission_id UUID를 쓴다.

CREATE SCHEMA IF NOT EXISTS storage;

CREATE TABLE robot.patrol_area (
    patrol_area_id UUID DEFAULT gen_random_uuid() NOT NULL,
    area_code VARCHAR(50) NOT NULL,
    area_name VARCHAR(100) NOT NULL,
    boundary GEOMETRY(GEOMETRY, 4326) NOT NULL,
    lifecycle_status VARCHAR(20) DEFAULT 'ACTIVE' NOT NULL,
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT pk_patrol_area PRIMARY KEY (patrol_area_id),
    CONSTRAINT uq_patrol_area_code UNIQUE (area_code),
    CONSTRAINT ck_patrol_area_code CHECK (
        area_code = BTRIM(area_code)
        AND area_code <> ''
    ),
    CONSTRAINT ck_patrol_area_name CHECK (
        area_name = BTRIM(area_name)
        AND area_name <> ''
    ),
    CONSTRAINT ck_patrol_area_geometry_type CHECK (
        GeometryType(boundary) IN ('POLYGON', 'MULTIPOLYGON')
    ),
    CONSTRAINT ck_patrol_area_geometry_valid CHECK (
        ST_SRID(boundary) = 4326
        AND NOT ST_IsEmpty(boundary)
        AND ST_IsValid(boundary)
    ),
    CONSTRAINT ck_patrol_area_lifecycle CHECK (
        lifecycle_status IN ('ACTIVE', 'INACTIVE', 'ARCHIVED')
    ),
    CONSTRAINT ck_patrol_area_timestamps CHECK (
        updated_at >= created_at
    ),
    CONSTRAINT fk_patrol_area_created_by
        FOREIGN KEY (created_by)
        REFERENCES auth.admin_user (user_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_patrol_area_updated_by
        FOREIGN KEY (updated_by)
        REFERENCES auth.admin_user (user_id)
        ON DELETE RESTRICT
);

CREATE INDEX ix_patrol_area_boundary
    ON robot.patrol_area
    USING GIST (boundary);

CREATE INDEX ix_patrol_area_lifecycle_code
    ON robot.patrol_area (lifecycle_status, area_code);

CREATE TABLE robot.patrol_route (
    patrol_route_id UUID DEFAULT gen_random_uuid() NOT NULL,
    patrol_area_id UUID NOT NULL,
    route_code VARCHAR(50) NOT NULL,
    route_name VARCHAR(100) NOT NULL,
    route_version INTEGER NOT NULL,
    route_geometry GEOMETRY(GEOMETRY, 4326) NOT NULL,
    lifecycle_status VARCHAR(20) DEFAULT 'DRAFT' NOT NULL,
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT pk_patrol_route PRIMARY KEY (patrol_route_id),
    CONSTRAINT uq_patrol_route_area_identity UNIQUE (
        patrol_route_id,
        patrol_area_id
    ),
    CONSTRAINT uq_patrol_route_version UNIQUE (
        patrol_area_id,
        route_code,
        route_version
    ),
    CONSTRAINT ck_patrol_route_code CHECK (
        route_code = BTRIM(route_code)
        AND route_code <> ''
    ),
    CONSTRAINT ck_patrol_route_name CHECK (
        route_name = BTRIM(route_name)
        AND route_name <> ''
    ),
    CONSTRAINT ck_patrol_route_version CHECK (route_version >= 1),
    CONSTRAINT ck_patrol_route_geometry_type CHECK (
        GeometryType(route_geometry) IN ('LINESTRING', 'MULTILINESTRING')
    ),
    CONSTRAINT ck_patrol_route_geometry_valid CHECK (
        ST_SRID(route_geometry) = 4326
        AND NOT ST_IsEmpty(route_geometry)
        AND ST_IsValid(route_geometry)
    ),
    CONSTRAINT ck_patrol_route_lifecycle CHECK (
        lifecycle_status IN ('DRAFT', 'ACTIVE', 'INACTIVE', 'ARCHIVED')
    ),
    CONSTRAINT ck_patrol_route_timestamps CHECK (
        updated_at >= created_at
    ),
    CONSTRAINT fk_patrol_route_area
        FOREIGN KEY (patrol_area_id)
        REFERENCES robot.patrol_area (patrol_area_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_patrol_route_created_by
        FOREIGN KEY (created_by)
        REFERENCES auth.admin_user (user_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_patrol_route_updated_by
        FOREIGN KEY (updated_by)
        REFERENCES auth.admin_user (user_id)
        ON DELETE RESTRICT
);

-- 한 지역의 같은 논리 Route에는 활성 버전을 하나만 허용한다.
CREATE UNIQUE INDEX uq_patrol_route_active_version
    ON robot.patrol_route (patrol_area_id, route_code)
    WHERE lifecycle_status = 'ACTIVE';

CREATE INDEX ix_patrol_route_area_status
    ON robot.patrol_route (
        patrol_area_id,
        lifecycle_status,
        route_version DESC
    );

CREATE INDEX ix_patrol_route_geometry
    ON robot.patrol_route
    USING GIST (route_geometry);

CREATE TABLE robot.mission (
    mission_id UUID DEFAULT gen_random_uuid() NOT NULL,
    mission_code VARCHAR(100) NOT NULL,
    robot_id UUID NOT NULL,
    patrol_area_id UUID NOT NULL,
    patrol_route_id UUID NOT NULL,
    status VARCHAR(20) DEFAULT 'CREATED' NOT NULL,
    scheduled_start_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    progress_percent NUMERIC(5, 2) DEFAULT 0 NOT NULL,
    completion_reason VARCHAR(100),
    result_duration_sec INTEGER,
    result_incident_count INTEGER,
    result_normal_detection_count INTEGER,
    result_payload JSONB,
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT pk_mission PRIMARY KEY (mission_id),
    CONSTRAINT uq_mission_code UNIQUE (mission_code),
    CONSTRAINT uq_mission_robot_identity UNIQUE (mission_id, robot_id),
    CONSTRAINT ck_mission_code CHECK (
        mission_code = BTRIM(mission_code)
        AND mission_code <> ''
    ),
    CONSTRAINT ck_mission_status CHECK (
        status IN (
            'CREATED',
            'READY',
            'RUNNING',
            'PAUSED',
            'COMPLETED',
            'CANCELLED',
            'FAILED'
        )
    ),
    CONSTRAINT ck_mission_progress CHECK (
        progress_percent BETWEEN 0 AND 100
        AND (
            status <> 'COMPLETED'
            OR progress_percent = 100
        )
    ),
    CONSTRAINT ck_mission_result_counts CHECK (
        (result_duration_sec IS NULL OR result_duration_sec >= 0)
        AND (
            result_incident_count IS NULL
            OR result_incident_count >= 0
        )
        AND (
            result_normal_detection_count IS NULL
            OR result_normal_detection_count >= 0
        )
    ),
    CONSTRAINT ck_mission_result_payload CHECK (
        result_payload IS NULL
        OR JSONB_TYPEOF(result_payload) = 'object'
    ),
    CONSTRAINT ck_mission_lifecycle_times CHECK (
        (
            status IN ('CREATED', 'READY')
            AND started_at IS NULL
            AND ended_at IS NULL
        )
        OR (
            status IN ('RUNNING', 'PAUSED')
            AND started_at IS NOT NULL
            AND ended_at IS NULL
        )
        OR (
            status IN ('COMPLETED', 'FAILED')
            AND started_at IS NOT NULL
            AND ended_at IS NOT NULL
            AND ended_at >= started_at
        )
        OR (
            status = 'CANCELLED'
            AND ended_at IS NOT NULL
            AND (
                started_at IS NULL
                OR ended_at >= started_at
            )
        )
    ),
    CONSTRAINT ck_mission_completion_result CHECK (
        (
            status IN ('COMPLETED', 'CANCELLED', 'FAILED')
            AND completion_reason IS NOT NULL
            AND BTRIM(completion_reason) <> ''
        )
        OR (
            status NOT IN ('COMPLETED', 'CANCELLED', 'FAILED')
            AND completion_reason IS NULL
            AND result_duration_sec IS NULL
            AND result_incident_count IS NULL
            AND result_normal_detection_count IS NULL
            AND result_payload IS NULL
        )
    ),
    CONSTRAINT ck_mission_timestamps CHECK (
        updated_at >= created_at
        AND (
            scheduled_start_at IS NULL
            OR scheduled_start_at >= created_at
        )
    ),
    CONSTRAINT fk_mission_robot
        FOREIGN KEY (robot_id)
        REFERENCES robot.robot (robot_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_mission_patrol_area
        FOREIGN KEY (patrol_area_id)
        REFERENCES robot.patrol_area (patrol_area_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_mission_patrol_route_area
        FOREIGN KEY (patrol_route_id, patrol_area_id)
        REFERENCES robot.patrol_route (
            patrol_route_id,
            patrol_area_id
        )
        ON DELETE RESTRICT,
    CONSTRAINT fk_mission_created_by
        FOREIGN KEY (created_by)
        REFERENCES auth.admin_user (user_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_mission_updated_by
        FOREIGN KEY (updated_by)
        REFERENCES auth.admin_user (user_id)
        ON DELETE RESTRICT
);

CREATE INDEX ix_mission_robot_started
    ON robot.mission (robot_id, started_at DESC, mission_id);

CREATE INDEX ix_mission_area_started
    ON robot.mission (patrol_area_id, started_at DESC, mission_id);

CREATE INDEX ix_mission_route_started
    ON robot.mission (patrol_route_id, started_at DESC, mission_id);

CREATE INDEX ix_mission_status_started
    ON robot.mission (status, started_at DESC, mission_id);

CREATE FUNCTION robot.enforce_mission_status_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, robot
AS $$
BEGIN
    IF OLD.status = NEW.status THEN
        RETURN NEW;
    END IF;

    IF (OLD.status = 'CREATED'
            AND NEW.status IN ('READY', 'RUNNING', 'CANCELLED'))
        OR (OLD.status = 'READY'
            AND NEW.status IN ('RUNNING', 'CANCELLED', 'FAILED'))
        OR (OLD.status = 'RUNNING'
            AND NEW.status IN (
                'PAUSED',
                'COMPLETED',
                'CANCELLED',
                'FAILED'
            ))
        OR (OLD.status = 'PAUSED'
            AND NEW.status IN ('RUNNING', 'CANCELLED', 'FAILED'))
    THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION
        'illegal mission status transition: % -> %',
        OLD.status,
        NEW.status
        USING ERRCODE = '23514';
END;
$$;

REVOKE ALL
    ON FUNCTION robot.enforce_mission_status_transition()
    FROM PUBLIC;

CREATE TRIGGER tr_mission_status_transition
    BEFORE UPDATE OF status ON robot.mission
    FOR EACH ROW
    EXECUTE FUNCTION robot.enforce_mission_status_transition();

-- 현재 Telemetry 이력이 Mission의 실제 위치 궤적 원본이므로 별도 중복
-- trajectory 테이블 대신 Mission FK를 직접 추가한다.
ALTER TABLE telemetry.robot_telemetry
    ADD COLUMN mission_id UUID;

ALTER TABLE telemetry.robot_telemetry
    ADD CONSTRAINT fk_robot_telemetry_mission_robot
        FOREIGN KEY (mission_id, robot_id)
        REFERENCES robot.mission (mission_id, robot_id)
        ON DELETE RESTRICT;

CREATE INDEX ix_robot_telemetry_mission_sampled
    ON telemetry.robot_telemetry (
        mission_id,
        sampled_at DESC,
        message_id
    )
    WHERE mission_id IS NOT NULL;

CREATE TABLE telemetry.robot_event (
    event_id UUID NOT NULL,
    schema_version VARCHAR(10) NOT NULL,
    robot_id UUID NOT NULL,
    boot_id UUID NOT NULL,
    seq BIGINT NOT NULL,
    mission_id UUID,
    event_type VARCHAR(50) NOT NULL,
    source_component VARCHAR(20) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    raw_payload JSONB NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT pk_robot_event PRIMARY KEY (event_id),
    CONSTRAINT uq_robot_event_robot_identity UNIQUE (event_id, robot_id),
    CONSTRAINT uq_robot_event_sequence UNIQUE (robot_id, boot_id, seq),
    CONSTRAINT ck_robot_event_schema CHECK (schema_version = '2.0'),
    CONSTRAINT ck_robot_event_sequence CHECK (seq >= 1),
    CONSTRAINT ck_robot_event_type CHECK (
        event_type IN (
            'BRAILLE_BLOCK_INCIDENT_DETECTED',
            'BATTERY_LOW',
            'BATTERY_CRITICAL',
            'BATTERY_RECOVERED',
            'BATTERY_SENSOR_FAULT',
            'BATTERY_SENSOR_RECOVERED',
            'SAFETY_STOPPED',
            'SAFETY_CLEARED',
            'SENSOR_FAULT',
            'SENSOR_RECOVERED',
            'MISSION_STARTED',
            'MISSION_PAUSED',
            'MISSION_RESUMED',
            'MISSION_COMPLETED',
            'MISSION_CANCELLED',
            'MISSION_FAILED'
        )
    ),
    CONSTRAINT ck_robot_event_source CHECK (
        (
            event_type = 'BRAILLE_BLOCK_INCIDENT_DETECTED'
            AND source_component = 'ai'
        )
        OR (
            event_type IN (
                'BATTERY_LOW',
                'BATTERY_CRITICAL',
                'BATTERY_RECOVERED',
                'BATTERY_SENSOR_FAULT',
                'BATTERY_SENSOR_RECOVERED'
            )
            AND source_component = 'power'
        )
        OR (
            event_type IN (
                'SAFETY_STOPPED',
                'SAFETY_CLEARED',
                'SENSOR_FAULT',
                'SENSOR_RECOVERED'
            )
            AND source_component = 'safety'
        )
        OR (
            event_type IN (
                'MISSION_STARTED',
                'MISSION_PAUSED',
                'MISSION_RESUMED',
                'MISSION_COMPLETED',
                'MISSION_CANCELLED',
                'MISSION_FAILED'
            )
            AND source_component = 'mission'
        )
    ),
    CONSTRAINT ck_robot_event_mission CHECK (
        event_type NOT IN (
            'MISSION_STARTED',
            'MISSION_PAUSED',
            'MISSION_RESUMED',
            'MISSION_COMPLETED',
            'MISSION_CANCELLED',
            'MISSION_FAILED'
        )
        OR mission_id IS NOT NULL
    ),
    CONSTRAINT ck_robot_event_times CHECK (sent_at >= occurred_at),
    CONSTRAINT ck_robot_event_payload CHECK (
        JSONB_TYPEOF(raw_payload) = 'object'
    ),
    CONSTRAINT fk_robot_event_robot
        FOREIGN KEY (robot_id)
        REFERENCES robot.robot (robot_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_robot_event_boot
        FOREIGN KEY (robot_id, boot_id)
        REFERENCES telemetry.robot_boot_session (robot_id, boot_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_robot_event_mission_robot
        FOREIGN KEY (mission_id, robot_id)
        REFERENCES robot.mission (mission_id, robot_id)
        ON DELETE RESTRICT
);

CREATE INDEX ix_robot_event_robot_occurred
    ON telemetry.robot_event (robot_id, occurred_at DESC, event_id);

CREATE INDEX ix_robot_event_mission_occurred
    ON telemetry.robot_event (mission_id, occurred_at DESC, event_id)
    WHERE mission_id IS NOT NULL;

CREATE INDEX ix_robot_event_type_occurred
    ON telemetry.robot_event (event_type, occurred_at DESC, event_id);

CREATE FUNCTION telemetry.reject_robot_event_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, telemetry
AS $$
BEGIN
    RAISE EXCEPTION 'robot_event is append-only'
        USING ERRCODE = '55000';
END;
$$;

REVOKE ALL
    ON FUNCTION telemetry.reject_robot_event_mutation()
    FROM PUBLIC;

CREATE TRIGGER tr_robot_event_append_only
    BEFORE UPDATE OR DELETE ON telemetry.robot_event
    FOR EACH ROW
    EXECUTE FUNCTION telemetry.reject_robot_event_mutation();

CREATE TABLE robot.mission_status_history (
    history_id UUID DEFAULT gen_random_uuid() NOT NULL,
    mission_id UUID NOT NULL,
    source_event_id UUID,
    transition_source VARCHAR(10) NOT NULL,
    before_status VARCHAR(20),
    after_status VARCHAR(20) NOT NULL,
    transition_reason VARCHAR(500),
    result_payload JSONB,
    changed_by UUID,
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT pk_mission_status_history PRIMARY KEY (history_id),
    CONSTRAINT ck_mission_history_source CHECK (
        transition_source IN ('MQTT', 'ADMIN', 'SYSTEM')
    ),
    CONSTRAINT ck_mission_history_before_status CHECK (
        before_status IS NULL
        OR before_status IN (
            'CREATED',
            'READY',
            'RUNNING',
            'PAUSED',
            'COMPLETED',
            'CANCELLED',
            'FAILED'
        )
    ),
    CONSTRAINT ck_mission_history_after_status CHECK (
        after_status IN (
            'CREATED',
            'READY',
            'RUNNING',
            'PAUSED',
            'COMPLETED',
            'CANCELLED',
            'FAILED'
        )
    ),
    CONSTRAINT ck_mission_history_actor CHECK (
        (
            transition_source = 'MQTT'
            AND source_event_id IS NOT NULL
            AND changed_by IS NULL
        )
        OR (
            transition_source = 'ADMIN'
            AND source_event_id IS NULL
            AND changed_by IS NOT NULL
        )
        OR (
            transition_source = 'SYSTEM'
            AND source_event_id IS NULL
            AND changed_by IS NULL
        )
    ),
    CONSTRAINT ck_mission_history_result CHECK (
        result_payload IS NULL
        OR JSONB_TYPEOF(result_payload) = 'object'
    ),
    CONSTRAINT fk_mission_history_mission
        FOREIGN KEY (mission_id)
        REFERENCES robot.mission (mission_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_mission_history_source_event
        FOREIGN KEY (source_event_id)
        REFERENCES telemetry.robot_event (event_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_mission_history_changed_by
        FOREIGN KEY (changed_by)
        REFERENCES auth.admin_user (user_id)
        ON DELETE RESTRICT
);

CREATE UNIQUE INDEX uq_mission_history_source_event
    ON robot.mission_status_history (source_event_id)
    WHERE source_event_id IS NOT NULL;

CREATE INDEX ix_mission_history_mission_occurred
    ON robot.mission_status_history (
        mission_id,
        occurred_at ASC,
        history_id
    );

CREATE FUNCTION robot.reject_mission_status_history_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, robot
AS $$
BEGIN
    RAISE EXCEPTION 'mission_status_history is append-only'
        USING ERRCODE = '55000';
END;
$$;

REVOKE ALL
    ON FUNCTION robot.reject_mission_status_history_mutation()
    FROM PUBLIC;

CREATE TRIGGER tr_mission_status_history_append_only
    BEFORE UPDATE OR DELETE ON robot.mission_status_history
    FOR EACH ROW
    EXECUTE FUNCTION robot.reject_mission_status_history_mutation();

CREATE TABLE storage.robot_image (
    image_id UUID NOT NULL,
    robot_id UUID NOT NULL,
    event_id UUID,
    bucket_name VARCHAR(100) NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    content_type VARCHAR(50) DEFAULT 'image/jpeg' NOT NULL,
    byte_size INTEGER NOT NULL,
    sha256_hex CHAR(64) NOT NULL,
    image_width INTEGER NOT NULL,
    image_height INTEGER NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL,
    uploaded_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    image_status VARCHAR(20) DEFAULT 'AVAILABLE' NOT NULL,
    deleted_at TIMESTAMPTZ,
    metadata JSONB DEFAULT '{}'::JSONB NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT pk_robot_image PRIMARY KEY (image_id),
    CONSTRAINT uq_robot_image_robot_identity UNIQUE (image_id, robot_id),
    CONSTRAINT uq_robot_image_object_key UNIQUE (object_key),
    CONSTRAINT ck_robot_image_bucket CHECK (
        bucket_name = BTRIM(bucket_name)
        AND bucket_name <> ''
    ),
    CONSTRAINT ck_robot_image_object_key CHECK (
        object_key = BTRIM(object_key)
        AND object_key <> ''
    ),
    CONSTRAINT ck_robot_image_content_type CHECK (
        content_type = 'image/jpeg'
    ),
    CONSTRAINT ck_robot_image_size CHECK (
        byte_size BETWEEN 1 AND 512000
    ),
    CONSTRAINT ck_robot_image_sha256 CHECK (
        sha256_hex ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_robot_image_dimensions CHECK (
        image_width > 0
        AND image_height > 0
    ),
    CONSTRAINT ck_robot_image_status CHECK (
        image_status IN ('AVAILABLE', 'QUARANTINED', 'DELETED')
    ),
    CONSTRAINT ck_robot_image_lifecycle CHECK (
        (
            image_status = 'DELETED'
            AND deleted_at IS NOT NULL
        )
        OR (
            image_status <> 'DELETED'
            AND deleted_at IS NULL
        )
    ),
    CONSTRAINT ck_robot_image_metadata CHECK (
        JSONB_TYPEOF(metadata) = 'object'
    ),
    CONSTRAINT ck_robot_image_timestamps CHECK (
        uploaded_at >= captured_at
        AND updated_at >= created_at
        AND (
            deleted_at IS NULL
            OR deleted_at >= uploaded_at
        )
    ),
    CONSTRAINT fk_robot_image_robot
        FOREIGN KEY (robot_id)
        REFERENCES robot.robot (robot_id)
        ON DELETE RESTRICT
);

CREATE UNIQUE INDEX uq_robot_image_event
    ON storage.robot_image (event_id)
    WHERE event_id IS NOT NULL;

CREATE INDEX ix_robot_image_robot_captured
    ON storage.robot_image (robot_id, captured_at DESC, image_id);

CREATE INDEX ix_robot_image_status_uploaded
    ON storage.robot_image (image_status, uploaded_at DESC, image_id);

COMMENT ON TABLE robot.patrol_area IS
    '기간·지역 통계와 Mission 계획이 참조하는 SRID 4326 순찰 구역 정본';
COMMENT ON COLUMN robot.patrol_area.boundary IS
    'WGS84 Polygon 또는 MultiPolygon';
COMMENT ON TABLE robot.patrol_route IS
    '순찰 구역 안의 버전별 계획 경로. 같은 route_code의 ACTIVE 버전은 하나';
COMMENT ON COLUMN robot.patrol_route.route_geometry IS
    'WGS84 LineString 또는 MultiLineString';
COMMENT ON TABLE robot.mission IS
    'Robot·Patrol Area·Patrol Route를 연결하는 순찰 Mission 정본';
COMMENT ON COLUMN robot.mission.mission_code IS
    'MQTT Payload의 문자열 mission_id. 예: MIS-20260726-001';
COMMENT ON COLUMN robot.mission.mission_id IS
    'DB 내부 관계에 사용하는 Mission UUID PK';
COMMENT ON TABLE telemetry.robot_event IS
    'Incident·Battery·Safety·Mission MQTT Event 공통 append-only 원본';
COMMENT ON COLUMN telemetry.robot_event.event_id IS
    'QoS 1 재전송을 제거하는 MQTT Event UUID 멱등 키';
COMMENT ON COLUMN telemetry.robot_event.mission_id IS
    'mission_code를 검증해 변환한 DB Mission UUID';
COMMENT ON COLUMN telemetry.robot_telemetry.mission_id IS
    'MQTT 문자열 mission_id를 mission_code로 조회해 변환한 DB Mission UUID';
COMMENT ON TABLE robot.mission_status_history IS
    'REST·MQTT·SYSTEM Mission 상태 전이의 append-only 이력';
COMMENT ON TABLE storage.robot_image IS
    'Incident보다 먼저 HTTPS로 업로드한 private JPEG Object 메타데이터';
COMMENT ON COLUMN storage.robot_image.event_id IS
    '이미지 업로드 멱등성 연결 값. Event보다 이미지가 먼저 저장되므로 FK는 걸지 않음';

GRANT USAGE ON SCHEMA storage TO "${applicationRole}";

GRANT SELECT, INSERT, UPDATE
    ON robot.patrol_area,
       robot.patrol_route,
       robot.mission
    TO "${applicationRole}";

GRANT SELECT, INSERT
    ON robot.mission_status_history,
       telemetry.robot_event
    TO "${applicationRole}";

GRANT SELECT, INSERT, UPDATE
    ON storage.robot_image
    TO "${applicationRole}";
