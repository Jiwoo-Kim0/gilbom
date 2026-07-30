-- report header의 상태 포인터와 append-only version 구조를 유지하면서
-- 버전별 생성 출처·근거 snapshot, 승인 PDF, 생성 멱등 응답을 보강한다.

ALTER TABLE report.report_version
    ADD COLUMN generation_method VARCHAR(20),
    ADD COLUMN evidence_snapshot JSONB
        DEFAULT '{"defectIds":[],"missionIds":[],"patrolAreaIds":[]}'::JSONB
        NOT NULL;

UPDATE report.report_version version
SET generation_method = header.generation_method
FROM report.anomaly_report header
WHERE header.report_id = version.report_id
  AND version.generation_method IS NULL;

ALTER TABLE report.report_version
    ALTER COLUMN generation_method SET NOT NULL,
    ADD CONSTRAINT ck_report_version_generation_method CHECK (
        generation_method IN ('MANUAL', 'TEMPLATE', 'LLM', 'SYSTEM')
    ),
    ADD CONSTRAINT ck_report_version_evidence_snapshot CHECK (
        JSONB_TYPEOF(evidence_snapshot) = 'object'
        AND JSONB_TYPEOF(evidence_snapshot -> 'defectIds') = 'array'
        AND JSONB_TYPEOF(evidence_snapshot -> 'missionIds') = 'array'
        AND JSONB_TYPEOF(evidence_snapshot -> 'patrolAreaIds') = 'array'
    );

COMMENT ON COLUMN report.report_version.content IS
    'API body/content 필드의 버전별 불변 본문 snapshot';
COMMENT ON COLUMN report.report_version.generation_method IS
    '해당 버전이 MANUAL, TEMPLATE, LLM, SYSTEM 중 어떤 방식으로 작성됐는지 나타낸다.';
COMMENT ON COLUMN report.report_version.evidence_snapshot IS
    'FK가 아닌 결함·임무·순찰 구역 UUID 배열 snapshot. 원본 정리 후에도 승인 근거를 재현한다.';
COMMENT ON COLUMN report.report_version.prompt_version IS
    'LLM 생성에 사용한 Prompt 템플릿 버전. Prompt 원문은 저장하지 않는다.';

ALTER TABLE report.anomaly_report
    ADD COLUMN approved_pdf_version_no INTEGER,
    ADD COLUMN approved_pdf_storage_key VARCHAR(500),
    ADD COLUMN approved_pdf_sha256 BYTEA,
    ADD COLUMN approved_pdf_created_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_anomaly_report_approved_pdf_bundle CHECK (
        (
            approved_pdf_version_no IS NULL
            AND approved_pdf_storage_key IS NULL
            AND approved_pdf_sha256 IS NULL
            AND approved_pdf_created_at IS NULL
        )
        OR
        (
            report_status = 'APPROVED'
            AND approved_pdf_version_no = approved_version_no
            AND approved_pdf_storage_key = BTRIM(approved_pdf_storage_key)
            AND approved_pdf_storage_key <> ''
            AND OCTET_LENGTH(approved_pdf_sha256) = 32
            AND approved_pdf_created_at IS NOT NULL
            AND approved_pdf_created_at >= approved_at
        )
    ),
    ADD CONSTRAINT fk_anomaly_report_approved_pdf_version
        FOREIGN KEY (report_id, approved_pdf_version_no)
        REFERENCES report.report_version (report_id, version_no)
        DEFERRABLE INITIALLY DEFERRED;

COMMENT ON COLUMN report.anomaly_report.approved_pdf_version_no IS
    'PDF가 렌더링된 승인 버전. approved_version_no와 반드시 일치한다.';
COMMENT ON COLUMN report.anomaly_report.approved_pdf_storage_key IS
    '승인 PDF의 내부 object storage key. 외부 presigned URL은 저장하지 않는다.';
COMMENT ON COLUMN report.anomaly_report.approved_pdf_sha256 IS
    '승인 PDF 바이트의 SHA-256. 승인본 동일성 검증에 사용한다.';
COMMENT ON COLUMN report.anomaly_report.approved_pdf_created_at IS
    '승인 PDF 결합 완료 시각. PDF bundle은 한 번 결합한 뒤 변경할 수 없다.';

ALTER TABLE request_control.idempotency_request
    ADD COLUMN resource_type VARCHAR(50),
    ADD COLUMN resource_id UUID,
    ADD CONSTRAINT ck_idempotency_resource CHECK (
        (
            resource_type IS NULL
            AND resource_id IS NULL
        )
        OR
        (
            resource_type ~ '^[A-Z][A-Z0-9_]{1,49}$'
            AND resource_id IS NOT NULL
        )
    );

CREATE INDEX ix_idempotency_report_expiry
    ON request_control.idempotency_request (expires_at)
    WHERE request_method = 'POST'
      AND request_path = '/api/v1/reports';

COMMENT ON COLUMN request_control.idempotency_request.resource_type IS
    '멱등 요청으로 생성된 리소스 종류. 보고서 생성은 REPORT를 저장한다.';
COMMENT ON COLUMN request_control.idempotency_request.resource_id IS
    '완료 응답 replay와 생성 결과 추적에 사용하는 리소스 UUID';
COMMENT ON INDEX request_control.ix_idempotency_report_expiry IS
    '24시간 TTL로 저장되는 보고서 생성 멱등 행의 만료 정리 인덱스';

-- 새 version은 첫 version이거나 header의 current_version_no 바로 다음 번호여야 한다.
CREATE FUNCTION report.enforce_report_version_sequence()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    header_version_no INTEGER;
    existing_version_count INTEGER;
