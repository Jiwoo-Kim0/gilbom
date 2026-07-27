-- 점자블록 결함 관리 스키마. robot·telemetry·storage 도메인과 결합되는 칼럼
-- (patrol_area_id, patrol_route_id, defect_observation.event_id,
-- defect_observation.image_id)은 현재 FK 없이 UUID만 보관하고,
-- 연동 대상 테이블 계약이 확정되면 후속 migration에서 FK를 이어 붙인다.
CREATE SCHEMA defect;

-- 서로 다른 event_id의 공간적 중복을 병합해 만드는 실제 관리 대상. 위험도는 서버가
-- 계산하고, workflow_status는 관리자 처리 상태를 나타낸다.
CREATE TABLE defect.defect (
    defect_id UUID DEFAULT gen_random_uuid() NOT NULL,
    incident_code VARCHAR(40) NOT NULL,
    asset_type VARCHAR(30) DEFAULT 'BRAILLE_BLOCK' NOT NULL,
    block_type VARCHAR(20) NOT NULL,
    defect_type VARCHAR(40) NOT NULL,
    risk_level VARCHAR(20) DEFAULT 'MEDIUM' NOT NULL,
    risk_score NUMERIC(6,2),
    risk_rule_version VARCHAR(30),
    risk_calculated_at TIMESTAMPTZ,
    workflow_status VARCHAR(30) DEFAULT 'NEW' NOT NULL,
    resolution VARCHAR(40),
    resolution_note TEXT,
    location GEOMETRY(POINT, 4326) NOT NULL,
    location_accuracy_m REAL,
    zone_id VARCHAR(10) NOT NULL,
    patrol_area_id UUID,
    patrol_route_id UUID,
    location_label VARCHAR(200),
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    observation_count INTEGER DEFAULT 1 NOT NULL,
    latest_confidence NUMERIC(4,3),
    review_required BOOLEAN DEFAULT FALSE NOT NULL,
    merged_into_defect_id UUID,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT pk_defect PRIMARY KEY (defect_id),
    CONSTRAINT uq_defect_incident_code UNIQUE (incident_code),
    CONSTRAINT ck_defect_incident_code CHECK (incident_code = BTRIM(incident_code) AND incident_code <> ''),
    CONSTRAINT ck_defect_asset_type CHECK (asset_type = 'BRAILLE_BLOCK'),
    CONSTRAINT ck_defect_block_type CHECK (block_type IN ('LINEAR', 'DOT', 'UNKNOWN')),
    CONSTRAINT ck_defect_type CHECK (defect_type IN (
        'DAMAGED', 'CONTAMINATED', 'UPLIFTED', 'BLOCKED',
        'MISSING_SUSPECTED', 'INSTALLATION_INVALID', 'UNKNOWN_ANOMALY'
    )),
    CONSTRAINT ck_defect_risk_level CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_defect_risk_score CHECK (risk_score IS NULL OR risk_score BETWEEN 0 AND 100),
    CONSTRAINT ck_defect_workflow_status CHECK (workflow_status IN (
        'NEW', 'ACCEPTED', 'IN_PROGRESS', 'RESOLVED', 'FALSE_POSITIVE', 'REJECTED'
    )),
    CONSTRAINT ck_defect_resolution CHECK (resolution IS NULL OR resolution IN (
        'REPAIRED', 'CLEANED', 'DUPLICATE', 'REINSPECTION_REQUIRED', 'NOT_APPLICABLE'
    )),
    CONSTRAINT ck_defect_zone_id CHECK (zone_id ~ '^[A-Z]-[0-9]{2}$'),
    CONSTRAINT ck_defect_observation_times CHECK (last_seen_at >= first_seen_at),
    CONSTRAINT ck_defect_observation_count CHECK (observation_count >= 1),
    CONSTRAINT ck_defect_latest_confidence CHECK (latest_confidence IS NULL OR latest_confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_defect_not_self_merged CHECK (merged_into_defect_id IS NULL OR merged_into_defect_id <> defect_id),
    CONSTRAINT fk_defect_merged_into
        FOREIGN KEY (merged_into_defect_id) REFERENCES defect.defect (defect_id) ON DELETE SET NULL
);

CREATE INDEX ix_defect_location ON defect.defect USING GIST (location);
CREATE INDEX ix_defect_workflow_filter
    ON defect.defect (workflow_status, defect_type, risk_level, last_seen_at DESC);
CREATE INDEX ix_defect_zone_last_seen ON defect.defect (zone_id, last_seen_at DESC);

COMMENT ON TABLE defect.defect IS '서로 다른 event_id의 공간적 중복을 병합해 생성하는 실제 관리 대상과 위험도·행정 처리상태';
COMMENT ON COLUMN defect.defect.incident_code IS '외부 표시 코드. 예: INC-20260723-000031';
COMMENT ON COLUMN defect.defect.risk_level IS '서버가 계산한 위험도. AI confidence와는 별개 값';
COMMENT ON COLUMN defect.defect.workflow_status IS '관리자 REST 처리로만 전이되는 행정 처리 상태';
COMMENT ON COLUMN defect.defect.patrol_area_id IS 'robot.patrol_area 참조 예정 UUID. 대상 테이블 계약 확정 후 FK를 추가한다.';
COMMENT ON COLUMN defect.defect.patrol_route_id IS 'robot.patrol_route 참조 예정 UUID. 대상 테이블 계약 확정 후 FK를 추가한다.';
COMMENT ON COLUMN defect.defect.merged_into_defect_id IS '공간 병합으로 흡수된 경우의 대표 결함';

-- events/incident의 불변 관측 사실. AI가 보낸 모델·이미지·BBox·파손 비율을 실제 관리
-- 결함에 연결한다. defect와 달리 한 번 저장되면 내용이 바뀌지 않는다.
CREATE TABLE defect.defect_observation (
    event_id UUID NOT NULL,
    defect_id UUID NOT NULL,
    location_valid BOOLEAN NOT NULL,
    location GEOMETRY(POINT, 4326),
    location_accuracy_m REAL,
    zone_id VARCHAR(10) NOT NULL,
    asset_type VARCHAR(30) NOT NULL,
    block_type VARCHAR(20) NOT NULL,
    detected_defect_type VARCHAR(40) NOT NULL,
    confidence NUMERIC(4,3) NOT NULL,
    damage_ratio NUMERIC(5,4),
    attributes JSONB,
    image_id UUID,
    evidence_captured_at TIMESTAMPTZ,
    image_width INTEGER,
    image_height INTEGER,
    bbox_norm JSONB,
    model_name VARCHAR(100) NOT NULL,
    model_version VARCHAR(50) NOT NULL,
    is_representative BOOLEAN DEFAULT FALSE NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT pk_defect_observation PRIMARY KEY (event_id),
    CONSTRAINT ck_observation_zone_id CHECK (zone_id ~ '^[A-Z]-[0-9]{2}$'),
    CONSTRAINT ck_observation_asset_type CHECK (asset_type = 'BRAILLE_BLOCK'),
    CONSTRAINT ck_observation_block_type CHECK (block_type IN ('LINEAR', 'DOT', 'UNKNOWN')),
    CONSTRAINT ck_observation_defect_type CHECK (detected_defect_type IN (
        'DAMAGED', 'CONTAMINATED', 'UPLIFTED', 'BLOCKED',
        'MISSING_SUSPECTED', 'INSTALLATION_INVALID', 'UNKNOWN_ANOMALY'
    )),
    CONSTRAINT ck_observation_confidence CHECK (confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_observation_damage_ratio CHECK (damage_ratio IS NULL OR damage_ratio BETWEEN 0 AND 1),
    CONSTRAINT ck_observation_attributes CHECK (attributes IS NULL OR JSONB_TYPEOF(attributes) = 'object'),
    CONSTRAINT ck_observation_bbox_norm CHECK (bbox_norm IS NULL OR JSONB_TYPEOF(bbox_norm) = 'array'),
    CONSTRAINT ck_observation_position CHECK (
        (location_valid = TRUE AND location IS NOT NULL) OR location_valid = FALSE
    ),
    CONSTRAINT ck_observation_image_dims CHECK (
        (image_width IS NULL OR image_width > 0) AND (image_height IS NULL OR image_height > 0)
    ),
    CONSTRAINT fk_observation_defect
        FOREIGN KEY (defect_id) REFERENCES defect.defect (defect_id) ON DELETE RESTRICT
);

CREATE INDEX ix_observation_defect_created ON defect.defect_observation (defect_id, created_at DESC);
CREATE INDEX ix_observation_location ON defect.defect_observation USING GIST (location);
CREATE UNIQUE INDEX uq_observation_representative
    ON defect.defect_observation (defect_id)
    WHERE is_representative = TRUE;

COMMENT ON TABLE defect.defect_observation IS 'events/incident의 불변 관측 사실과 AI 모델·이미지·BBox·파손 비율';
COMMENT ON COLUMN defect.defect_observation.event_id IS 'telemetry.robot_event.event_id 참조 예정 UUID. 대상 테이블 계약 확정 후 FK를 추가한다.';
COMMENT ON COLUMN defect.defect_observation.image_id IS 'storage.robot_image.image_id 참조 예정 UUID. 대상 테이블 계약 확정 후 FK를 추가한다.';
COMMENT ON COLUMN defect.defect_observation.is_representative IS '결함 카드에 표시할 대표 관측 여부. 결함당 최대 하나';

-- 관리자 처리상태·유형·위험도·위치·처리 결과 변경의 전후 값과 사유. report_version,
-- audit_log와 동일하게 append-only로 보호해 처리 이력을 사후에 고치지 못하게 한다.
CREATE TABLE defect.defect_status_history (
    history_id BIGINT GENERATED ALWAYS AS IDENTITY,
    defect_id UUID NOT NULL,
    changed_by UUID,
    change_type VARCHAR(40) NOT NULL,
    before_status VARCHAR(30),
    after_status VARCHAR(30),
    before_values JSONB,
    after_values JSONB,
    reason VARCHAR(1000),
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT pk_defect_status_history PRIMARY KEY (history_id),
    CONSTRAINT ck_defect_history_change_type CHECK (change_type IN (
        'STATUS_CHANGED', 'TYPE_CHANGED', 'RISK_CHANGED', 'LOCATION_CHANGED',
        'RESOLUTION_CHANGED', 'MERGED', 'UNMERGED'
    )),
    CONSTRAINT ck_defect_history_before_values CHECK (before_values IS NULL OR JSONB_TYPEOF(before_values) = 'object'),
    CONSTRAINT ck_defect_history_after_values CHECK (after_values IS NULL OR JSONB_TYPEOF(after_values) = 'object'),
    CONSTRAINT fk_defect_history_defect
        FOREIGN KEY (defect_id) REFERENCES defect.defect (defect_id) ON DELETE CASCADE,
    CONSTRAINT fk_defect_history_changed_by
        FOREIGN KEY (changed_by) REFERENCES auth.admin_user (user_id) ON DELETE SET NULL
);

CREATE INDEX ix_defect_status_history_defect_created
    ON defect.defect_status_history (defect_id, created_at DESC);

COMMENT ON TABLE defect.defect_status_history IS '결함 처리상태·유형·위험도·위치·처리 결과 변경의 전후 값과 사유. append-only';
COMMENT ON COLUMN defect.defect_status_history.changed_by IS '변경한 관리자. SYSTEM(자동 위험도 재계산 등)이면 NULL';

CREATE FUNCTION defect.reject_defect_status_history_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'defect_status_history is append-only' USING ERRCODE = '55000';
END;
$$;

REVOKE ALL ON FUNCTION defect.reject_defect_status_history_mutation() FROM PUBLIC;

CREATE TRIGGER tr_defect_status_history_append_only
    BEFORE UPDATE OR DELETE ON defect.defect_status_history
    FOR EACH ROW EXECUTE FUNCTION defect.reject_defect_status_history_mutation();

-- 서로 다른 event_id의 동일 위치·동일 유형 결함을 병합하거나 취소한 과정을 추적한다.
CREATE TABLE defect.defect_merge_history (
    merge_id UUID DEFAULT gen_random_uuid() NOT NULL,
    source_defect_id UUID NOT NULL,
    target_defect_id UUID NOT NULL,
    merge_status VARCHAR(20) DEFAULT 'MERGED' NOT NULL,
    merge_reason TEXT,
    merged_by UUID NOT NULL,
    merged_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    reverted_by UUID,
    reverted_at TIMESTAMPTZ,
    revert_reason TEXT,
    CONSTRAINT pk_defect_merge_history PRIMARY KEY (merge_id),
    CONSTRAINT ck_defect_merge_status CHECK (merge_status IN ('MERGED', 'REVERTED')),
    CONSTRAINT ck_defect_merge_distinct CHECK (source_defect_id <> target_defect_id),
    CONSTRAINT ck_defect_merge_lifecycle CHECK (
        (merge_status = 'MERGED' AND reverted_by IS NULL AND reverted_at IS NULL)
        OR (merge_status = 'REVERTED' AND reverted_by IS NOT NULL AND reverted_at IS NOT NULL AND reverted_at >= merged_at)
    ),
    CONSTRAINT fk_defect_merge_source
        FOREIGN KEY (source_defect_id) REFERENCES defect.defect (defect_id) ON DELETE RESTRICT,
    CONSTRAINT fk_defect_merge_target
        FOREIGN KEY (target_defect_id) REFERENCES defect.defect (defect_id) ON DELETE RESTRICT,
    CONSTRAINT fk_defect_merge_merged_by
        FOREIGN KEY (merged_by) REFERENCES auth.admin_user (user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_defect_merge_reverted_by
        FOREIGN KEY (reverted_by) REFERENCES auth.admin_user (user_id) ON DELETE SET NULL
);

CREATE INDEX ix_defect_merge_target_merged
    ON defect.defect_merge_history (target_defect_id, merged_at DESC);

COMMENT ON TABLE defect.defect_merge_history IS '서로 다른 event_id의 동일 위치·동일 유형 결함을 병합하거나 취소한 과정';
COMMENT ON COLUMN defect.defect_merge_history.source_defect_id IS '병합되어 흡수된 원본 결함';
COMMENT ON COLUMN defect.defect_merge_history.target_defect_id IS '병합 후 대표로 남는 결함';

-- defect_observation은 AI 관측 사실이므로 수정하지 않고 SELECT/INSERT만 허용한다.
-- defect_status_history는 append-only trigger로 UPDATE/DELETE를 이미 차단하므로
-- SELECT/INSERT만 부여한다.
GRANT USAGE ON SCHEMA defect TO "${applicationRole}";
GRANT SELECT, INSERT, UPDATE ON defect.defect TO "${applicationRole}";
GRANT SELECT, INSERT ON defect.defect_observation TO "${applicationRole}";
GRANT SELECT, INSERT ON defect.defect_status_history TO "${applicationRole}";
GRANT SELECT, INSERT, UPDATE ON defect.defect_merge_history TO "${applicationRole}";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA defect TO "${applicationRole}";
