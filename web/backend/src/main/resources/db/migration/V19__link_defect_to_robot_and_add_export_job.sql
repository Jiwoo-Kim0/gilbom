-- 결함 REST API가 계약대로 동작하려면 결함을 로봇까지 추적할 수 있어야 한다.
--
-- 1) authorization-matrix.md 11장은 결함 API의 객체 범위를 "배정 로봇 파생 결함"
--    으로 정의한다. V12의 defect 스키마에는 로봇을 가리키는 칼럼이 전혀 없어
--    auth.user_robot_assignment 범위를 SQL에서 적용할 수 없었다.
-- 2) Swagger 21.10의 목록·지도 필터에는 robotId가 있으나 조인 대상이 없었다.
--
-- 관측(defect_observation)이 "어떤 로봇이 보냈는가"를 가지는 사실 단위이므로
-- robot_id는 결함이 아니라 관측에 둔다. 결함 하나가 여러 로봇의 관측을 병합할 수
-- 있고, 그 경우에도 관측 단위로 로봇을 추적할 수 있다.
--
-- telemetry.robot_event 테이블이 아직 없어 event_id로는 로봇을 역추적할 수 없다.
-- 그 테이블이 추가되면 robot_id는 event로부터 파생되는 중복 칼럼이 되지만,
-- 범위 판정 질의가 매번 telemetry를 조인하지 않도록 관측에 그대로 유지한다.
ALTER TABLE defect.defect_observation
    ADD COLUMN robot_id UUID NOT NULL;

ALTER TABLE defect.defect_observation
    ADD CONSTRAINT fk_observation_robot
        FOREIGN KEY (robot_id) REFERENCES robot.robot (robot_id)
        ON DELETE RESTRICT;

-- 범위 판정과 robotId 필터가 결함별 관측을 로봇으로 좁혀 읽는다.
CREATE INDEX ix_observation_robot_created
    ON defect.defect_observation (robot_id, created_at DESC);

COMMENT ON COLUMN defect.defect_observation.robot_id IS
    '관측을 발행한 로봇. 결함의 객체 범위(auth.user_robot_assignment)와 robotId 필터 판정 기준';

-- Swagger 21.10의 POST /api/v1/defects/export는 202로 "비동기 작업 접수"를
-- 응답한다. 접수 사실이 어디에도 남지 않으면 202를 반환할 근거가 없으므로 요청을
-- 저장한다. 실제 파일 생성 worker는 이 migration 범위가 아니며, job_status는
-- QUEUED로만 기록된다.
CREATE TABLE defect.defect_export_job (
    export_job_id UUID DEFAULT gen_random_uuid() NOT NULL,
    requested_by UUID NOT NULL,
    export_format VARCHAR(10) NOT NULL,
    job_status VARCHAR(20) DEFAULT 'QUEUED' NOT NULL,
    filter_criteria JSONB NOT NULL,
    row_count INTEGER,
    file_object_key VARCHAR(500),
    failure_reason VARCHAR(1000),
    requested_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CONSTRAINT pk_defect_export_job PRIMARY KEY (export_job_id),
    CONSTRAINT ck_defect_export_format CHECK (export_format IN ('CSV', 'XLSX')),
    CONSTRAINT ck_defect_export_status CHECK (job_status IN (
        'QUEUED', 'RUNNING', 'COMPLETED', 'FAILED'
    )),
    CONSTRAINT ck_defect_export_filter CHECK (JSONB_TYPEOF(filter_criteria) = 'object'),
    CONSTRAINT ck_defect_export_row_count CHECK (row_count IS NULL OR row_count >= 0),
    -- 완료·실패는 종료 시각을 남기고, 접수 상태는 종료 시각을 가질 수 없다.
    CONSTRAINT ck_defect_export_lifecycle CHECK (
        (job_status IN ('QUEUED', 'RUNNING') AND completed_at IS NULL)
        OR (job_status IN ('COMPLETED', 'FAILED')
            AND completed_at IS NOT NULL
            AND completed_at >= requested_at)
    ),
    CONSTRAINT fk_defect_export_requested_by
        FOREIGN KEY (requested_by) REFERENCES auth.admin_user (user_id)
        ON DELETE RESTRICT
);

CREATE INDEX ix_defect_export_job_requested
    ON defect.defect_export_job (requested_by, requested_at DESC);

COMMENT ON TABLE defect.defect_export_job IS
    'POST /api/v1/defects/export 접수 기록. 202 응답의 근거이며 실제 생성은 후속 worker가 담당한다.';
COMMENT ON COLUMN defect.defect_export_job.filter_criteria IS
    '접수 시점의 결함 필터 조건 snapshot. 재현 가능한 내보내기를 위해 그대로 보관한다.';

-- worker가 job_status와 결과 칼럼을 갱신하므로 UPDATE까지 부여한다.
GRANT SELECT, INSERT, UPDATE ON defect.defect_export_job TO "${applicationRole}";