BEGIN
    SELECT current_version_no
    INTO header_version_no
    FROM report.anomaly_report
    WHERE report_id = NEW.report_id
    FOR UPDATE;

    IF header_version_no IS NULL THEN
        RAISE EXCEPTION 'report header is required before report version'
            USING ERRCODE = '23503';
    END IF;

    SELECT COUNT(*)
    INTO existing_version_count
    FROM report.report_version
    WHERE report_id = NEW.report_id;

    IF existing_version_count = 0 THEN
        IF NEW.version_no <> 1 OR header_version_no <> 1 THEN
            RAISE EXCEPTION 'first report version must be version 1'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.version_no <> header_version_no + 1 THEN
        RAISE EXCEPTION 'report version must increment current version by one'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION report.enforce_report_version_sequence() FROM PUBLIC;

CREATE TRIGGER tr_report_version_sequence
    BEFORE INSERT ON report.report_version
    FOR EACH ROW EXECUTE FUNCTION report.enforce_report_version_sequence();

-- INSERT한 version이 transaction 종료 전에 header의 현재 version이 됐는지 역방향으로 확인한다.
CREATE FUNCTION report.require_report_version_pointer()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    header_version_no INTEGER;
BEGIN
    SELECT current_version_no
    INTO header_version_no
    FROM report.anomaly_report
    WHERE report_id = NEW.report_id;

    IF header_version_no IS DISTINCT FROM NEW.version_no THEN
        RAISE EXCEPTION 'inserted report version must become current'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION report.require_report_version_pointer() FROM PUBLIC;

CREATE CONSTRAINT TRIGGER tr_report_version_current_pointer
    AFTER INSERT ON report.report_version
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION report.require_report_version_pointer();

-- 상태 전이, 수정 가능 상태, header/version 포인터 변경을 DB에서도 검증한다.
CREATE FUNCTION report.enforce_anomaly_report_lifecycle()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.deleted_at IS NOT NULL THEN
        RAISE EXCEPTION 'soft-deleted report is immutable'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.report_id IS DISTINCT FROM OLD.report_id
        OR NEW.report_code IS DISTINCT FROM OLD.report_code
        OR NEW.report_type IS DISTINCT FROM OLD.report_type
        OR NEW.creator_type IS DISTINCT FROM OLD.creator_type
        OR NEW.created_by IS DISTINCT FROM OLD.created_by
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR NEW.filter_criteria IS DISTINCT FROM OLD.filter_criteria
        OR NEW.generation_method IS DISTINCT FROM OLD.generation_method THEN
        RAISE EXCEPTION 'immutable report header metadata cannot be changed'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.report_status IS DISTINCT FROM OLD.report_status
        AND NOT (
            (OLD.report_status = 'DRAFT' AND NEW.report_status = 'IN_REVIEW')
            OR (
                OLD.report_status = 'IN_REVIEW'
                AND NEW.report_status IN ('APPROVED', 'REJECTED')
            )
            OR (
                OLD.report_status = 'REJECTED'
                AND NEW.report_status = 'DRAFT'
            )
        ) THEN
        RAISE EXCEPTION 'unsupported report state transition'
            USING ERRCODE = '23514';
    END IF;

    IF OLD.report_status = 'REJECTED'
        AND NEW.report_status = 'DRAFT'
        AND NEW.current_version_no = OLD.current_version_no THEN
        RAISE EXCEPTION 'rejected report requires a new draft version'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.current_version_no IS DISTINCT FROM OLD.current_version_no THEN
        IF NEW.current_version_no <> OLD.current_version_no + 1
            OR OLD.report_status NOT IN ('DRAFT', 'REJECTED')
            OR NEW.report_status <> 'DRAFT' THEN
            RAISE EXCEPTION 'report version change is not allowed in this state'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.title IS DISTINCT FROM OLD.title
        OR NEW.summary IS DISTINCT FROM OLD.summary
        OR NEW.period_from IS DISTINCT FROM OLD.period_from
        OR NEW.period_to IS DISTINCT FROM OLD.period_to THEN
        RAISE EXCEPTION 'report content metadata requires a new version'
            USING ERRCODE = '23514';
    END IF;

    IF OLD.report_status = 'APPROVED' THEN
        IF NEW.report_status <> 'APPROVED'
            OR NEW.current_version_no <> OLD.current_version_no
            OR NEW.approved_version_no IS DISTINCT FROM OLD.approved_version_no
            OR NEW.approved_by IS DISTINCT FROM OLD.approved_by
            OR NEW.approved_at IS DISTINCT FROM OLD.approved_at
            OR NEW.deleted_at IS NOT NULL THEN
            RAISE EXCEPTION 'approved report is immutable'
                USING ERRCODE = '55000';
        END IF;

        IF OLD.approved_pdf_version_no IS NOT NULL
            AND (
                NEW.approved_pdf_version_no
                    IS DISTINCT FROM OLD.approved_pdf_version_no
                OR NEW.approved_pdf_storage_key
                    IS DISTINCT FROM OLD.approved_pdf_storage_key
                OR NEW.approved_pdf_sha256
                    IS DISTINCT FROM OLD.approved_pdf_sha256
                OR NEW.approved_pdf_created_at
                    IS DISTINCT FROM OLD.approved_pdf_created_at
            ) THEN
            RAISE EXCEPTION 'approved report PDF is immutable'
                USING ERRCODE = '55000';
        END IF;
    ELSIF NEW.approved_pdf_version_no IS NOT NULL THEN
        RAISE EXCEPTION 'PDF can only be bound to an approved report'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION report.enforce_anomaly_report_lifecycle() FROM PUBLIC;

CREATE TRIGGER tr_anomaly_report_lifecycle
    BEFORE UPDATE ON report.anomaly_report
    FOR EACH ROW EXECUTE FUNCTION report.enforce_anomaly_report_lifecycle();
