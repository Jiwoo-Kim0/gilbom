-- PostgreSQL 기능과 업무 schema는 Flyway가 소유한다.
-- PostGIS image가 먼저 설치한 확장도 IF NOT EXISTS로 검증해 일반 PostgreSQL 오기동을 방지한다.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE SCHEMA auth;
CREATE SCHEMA report;
CREATE SCHEMA audit;
CREATE SCHEMA session_store;

-- 로그인 계정은 물리 삭제하지 않고 is_active/deleted_at으로 수명주기를 관리한다.
CREATE TABLE auth.admin_user (
    user_id UUID DEFAULT gen_random_uuid() NOT NULL,
    user_code VARCHAR(30) NOT NULL,
    username VARCHAR(50) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    email VARCHAR(150) NOT NULL,
    department_name VARCHAR(100),
    job_title VARCHAR(100),
    office_name VARCHAR(100),
    is_active BOOLEAN DEFAULT TRUE NOT NULL,
    password_change_required BOOLEAN DEFAULT TRUE NOT NULL,
    password_changed_at TIMESTAMPTZ,
    last_login_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT pk_admin_user PRIMARY KEY (user_id),
    CONSTRAINT uq_admin_user_code UNIQUE (user_code),
    CONSTRAINT uq_admin_username UNIQUE (username),
    CONSTRAINT uq_admin_email UNIQUE (email),
    CONSTRAINT ck_admin_username_not_blank CHECK (username = BTRIM(username) AND username <> ''),
    CONSTRAINT ck_admin_password_hash_not_blank CHECK (BTRIM(password_hash) <> ''),
    CONSTRAINT ck_admin_display_name_not_blank CHECK (display_name = BTRIM(display_name) AND display_name <> ''),
    CONSTRAINT ck_admin_email_not_blank CHECK (email = BTRIM(email) AND email <> ''),
    CONSTRAINT ck_admin_soft_delete CHECK (deleted_at IS NULL OR is_active = FALSE)
);

COMMENT ON TABLE auth.admin_user IS '관리자 로그인 계정. 비밀번호 원문은 저장하지 않고 adaptive hash만 저장한다.';
COMMENT ON COLUMN auth.admin_user.user_id IS '내부 관리자 UUID';
COMMENT ON COLUMN auth.admin_user.user_code IS '외부 표시용 관리자 코드';
COMMENT ON COLUMN auth.admin_user.username IS '로그인 아이디';
COMMENT ON COLUMN auth.admin_user.password_hash IS '{id}encoded 형식의 adaptive password hash';
COMMENT ON COLUMN auth.admin_user.password_change_required IS '임시 비밀번호 발급 후 다음 로그인에서 변경을 강제하는 플래그';
COMMENT ON COLUMN auth.admin_user.deleted_at IS '계정 소프트 삭제 시각';

-- Role은 권한 묶음이며 시스템 기본 Role과 사용자 정의 Role을 같은 구조로 관리한다.
CREATE TABLE auth.role (
    role_id UUID DEFAULT gen_random_uuid() NOT NULL,
    role_code VARCHAR(50) NOT NULL,
    role_name VARCHAR(100) NOT NULL,
    description TEXT,
    is_system BOOLEAN DEFAULT FALSE NOT NULL,
    is_active BOOLEAN DEFAULT TRUE NOT NULL,
    display_order INTEGER DEFAULT 0 NOT NULL,
    created_by UUID,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT pk_role PRIMARY KEY (role_id),
    CONSTRAINT uq_role_code UNIQUE (role_code),
    CONSTRAINT ck_role_code CHECK (role_code ~ '^[A-Z][A-Z0-9_]{1,49}$'),
    CONSTRAINT ck_role_name_not_blank CHECK (role_name = BTRIM(role_name) AND role_name <> ''),
    CONSTRAINT ck_role_display_order CHECK (display_order >= 0),
    CONSTRAINT fk_role_created_by
        FOREIGN KEY (created_by) REFERENCES auth.admin_user (user_id) ON DELETE SET NULL
);

COMMENT ON TABLE auth.role IS 'ADMIN, MANAGER, OPERATOR 및 사용자 정의 역할';
COMMENT ON COLUMN auth.role.is_system IS '삭제·코드 변경이 금지되는 시스템 기본 역할 여부';
COMMENT ON COLUMN auth.role.created_by IS '사용자 정의 역할을 생성한 관리자';

