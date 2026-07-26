-- 보고서 header는 목록·상태 전이를 위한 현재값을, report_version은 재현 가능한 불변 snapshot을 가진다.
CREATE TABLE report.anomaly_report (
    report_id UUID DEFAULT gen_random_uuid() NOT NULL,
    report_code VARCHAR(30) NOT NULL,
    title VARCHAR(200) NOT NULL,
    summary VARCHAR(255),
    report_type VARCHAR(20) NOT NULL,
    report_status VARCHAR(20) DEFAULT 'DRAFT' NOT NULL,
    generation_method VARCHAR(20) DEFAULT 'MANUAL' NOT NULL,
    creator_type VARCHAR(20) DEFAULT 'USER' NOT NULL,
    period_from TIMESTAMPTZ NOT NULL,
    period_to TIMESTAMPTZ NOT NULL,
    filter_criteria JSONB,
    current_version_no INTEGER DEFAULT 1 NOT NULL,
    approved_version_no INTEGER,
    created_by UUID,
    approved_by UUID,
    approved_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT pk_anomaly_report PRIMARY KEY (report_id),
    CONSTRAINT uq_anomaly_report_code UNIQUE (report_code),
    CONSTRAINT ck_anomaly_report_title CHECK (title = BTRIM(title) AND title <> ''),
    CONSTRAINT ck_anomaly_report_type CHECK (report_type IN ('PERIODIC', 'ISSUE')),
    CONSTRAINT ck_anomaly_report_status CHECK (
        report_status IN ('DRAFT', 'IN_REVIEW', 'APPROVED', 'REJECTED')
    ),
    CONSTRAINT ck_anomaly_report_generation CHECK (
        generation_method IN ('MANUAL', 'TEMPLATE', 'LLM', 'SYSTEM')
    ),
    CONSTRAINT ck_anomaly_report_creator_type CHECK (creator_type IN ('USER', 'SYSTEM')),
    CONSTRAINT ck_anomaly_report_creator CHECK (
        (creator_type = 'USER' AND created_by IS NOT NULL)
        OR (creator_type = 'SYSTEM' AND created_by IS NULL)
    ),
    CONSTRAINT ck_anomaly_report_period CHECK (period_to >= period_from),
    CONSTRAINT ck_anomaly_report_filter CHECK (
        filter_criteria IS NULL OR JSONB_TYPEOF(filter_criteria) = 'object'
    ),
    CONSTRAINT ck_anomaly_report_version CHECK (
        current_version_no >= 1
        AND (approved_version_no IS NULL OR approved_version_no BETWEEN 1 AND current_version_no)
    ),
    CONSTRAINT ck_anomaly_report_approval CHECK (
        (
            report_status = 'APPROVED'
            AND approved_by IS NOT NULL
            AND approved_at IS NOT NULL
            AND approved_version_no IS NOT NULL
        )
        OR
        (
            report_status <> 'APPROVED'
            AND approved_by IS NULL
            AND approved_at IS NULL
            AND approved_version_no IS NULL
        )
    ),
    CONSTRAINT ck_anomaly_report_soft_delete CHECK (
        deleted_at IS NULL OR report_status IN ('DRAFT', 'REJECTED')
    ),
    CONSTRAINT ck_anomaly_report_timestamps CHECK (updated_at >= created_at),
    CONSTRAINT fk_anomaly_report_created_by
        FOREIGN KEY (created_by) REFERENCES auth.admin_user (user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_anomaly_report_approved_by
        FOREIGN KEY (approved_by) REFERENCES auth.admin_user (user_id) ON DELETE RESTRICT
);

CREATE INDEX ix_anomaly_report_status_created
    ON report.anomaly_report (report_status, created_at DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX ix_anomaly_report_creator_created
    ON report.anomaly_report (created_by, created_at DESC)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE report.anomaly_report IS '보고서 현재 상태와 최신 버전 포인터를 저장하는 header';
COMMENT ON COLUMN report.anomaly_report.current_version_no IS '현재 화면과 상태 전이가 참조하는 최신 불변 version';
COMMENT ON COLUMN report.anomaly_report.approved_version_no IS '승인된 정확한 version 번호';
COMMENT ON COLUMN report.anomaly_report.deleted_at IS 'DRAFT·REJECTED에서만 가능한 소프트 삭제 시각';
COMMENT ON COLUMN report.anomaly_report.filter_criteria IS '보고서 생성 시점 필터의 JSON object snapshot';

-- 제목·기간도 수정 가능한 내용이므로 본문과 함께 매 version에 snapshot으로 보존한다.
CREATE TABLE report.report_version (
    report_version_id UUID DEFAULT gen_random_uuid() NOT NULL,
    report_id UUID NOT NULL,
    version_no INTEGER NOT NULL,
    title VARCHAR(200) NOT NULL,
    summary VARCHAR(255),
    period_from TIMESTAMPTZ NOT NULL,
    period_to TIMESTAMPTZ NOT NULL,
    content TEXT NOT NULL,
    statistics JSONB DEFAULT '{}'::JSONB NOT NULL,
    llm_model VARCHAR(100),
    prompt_version VARCHAR(50),
    change_note VARCHAR(500),
    creator_type VARCHAR(20) DEFAULT 'USER' NOT NULL,
    created_by UUID,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT pk_report_version PRIMARY KEY (report_version_id),
    CONSTRAINT uq_report_version UNIQUE (report_id, version_no),
    CONSTRAINT ck_report_version_number CHECK (version_no >= 1),
    CONSTRAINT ck_report_version_title CHECK (title = BTRIM(title) AND title <> ''),
    CONSTRAINT ck_report_version_period CHECK (period_to >= period_from),
    CONSTRAINT ck_report_version_statistics CHECK (JSONB_TYPEOF(statistics) = 'object'),
    CONSTRAINT ck_report_version_creator_type CHECK (creator_type IN ('USER', 'SYSTEM')),
    CONSTRAINT ck_report_version_creator CHECK (
        (creator_type = 'USER' AND created_by IS NOT NULL)
        OR (creator_type = 'SYSTEM' AND created_by IS NULL)
    ),
    CONSTRAINT fk_report_version_report
        FOREIGN KEY (report_id) REFERENCES report.anomaly_report (report_id) ON DELETE RESTRICT,
    CONSTRAINT fk_report_version_created_by
        FOREIGN KEY (created_by) REFERENCES auth.admin_user (user_id) ON DELETE RESTRICT
);

CREATE INDEX ix_report_version_report_created
    ON report.report_version (report_id, version_no DESC);

COMMENT ON TABLE report.report_version IS '덮어쓰지 않는 보고서 제목·기간·본문·통계 snapshot';
COMMENT ON COLUMN report.report_version.version_no IS '보고서 안에서 1부터 증가하는 낙관적 잠금 version';
COMMENT ON COLUMN report.report_version.statistics IS '보고서를 재현할 수 있는 집계 결과 JSON object';
COMMENT ON COLUMN report.report_version.llm_model IS 'LLM 생성 시 사용한 모델 식별자. Prompt나 secret 원문은 저장하지 않는다.';
COMMENT ON COLUMN report.report_version.change_note IS '민감정보를 제외한 500자 이하 수정 사유';

-- 보고서 생성 시 header와 첫 version을 한 transaction에 넣을 수 있도록 commit 시점에 검사한다.
ALTER TABLE report.anomaly_report
    ADD CONSTRAINT fk_anomaly_report_current_version
    FOREIGN KEY (report_id, current_version_no)
    REFERENCES report.report_version (report_id, version_no)
    DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE report.anomaly_report
    ADD CONSTRAINT fk_anomaly_report_approved_version
    FOREIGN KEY (report_id, approved_version_no)
    REFERENCES report.report_version (report_id, version_no)
    DEFERRABLE INITIALLY DEFERRED;

-- version history는 잘못된 application 권한이 생겨도 UPDATE·DELETE할 수 없게 DB에서도 차단한다.
CREATE FUNCTION report.reject_report_version_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'report_version is append-only' USING ERRCODE = '55000';
END;
$$;

REVOKE ALL ON FUNCTION report.reject_report_version_mutation() FROM PUBLIC;

CREATE TRIGGER tr_report_version_append_only
    BEFORE UPDATE OR DELETE ON report.report_version
    FOR EACH ROW EXECUTE FUNCTION report.reject_report_version_mutation();

-- 감사 로그에는 민감 원문 대신 허용된 최소 snapshot과 행위 결과만 추가한다.
CREATE TABLE audit.audit_log (
    audit_id BIGINT GENERATED ALWAYS AS IDENTITY,
    request_id UUID,
    actor_user_id UUID,
    actor_type VARCHAR(20) NOT NULL,
    action_type VARCHAR(80) NOT NULL,
    target_type VARCHAR(50) NOT NULL,
    target_id UUID,
    before_value JSONB,
    after_value JSONB,
    reason VARCHAR(1000),
    result VARCHAR(20) NOT NULL,
    ip_address INET,
    user_agent VARCHAR(500),
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT pk_audit_log PRIMARY KEY (audit_id),
    CONSTRAINT ck_audit_actor_type CHECK (actor_type IN ('USER', 'SYSTEM', 'ROBOT')),
    -- 인증 전 로그인 실패는 USER 행위여도 actor_user_id를 알 수 없으므로 NULL을 허용한다.
    CONSTRAINT ck_audit_actor CHECK (
        actor_type = 'USER'
        OR (actor_type IN ('SYSTEM', 'ROBOT') AND actor_user_id IS NULL)
    ),
    CONSTRAINT ck_audit_action_type CHECK (action_type ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    CONSTRAINT ck_audit_target_type CHECK (target_type ~ '^[A-Z][A-Z0-9_]{1,49}$'),
    CONSTRAINT ck_audit_before_value CHECK (
        before_value IS NULL OR JSONB_TYPEOF(before_value) = 'object'
    ),
    CONSTRAINT ck_audit_after_value CHECK (
        after_value IS NULL OR JSONB_TYPEOF(after_value) = 'object'
    ),
    CONSTRAINT ck_audit_result CHECK (result IN ('SUCCESS', 'FAILURE', 'DENIED')),
    CONSTRAINT ck_audit_user_agent CHECK (
        user_agent IS NULL OR user_agent !~ '[[:cntrl:]]'
    ),
    CONSTRAINT fk_audit_actor_user
        FOREIGN KEY (actor_user_id) REFERENCES auth.admin_user (user_id) ON DELETE RESTRICT
);

CREATE INDEX ix_audit_log_created_at ON audit.audit_log (created_at DESC);
CREATE INDEX ix_audit_log_request_id ON audit.audit_log (request_id) WHERE request_id IS NOT NULL;
CREATE INDEX ix_audit_log_actor_created
    ON audit.audit_log (actor_user_id, created_at DESC)
    WHERE actor_user_id IS NOT NULL;
CREATE INDEX ix_audit_log_target_created
    ON audit.audit_log (target_type, target_id, created_at DESC);

COMMENT ON TABLE audit.audit_log IS '인증·권한·업무 행위를 추가 전용으로 보존하는 감사 로그';
COMMENT ON COLUMN audit.audit_log.request_id IS 'HTTP X-Request-Id와 동일한 요청 추적 UUID';
COMMENT ON COLUMN audit.audit_log.actor_user_id IS '행위 관리자. 변경 대상 사용자는 target_id에 기록한다.';
COMMENT ON COLUMN audit.audit_log.before_value IS '민감정보를 제거한 변경 전 최소 JSON object';
COMMENT ON COLUMN audit.audit_log.after_value IS '민감정보를 제거한 변경 후 최소 JSON object';
COMMENT ON COLUMN audit.audit_log.result IS 'SUCCESS, FAILURE, DENIED';

CREATE FUNCTION audit.reject_audit_log_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only' USING ERRCODE = '55000';
END;
$$;

REVOKE ALL ON FUNCTION audit.reject_audit_log_mutation() FROM PUBLIC;

CREATE TRIGGER tr_audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit.audit_log
    FOR EACH ROW EXECUTE FUNCTION audit.reject_audit_log_mutation();

-- 보고서 header는 수정 가능하지만 물리 삭제는 허용하지 않고, version은 INSERT만 허용한다.
GRANT USAGE ON SCHEMA report TO "${applicationRole}";
GRANT SELECT, INSERT, UPDATE ON report.anomaly_report TO "${applicationRole}";
GRANT SELECT, INSERT ON report.report_version TO "${applicationRole}";

-- 감사 테이블 runtime 권한은 요구사항대로 INSERT/SELECT로 제한한다.
GRANT USAGE ON SCHEMA audit TO "${applicationRole}";
GRANT SELECT, INSERT ON audit.audit_log TO "${applicationRole}";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA audit TO "${applicationRole}";
