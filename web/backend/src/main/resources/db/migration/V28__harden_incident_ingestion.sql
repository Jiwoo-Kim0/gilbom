-- 신규 결함의 외부 표시 코드를 INC-YYYYMMDD-NNNNNN으로 생성할 수 있도록
-- 일자별 원자적 Counter를 두고, 위치를 확정할 수 없는 Incident도 검토 대상으로
-- 보존한다. 기존 비정형 incident_code는 운영 참조를 깨지 않도록 즉시 갱신하지
-- 않고 NOT VALID CHECK로 신규 쓰기부터 정형식을 강제한다.

CREATE TABLE defect.incident_code_counter (
    incident_date DATE NOT NULL,
    last_value INTEGER NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT pk_incident_code_counter PRIMARY KEY (incident_date),
    CONSTRAINT ck_incident_code_counter_value
        CHECK (last_value BETWEEN 1 AND 999999)
);

INSERT INTO defect.incident_code_counter (
    incident_date,
    last_value
)
SELECT
    TO_DATE(SUBSTRING(incident_code FROM 5 FOR 8), 'YYYYMMDD'),
    MAX(RIGHT(incident_code, 6)::INTEGER)
FROM defect.defect
WHERE incident_code ~ '^INC-[0-9]{8}-[0-9]{6}$'
GROUP BY TO_DATE(SUBSTRING(incident_code FROM 5 FOR 8), 'YYYYMMDD')
ON CONFLICT (incident_date) DO UPDATE
SET last_value = GREATEST(
        defect.incident_code_counter.last_value,
        EXCLUDED.last_value),
    updated_at = NOW();

ALTER TABLE defect.defect
    ADD CONSTRAINT ck_defect_incident_code_format
        CHECK (incident_code ~ '^INC-[0-9]{8}-[0-9]{6}$')
        NOT VALID;

CREATE TABLE defect.incident_ingestion_issue (
    event_id UUID NOT NULL,
    robot_id UUID NOT NULL,
    mission_id UUID,
    image_id UUID,
    issue_type VARCHAR(40) NOT NULL,
    issue_status VARCHAR(20) DEFAULT 'OPEN' NOT NULL,
    zone_id VARCHAR(10),
    detected_defect_type VARCHAR(40) NOT NULL,
    confidence NUMERIC(4,3) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    resolved_at TIMESTAMPTZ,
    CONSTRAINT pk_incident_ingestion_issue PRIMARY KEY (event_id),
    CONSTRAINT ck_incident_ingestion_issue_type CHECK (
        issue_type IN ('INVALID_GPS', 'PATROL_AREA_UNRESOLVED')
    ),
    CONSTRAINT ck_incident_ingestion_issue_status CHECK (
        issue_status IN ('OPEN', 'RESOLVED', 'DISMISSED')
    ),
    CONSTRAINT ck_incident_ingestion_issue_zone CHECK (
        zone_id IS NULL OR zone_id ~ '^[A-Z]-[0-9]{2}$'
    ),
    CONSTRAINT ck_incident_ingestion_issue_defect_type CHECK (
        detected_defect_type IN (
            'DAMAGED', 'CONTAMINATED', 'UPLIFTED', 'BLOCKED',
            'MISSING_SUSPECTED', 'INSTALLATION_INVALID',
            'UNKNOWN_ANOMALY'
        )
    ),
    CONSTRAINT ck_incident_ingestion_issue_confidence CHECK (
        confidence BETWEEN 0 AND 1
    ),
    CONSTRAINT ck_incident_ingestion_issue_resolution CHECK (
        (issue_status = 'OPEN' AND resolved_at IS NULL)
        OR (issue_status IN ('RESOLVED', 'DISMISSED')
            AND resolved_at IS NOT NULL)
    ),
    CONSTRAINT fk_incident_ingestion_issue_event
        FOREIGN KEY (event_id, robot_id)
        REFERENCES telemetry.robot_event (event_id, robot_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_incident_ingestion_issue_mission
        FOREIGN KEY (mission_id, robot_id)
        REFERENCES robot.mission (mission_id, robot_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_incident_ingestion_issue_image
        FOREIGN KEY (image_id, robot_id)
        REFERENCES storage.robot_image (image_id, robot_id)
        ON DELETE RESTRICT
);

CREATE INDEX ix_incident_ingestion_issue_open_created
    ON defect.incident_ingestion_issue (created_at DESC, event_id)
    WHERE issue_status = 'OPEN';

COMMENT ON TABLE defect.incident_code_counter IS
    'INC-YYYYMMDD-NNNNNN 외부 표시 코드의 Asia/Seoul 일자별 원자적 순번';
COMMENT ON TABLE defect.incident_ingestion_issue IS
    'GPS 또는 순찰 구역을 확정하지 못해 결함으로 만들지 않은 Incident 검토 대기열';

GRANT SELECT, INSERT, UPDATE
    ON defect.incident_code_counter
    TO "${applicationRole}";
GRANT SELECT, INSERT, UPDATE
    ON defect.incident_ingestion_issue
    TO "${applicationRole}";
