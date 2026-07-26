-- 권한 코드는 API가 역할명에 직접 결합되지 않도록 별도 catalog로 관리한다.
-- 시스템 seed는 환경마다 같은 UUID를 사용해 감사 이력과 테스트를 결정적으로 만든다.
CREATE TABLE auth.permission (
    permission_id UUID NOT NULL,
    permission_code VARCHAR(80) NOT NULL,
    permission_name VARCHAR(120) NOT NULL,
    description VARCHAR(500) NOT NULL,
    resource_type VARCHAR(40) NOT NULL,
    is_system BOOLEAN DEFAULT TRUE NOT NULL,
    is_active BOOLEAN DEFAULT TRUE NOT NULL,
    display_order INTEGER DEFAULT 0 NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT pk_permission PRIMARY KEY (permission_id),
    CONSTRAINT uq_permission_code UNIQUE (permission_code),
    CONSTRAINT ck_permission_code CHECK (permission_code ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    CONSTRAINT ck_permission_name CHECK (
        permission_name = BTRIM(permission_name) AND permission_name <> ''
    ),
    CONSTRAINT ck_permission_description CHECK (BTRIM(description) <> ''),
    CONSTRAINT ck_permission_resource_type CHECK (
        resource_type ~ '^[A-Z][A-Z0-9_]{1,39}$'
    ),
    CONSTRAINT ck_permission_display_order CHECK (display_order >= 0),
    CONSTRAINT ck_permission_timestamps CHECK (updated_at >= created_at)
);

COMMENT ON TABLE auth.permission IS 'API와 서비스가 검사하는 세부 권한 code catalog';
COMMENT ON COLUMN auth.permission.permission_code IS 'Spring Security authority로 사용하는 불변 code';
COMMENT ON COLUMN auth.permission.is_system IS '애플리케이션이 삭제하거나 code를 변경할 수 없는 권한 여부';

-- 권한 해제도 이력을 남길 수 있도록 물리 삭제 대신 lifecycle column을 사용한다.
CREATE TABLE auth.role_permission (
    role_id UUID NOT NULL,
    permission_id UUID NOT NULL,
    is_active BOOLEAN DEFAULT TRUE NOT NULL,
    granted_by UUID,
    granted_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT pk_role_permission PRIMARY KEY (role_id, permission_id),
    CONSTRAINT ck_role_permission_lifecycle CHECK (
        (is_active = TRUE AND revoked_at IS NULL)
        OR (is_active = FALSE AND revoked_at IS NOT NULL AND revoked_at >= granted_at)
    ),
    CONSTRAINT fk_role_permission_role
        FOREIGN KEY (role_id) REFERENCES auth.role (role_id) ON DELETE RESTRICT,
    CONSTRAINT fk_role_permission_permission
        FOREIGN KEY (permission_id) REFERENCES auth.permission (permission_id) ON DELETE RESTRICT,
    CONSTRAINT fk_role_permission_granted_by
        FOREIGN KEY (granted_by) REFERENCES auth.admin_user (user_id) ON DELETE SET NULL
);

CREATE INDEX ix_role_permission_active_permission
    ON auth.role_permission (permission_id, role_id)
    WHERE is_active = TRUE;

COMMENT ON TABLE auth.role_permission IS 'Role에 부여된 세부 permission과 해제 이력';
COMMENT ON COLUMN auth.role_permission.granted_by IS '사용자 정의 Role 권한을 변경한 관리자. system seed는 NULL';

-- ADMIN_SETTINGS_MANAGE는 시스템 ADMIN 외 다른 역할에 우회 부여할 수 없다.
CREATE FUNCTION auth.enforce_restricted_permission_assignment()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    target_role_code VARCHAR(50);
    target_role_is_system BOOLEAN;
    target_permission_code VARCHAR(80);
BEGIN
    SELECT role_code, is_system
      INTO target_role_code, target_role_is_system
      FROM auth.role
     WHERE role_id = NEW.role_id;

    SELECT permission_code
      INTO target_permission_code
      FROM auth.permission
     WHERE permission_id = NEW.permission_id;

    IF NEW.is_active
       AND target_permission_code = 'ADMIN_SETTINGS_MANAGE'
       AND (target_role_code <> 'ADMIN' OR target_role_is_system = FALSE) THEN
        RAISE EXCEPTION 'restricted permission assignment'
            USING ERRCODE = '42501';
    END IF;

    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION auth.enforce_restricted_permission_assignment() FROM PUBLIC;

CREATE TRIGGER tr_role_permission_restricted_assignment
    BEFORE INSERT OR UPDATE ON auth.role_permission
    FOR EACH ROW EXECUTE FUNCTION auth.enforce_restricted_permission_assignment();

INSERT INTO auth.permission (
    permission_id,
    permission_code,
    permission_name,
    description,
    resource_type,
    is_system,
    display_order
) VALUES
    ('00000000-0000-4100-8000-000000000001', 'ROBOT_STATUS_VIEW', '로봇 상태 조회', '로봇, 텔레메트리, 이벤트를 조회한다.', 'ROBOT', TRUE, 10),
    ('00000000-0000-4100-8000-000000000002', 'ROBOT_REGISTER', '로봇 등록 관리', '로봇과 기기 인증정보를 관리한다.', 'ROBOT', TRUE, 20),
    ('00000000-0000-4100-8000-000000000003', 'ROBOT_COMMAND_VIEW', '로봇 명령 조회', '로봇 명령과 실행 결과를 조회한다.', 'ROBOT_COMMAND', TRUE, 30),
    ('00000000-0000-4100-8000-000000000004', 'ROBOT_COMMAND_EXECUTE', '로봇 명령 실행', '임무와 긴급정지 명령을 발행한다.', 'ROBOT_COMMAND', TRUE, 40),
    ('00000000-0000-4100-8000-000000000005', 'ROBOT_EVENT_ACKNOWLEDGE', '로봇 경고 확인', '로봇 경고 이벤트를 확인 처리한다.', 'ROBOT_EVENT', TRUE, 50),
    ('00000000-0000-4100-8000-000000000006', 'MISSION_VIEW', '임무 조회', '구역, 경로, 임무를 조회한다.', 'MISSION', TRUE, 60),
    ('00000000-0000-4100-8000-000000000007', 'MISSION_MANAGE', '임무 관리', '구역, 경로, 임무를 생성하거나 수정한다.', 'MISSION', TRUE, 70),
    ('00000000-0000-4100-8000-000000000008', 'DEFECT_STATUS_VIEW', '결함 조회', '결함과 근거 이미지를 조회한다.', 'DEFECT', TRUE, 80),
    ('00000000-0000-4100-8000-000000000009', 'DEFECT_STATUS_UPDATE', '결함 상태 변경', '결함 정보와 처리 상태를 변경한다.', 'DEFECT', TRUE, 90),
    ('00000000-0000-4100-8000-000000000010', 'DEFECT_MERGE', '결함 병합', '중복 결함을 병합하거나 취소한다.', 'DEFECT', TRUE, 100),
    ('00000000-0000-4100-8000-000000000011', 'DEFECT_EXPORT', '결함 내보내기', '접근 가능한 결함 데이터를 내보낸다.', 'DEFECT', TRUE, 110),
    ('00000000-0000-4100-8000-000000000012', 'REPORT_VIEW', '보고서 조회', '보고서와 수정 이력을 조회하고 다운로드한다.', 'REPORT', TRUE, 120),
    ('00000000-0000-4100-8000-000000000013', 'REPORT_CREATE', '보고서 생성', '수동 또는 자동 보고서를 생성한다.', 'REPORT', TRUE, 130),
    ('00000000-0000-4100-8000-000000000014', 'REPORT_UPDATE', '보고서 수정', '보고서를 수정하고 검토를 요청한다.', 'REPORT', TRUE, 140),
    ('00000000-0000-4100-8000-000000000015', 'REPORT_APPROVE', '보고서 승인', '보고서를 승인, 반려하고 일정을 관리한다.', 'REPORT', TRUE, 150),
    ('00000000-0000-4100-8000-000000000016', 'AUDIT_LOG_VIEW', '감사 로그 조회', '변경 불가능한 감사 로그를 조회한다.', 'AUDIT', TRUE, 160),
    ('00000000-0000-4100-8000-000000000017', 'ADMIN_SETTINGS_MANAGE', '관리자 설정 관리', '사용자, 역할, 권한과 운영 설정을 관리한다.', 'ADMIN', TRUE, 170),
    ('00000000-0000-4100-8000-000000000018', 'SYSTEM_LOG_VIEW', '시스템 로그 조회', 'MQTT 수신 상태와 기술 로그를 조회한다.', 'SYSTEM', TRUE, 180);

-- 역할별 매트릭스는 docs/authorization-matrix.md와 같은 권한 code를 사용한다.
INSERT INTO auth.role_permission (role_id, permission_id)
SELECT role.role_id, permission.permission_id
FROM (
    VALUES
        ('ADMIN', 'ROBOT_STATUS_VIEW'),
        ('ADMIN', 'ROBOT_REGISTER'),
        ('ADMIN', 'ROBOT_COMMAND_VIEW'),
        ('ADMIN', 'ROBOT_COMMAND_EXECUTE'),
        ('ADMIN', 'ROBOT_EVENT_ACKNOWLEDGE'),
        ('ADMIN', 'MISSION_VIEW'),
        ('ADMIN', 'MISSION_MANAGE'),
        ('ADMIN', 'DEFECT_STATUS_VIEW'),
        ('ADMIN', 'DEFECT_STATUS_UPDATE'),
        ('ADMIN', 'DEFECT_MERGE'),
        ('ADMIN', 'DEFECT_EXPORT'),
        ('ADMIN', 'REPORT_VIEW'),
        ('ADMIN', 'REPORT_CREATE'),
        ('ADMIN', 'REPORT_UPDATE'),
        ('ADMIN', 'REPORT_APPROVE'),
        ('ADMIN', 'AUDIT_LOG_VIEW'),
        ('ADMIN', 'ADMIN_SETTINGS_MANAGE'),
        ('ADMIN', 'SYSTEM_LOG_VIEW'),
        ('MANAGER', 'ROBOT_STATUS_VIEW'),
        ('MANAGER', 'ROBOT_COMMAND_VIEW'),
        ('MANAGER', 'ROBOT_COMMAND_EXECUTE'),
        ('MANAGER', 'ROBOT_EVENT_ACKNOWLEDGE'),
        ('MANAGER', 'MISSION_VIEW'),
        ('MANAGER', 'MISSION_MANAGE'),
        ('MANAGER', 'DEFECT_STATUS_VIEW'),
        ('MANAGER', 'DEFECT_STATUS_UPDATE'),
        ('MANAGER', 'DEFECT_MERGE'),
        ('MANAGER', 'DEFECT_EXPORT'),
        ('MANAGER', 'REPORT_VIEW'),
        ('MANAGER', 'REPORT_CREATE'),
        ('MANAGER', 'REPORT_UPDATE'),
        ('MANAGER', 'REPORT_APPROVE'),
        ('MANAGER', 'AUDIT_LOG_VIEW'),
        ('OPERATOR', 'ROBOT_STATUS_VIEW'),
        ('OPERATOR', 'ROBOT_COMMAND_VIEW'),
        ('OPERATOR', 'ROBOT_COMMAND_EXECUTE'),
        ('OPERATOR', 'ROBOT_EVENT_ACKNOWLEDGE'),
        ('OPERATOR', 'MISSION_VIEW'),
        ('OPERATOR', 'DEFECT_STATUS_VIEW'),
        ('OPERATOR', 'DEFECT_STATUS_UPDATE'),
        ('OPERATOR', 'REPORT_VIEW'),
        ('OPERATOR', 'REPORT_CREATE'),
        ('OPERATOR', 'REPORT_UPDATE')
) AS role_permission_seed(role_code, permission_code)
JOIN auth.role role
  ON role.role_code = role_permission_seed.role_code
JOIN auth.permission permission
  ON permission.permission_code = role_permission_seed.permission_code;

-- 사용자명과 IP 원문은 저장하지 않고 외부 pepper로 만든 HMAC-SHA-256만 key로 사용한다.
-- 계정 실패 제한과 IP 요청 제한을 한 구조에서 원자적으로 upsert할 수 있다.
CREATE TABLE auth.authentication_rate_limit (
    rate_limit_id UUID DEFAULT gen_random_uuid() NOT NULL,
    limit_type VARCHAR(30) NOT NULL,
    subject_key_hash BYTEA NOT NULL,
    hash_key_version INTEGER NOT NULL,
    window_started_at TIMESTAMPTZ NOT NULL,
    last_attempt_at TIMESTAMPTZ NOT NULL,
    request_count INTEGER DEFAULT 0 NOT NULL,
    failure_count INTEGER DEFAULT 0 NOT NULL,
    blocked_until TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT pk_authentication_rate_limit PRIMARY KEY (rate_limit_id),
    CONSTRAINT uq_authentication_rate_limit_subject
        UNIQUE (limit_type, hash_key_version, subject_key_hash),
    CONSTRAINT ck_authentication_rate_limit_type CHECK (
        limit_type IN ('ACCOUNT_PASSWORD_FAILURE', 'IP_AUTHENTICATION_REQUEST')
    ),
    CONSTRAINT ck_authentication_rate_limit_hash CHECK (
        OCTET_LENGTH(subject_key_hash) = 32 AND hash_key_version > 0
    ),
    CONSTRAINT ck_authentication_rate_limit_counts CHECK (
        request_count >= 0 AND failure_count >= 0 AND failure_count <= request_count
    ),
    CONSTRAINT ck_authentication_rate_limit_window CHECK (
        last_attempt_at >= window_started_at
        AND expires_at > window_started_at
        AND (blocked_until IS NULL OR blocked_until >= last_attempt_at)
    ),
    CONSTRAINT ck_authentication_rate_limit_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX ix_authentication_rate_limit_cleanup
    ON auth.authentication_rate_limit (expires_at);
CREATE INDEX ix_authentication_rate_limit_blocked
    ON auth.authentication_rate_limit (blocked_until)
    WHERE blocked_until IS NOT NULL;

COMMENT ON TABLE auth.authentication_rate_limit IS '계정별 비밀번호 실패와 IP별 인증 요청 제한 bucket';
COMMENT ON COLUMN auth.authentication_rate_limit.subject_key_hash IS '사용자명 또는 IP 원문 대신 외부 pepper로 만든 HMAC-SHA-256';
COMMENT ON COLUMN auth.authentication_rate_limit.blocked_until IS '제한 응답의 Retry-After 계산 기준 시각';

-- 멱등 key 원문과 요청 body는 저장하지 않고 digest로 동일 요청 여부를 판별한다.
CREATE SCHEMA request_control;

CREATE TABLE request_control.idempotency_request (
    idempotency_request_id UUID DEFAULT gen_random_uuid() NOT NULL,
    principal_type VARCHAR(20) NOT NULL,
    principal_key_hash BYTEA NOT NULL,
    idempotency_key_hash BYTEA NOT NULL,
    request_fingerprint BYTEA NOT NULL,
    request_method VARCHAR(10) NOT NULL,
    request_path VARCHAR(500) NOT NULL,
    processing_status VARCHAR(20) DEFAULT 'PROCESSING' NOT NULL,
    response_status SMALLINT,
    response_headers JSONB,
    response_body JSONB,
    lease_expires_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT pk_idempotency_request PRIMARY KEY (idempotency_request_id),
    CONSTRAINT uq_idempotency_request_key
        UNIQUE (principal_key_hash, idempotency_key_hash),
    CONSTRAINT ck_idempotency_principal_type CHECK (
        principal_type IN ('USER', 'ROBOT', 'SYSTEM')
    ),
    CONSTRAINT ck_idempotency_hashes CHECK (
        OCTET_LENGTH(principal_key_hash) = 32
        AND OCTET_LENGTH(idempotency_key_hash) = 32
        AND OCTET_LENGTH(request_fingerprint) = 32
    ),
    CONSTRAINT ck_idempotency_method CHECK (
        request_method IN ('POST', 'PUT', 'PATCH', 'DELETE')
    ),
    CONSTRAINT ck_idempotency_path CHECK (
        request_path LIKE '/%' AND request_path = BTRIM(request_path)
    ),
    CONSTRAINT ck_idempotency_response_headers CHECK (
        response_headers IS NULL OR JSONB_TYPEOF(response_headers) = 'object'
    ),
    CONSTRAINT ck_idempotency_status CHECK (
        (
            processing_status = 'PROCESSING'
            AND response_status IS NULL
            AND response_body IS NULL
        )
        OR
        (
            processing_status IN ('SUCCEEDED', 'FAILED')
            AND response_status BETWEEN 100 AND 599
            AND response_body IS NOT NULL
        )
    ),
    CONSTRAINT ck_idempotency_expiry CHECK (
        lease_expires_at > created_at AND expires_at >= lease_expires_at
    ),
    CONSTRAINT ck_idempotency_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX ix_idempotency_request_cleanup
    ON request_control.idempotency_request (expires_at);
CREATE INDEX ix_idempotency_request_processing_lease
    ON request_control.idempotency_request (lease_expires_at)
    WHERE processing_status = 'PROCESSING';

COMMENT ON TABLE request_control.idempotency_request IS '상태 변경 API의 동시 실행 방지와 완료 응답 replay 저장소';
COMMENT ON COLUMN request_control.idempotency_request.idempotency_key_hash IS 'Idempotency-Key 원문 대신 저장하는 SHA-256';
COMMENT ON COLUMN request_control.idempotency_request.request_fingerprint IS 'method, path, canonical body를 묶은 SHA-256';
COMMENT ON COLUMN request_control.idempotency_request.response_headers IS '재전송이 필요한 안전한 allowlist header만 저장하는 JSON object';
COMMENT ON COLUMN request_control.idempotency_request.response_body IS '동일 요청 replay용 JSON 응답. Secret 응답에는 멱등 저장소를 사용하지 않는다.';
COMMENT ON COLUMN request_control.idempotency_request.lease_expires_at IS 'PROCESSING 소유권 장애 복구를 위한 lease 만료 시각';

-- permission catalog는 runtime에서 수정하지 않고, 역할 관계는 lifecycle UPDATE로 관리한다.
REVOKE INSERT, UPDATE, DELETE ON auth.permission FROM "${applicationRole}";
GRANT SELECT ON auth.permission TO "${applicationRole}";
GRANT SELECT, INSERT, UPDATE ON auth.role_permission TO "${applicationRole}";
GRANT SELECT, INSERT, UPDATE, DELETE ON auth.authentication_rate_limit
    TO "${applicationRole}";

GRANT USAGE ON SCHEMA request_control TO "${applicationRole}";
GRANT SELECT, INSERT, UPDATE, DELETE ON request_control.idempotency_request
    TO "${applicationRole}";

ALTER DEFAULT PRIVILEGES IN SCHEMA request_control
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "${applicationRole}";