-- 한 관리자가 여러 Role을 가질 수 있으며 활성 대표 Role은 최대 하나만 허용한다.
CREATE TABLE auth.user_role (
    user_id UUID NOT NULL,
    role_id UUID NOT NULL,
    is_primary BOOLEAN DEFAULT FALSE NOT NULL,
    is_active BOOLEAN DEFAULT TRUE NOT NULL,
    assigned_by UUID,
    assigned_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    ended_at TIMESTAMPTZ,
    CONSTRAINT pk_user_role PRIMARY KEY (user_id, role_id),
    CONSTRAINT ck_user_role_lifecycle CHECK (
        (is_active = TRUE AND ended_at IS NULL)
        OR (is_active = FALSE AND ended_at IS NOT NULL AND ended_at >= assigned_at)
    ),
    CONSTRAINT ck_user_role_primary_active CHECK (is_primary = FALSE OR is_active = TRUE),
    CONSTRAINT fk_user_role_user
        FOREIGN KEY (user_id) REFERENCES auth.admin_user (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_user_role_role
        FOREIGN KEY (role_id) REFERENCES auth.role (role_id) ON DELETE RESTRICT,
    CONSTRAINT fk_user_role_assigned_by
        FOREIGN KEY (assigned_by) REFERENCES auth.admin_user (user_id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX uq_user_role_active_primary
    ON auth.user_role (user_id)
    WHERE is_active = TRUE AND is_primary = TRUE;
CREATE INDEX ix_user_role_active_role
    ON auth.user_role (role_id, user_id)
    WHERE is_active = TRUE;

COMMENT ON TABLE auth.user_role IS '관리자와 역할의 N:M 관계 및 현재 대표 역할';
COMMENT ON COLUMN auth.user_role.assigned_by IS '역할을 부여한 관리자';
COMMENT ON COLUMN auth.user_role.ended_at IS '역할 해제 시각. 활성 관계에서는 NULL';

-- TOTP secret은 검증 시 복호화가 필요하므로 hash가 아니라 AES-GCM 암호문 구조로 저장한다.
CREATE TABLE auth.mfa_factor (
    factor_id UUID DEFAULT gen_random_uuid() NOT NULL,
    user_id UUID NOT NULL,
    factor_type VARCHAR(20) DEFAULT 'TOTP' NOT NULL,
    factor_status VARCHAR(20) DEFAULT 'PENDING' NOT NULL,
    secret_ciphertext BYTEA NOT NULL,
    secret_nonce BYTEA NOT NULL,
    secret_key_version INTEGER NOT NULL,
    encryption_algorithm VARCHAR(30) DEFAULT 'AES-256-GCM' NOT NULL,
    last_successful_totp_time_step BIGINT,
    activated_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT pk_mfa_factor PRIMARY KEY (factor_id),
    CONSTRAINT ck_mfa_factor_type CHECK (factor_type = 'TOTP'),
    CONSTRAINT ck_mfa_factor_status CHECK (factor_status IN ('PENDING', 'ACTIVE', 'REVOKED')),
    CONSTRAINT ck_mfa_factor_ciphertext CHECK (OCTET_LENGTH(secret_ciphertext) >= 17),
    CONSTRAINT ck_mfa_factor_nonce CHECK (OCTET_LENGTH(secret_nonce) = 12),
    CONSTRAINT ck_mfa_factor_key_version CHECK (secret_key_version > 0),
    CONSTRAINT ck_mfa_factor_algorithm CHECK (encryption_algorithm = 'AES-256-GCM'),
    CONSTRAINT ck_mfa_factor_time_step CHECK (
        last_successful_totp_time_step IS NULL OR last_successful_totp_time_step >= 0
    ),
    CONSTRAINT ck_mfa_factor_lifecycle CHECK (
        (factor_status = 'PENDING' AND activated_at IS NULL AND revoked_at IS NULL)
        OR (factor_status = 'ACTIVE' AND activated_at IS NOT NULL AND revoked_at IS NULL)
        OR (factor_status = 'REVOKED' AND revoked_at IS NOT NULL)
    ),
    CONSTRAINT fk_mfa_factor_user
        FOREIGN KEY (user_id) REFERENCES auth.admin_user (user_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_mfa_factor_current_totp
    ON auth.mfa_factor (user_id, factor_type)
    WHERE factor_status IN ('PENDING', 'ACTIVE');
CREATE INDEX ix_mfa_factor_active_user
    ON auth.mfa_factor (user_id)
    WHERE factor_status = 'ACTIVE';

COMMENT ON TABLE auth.mfa_factor IS '관리자별 TOTP factor와 암호화 envelope metadata';
COMMENT ON COLUMN auth.mfa_factor.secret_ciphertext IS '인증된 암호화 결과. GCM authentication tag를 포함한다.';
COMMENT ON COLUMN auth.mfa_factor.secret_nonce IS 'AES-GCM 96-bit nonce. 같은 key에서 재사용하지 않는다.';
COMMENT ON COLUMN auth.mfa_factor.secret_key_version IS '외부 KMS·master key rotation을 위한 key version';
COMMENT ON COLUMN auth.mfa_factor.last_successful_totp_time_step IS '동일 TOTP time-step 재사용을 차단하는 마지막 성공 step';

-- 복구 코드는 최초 발급 응답에서만 원문을 보여주고 DB에는 adaptive hash만 남긴다.
CREATE TABLE auth.mfa_recovery_code (
    recovery_code_id UUID DEFAULT gen_random_uuid() NOT NULL,
    factor_id UUID NOT NULL,
    code_position SMALLINT NOT NULL,
    code_hash VARCHAR(255) NOT NULL,
    used_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT pk_mfa_recovery_code PRIMARY KEY (recovery_code_id),
    CONSTRAINT uq_mfa_recovery_code_position UNIQUE (factor_id, code_position),
    CONSTRAINT ck_mfa_recovery_code_position CHECK (code_position BETWEEN 1 AND 10),
    CONSTRAINT ck_mfa_recovery_code_hash CHECK (BTRIM(code_hash) <> ''),
    CONSTRAINT ck_mfa_recovery_code_expiry CHECK (expires_at IS NULL OR expires_at > created_at),
    CONSTRAINT ck_mfa_recovery_code_usage CHECK (used_at IS NULL OR used_at >= created_at),
    CONSTRAINT fk_mfa_recovery_code_factor
        FOREIGN KEY (factor_id) REFERENCES auth.mfa_factor (factor_id) ON DELETE CASCADE
);

CREATE INDEX ix_mfa_recovery_code_available
    ON auth.mfa_recovery_code (factor_id, code_position)
    WHERE used_at IS NULL;

COMMENT ON TABLE auth.mfa_recovery_code IS 'TOTP 분실 시 한 번만 사용할 수 있는 복구 코드 hash';
COMMENT ON COLUMN auth.mfa_recovery_code.code_position IS '사용자에게 발급한 10개 코드의 순번';
COMMENT ON COLUMN auth.mfa_recovery_code.code_hash IS '원문 복구 코드가 아닌 adaptive hash';
COMMENT ON COLUMN auth.mfa_recovery_code.used_at IS '복구 코드가 원자적으로 소비된 시각';

-- 시스템 Role은 환경마다 같은 UUID를 사용해 seed와 테스트를 결정적으로 만든다.
INSERT INTO auth.role (
    role_id, role_code, role_name, description, is_system, display_order
) VALUES
    ('00000000-0000-4000-8000-000000000001', 'ADMIN', '관리자', '전체 시스템 관리 역할', TRUE, 10),
    ('00000000-0000-4000-8000-000000000002', 'MANAGER', '매니저', '운영 관리 및 승인 역할', TRUE, 20),
    ('00000000-0000-4000-8000-000000000003', 'OPERATOR', '운영자', '일상 순찰 운영 역할', TRUE, 30);

-- 인증 이력은 상태 컬럼으로 폐기하므로 runtime 계정에 물리 DELETE 권한을 주지 않는다.
GRANT USAGE ON SCHEMA auth TO "${applicationRole}";
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA auth TO "${applicationRole}";

ALTER DEFAULT PRIVILEGES IN SCHEMA auth
    GRANT SELECT, INSERT, UPDATE ON TABLES TO "${applicationRole}";
