-- 길봄 DB DDL v1.0
-- 기준: MQTT 통신 API 명세서 v1.0 / PostgreSQL 16 + PostGIS
-- 모든 시각은 UTC TIMESTAMPTZ로 저장한다.

BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE SCHEMA IF NOT EXISTS storage;
CREATE SCHEMA IF NOT EXISTS auth;
CREATE SCHEMA IF NOT EXISTS robot;
CREATE SCHEMA IF NOT EXISTS telemetry;
CREATE SCHEMA IF NOT EXISTS defect;
CREATE SCHEMA IF NOT EXISTS report;
CREATE SCHEMA IF NOT EXISTS audit;
CREATE SCHEMA IF NOT EXISTS system;
CREATE SCHEMA IF NOT EXISTS integration;

-- storage.file_object: Private S3에 저장되는 이미지·프로필·보고서 PDF의 공통 메타데이터를 관리한다. DB에는 만료 URL이 아닌 object_key를 저장한다.
CREATE TABLE storage.file_object (
    file_id UUID DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    bucket_name VARCHAR(100) NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    file_type VARCHAR(30) NOT NULL,
    original_file_name VARCHAR(255),
    mime_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT,
    sha256 VARCHAR(64),
    anonymized BOOLEAN DEFAULT FALSE NOT NULL,
    upload_status VARCHAR(20) DEFAULT 'PENDING' NOT NULL,
    uploaded_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT uq_file_object_key UNIQUE (object_key),
    CONSTRAINT ck_file_type CHECK (file_type IN ('ROBOT_INCIDENT_IMAGE','PROFILE_IMAGE','REPORT_PDF','OTHER')),
    CONSTRAINT ck_file_upload_status CHECK (upload_status IN ('PENDING','UPLOADED','FAILED','DELETED')),
    CONSTRAINT ck_file_size CHECK (size_bytes IS NULL OR size_bytes >= 0),
    CONSTRAINT ck_file_sha256 CHECK (sha256 IS NULL OR sha256 ~ '^[0-9A-Fa-f]{64}$')
);
COMMENT ON TABLE storage.file_object IS 'Private S3에 저장되는 이미지·프로필·보고서 PDF의 공통 메타데이터를 관리한다. DB에는 만료 URL이 아닌 object_key를 저장한다.';
COMMENT ON COLUMN storage.file_object.file_id IS '파일 내부 식별자';
COMMENT ON COLUMN storage.file_object.bucket_name IS 'S3 버킷명';
COMMENT ON COLUMN storage.file_object.object_key IS '버킷 내부 객체 키';
COMMENT ON COLUMN storage.file_object.file_type IS 'ROBOT_INCIDENT_IMAGE, PROFILE_IMAGE, REPORT_PDF, OTHER';
COMMENT ON COLUMN storage.file_object.original_file_name IS '원본 파일명';
COMMENT ON COLUMN storage.file_object.mime_type IS 'MIME 유형';
COMMENT ON COLUMN storage.file_object.size_bytes IS '파일 크기(Byte)';
COMMENT ON COLUMN storage.file_object.sha256 IS 'SHA-256 무결성 해시';
COMMENT ON COLUMN storage.file_object.anonymized IS '비식별화 완료 여부';
COMMENT ON COLUMN storage.file_object.upload_status IS 'PENDING, UPLOADED, FAILED, DELETED';
COMMENT ON COLUMN storage.file_object.uploaded_at IS '업로드 완료 시각';
COMMENT ON COLUMN storage.file_object.deleted_at IS '논리 삭제 시각';
COMMENT ON COLUMN storage.file_object.created_at IS '레코드 생성 시각';

-- auth.admin_user: 로그인 계정과 마이페이지 기본정보를 저장한다. 계정 삭제는 물리 삭제가 아닌 소프트 삭제로 처리한다.
CREATE TABLE auth.admin_user (
    user_id UUID DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    user_code VARCHAR(30) NOT NULL,
    username VARCHAR(50) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    email VARCHAR(150) NOT NULL,
    profile_file_id UUID,
    department_name VARCHAR(100),
    job_title VARCHAR(100),
    office_name VARCHAR(100),
    is_active BOOLEAN DEFAULT TRUE NOT NULL,
    password_changed_at TIMESTAMPTZ,
    last_login_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT uq_admin_user_code UNIQUE (user_code),
    CONSTRAINT uq_admin_username UNIQUE (username),
    CONSTRAINT uq_admin_email UNIQUE (email)
);
COMMENT ON TABLE auth.admin_user IS '로그인 계정과 마이페이지 기본정보를 저장한다. 계정 삭제는 물리 삭제가 아닌 소프트 삭제로 처리한다.';
COMMENT ON COLUMN auth.admin_user.user_id IS '내부 사용자 식별자';
COMMENT ON COLUMN auth.admin_user.user_code IS '외부·MQTT 요청자 코드. 예: ADM-0042';
COMMENT ON COLUMN auth.admin_user.username IS '로그인 아이디';
COMMENT ON COLUMN auth.admin_user.password_hash IS '단방향 비밀번호 해시';
COMMENT ON COLUMN auth.admin_user.display_name IS '관리자 표시 이름';
COMMENT ON COLUMN auth.admin_user.email IS '이메일';
COMMENT ON COLUMN auth.admin_user.profile_file_id IS '프로필 이미지 파일';
COMMENT ON COLUMN auth.admin_user.department_name IS '부서명';
COMMENT ON COLUMN auth.admin_user.job_title IS '직급·직책';
COMMENT ON COLUMN auth.admin_user.office_name IS '근무 조직·지점';
COMMENT ON COLUMN auth.admin_user.is_active IS '로그인 가능 여부';
COMMENT ON COLUMN auth.admin_user.password_changed_at IS '최근 비밀번호 변경 시각';
COMMENT ON COLUMN auth.admin_user.last_login_at IS '최근 로그인 시각';
COMMENT ON COLUMN auth.admin_user.deleted_at IS '소프트 삭제 시각';
COMMENT ON COLUMN auth.admin_user.created_at IS '가입 시각';
COMMENT ON COLUMN auth.admin_user.updated_at IS '최종 수정 시각';

-- auth.role: ADMIN, OPERATOR, MANAGER 및 사용자 정의 역할의 이름·설명·표시 속성을 관리한다.
CREATE TABLE auth.role (
    role_id UUID DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    role_code VARCHAR(50) NOT NULL,
    role_name VARCHAR(100) NOT NULL,
    description TEXT,
    is_system BOOLEAN DEFAULT FALSE NOT NULL,
    is_active BOOLEAN DEFAULT TRUE NOT NULL,
    display_order INTEGER DEFAULT 0 NOT NULL,
    icon_key VARCHAR(100),
    color_hex VARCHAR(7),
    created_by UUID,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT uq_role_code UNIQUE (role_code),
    CONSTRAINT ck_role_color CHECK (color_hex IS NULL OR color_hex ~ '^#[0-9A-Fa-f]{6}$')
);
COMMENT ON TABLE auth.role IS 'ADMIN, OPERATOR, MANAGER 및 사용자 정의 역할의 이름·설명·표시 속성을 관리한다.';
COMMENT ON COLUMN auth.role.role_id IS '역할 식별자';
COMMENT ON COLUMN auth.role.role_code IS '역할 코드';
COMMENT ON COLUMN auth.role.role_name IS '화면 표시명';
COMMENT ON COLUMN auth.role.description IS '역할 설명';
COMMENT ON COLUMN auth.role.is_system IS '시스템 기본 역할 여부';
COMMENT ON COLUMN auth.role.is_active IS '활성 여부';
COMMENT ON COLUMN auth.role.display_order IS '표시 순서';
COMMENT ON COLUMN auth.role.icon_key IS 'UI 아이콘 키';
COMMENT ON COLUMN auth.role.color_hex IS 'UI 표시 색상';
COMMENT ON COLUMN auth.role.created_by IS '역할 생성 관리자';
COMMENT ON COLUMN auth.role.created_at IS '생성 시각';
COMMENT ON COLUMN auth.role.updated_at IS '수정 시각';

-- auth.user_role: 관리자에게 하나 이상의 역할을 부여하고 대표 역할 및 해제 시각을 관리한다.
CREATE TABLE auth.user_role (
    user_id UUID NOT NULL,
    role_id UUID NOT NULL,
    is_primary BOOLEAN DEFAULT FALSE NOT NULL,
    is_active BOOLEAN DEFAULT TRUE NOT NULL,
    assigned_by UUID,
    assigned_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    ended_at TIMESTAMPTZ,
    CONSTRAINT pk_user_role PRIMARY KEY (user_id, role_id),
    CONSTRAINT ck_user_role_dates CHECK (ended_at IS NULL OR ended_at >= assigned_at)
);
COMMENT ON TABLE auth.user_role IS '관리자에게 하나 이상의 역할을 부여하고 대표 역할 및 해제 시각을 관리한다.';
COMMENT ON COLUMN auth.user_role.user_id IS '관리자';
COMMENT ON COLUMN auth.user_role.role_id IS '역할';
COMMENT ON COLUMN auth.user_role.is_primary IS '대표 역할 여부';
COMMENT ON COLUMN auth.user_role.is_active IS '현재 유효 여부';
COMMENT ON COLUMN auth.user_role.assigned_by IS '역할 부여 관리자';
COMMENT ON COLUMN auth.user_role.assigned_at IS '역할 부여 시각';
COMMENT ON COLUMN auth.user_role.ended_at IS '역할 해제 시각';

-- auth.menu: 사이드바 및 페이지 경로를 계층적으로 관리하고 PAGE 권한과 연결한다.
CREATE TABLE auth.menu (
    menu_id UUID DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    menu_code VARCHAR(50) NOT NULL,
    menu_name VARCHAR(100) NOT NULL,
    menu_type VARCHAR(20) NOT NULL,
    parent_menu_id UUID,
    route_path VARCHAR(255),
    icon_key VARCHAR(100),
    display_order INTEGER DEFAULT 0 NOT NULL,
    is_active BOOLEAN DEFAULT TRUE NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT uq_menu_code UNIQUE (menu_code),
    CONSTRAINT ck_menu_type CHECK (menu_type IN ('GROUP','PAGE'))
);
COMMENT ON TABLE auth.menu IS '사이드바 및 페이지 경로를 계층적으로 관리하고 PAGE 권한과 연결한다.';
COMMENT ON COLUMN auth.menu.menu_id IS '메뉴 식별자';
COMMENT ON COLUMN auth.menu.menu_code IS '메뉴 코드';
COMMENT ON COLUMN auth.menu.menu_name IS '메뉴명';
COMMENT ON COLUMN auth.menu.menu_type IS 'GROUP 또는 PAGE';
COMMENT ON COLUMN auth.menu.parent_menu_id IS '상위 메뉴';
COMMENT ON COLUMN auth.menu.route_path IS 'React 라우트 경로';
COMMENT ON COLUMN auth.menu.icon_key IS '아이콘 키';
COMMENT ON COLUMN auth.menu.display_order IS '표시 순서';
COMMENT ON COLUMN auth.menu.is_active IS '활성 여부';
COMMENT ON COLUMN auth.menu.created_at IS '생성 시각';
COMMENT ON COLUMN auth.menu.updated_at IS '수정 시각';

-- auth.permission: 페이지·API·동작 단위의 세부 권한을 정의한다.
CREATE TABLE auth.permission (
    permission_id UUID DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    permission_code VARCHAR(100) NOT NULL,
    permission_name VARCHAR(100) NOT NULL,
    description TEXT,
    resource_type VARCHAR(30) NOT NULL,
    resource_code VARCHAR(100) NOT NULL,
    action VARCHAR(30) NOT NULL,
    menu_id UUID,
    is_active BOOLEAN DEFAULT TRUE NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT uq_permission_code UNIQUE (permission_code),
    CONSTRAINT ck_permission_resource_type CHECK (resource_type IN ('PAGE','API','ACTION')),
    CONSTRAINT ck_permission_action CHECK (action IN ('VIEW','CREATE','UPDATE','DELETE','EXECUTE','APPROVE'))
);
COMMENT ON TABLE auth.permission IS '페이지·API·동작 단위의 세부 권한을 정의한다.';
COMMENT ON COLUMN auth.permission.permission_id IS '권한 식별자';
COMMENT ON COLUMN auth.permission.permission_code IS '권한 코드';
COMMENT ON COLUMN auth.permission.permission_name IS '권한 표시명';
COMMENT ON COLUMN auth.permission.description IS '권한 설명';
COMMENT ON COLUMN auth.permission.resource_type IS 'PAGE, API, ACTION';
COMMENT ON COLUMN auth.permission.resource_code IS '대상 리소스 코드';
COMMENT ON COLUMN auth.permission.action IS 'VIEW, CREATE, UPDATE, DELETE, EXECUTE, APPROVE';
COMMENT ON COLUMN auth.permission.menu_id IS '연결 메뉴';
COMMENT ON COLUMN auth.permission.is_active IS '활성 여부';
COMMENT ON COLUMN auth.permission.created_at IS '생성 시각';
COMMENT ON COLUMN auth.permission.updated_at IS '수정 시각';

-- auth.role_permission: 역할이 기본적으로 보유하는 권한을 N:M으로 연결한다.
CREATE TABLE auth.role_permission (
    role_id UUID NOT NULL,
    permission_id UUID NOT NULL,
    created_by UUID,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT pk_role_permission PRIMARY KEY (role_id, permission_id)
);
COMMENT ON TABLE auth.role_permission IS '역할이 기본적으로 보유하는 권한을 N:M으로 연결한다.';
COMMENT ON COLUMN auth.role_permission.role_id IS '역할';
COMMENT ON COLUMN auth.role_permission.permission_id IS '권한';
COMMENT ON COLUMN auth.role_permission.created_by IS '권한 부여 관리자';
COMMENT ON COLUMN auth.role_permission.created_at IS '권한 부여 시각';

-- auth.user_permission_override: 역할 권한보다 우선하는 사용자별 ALLOW/DENY 예외를 저장한다.
CREATE TABLE auth.user_permission_override (
    user_id UUID NOT NULL,
    permission_id UUID NOT NULL,
    effect VARCHAR(10) NOT NULL,
    reason TEXT,
    expires_at TIMESTAMPTZ,
    granted_by UUID NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT pk_user_permission_override PRIMARY KEY (user_id, permission_id),
    CONSTRAINT ck_user_permission_effect CHECK (effect IN ('ALLOW','DENY'))
);
COMMENT ON TABLE auth.user_permission_override IS '역할 권한보다 우선하는 사용자별 ALLOW/DENY 예외를 저장한다.';
COMMENT ON COLUMN auth.user_permission_override.user_id IS '관리자';
COMMENT ON COLUMN auth.user_permission_override.permission_id IS '권한';
COMMENT ON COLUMN auth.user_permission_override.effect IS 'ALLOW 또는 DENY';
COMMENT ON COLUMN auth.user_permission_override.reason IS '예외 사유';
COMMENT ON COLUMN auth.user_permission_override.expires_at IS '임시 권한 만료 시각';
COMMENT ON COLUMN auth.user_permission_override.granted_by IS '예외 권한 부여자';
COMMENT ON COLUMN auth.user_permission_override.created_at IS '생성 시각';
COMMENT ON COLUMN auth.user_permission_override.updated_at IS '수정 시각';

-- robot.robot: 등록된 로봇의 내부 UUID, MQTT 기기 코드·Client ID, 하드웨어·펌웨어 정보를 관리한다.
CREATE TABLE robot.robot (
    robot_id UUID DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    robot_code VARCHAR(30) NOT NULL,
    robot_name VARCHAR(100) NOT NULL,
    serial_number VARCHAR(100),
    mqtt_client_id VARCHAR(100) NOT NULL,
    mqtt_username VARCHAR(100) NOT NULL,
    hardware_model VARCHAR(100),
    firmware_version VARCHAR(50),
    operational_status VARCHAR(20) DEFAULT 'ACTIVE' NOT NULL,
    registered_by UUID,
    registered_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT uq_robot_code UNIQUE (robot_code),
    CONSTRAINT uq_robot_serial UNIQUE (serial_number),
    CONSTRAINT uq_robot_mqtt_client UNIQUE (mqtt_client_id),
    CONSTRAINT uq_robot_mqtt_username UNIQUE (mqtt_username),
    CONSTRAINT ck_robot_operational_status CHECK (operational_status IN ('ACTIVE','INACTIVE','MAINTENANCE','DISABLED'))
);
COMMENT ON TABLE robot.robot IS '등록된 로봇의 내부 UUID, MQTT 기기 코드·Client ID, 하드웨어·펌웨어 정보를 관리한다.';
COMMENT ON COLUMN robot.robot.robot_id IS '내부 로봇 식별자';
COMMENT ON COLUMN robot.robot.robot_code IS 'MQTT robot_id. 예: RBT-001';
COMMENT ON COLUMN robot.robot.robot_name IS '관리자 표시명';
COMMENT ON COLUMN robot.robot.serial_number IS '기기 시리얼번호';
COMMENT ON COLUMN robot.robot.mqtt_client_id IS '고정 MQTT Client ID';
COMMENT ON COLUMN robot.robot.mqtt_username IS '기기별 MQTT 계정';
COMMENT ON COLUMN robot.robot.hardware_model IS '하드웨어 모델';
COMMENT ON COLUMN robot.robot.firmware_version IS '최근 Presence에서 보고된 펌웨어';
COMMENT ON COLUMN robot.robot.operational_status IS 'ACTIVE, INACTIVE, MAINTENANCE, DISABLED';
COMMENT ON COLUMN robot.robot.registered_by IS '등록 관리자';
COMMENT ON COLUMN robot.robot.registered_at IS '등록 시각';
COMMENT ON COLUMN robot.robot.created_at IS '생성 시각';
COMMENT ON COLUMN robot.robot.updated_at IS '수정 시각';

-- robot.robot_credential: MQTT TLS·기기별 계정 및 이미지 API Key의 해시·인증서 지문과 수명주기를 관리한다.
CREATE TABLE robot.robot_credential (
    credential_id UUID DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    robot_id UUID NOT NULL,
    credential_type VARCHAR(30) NOT NULL,
    principal_name VARCHAR(100) NOT NULL,
    secret_hash VARCHAR(255),
    certificate_fingerprint VARCHAR(128),
    credential_status VARCHAR(20) DEFAULT 'ACTIVE' NOT NULL,
    valid_from TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    expires_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    created_by UUID,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT ck_robot_credential_type CHECK (credential_type IN ('MQTT_PASSWORD','IMAGE_API_KEY','TLS_CERT')),
    CONSTRAINT ck_robot_credential_status CHECK (credential_status IN ('ACTIVE','EXPIRED','REVOKED')),
    CONSTRAINT ck_robot_credential_secret CHECK (secret_hash IS NOT NULL OR certificate_fingerprint IS NOT NULL),
    CONSTRAINT ck_robot_credential_dates CHECK (expires_at IS NULL OR expires_at > valid_from)
);
COMMENT ON TABLE robot.robot_credential IS 'MQTT TLS·기기별 계정 및 이미지 API Key의 해시·인증서 지문과 수명주기를 관리한다.';
COMMENT ON COLUMN robot.robot_credential.credential_id IS '인증정보 식별자';
COMMENT ON COLUMN robot.robot_credential.robot_id IS '대상 로봇';
COMMENT ON COLUMN robot.robot_credential.credential_type IS 'MQTT_PASSWORD, IMAGE_API_KEY, TLS_CERT';
COMMENT ON COLUMN robot.robot_credential.principal_name IS 'MQTT 사용자명 또는 Key ID';
COMMENT ON COLUMN robot.robot_credential.secret_hash IS '비밀번호·API Key 해시';
COMMENT ON COLUMN robot.robot_credential.certificate_fingerprint IS 'TLS 인증서 지문';
COMMENT ON COLUMN robot.robot_credential.credential_status IS 'ACTIVE, EXPIRED, REVOKED';
COMMENT ON COLUMN robot.robot_credential.valid_from IS '유효 시작 시각';
COMMENT ON COLUMN robot.robot_credential.expires_at IS '만료 시각';
COMMENT ON COLUMN robot.robot_credential.last_used_at IS '최근 사용 시각';
COMMENT ON COLUMN robot.robot_credential.created_by IS '생성 관리자';
COMMENT ON COLUMN robot.robot_credential.created_at IS '생성 시각';
COMMENT ON COLUMN robot.robot_credential.revoked_at IS '폐기 시각';

-- auth.user_robot_assignment: 마이페이지의 관리 로봇 수와 사용자별 로봇 조회·운영 범위를 연결한다.
CREATE TABLE auth.user_robot_assignment (
    user_id UUID NOT NULL,
    robot_id UUID NOT NULL,
    assignment_type VARCHAR(30) NOT NULL,
    is_active BOOLEAN DEFAULT TRUE NOT NULL,
    assigned_by UUID,
    assigned_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    ended_at TIMESTAMPTZ,
    CONSTRAINT pk_user_robot_assignment PRIMARY KEY (user_id, robot_id),
    CONSTRAINT ck_user_robot_assignment_type CHECK (assignment_type IN ('OWNER','OPERATOR','VIEWER')),
    CONSTRAINT ck_user_robot_assignment_dates CHECK (ended_at IS NULL OR ended_at >= assigned_at)
);
COMMENT ON TABLE auth.user_robot_assignment IS '마이페이지의 관리 로봇 수와 사용자별 로봇 조회·운영 범위를 연결한다.';
COMMENT ON COLUMN auth.user_robot_assignment.user_id IS '관리자';
COMMENT ON COLUMN auth.user_robot_assignment.robot_id IS '담당 로봇';
COMMENT ON COLUMN auth.user_robot_assignment.assignment_type IS 'OWNER, OPERATOR, VIEWER';
COMMENT ON COLUMN auth.user_robot_assignment.is_active IS '현재 유효 여부';
COMMENT ON COLUMN auth.user_robot_assignment.assigned_by IS '배정 관리자';
COMMENT ON COLUMN auth.user_robot_assignment.assigned_at IS '배정 시각';
COMMENT ON COLUMN auth.user_robot_assignment.ended_at IS '배정 종료 시각';

-- robot.patrol_area: 관리자 지도에서 지정한 Polygon과 MQTT zone_id를 연결한다.
CREATE TABLE robot.patrol_area (
    patrol_area_id UUID DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    zone_id VARCHAR(10) NOT NULL,
    area_name VARCHAR(100) NOT NULL,
    area_geometry GEOMETRY(POLYGON,4326) NOT NULL,
    description TEXT,
    is_active BOOLEAN DEFAULT TRUE NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT uq_patrol_area_zone UNIQUE (zone_id),
    CONSTRAINT ck_patrol_area_zone CHECK (zone_id ~ '^[A-Z]-[0-9]{2}$')
);
COMMENT ON TABLE robot.patrol_area IS '관리자 지도에서 지정한 Polygon과 MQTT zone_id를 연결한다.';
COMMENT ON COLUMN robot.patrol_area.patrol_area_id IS '순찰 구역 식별자';
COMMENT ON COLUMN robot.patrol_area.zone_id IS 'MQTT 구역 코드. 예: A-01';
COMMENT ON COLUMN robot.patrol_area.area_name IS '구역명';
COMMENT ON COLUMN robot.patrol_area.area_geometry IS '구역 Polygon';
COMMENT ON COLUMN robot.patrol_area.description IS '구역 설명';
COMMENT ON COLUMN robot.patrol_area.is_active IS '활성 여부';
COMMENT ON COLUMN robot.patrol_area.created_by IS '생성 관리자';
COMMENT ON COLUMN robot.patrol_area.created_at IS '생성 시각';
COMMENT ON COLUMN robot.patrol_area.updated_at IS '수정 시각';

-- robot.patrol_route: 관리자 지도에 표시할 계획 경로를 저장한다. MVP 로봇은 경로 GeoJSON을 직접 소비하지 않는다.
CREATE TABLE robot.patrol_route (
    patrol_route_id UUID DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    patrol_area_id UUID NOT NULL,
    route_code VARCHAR(30) NOT NULL,
    route_name VARCHAR(100) NOT NULL,
    route_geometry GEOMETRY(LINESTRING,4326) NOT NULL,
    planned_distance_m NUMERIC(12,2),
    display_order INTEGER DEFAULT 0 NOT NULL,
    is_active BOOLEAN DEFAULT TRUE NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT uq_patrol_route_code UNIQUE (route_code),
    CONSTRAINT ck_patrol_route_distance CHECK (planned_distance_m IS NULL OR planned_distance_m >= 0)
);
COMMENT ON TABLE robot.patrol_route IS '관리자 지도에 표시할 계획 경로를 저장한다. MVP 로봇은 경로 GeoJSON을 직접 소비하지 않는다.';
COMMENT ON COLUMN robot.patrol_route.patrol_route_id IS '경로 식별자';
COMMENT ON COLUMN robot.patrol_route.patrol_area_id IS '소속 순찰 구역';
COMMENT ON COLUMN robot.patrol_route.route_code IS '경로 코드';
COMMENT ON COLUMN robot.patrol_route.route_name IS '경로명';
COMMENT ON COLUMN robot.patrol_route.route_geometry IS '계획 경로';
COMMENT ON COLUMN robot.patrol_route.planned_distance_m IS '계획 거리(m)';
COMMENT ON COLUMN robot.patrol_route.display_order IS '표시 순서';
COMMENT ON COLUMN robot.patrol_route.is_active IS '활성 여부';
COMMENT ON COLUMN robot.patrol_route.created_by IS '생성 관리자';
COMMENT ON COLUMN robot.patrol_route.created_at IS '생성 시각';
COMMENT ON COLUMN robot.patrol_route.updated_at IS '수정 시각';

-- robot.mission: 서버가 발급한 mission_code와 로봇·구역·경로, 일정, 진행률 및 임무 결과 집계를 관리한다.
CREATE TABLE robot.mission (
    mission_id UUID DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    mission_code VARCHAR(40) NOT NULL,
    mission_name VARCHAR(100) NOT NULL,
    description TEXT,
    robot_id UUID NOT NULL,
    patrol_area_id UUID NOT NULL,
    patrol_route_id UUID,
    mission_status VARCHAR(20) DEFAULT 'READY' NOT NULL,
    priority SMALLINT DEFAULT 3 NOT NULL,
    scheduled_start_at TIMESTAMPTZ,
    scheduled_end_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    progress_percent NUMERIC(5,2) DEFAULT 0 NOT NULL,
    estimated_completion_at TIMESTAMPTZ,
    incident_count INTEGER DEFAULT 0 NOT NULL,
    normal_detection_count INTEGER DEFAULT 0 NOT NULL,
    result_distance_m NUMERIC(12,2),
    completion_reason VARCHAR(50),
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT uq_mission_code UNIQUE (mission_code),
    CONSTRAINT ck_mission_status CHECK (mission_status IN ('READY','ASSIGNED','RUNNING','PAUSED','COMPLETED','CANCELLED','FAILED')),
    CONSTRAINT ck_mission_priority CHECK (priority BETWEEN 1 AND 5),
    CONSTRAINT ck_mission_progress CHECK (progress_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_mission_counts CHECK (incident_count >= 0 AND normal_detection_count >= 0),
    CONSTRAINT ck_mission_distance CHECK (result_distance_m IS NULL OR result_distance_m >= 0),
    CONSTRAINT ck_mission_schedule CHECK (scheduled_end_at IS NULL OR scheduled_start_at IS NULL OR scheduled_end_at >= scheduled_start_at)
);
COMMENT ON TABLE robot.mission IS '서버가 발급한 mission_code와 로봇·구역·경로, 일정, 진행률 및 임무 결과 집계를 관리한다.';
COMMENT ON COLUMN robot.mission.mission_id IS '내부 임무 식별자';
COMMENT ON COLUMN robot.mission.mission_code IS 'MQTT mission_id. 예: MIS-20260723-001';
COMMENT ON COLUMN robot.mission.mission_name IS '임무명';
COMMENT ON COLUMN robot.mission.description IS '임무 설명';
COMMENT ON COLUMN robot.mission.robot_id IS '수행 로봇';
COMMENT ON COLUMN robot.mission.patrol_area_id IS '순찰 구역';
COMMENT ON COLUMN robot.mission.patrol_route_id IS '계획 경로';
COMMENT ON COLUMN robot.mission.mission_status IS 'READY, ASSIGNED, RUNNING, PAUSED, COMPLETED, CANCELLED, FAILED';
COMMENT ON COLUMN robot.mission.priority IS '우선순위 1~5';
COMMENT ON COLUMN robot.mission.scheduled_start_at IS '예정 시작 시각';
COMMENT ON COLUMN robot.mission.scheduled_end_at IS '예정 종료 시각';
COMMENT ON COLUMN robot.mission.started_at IS '실제 시작 시각';
COMMENT ON COLUMN robot.mission.completed_at IS '완료 시각';
COMMENT ON COLUMN robot.mission.progress_percent IS '진행률';
COMMENT ON COLUMN robot.mission.estimated_completion_at IS '예상 완료 시각';
COMMENT ON COLUMN robot.mission.incident_count IS '임무 이상 사건 수';
COMMENT ON COLUMN robot.mission.normal_detection_count IS '정상 점자블록 탐지 집계';
COMMENT ON COLUMN robot.mission.result_distance_m IS '임무 완료 거리';
COMMENT ON COLUMN robot.mission.completion_reason IS '완료·중단 이유';
COMMENT ON COLUMN robot.mission.created_by IS '생성 관리자';
COMMENT ON COLUMN robot.mission.created_at IS '생성 시각';
COMMENT ON COLUMN robot.mission.updated_at IS '수정 시각';

-- robot.robot_command: 관리자 REST 요청을 MQTT commands/request로 변환하기 위한 명령 원본과 발행·만료 상태를 저장한다.
CREATE TABLE robot.robot_command (
    command_id UUID DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    schema_version VARCHAR(20) DEFAULT '1.0' NOT NULL,
    robot_id UUID NOT NULL,
    mission_id UUID,
    command_type VARCHAR(40) NOT NULL,
    parameters JSONB,
    requested_by_actor_type VARCHAR(20) DEFAULT 'ADMIN' NOT NULL,
    requested_by_user_id UUID,
    requested_by_actor_code VARCHAR(30),
    command_status VARCHAR(20) DEFAULT 'CREATED' NOT NULL,
    issued_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    message_expiry_sec INTEGER NOT NULL,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT ck_robot_command_type CHECK (command_type IN ('START_MISSION','PAUSE_MISSION','RESUME_MISSION','CANCEL_MISSION','EMERGENCY_STOP','CLEAR_EMERGENCY_STOP','RETURN_TO_CHARGE','UPDATE_CONFIG')),
    CONSTRAINT ck_robot_command_actor CHECK (requested_by_actor_type IN ('ADMIN','SYSTEM')),
    CONSTRAINT ck_robot_command_status CHECK (command_status IN ('CREATED','PUBLISHED','ACCEPTED','COMPLETED','FAILED','REJECTED','EXPIRED')),
    CONSTRAINT ck_robot_command_expiry CHECK (expires_at > issued_at AND message_expiry_sec > 0),
    CONSTRAINT ck_robot_command_requester CHECK ((requested_by_actor_type = 'ADMIN' AND requested_by_user_id IS NOT NULL) OR requested_by_actor_type = 'SYSTEM')
);
COMMENT ON TABLE robot.robot_command IS '관리자 REST 요청을 MQTT commands/request로 변환하기 위한 명령 원본과 발행·만료 상태를 저장한다.';
COMMENT ON COLUMN robot.robot_command.command_id IS 'MQTT command_id';
COMMENT ON COLUMN robot.robot_command.schema_version IS 'MQTT 스키마 버전';
COMMENT ON COLUMN robot.robot_command.robot_id IS '대상 로봇';
COMMENT ON COLUMN robot.robot_command.mission_id IS '관련 임무';
COMMENT ON COLUMN robot.robot_command.command_type IS '명령 유형';
COMMENT ON COLUMN robot.robot_command.parameters IS 'MQTT parameters';
COMMENT ON COLUMN robot.robot_command.requested_by_actor_type IS 'ADMIN 또는 SYSTEM';
COMMENT ON COLUMN robot.robot_command.requested_by_user_id IS '관리자 요청자';
COMMENT ON COLUMN robot.robot_command.requested_by_actor_code IS 'MQTT actor_id Snapshot';
COMMENT ON COLUMN robot.robot_command.command_status IS 'CREATED, PUBLISHED, ACCEPTED, COMPLETED, FAILED, REJECTED, EXPIRED';
COMMENT ON COLUMN robot.robot_command.issued_at IS '발급 시각';
COMMENT ON COLUMN robot.robot_command.expires_at IS '만료 시각';
COMMENT ON COLUMN robot.robot_command.message_expiry_sec IS 'MQTT 5 message expiry';
COMMENT ON COLUMN robot.robot_command.published_at IS 'MQTT 발행 시각';
COMMENT ON COLUMN robot.robot_command.created_at IS '생성 시각';
COMMENT ON COLUMN robot.robot_command.updated_at IS '수정 시각';

-- telemetry.robot_boot_session: Presence Birth/LWT/정상 종료와 boot_id를 연결하여 온라인·오프라인 이력을 보존한다.
CREATE TABLE telemetry.robot_boot_session (
    boot_id UUID NOT NULL PRIMARY KEY,
    robot_id UUID NOT NULL,
    schema_version VARCHAR(20) NOT NULL,
    firmware_version VARCHAR(50),
    presence_status VARCHAR(20) NOT NULL,
    connected_at TIMESTAMPTZ,
    disconnected_at TIMESTAMPTZ,
    disconnect_reason VARCHAR(50),
    graceful_shutdown BOOLEAN,
    last_presence_received_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT ck_boot_presence_status CHECK (presence_status IN ('ONLINE','OFFLINE')),
    CONSTRAINT ck_boot_dates CHECK (disconnected_at IS NULL OR connected_at IS NULL OR disconnected_at >= connected_at)
);
COMMENT ON TABLE telemetry.robot_boot_session IS 'Presence Birth/LWT/정상 종료와 boot_id를 연결하여 온라인·오프라인 이력을 보존한다.';
COMMENT ON COLUMN telemetry.robot_boot_session.boot_id IS '로봇이 부팅 시 생성한 UUID';
COMMENT ON COLUMN telemetry.robot_boot_session.robot_id IS '로봇';
COMMENT ON COLUMN telemetry.robot_boot_session.schema_version IS 'Presence 스키마 버전';
COMMENT ON COLUMN telemetry.robot_boot_session.firmware_version IS '부팅 시 펌웨어 버전';
COMMENT ON COLUMN telemetry.robot_boot_session.presence_status IS 'ONLINE 또는 OFFLINE';
COMMENT ON COLUMN telemetry.robot_boot_session.connected_at IS 'Birth 연결 시각';
COMMENT ON COLUMN telemetry.robot_boot_session.disconnected_at IS '종료·LWT 서버 수신 시각';
COMMENT ON COLUMN telemetry.robot_boot_session.disconnect_reason IS 'UNGRACEFUL_DISCONNECT, GRACEFUL_SHUTDOWN 등';
COMMENT ON COLUMN telemetry.robot_boot_session.graceful_shutdown IS '정상 종료 여부';
COMMENT ON COLUMN telemetry.robot_boot_session.last_presence_received_at IS '마지막 Presence 수신 시각';
COMMENT ON COLUMN telemetry.robot_boot_session.created_at IS '생성 시각';
COMMENT ON COLUMN telemetry.robot_boot_session.updated_at IS '수정 시각';

-- telemetry.robot_telemetry: QoS 0, 5초 주기의 위치·배터리·Jetson 온도·환경·임무 측정값을 message_id 기준으로 저장한다.
CREATE TABLE telemetry.robot_telemetry (
    message_id UUID NOT NULL PRIMARY KEY,
    schema_version VARCHAR(20) NOT NULL,
    robot_id UUID NOT NULL,
    boot_id UUID NOT NULL,
    seq BIGINT NOT NULL,
    sampled_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    mission_id UUID,
    position_valid BOOLEAN NOT NULL,
    location GEOMETRY(POINT,4326),
    location_accuracy_m REAL,
    heading_deg REAL,
    motion_moving BOOLEAN,
    speed_mps REAL,
    battery_valid BOOLEAN NOT NULL,
    battery_percent NUMERIC(5,2),
    battery_state VARCHAR(20) NOT NULL,
    battery_source VARCHAR(20),
    battery_voltage_v REAL,
    battery_current_a REAL,
    battery_charging BOOLEAN,
    battery_temperature_c REAL,
    estimated_runtime_sec INTEGER,
    jetson_temperature_c REAL NOT NULL,
    environment_temperature_c REAL,
    environment_humidity_pct REAL,
    mission_state VARCHAR(30),
    mission_elapsed_sec INTEGER,
    mission_incident_count INTEGER,
    raw_payload JSONB NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT uq_telemetry_boot_seq UNIQUE (robot_id, boot_id, seq),
    CONSTRAINT ck_telemetry_heading CHECK (heading_deg IS NULL OR heading_deg BETWEEN 0 AND 360),
    CONSTRAINT ck_telemetry_speed CHECK (speed_mps IS NULL OR speed_mps >= 0),
    CONSTRAINT ck_telemetry_battery_percent CHECK (battery_percent IS NULL OR battery_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_telemetry_battery_state CHECK (battery_state IN ('NORMAL','LOW','CRITICAL','UNKNOWN')),
    CONSTRAINT ck_telemetry_battery_source CHECK (battery_source IS NULL OR battery_source IN ('MEASURED','ESTIMATED')),
    CONSTRAINT ck_telemetry_humidity CHECK (environment_humidity_pct IS NULL OR environment_humidity_pct BETWEEN 0 AND 100),
    CONSTRAINT ck_telemetry_elapsed CHECK (mission_elapsed_sec IS NULL OR mission_elapsed_sec >= 0),
    CONSTRAINT ck_telemetry_incidents CHECK (mission_incident_count IS NULL OR mission_incident_count >= 0),
    CONSTRAINT ck_telemetry_position CHECK ((position_valid = TRUE AND location IS NOT NULL) OR position_valid = FALSE),
    CONSTRAINT ck_telemetry_battery_valid CHECK (battery_valid = TRUE OR battery_percent IS NULL)
);
COMMENT ON TABLE telemetry.robot_telemetry IS 'QoS 0, 5초 주기의 위치·배터리·Jetson 온도·환경·임무 측정값을 message_id 기준으로 저장한다.';
COMMENT ON COLUMN telemetry.robot_telemetry.message_id IS 'MQTT message_id';
COMMENT ON COLUMN telemetry.robot_telemetry.schema_version IS '페이로드 버전';
COMMENT ON COLUMN telemetry.robot_telemetry.robot_id IS '로봇';
COMMENT ON COLUMN telemetry.robot_telemetry.boot_id IS '부팅 세션';
COMMENT ON COLUMN telemetry.robot_telemetry.seq IS '부팅 내 공용 순번';
COMMENT ON COLUMN telemetry.robot_telemetry.sampled_at IS '로봇 표본 시각';
COMMENT ON COLUMN telemetry.robot_telemetry.received_at IS '서버 수신 시각';
COMMENT ON COLUMN telemetry.robot_telemetry.mission_id IS '수행 임무';
COMMENT ON COLUMN telemetry.robot_telemetry.position_valid IS 'GPS fix 유효 여부';
COMMENT ON COLUMN telemetry.robot_telemetry.location IS '유효할 때 현재 위치';
COMMENT ON COLUMN telemetry.robot_telemetry.location_accuracy_m IS 'GPS 오차 추정';
COMMENT ON COLUMN telemetry.robot_telemetry.heading_deg IS '진행 방향';
COMMENT ON COLUMN telemetry.robot_telemetry.motion_moving IS '이동 여부';
COMMENT ON COLUMN telemetry.robot_telemetry.speed_mps IS '현재 속도';
COMMENT ON COLUMN telemetry.robot_telemetry.battery_valid IS '배터리 값 유효 여부';
COMMENT ON COLUMN telemetry.robot_telemetry.battery_percent IS '배터리 잔량';
COMMENT ON COLUMN telemetry.robot_telemetry.battery_state IS 'NORMAL, LOW, CRITICAL, UNKNOWN';
COMMENT ON COLUMN telemetry.robot_telemetry.battery_source IS 'MEASURED 또는 ESTIMATED';
COMMENT ON COLUMN telemetry.robot_telemetry.battery_voltage_v IS '배터리 전압';
COMMENT ON COLUMN telemetry.robot_telemetry.battery_current_a IS '배터리 전류';
COMMENT ON COLUMN telemetry.robot_telemetry.battery_charging IS '충전 여부';
COMMENT ON COLUMN telemetry.robot_telemetry.battery_temperature_c IS '배터리 온도';
COMMENT ON COLUMN telemetry.robot_telemetry.estimated_runtime_sec IS '예상 잔여 운용시간';
COMMENT ON COLUMN telemetry.robot_telemetry.jetson_temperature_c IS 'Jetson 열 존 최고 온도';
COMMENT ON COLUMN telemetry.robot_telemetry.environment_temperature_c IS '외기 온도';
COMMENT ON COLUMN telemetry.robot_telemetry.environment_humidity_pct IS '외기 습도';
COMMENT ON COLUMN telemetry.robot_telemetry.mission_state IS 'RUNNING, PAUSED 등';
COMMENT ON COLUMN telemetry.robot_telemetry.mission_elapsed_sec IS '임무 경과시간';
COMMENT ON COLUMN telemetry.robot_telemetry.mission_incident_count IS '현재 임무 사건 수';
COMMENT ON COLUMN telemetry.robot_telemetry.raw_payload IS '원본 MQTT JSON';
COMMENT ON COLUMN telemetry.robot_telemetry.created_at IS '저장 시각';

-- telemetry.robot_current_state: Retained Presence·State와 최신 유효 Telemetry를 결합하여 로봇 관제 화면의 지도·카드·상태 원천으로 사용한다.
CREATE TABLE telemetry.robot_current_state (
    robot_id UUID NOT NULL PRIMARY KEY,
    boot_id UUID,
    presence_status VARCHAR(20) DEFAULT 'UNKNOWN' NOT NULL,
    presence_reason VARCHAR(50),
    presence_received_at TIMESTAMPTZ,
    state_seq BIGINT,
    state_updated_at TIMESTAMPTZ,
    state_received_at TIMESTAMPTZ,
    mode VARCHAR(40) DEFAULT 'BOOTING' NOT NULL,
    reason_code VARCHAR(80) DEFAULT 'NONE' NOT NULL,
    mission_id UUID,
    mission_state VARCHAR(30),
    battery_valid BOOLEAN DEFAULT FALSE NOT NULL,
    battery_percent NUMERIC(5,2),
    battery_state VARCHAR(20) DEFAULT 'UNKNOWN' NOT NULL,
    health_camera VARCHAR(20) DEFAULT 'UNKNOWN' NOT NULL,
    health_lidar VARCHAR(20) DEFAULT 'UNKNOWN' NOT NULL,
    health_gps VARCHAR(20) DEFAULT 'UNKNOWN' NOT NULL,
    health_battery VARCHAR(20) DEFAULT 'UNKNOWN' NOT NULL,
    health_motor VARCHAR(20) DEFAULT 'UNKNOWN' NOT NULL,
    health_storage VARCHAR(20) DEFAULT 'UNKNOWN' NOT NULL,
    overall_health_status VARCHAR(20) DEFAULT 'NORMAL' NOT NULL,
    last_telemetry_message_id UUID,
    telemetry_sampled_at TIMESTAMPTZ,
    telemetry_received_at TIMESTAMPTZ,
    position_valid BOOLEAN DEFAULT FALSE NOT NULL,
    location GEOMETRY(POINT,4326),
    location_accuracy_m REAL,
    heading_deg REAL,
    motion_moving BOOLEAN,
    speed_mps REAL,
    battery_source VARCHAR(20),
    battery_voltage_v REAL,
    battery_current_a REAL,
    battery_charging BOOLEAN,
    battery_temperature_c REAL,
    estimated_runtime_sec INTEGER,
    jetson_temperature_c REAL,
    environment_temperature_c REAL,
    environment_humidity_pct REAL,
    mission_elapsed_sec INTEGER,
    mission_incident_count INTEGER,
    charging_status VARCHAR(30) DEFAULT 'NOT_CHARGING' NOT NULL,
    state_raw_payload JSONB,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT ck_current_presence CHECK (presence_status IN ('ONLINE','OFFLINE','UNKNOWN')),
    CONSTRAINT ck_current_mode CHECK (mode IN ('BOOTING','READY','PATROLLING','PAUSED','SAFE_STOP','ERROR','SHUTTING_DOWN','RETURNING_TO_CHARGE','CHARGING')),
    CONSTRAINT ck_current_battery_state CHECK (battery_state IN ('NORMAL','LOW','CRITICAL','UNKNOWN')),
    CONSTRAINT ck_current_health_camera CHECK (health_camera IN ('OK','DEGRADED','FAULT','UNKNOWN')),
    CONSTRAINT ck_current_health_lidar CHECK (health_lidar IN ('OK','DEGRADED','FAULT','UNKNOWN')),
    CONSTRAINT ck_current_health_gps CHECK (health_gps IN ('OK','DEGRADED','FAULT','UNKNOWN')),
    CONSTRAINT ck_current_health_battery CHECK (health_battery IN ('OK','DEGRADED','FAULT','UNKNOWN')),
    CONSTRAINT ck_current_health_motor CHECK (health_motor IN ('OK','DEGRADED','FAULT','UNKNOWN')),
    CONSTRAINT ck_current_health_storage CHECK (health_storage IN ('OK','DEGRADED','FAULT','UNKNOWN')),
    CONSTRAINT ck_current_overall_health CHECK (overall_health_status IN ('NORMAL','WARNING','CRITICAL')),
    CONSTRAINT ck_current_charging_status CHECK (charging_status IN ('NOT_CHARGING','RETURNING','CHARGING','FULL')),
    CONSTRAINT ck_current_battery_percent CHECK (battery_percent IS NULL OR battery_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_current_heading CHECK (heading_deg IS NULL OR heading_deg BETWEEN 0 AND 360),
    CONSTRAINT ck_current_position CHECK ((position_valid = TRUE AND location IS NOT NULL) OR position_valid = FALSE)
);
COMMENT ON TABLE telemetry.robot_current_state IS 'Retained Presence·State와 최신 유효 Telemetry를 결합하여 로봇 관제 화면의 지도·카드·상태 원천으로 사용한다.';
COMMENT ON COLUMN telemetry.robot_current_state.robot_id IS '로봇';
COMMENT ON COLUMN telemetry.robot_current_state.boot_id IS '현재 부팅 세션';
COMMENT ON COLUMN telemetry.robot_current_state.presence_status IS 'ONLINE, OFFLINE, UNKNOWN';
COMMENT ON COLUMN telemetry.robot_current_state.presence_reason IS '종료·오프라인 이유';
COMMENT ON COLUMN telemetry.robot_current_state.presence_received_at IS 'Presence 수신 시각';
COMMENT ON COLUMN telemetry.robot_current_state.state_seq IS '최근 State 순번';
COMMENT ON COLUMN telemetry.robot_current_state.state_updated_at IS '로봇 State 시각';
COMMENT ON COLUMN telemetry.robot_current_state.state_received_at IS '서버 State 수신 시각';
COMMENT ON COLUMN telemetry.robot_current_state.mode IS 'BOOTING, READY, PATROLLING, PAUSED, SAFE_STOP, ERROR, SHUTTING_DOWN, RETURNING_TO_CHARGE, CHARGING';
COMMENT ON COLUMN telemetry.robot_current_state.reason_code IS '정지·오류 원인 코드';
COMMENT ON COLUMN telemetry.robot_current_state.mission_id IS '현재 임무';
COMMENT ON COLUMN telemetry.robot_current_state.mission_state IS '현재 임무 상태';
COMMENT ON COLUMN telemetry.robot_current_state.battery_valid IS '배터리 유효 여부';
COMMENT ON COLUMN telemetry.robot_current_state.battery_percent IS '배터리 잔량';
COMMENT ON COLUMN telemetry.robot_current_state.battery_state IS 'NORMAL, LOW, CRITICAL, UNKNOWN';
COMMENT ON COLUMN telemetry.robot_current_state.health_camera IS '카메라 건강 상태';
COMMENT ON COLUMN telemetry.robot_current_state.health_lidar IS 'LiDAR 건강 상태';
COMMENT ON COLUMN telemetry.robot_current_state.health_gps IS 'GPS 건강 상태';
COMMENT ON COLUMN telemetry.robot_current_state.health_battery IS '배터리 건강 상태';
COMMENT ON COLUMN telemetry.robot_current_state.health_motor IS '모터 건강 상태';
COMMENT ON COLUMN telemetry.robot_current_state.health_storage IS '저장장치 건강 상태';
COMMENT ON COLUMN telemetry.robot_current_state.overall_health_status IS '서버 계산 NORMAL, WARNING, CRITICAL';
COMMENT ON COLUMN telemetry.robot_current_state.last_telemetry_message_id IS '최근 텔레메트리';
COMMENT ON COLUMN telemetry.robot_current_state.telemetry_sampled_at IS '최근 표본 시각';
COMMENT ON COLUMN telemetry.robot_current_state.telemetry_received_at IS '최근 표본 서버 수신 시각';
COMMENT ON COLUMN telemetry.robot_current_state.position_valid IS '현재 지도 위치 유효 여부';
COMMENT ON COLUMN telemetry.robot_current_state.location IS '현재 유효 위치';
COMMENT ON COLUMN telemetry.robot_current_state.location_accuracy_m IS 'GPS 오차';
COMMENT ON COLUMN telemetry.robot_current_state.heading_deg IS '진행 방향';
COMMENT ON COLUMN telemetry.robot_current_state.motion_moving IS '이동 여부';
COMMENT ON COLUMN telemetry.robot_current_state.speed_mps IS '현재 속도';
COMMENT ON COLUMN telemetry.robot_current_state.battery_source IS 'MEASURED, ESTIMATED';
COMMENT ON COLUMN telemetry.robot_current_state.battery_voltage_v IS '전압';
COMMENT ON COLUMN telemetry.robot_current_state.battery_current_a IS '전류';
COMMENT ON COLUMN telemetry.robot_current_state.battery_charging IS '충전 여부';
COMMENT ON COLUMN telemetry.robot_current_state.battery_temperature_c IS '배터리 온도';
COMMENT ON COLUMN telemetry.robot_current_state.estimated_runtime_sec IS '예상 잔여 운용시간';
COMMENT ON COLUMN telemetry.robot_current_state.jetson_temperature_c IS 'Jetson 온도';
COMMENT ON COLUMN telemetry.robot_current_state.environment_temperature_c IS '외기 온도';
COMMENT ON COLUMN telemetry.robot_current_state.environment_humidity_pct IS '외기 습도';
COMMENT ON COLUMN telemetry.robot_current_state.mission_elapsed_sec IS '임무 경과시간';
COMMENT ON COLUMN telemetry.robot_current_state.mission_incident_count IS '임무 사건 수';
COMMENT ON COLUMN telemetry.robot_current_state.charging_status IS 'NOT_CHARGING, RETURNING, CHARGING, FULL';
COMMENT ON COLUMN telemetry.robot_current_state.state_raw_payload IS '최근 State 원본';
COMMENT ON COLUMN telemetry.robot_current_state.updated_at IS 'Read Model 갱신 시각';

-- telemetry.robot_event: incident·battery·safety·mission 사건의 공통 식별자·부팅·순번·시각·원본을 저장한다.
CREATE TABLE telemetry.robot_event (
    event_id UUID NOT NULL PRIMARY KEY,
    schema_version VARCHAR(20) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    topic_category VARCHAR(20) NOT NULL,
    robot_id UUID NOT NULL,
    boot_id UUID NOT NULL,
    seq BIGINT NOT NULL,
    source_component VARCHAR(30) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    mission_id UUID,
    server_severity VARCHAR(20),
    duplicate_count INTEGER DEFAULT 0 NOT NULL,
    last_received_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    raw_payload JSONB NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT uq_robot_event_boot_seq UNIQUE (robot_id, boot_id, seq),
    CONSTRAINT ck_robot_event_category CHECK (topic_category IN ('INCIDENT','BATTERY','SAFETY','MISSION')),
    CONSTRAINT ck_robot_event_component CHECK (source_component IN ('ai','power','safety','mission')),
    CONSTRAINT ck_robot_event_severity CHECK (server_severity IS NULL OR server_severity IN ('INFO','WARNING','CRITICAL')),
    CONSTRAINT ck_robot_event_duplicate_count CHECK (duplicate_count >= 0),
    CONSTRAINT ck_robot_event_times CHECK (sent_at >= occurred_at)
);
COMMENT ON TABLE telemetry.robot_event IS 'incident·battery·safety·mission 사건의 공통 식별자·부팅·순번·시각·원본을 저장한다.';
COMMENT ON COLUMN telemetry.robot_event.event_id IS 'MQTT event_id. QoS 1 멱등 키';
COMMENT ON COLUMN telemetry.robot_event.schema_version IS '페이로드 계약 버전';
COMMENT ON COLUMN telemetry.robot_event.event_type IS '사건 유형';
COMMENT ON COLUMN telemetry.robot_event.topic_category IS 'INCIDENT, BATTERY, SAFETY, MISSION';
COMMENT ON COLUMN telemetry.robot_event.robot_id IS '로봇';
COMMENT ON COLUMN telemetry.robot_event.boot_id IS '부팅 세션';
COMMENT ON COLUMN telemetry.robot_event.seq IS '부팅 내 공용 순번';
COMMENT ON COLUMN telemetry.robot_event.source_component IS 'ai, power, safety, mission';
COMMENT ON COLUMN telemetry.robot_event.occurred_at IS '실제 사건 발생 시각';
COMMENT ON COLUMN telemetry.robot_event.sent_at IS 'MQTT 발행 시각';
COMMENT ON COLUMN telemetry.robot_event.received_at IS '서버 수신 시각';
COMMENT ON COLUMN telemetry.robot_event.mission_id IS '관련 임무';
COMMENT ON COLUMN telemetry.robot_event.server_severity IS '서버 계산 INFO, WARNING, CRITICAL';
COMMENT ON COLUMN telemetry.robot_event.duplicate_count IS '동일 event_id 재수신 수';
COMMENT ON COLUMN telemetry.robot_event.last_received_at IS '마지막 중복 수신 시각';
COMMENT ON COLUMN telemetry.robot_event.raw_payload IS '원본 MQTT JSON';
COMMENT ON COLUMN telemetry.robot_event.created_at IS '최초 저장 시각';

-- telemetry.robot_battery_event: 배터리 잔량 표본이 아닌 LOW·CRITICAL·RECOVERED·센서 오류 등의 상태 전이 상세를 저장한다.
CREATE TABLE telemetry.robot_battery_event (
    event_id UUID NOT NULL PRIMARY KEY,
    previous_state VARCHAR(20),
    battery_valid BOOLEAN NOT NULL,
    battery_percent NUMERIC(5,2),
    battery_state VARCHAR(20) NOT NULL,
    threshold_pct NUMERIC(5,2),
    action_mission_state VARCHAR(30),
    action_robot_mode VARCHAR(40),
    CONSTRAINT ck_battery_event_previous CHECK (previous_state IS NULL OR previous_state IN ('NORMAL','LOW','CRITICAL','RECOVERED','UNKNOWN')),
    CONSTRAINT ck_battery_event_state CHECK (battery_state IN ('NORMAL','LOW','CRITICAL','RECOVERED','UNKNOWN')),
    CONSTRAINT ck_battery_event_percent CHECK (battery_percent IS NULL OR battery_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_battery_event_threshold CHECK (threshold_pct IS NULL OR threshold_pct BETWEEN 0 AND 100)
);
COMMENT ON TABLE telemetry.robot_battery_event IS '배터리 잔량 표본이 아닌 LOW·CRITICAL·RECOVERED·센서 오류 등의 상태 전이 상세를 저장한다.';
COMMENT ON COLUMN telemetry.robot_battery_event.event_id IS '공통 사건 ID';
COMMENT ON COLUMN telemetry.robot_battery_event.previous_state IS '이전 배터리 상태';
COMMENT ON COLUMN telemetry.robot_battery_event.battery_valid IS '배터리 값 유효 여부';
COMMENT ON COLUMN telemetry.robot_battery_event.battery_percent IS '사건 시 배터리 잔량';
COMMENT ON COLUMN telemetry.robot_battery_event.battery_state IS '현재 상태';
COMMENT ON COLUMN telemetry.robot_battery_event.threshold_pct IS '전이 임계값';
COMMENT ON COLUMN telemetry.robot_battery_event.action_mission_state IS '로봇이 적용한 임무 상태';
COMMENT ON COLUMN telemetry.robot_battery_event.action_robot_mode IS '로봇이 적용한 운행 모드';

-- telemetry.robot_safety_event: 장애물 정지·경계 소실·센서 오류 등의 안전 상태 전이와 측정·자동 재개 가능 여부를 저장한다.
CREATE TABLE telemetry.robot_safety_event (
    event_id UUID NOT NULL PRIMARY KEY,
    reason_code VARCHAR(80) NOT NULL,
    distance_m REAL,
    mode_before VARCHAR(40),
    mode_after VARCHAR(40),
    auto_resume_allowed BOOLEAN,
    CONSTRAINT ck_safety_distance CHECK (distance_m IS NULL OR distance_m >= 0)
);
COMMENT ON TABLE telemetry.robot_safety_event IS '장애물 정지·경계 소실·센서 오류 등의 안전 상태 전이와 측정·자동 재개 가능 여부를 저장한다.';
COMMENT ON COLUMN telemetry.robot_safety_event.event_id IS '공통 사건 ID';
COMMENT ON COLUMN telemetry.robot_safety_event.reason_code IS 'OBSTACLE, BOUNDARY_LOST 등';
COMMENT ON COLUMN telemetry.robot_safety_event.distance_m IS '장애물 거리';
COMMENT ON COLUMN telemetry.robot_safety_event.mode_before IS '전이 전 모드';
COMMENT ON COLUMN telemetry.robot_safety_event.mode_after IS '전이 후 모드';
COMMENT ON COLUMN telemetry.robot_safety_event.auto_resume_allowed IS '장애물 제거 후 자동 재출발 가능 여부';

-- telemetry.robot_mission_event: 임무 시작·중단·재개·완료·취소·실패 사건의 완료 결과와 정상 탐지 집계를 저장한다.
CREATE TABLE telemetry.robot_mission_event (
    event_id UUID NOT NULL PRIMARY KEY,
    duration_sec INTEGER,
    distance_m NUMERIC(12,2),
    incident_count INTEGER,
    normal_detection_count INTEGER,
    completion_reason VARCHAR(80),
    CONSTRAINT ck_mission_event_duration CHECK (duration_sec IS NULL OR duration_sec >= 0),
    CONSTRAINT ck_mission_event_distance CHECK (distance_m IS NULL OR distance_m >= 0),
    CONSTRAINT ck_mission_event_counts CHECK ((incident_count IS NULL OR incident_count >= 0) AND (normal_detection_count IS NULL OR normal_detection_count >= 0))
);
COMMENT ON TABLE telemetry.robot_mission_event IS '임무 시작·중단·재개·완료·취소·실패 사건의 완료 결과와 정상 탐지 집계를 저장한다.';
COMMENT ON COLUMN telemetry.robot_mission_event.event_id IS '공통 사건 ID';
COMMENT ON COLUMN telemetry.robot_mission_event.duration_sec IS '임무 수행시간';
COMMENT ON COLUMN telemetry.robot_mission_event.distance_m IS '임무 이동 거리';
COMMENT ON COLUMN telemetry.robot_mission_event.incident_count IS '이상 사건 수';
COMMENT ON COLUMN telemetry.robot_mission_event.normal_detection_count IS '정상 블록 탐지 집계';
COMMENT ON COLUMN telemetry.robot_mission_event.completion_reason IS 'ROUTE_COMPLETED 등';

-- robot.mission_status_history: 관리자·로봇·시스템에 의한 임무 상태 변화를 원인 사건과 함께 보존한다.
CREATE TABLE robot.mission_status_history (
    history_id BIGSERIAL NOT NULL PRIMARY KEY,
    mission_id UUID NOT NULL,
    robot_event_id UUID,
    before_status VARCHAR(20),
    after_status VARCHAR(20) NOT NULL,
    change_source VARCHAR(20) NOT NULL,
    changed_by UUID,
    reason_code VARCHAR(50),
    reason TEXT,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT ck_mission_history_status_before CHECK (before_status IS NULL OR before_status IN ('READY','ASSIGNED','RUNNING','PAUSED','COMPLETED','CANCELLED','FAILED')),
    CONSTRAINT ck_mission_history_status_after CHECK (after_status IN ('READY','ASSIGNED','RUNNING','PAUSED','COMPLETED','CANCELLED','FAILED')),
    CONSTRAINT ck_mission_history_source CHECK (change_source IN ('ADMIN','ROBOT','SYSTEM'))
);
COMMENT ON TABLE robot.mission_status_history IS '관리자·로봇·시스템에 의한 임무 상태 변화를 원인 사건과 함께 보존한다.';
COMMENT ON COLUMN robot.mission_status_history.history_id IS '이력 식별자';
COMMENT ON COLUMN robot.mission_status_history.mission_id IS '임무';
COMMENT ON COLUMN robot.mission_status_history.robot_event_id IS '원인이 된 MQTT 임무 사건';
COMMENT ON COLUMN robot.mission_status_history.before_status IS '이전 상태';
COMMENT ON COLUMN robot.mission_status_history.after_status IS '변경 상태';
COMMENT ON COLUMN robot.mission_status_history.change_source IS 'ADMIN, ROBOT, SYSTEM';
COMMENT ON COLUMN robot.mission_status_history.changed_by IS '관리자 변경자';
COMMENT ON COLUMN robot.mission_status_history.reason_code IS '변경 이유 코드';
COMMENT ON COLUMN robot.mission_status_history.reason IS '변경 사유';
COMMENT ON COLUMN robot.mission_status_history.occurred_at IS '실제 변경 시각';
COMMENT ON COLUMN robot.mission_status_history.created_at IS '저장 시각';

-- robot.mission_trajectory: 텔레메트리 위치를 LineString으로 집계해 관제 지도에서 계획 경로와 실제 궤적을 빠르게 표시한다.
CREATE TABLE robot.mission_trajectory (
    mission_id UUID NOT NULL PRIMARY KEY,
    trajectory_geometry GEOMETRY(LINESTRING,4326),
    point_count INTEGER DEFAULT 0 NOT NULL,
    distance_m NUMERIC(12,2) DEFAULT 0 NOT NULL,
    first_point_at TIMESTAMPTZ,
    last_point_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT ck_trajectory_point_count CHECK (point_count >= 0),
    CONSTRAINT ck_trajectory_distance CHECK (distance_m >= 0)
);
COMMENT ON TABLE robot.mission_trajectory IS '텔레메트리 위치를 LineString으로 집계해 관제 지도에서 계획 경로와 실제 궤적을 빠르게 표시한다.';
COMMENT ON COLUMN robot.mission_trajectory.mission_id IS '임무';
COMMENT ON COLUMN robot.mission_trajectory.trajectory_geometry IS '실제 이동 궤적';
COMMENT ON COLUMN robot.mission_trajectory.point_count IS '포인트 수';
COMMENT ON COLUMN robot.mission_trajectory.distance_m IS '집계 거리';
COMMENT ON COLUMN robot.mission_trajectory.first_point_at IS '첫 포인트 시각';
COMMENT ON COLUMN robot.mission_trajectory.last_point_at IS '마지막 포인트 시각';
COMMENT ON COLUMN robot.mission_trajectory.updated_at IS '집계 갱신 시각';

-- robot.robot_command_result: commands/result의 ACCEPTED·COMPLETED·FAILED·REJECTED·EXPIRED 결과를 원본 명령과 연결한다.
CREATE TABLE robot.robot_command_result (
    result_id UUID NOT NULL PRIMARY KEY,
    command_id UUID NOT NULL,
    robot_id UUID NOT NULL,
    result_status VARCHAR(20) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    server_received_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    reason_code VARCHAR(80) NOT NULL,
    state_mode VARCHAR(40),
    state_reason_code VARCHAR(80),
    raw_payload JSONB NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT ck_command_result_status CHECK (result_status IN ('ACCEPTED','COMPLETED','FAILED','REJECTED','EXPIRED')),
    CONSTRAINT ck_command_result_times CHECK (completed_at IS NULL OR completed_at >= received_at)
);
COMMENT ON TABLE robot.robot_command_result IS 'commands/result의 ACCEPTED·COMPLETED·FAILED·REJECTED·EXPIRED 결과를 원본 명령과 연결한다.';
COMMENT ON COLUMN robot.robot_command_result.result_id IS 'MQTT result_id';
COMMENT ON COLUMN robot.robot_command_result.command_id IS '원본 명령';
COMMENT ON COLUMN robot.robot_command_result.robot_id IS '응답 로봇';
COMMENT ON COLUMN robot.robot_command_result.result_status IS 'ACCEPTED, COMPLETED, FAILED, REJECTED, EXPIRED';
COMMENT ON COLUMN robot.robot_command_result.received_at IS '로봇이 명령을 받은 시각';
COMMENT ON COLUMN robot.robot_command_result.completed_at IS '실행 완료 시각';
COMMENT ON COLUMN robot.robot_command_result.server_received_at IS '서버 MQTT 수신 시각';
COMMENT ON COLUMN robot.robot_command_result.reason_code IS 'OK, SAFETY_CONDITION_NOT_CLEARED 등';
COMMENT ON COLUMN robot.robot_command_result.state_mode IS '실행 직후 로봇 모드';
COMMENT ON COLUMN robot.robot_command_result.state_reason_code IS '실행 직후 정지·상태 이유';
COMMENT ON COLUMN robot.robot_command_result.raw_payload IS '원본 MQTT JSON';
COMMENT ON COLUMN robot.robot_command_result.created_at IS '저장 시각';

-- storage.robot_image: POST /api/v1/images의 event_id 멱등 업로드와 MQTT image_id 참조를 연결한다.
CREATE TABLE storage.robot_image (
    image_id UUID DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    event_id UUID NOT NULL,
    robot_id UUID NOT NULL,
    linked_robot_event_id UUID,
    image_width INTEGER,
    image_height INTEGER,
    upload_received_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT uq_robot_image_event UNIQUE (event_id),
    CONSTRAINT ck_robot_image_dimensions CHECK ((image_width IS NULL OR image_width > 0) AND (image_height IS NULL OR image_height > 0))
);
COMMENT ON TABLE storage.robot_image IS 'POST /api/v1/images의 event_id 멱등 업로드와 MQTT image_id 참조를 연결한다.';
COMMENT ON COLUMN storage.robot_image.image_id IS '서버가 발급하는 image_id 및 file_id';
COMMENT ON COLUMN storage.robot_image.event_id IS '사건과의 멱등 연결 키';
COMMENT ON COLUMN storage.robot_image.robot_id IS '업로드 로봇';
COMMENT ON COLUMN storage.robot_image.linked_robot_event_id IS '사후 연결된 MQTT 사건';
COMMENT ON COLUMN storage.robot_image.image_width IS '이미지 폭';
COMMENT ON COLUMN storage.robot_image.image_height IS '이미지 높이';
COMMENT ON COLUMN storage.robot_image.upload_received_at IS 'HTTPS 업로드 수신 시각';
COMMENT ON COLUMN storage.robot_image.created_at IS '생성 시각';
COMMENT ON COLUMN storage.robot_image.updated_at IS '수정 시각';

-- defect.defect: 서로 다른 event_id의 공간적 중복을 병합해 생성하는 실제 관리 대상과 위험도·행정 처리상태를 저장한다.
CREATE TABLE defect.defect (
    defect_id UUID DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    incident_code VARCHAR(40) NOT NULL,
    asset_type VARCHAR(30) DEFAULT 'BRAILLE_BLOCK' NOT NULL,
    block_type VARCHAR(20) NOT NULL,
    defect_type VARCHAR(40) NOT NULL,
    risk_level VARCHAR(20) DEFAULT 'MEDIUM' NOT NULL,
    risk_score NUMERIC(6,3),
    risk_rule_version VARCHAR(30),
    risk_calculated_at TIMESTAMPTZ,
    workflow_status VARCHAR(30) DEFAULT 'NEW' NOT NULL,
    resolution VARCHAR(40),
    resolution_note TEXT,
    location GEOMETRY(POINT,4326) NOT NULL,
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
    CONSTRAINT uq_defect_incident_code UNIQUE (incident_code),
    CONSTRAINT ck_defect_asset_type CHECK (asset_type = 'BRAILLE_BLOCK'),
    CONSTRAINT ck_defect_block_type CHECK (block_type IN ('LINEAR','DOT','UNKNOWN')),
    CONSTRAINT ck_defect_type CHECK (defect_type IN ('DAMAGED','INSTALLATION_INVALID','UNKNOWN_ANOMALY')),
    CONSTRAINT ck_defect_risk_level CHECK (risk_level IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT ck_defect_risk_score CHECK (risk_score IS NULL OR risk_score BETWEEN 0 AND 100),
    CONSTRAINT ck_defect_workflow_status CHECK (workflow_status IN ('NEW','ACCEPTED','IN_PROGRESS','RESOLVED','FALSE_POSITIVE','REJECTED')),
    CONSTRAINT ck_defect_resolution CHECK (resolution IS NULL OR resolution IN ('REPAIRED','CLEANED','DUPLICATE','REINSPECTION_REQUIRED','NOT_APPLICABLE')),
    CONSTRAINT ck_defect_zone CHECK (zone_id ~ '^[A-Z]-[0-9]{2}$'),
    CONSTRAINT ck_defect_times CHECK (last_seen_at >= first_seen_at),
    CONSTRAINT ck_defect_observation_count CHECK (observation_count >= 1),
    CONSTRAINT ck_defect_confidence CHECK (latest_confidence IS NULL OR latest_confidence BETWEEN 0 AND 1)
);
COMMENT ON TABLE defect.defect IS '서로 다른 event_id의 공간적 중복을 병합해 생성하는 실제 관리 대상과 위험도·행정 처리상태를 저장한다.';
COMMENT ON COLUMN defect.defect.defect_id IS '내부 결함 식별자';
COMMENT ON COLUMN defect.defect.incident_code IS '외부 표시 코드. 예: INC-20260723-000031';
COMMENT ON COLUMN defect.defect.asset_type IS '시설물 유형';
COMMENT ON COLUMN defect.defect.block_type IS 'LINEAR, DOT, UNKNOWN';
COMMENT ON COLUMN defect.defect.defect_type IS 'DAMAGED 및 예약 결함 유형';
COMMENT ON COLUMN defect.defect.risk_level IS '서버 계산 LOW, MEDIUM, HIGH, CRITICAL';
COMMENT ON COLUMN defect.defect.risk_score IS '위험도 점수';
COMMENT ON COLUMN defect.defect.risk_rule_version IS '위험도 규칙 버전';
COMMENT ON COLUMN defect.defect.risk_calculated_at IS '위험도 계산 시각';
COMMENT ON COLUMN defect.defect.workflow_status IS 'NEW, ACCEPTED, IN_PROGRESS, RESOLVED, FALSE_POSITIVE, REJECTED';
COMMENT ON COLUMN defect.defect.resolution IS 'REPAIRED, CLEANED, DUPLICATE, REINSPECTION_REQUIRED, NOT_APPLICABLE';
COMMENT ON COLUMN defect.defect.resolution_note IS '처리 결과 설명';
COMMENT ON COLUMN defect.defect.location IS '대표 위치';
COMMENT ON COLUMN defect.defect.location_accuracy_m IS '대표 위치 오차';
COMMENT ON COLUMN defect.defect.zone_id IS '구역 코드';
COMMENT ON COLUMN defect.defect.patrol_area_id IS '매핑된 순찰 구역';
COMMENT ON COLUMN defect.defect.patrol_route_id IS '매핑된 순찰 경로';
COMMENT ON COLUMN defect.defect.location_label IS '관리자 표시 위치명';
COMMENT ON COLUMN defect.defect.first_seen_at IS '최초 관측 시각';
COMMENT ON COLUMN defect.defect.last_seen_at IS '최근 관측 시각';
COMMENT ON COLUMN defect.defect.observation_count IS '관측 횟수 캐시';
COMMENT ON COLUMN defect.defect.latest_confidence IS '최근 AI 신뢰도';
COMMENT ON COLUMN defect.defect.review_required IS '수동 검토 필요 여부';
COMMENT ON COLUMN defect.defect.merged_into_defect_id IS '병합 대상 결함';
COMMENT ON COLUMN defect.defect.created_at IS '생성 시각';
COMMENT ON COLUMN defect.defect.updated_at IS '수정 시각';

-- defect.defect_observation: events/incident의 불변 관측 사실과 AI 모델·이미지·BBox·파손 비율을 실제 관리 결함에 연결한다.
CREATE TABLE defect.defect_observation (
    event_id UUID NOT NULL PRIMARY KEY,
    defect_id UUID NOT NULL,
    location_valid BOOLEAN NOT NULL,
    location GEOMETRY(POINT,4326),
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
    CONSTRAINT ck_observation_zone CHECK (zone_id ~ '^[A-Z]-[0-9]{2}$'),
    CONSTRAINT ck_observation_asset CHECK (asset_type = 'BRAILLE_BLOCK'),
    CONSTRAINT ck_observation_block CHECK (block_type IN ('LINEAR','DOT','UNKNOWN')),
    CONSTRAINT ck_observation_defect_type CHECK (detected_defect_type IN ('DAMAGED','INSTALLATION_INVALID','UNKNOWN_ANOMALY')),
    CONSTRAINT ck_observation_confidence CHECK (confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_observation_damage_ratio CHECK (damage_ratio IS NULL OR damage_ratio BETWEEN 0 AND 1),
    CONSTRAINT ck_observation_position CHECK ((location_valid = TRUE AND location IS NOT NULL) OR location_valid = FALSE),
    CONSTRAINT ck_observation_image_dims CHECK ((image_width IS NULL OR image_width > 0) AND (image_height IS NULL OR image_height > 0))
);
COMMENT ON TABLE defect.defect_observation IS 'events/incident의 불변 관측 사실과 AI 모델·이미지·BBox·파손 비율을 실제 관리 결함에 연결한다.';
COMMENT ON COLUMN defect.defect_observation.event_id IS 'MQTT incident event_id';
COMMENT ON COLUMN defect.defect_observation.defect_id IS '병합된 관리 결함';
COMMENT ON COLUMN defect.defect_observation.location_valid IS '위치 유효 여부';
COMMENT ON COLUMN defect.defect_observation.location IS '첫 탐지 위치';
COMMENT ON COLUMN defect.defect_observation.location_accuracy_m IS 'GPS 오차';
COMMENT ON COLUMN defect.defect_observation.zone_id IS '구역 코드';
COMMENT ON COLUMN defect.defect_observation.asset_type IS 'BRAILLE_BLOCK';
COMMENT ON COLUMN defect.defect_observation.block_type IS 'LINEAR, DOT, UNKNOWN';
COMMENT ON COLUMN defect.defect_observation.detected_defect_type IS 'AI가 보낸 결함 유형';
COMMENT ON COLUMN defect.defect_observation.confidence IS 'AI 신뢰도';
COMMENT ON COLUMN defect.defect_observation.damage_ratio IS '파손 면적 비율';
COMMENT ON COLUMN defect.defect_observation.attributes IS 'AI 확장 속성';
COMMENT ON COLUMN defect.defect_observation.image_id IS '근거 이미지';
COMMENT ON COLUMN defect.defect_observation.evidence_captured_at IS '대표 프레임 촬영 시각';
COMMENT ON COLUMN defect.defect_observation.image_width IS '이미지 폭';
COMMENT ON COLUMN defect.defect_observation.image_height IS '이미지 높이';
COMMENT ON COLUMN defect.defect_observation.bbox_norm IS '[x_min,y_min,x_max,y_max] 정규화 좌표';
COMMENT ON COLUMN defect.defect_observation.model_name IS 'AI 모델명';
COMMENT ON COLUMN defect.defect_observation.model_version IS 'AI 모델 버전';
COMMENT ON COLUMN defect.defect_observation.is_representative IS '결함 대표 관측 여부';
COMMENT ON COLUMN defect.defect_observation.created_at IS '생성 시각';

-- defect.defect_status_history: 관리자 처리상태·유형·위험도·위치·처리 결과 변경의 전후 값과 사유를 저장한다.
CREATE TABLE defect.defect_status_history (
    history_id BIGSERIAL NOT NULL PRIMARY KEY,
    defect_id UUID NOT NULL,
    changed_by UUID,
    change_type VARCHAR(40) NOT NULL,
    before_status VARCHAR(30),
    after_status VARCHAR(30),
    before_values JSONB,
    after_values JSONB,
    reason TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT ck_defect_history_change_type CHECK (change_type IN ('STATUS_CHANGED','TYPE_CHANGED','RISK_CHANGED','LOCATION_CHANGED','RESOLUTION_CHANGED','MERGED','UNMERGED'))
);
COMMENT ON TABLE defect.defect_status_history IS '관리자 처리상태·유형·위험도·위치·처리 결과 변경의 전후 값과 사유를 저장한다.';
COMMENT ON COLUMN defect.defect_status_history.history_id IS '이력 식별자';
COMMENT ON COLUMN defect.defect_status_history.defect_id IS '대상 결함';
COMMENT ON COLUMN defect.defect_status_history.changed_by IS '변경 관리자';
COMMENT ON COLUMN defect.defect_status_history.change_type IS 'STATUS_CHANGED, TYPE_CHANGED, RISK_CHANGED, LOCATION_CHANGED, RESOLUTION_CHANGED, MERGED, UNMERGED';
COMMENT ON COLUMN defect.defect_status_history.before_status IS '이전 행정 상태';
COMMENT ON COLUMN defect.defect_status_history.after_status IS '변경 행정 상태';
COMMENT ON COLUMN defect.defect_status_history.before_values IS '변경 전 Snapshot';
COMMENT ON COLUMN defect.defect_status_history.after_values IS '변경 후 Snapshot';
COMMENT ON COLUMN defect.defect_status_history.reason IS '변경 사유';
COMMENT ON COLUMN defect.defect_status_history.created_at IS '변경 시각';

-- defect.defect_merge_history: 서로 다른 event_id의 동일 위치·동일 유형 결함을 병합하거나 취소한 과정을 추적한다.
CREATE TABLE defect.defect_merge_history (
    merge_id UUID DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    source_defect_id UUID NOT NULL,
    target_defect_id UUID NOT NULL,
    merge_status VARCHAR(20) DEFAULT 'MERGED' NOT NULL,
    merge_reason TEXT,
    merged_by UUID NOT NULL,
    merged_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    reverted_by UUID,
    reverted_at TIMESTAMPTZ,
    revert_reason TEXT,
    CONSTRAINT ck_defect_merge_status CHECK (merge_status IN ('MERGED','REVERTED')),
    CONSTRAINT ck_defect_merge_distinct CHECK (source_defect_id <> target_defect_id)
);
COMMENT ON TABLE defect.defect_merge_history IS '서로 다른 event_id의 동일 위치·동일 유형 결함을 병합하거나 취소한 과정을 추적한다.';
COMMENT ON COLUMN defect.defect_merge_history.merge_id IS '병합 이력 식별자';
COMMENT ON COLUMN defect.defect_merge_history.source_defect_id IS '병합된 원본 결함';
COMMENT ON COLUMN defect.defect_merge_history.target_defect_id IS '대표 결함';
COMMENT ON COLUMN defect.defect_merge_history.merge_status IS 'MERGED 또는 REVERTED';
COMMENT ON COLUMN defect.defect_merge_history.merge_reason IS '병합 사유';
COMMENT ON COLUMN defect.defect_merge_history.merged_by IS '병합 관리자';
COMMENT ON COLUMN defect.defect_merge_history.merged_at IS '병합 시각';
COMMENT ON COLUMN defect.defect_merge_history.reverted_by IS '취소 관리자';
COMMENT ON COLUMN defect.defect_merge_history.reverted_at IS '병합 취소 시각';
COMMENT ON COLUMN defect.defect_merge_history.revert_reason IS '취소 사유';

-- report.report_schedule: PERIODIC 보고서 자동 생성 일정과 필터 조건을 관리한다.
CREATE TABLE report.report_schedule (
    report_schedule_id UUID DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    schedule_name VARCHAR(100) NOT NULL,
    cron_expression VARCHAR(100) NOT NULL,
    report_type VARCHAR(20) DEFAULT 'PERIODIC' NOT NULL,
    patrol_area_id UUID,
    filter_criteria JSONB,
    is_active BOOLEAN DEFAULT TRUE NOT NULL,
    last_run_at TIMESTAMPTZ,
    next_run_at TIMESTAMPTZ,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT ck_report_schedule_type CHECK (report_type IN ('PERIODIC','ISSUE'))
);
COMMENT ON TABLE report.report_schedule IS 'PERIODIC 보고서 자동 생성 일정과 필터 조건을 관리한다.';
COMMENT ON COLUMN report.report_schedule.report_schedule_id IS '스케줄 식별자';
COMMENT ON COLUMN report.report_schedule.schedule_name IS '스케줄명';
COMMENT ON COLUMN report.report_schedule.cron_expression IS 'Cron 표현식';
COMMENT ON COLUMN report.report_schedule.report_type IS 'PERIODIC 또는 ISSUE';
COMMENT ON COLUMN report.report_schedule.patrol_area_id IS '대상 순찰 구역';
COMMENT ON COLUMN report.report_schedule.filter_criteria IS '생성 필터';
COMMENT ON COLUMN report.report_schedule.is_active IS '활성 여부';
COMMENT ON COLUMN report.report_schedule.last_run_at IS '최근 실행 시각';
COMMENT ON COLUMN report.report_schedule.next_run_at IS '다음 실행 시각';
COMMENT ON COLUMN report.report_schedule.created_by IS '생성 관리자';
COMMENT ON COLUMN report.report_schedule.created_at IS '생성 시각';
COMMENT ON COLUMN report.report_schedule.updated_at IS '수정 시각';

-- report.anomaly_report: 보고서 목록·검토 상태·생성 방식·기간·생성자·승인 정보를 저장한다.
CREATE TABLE report.anomaly_report (
    report_id UUID DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    report_code VARCHAR(30) NOT NULL,
    title VARCHAR(200) NOT NULL,
    summary VARCHAR(255),
    report_type VARCHAR(20) NOT NULL,
    report_status VARCHAR(20) DEFAULT 'DRAFT' NOT NULL,
    generation_method VARCHAR(20) DEFAULT 'TEMPLATE' NOT NULL,
    creator_type VARCHAR(20) DEFAULT 'USER' NOT NULL,
    report_schedule_id UUID,
    period_from TIMESTAMPTZ NOT NULL,
    period_to TIMESTAMPTZ NOT NULL,
    patrol_area_id UUID,
    filter_criteria JSONB,
    current_version_no INTEGER DEFAULT 1 NOT NULL,
    created_by UUID,
    approved_by UUID,
    approved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT uq_report_code UNIQUE (report_code),
    CONSTRAINT ck_report_type CHECK (report_type IN ('PERIODIC','ISSUE')),
    CONSTRAINT ck_report_status CHECK (report_status IN ('DRAFT','IN_REVIEW','APPROVED','REJECTED')),
    CONSTRAINT ck_report_generation CHECK (generation_method IN ('MANUAL','TEMPLATE','LLM','SYSTEM')),
    CONSTRAINT ck_report_creator_type CHECK (creator_type IN ('USER','SYSTEM')),
    CONSTRAINT ck_report_period CHECK (period_to >= period_from),
    CONSTRAINT ck_report_version_no CHECK (current_version_no >= 1),
    CONSTRAINT ck_report_creator CHECK ((creator_type = 'USER' AND created_by IS NOT NULL) OR creator_type = 'SYSTEM')
);
COMMENT ON TABLE report.anomaly_report IS '보고서 목록·검토 상태·생성 방식·기간·생성자·승인 정보를 저장한다.';
COMMENT ON COLUMN report.anomaly_report.report_id IS '보고서 식별자';
COMMENT ON COLUMN report.anomaly_report.report_code IS '외부 보고서 코드';
COMMENT ON COLUMN report.anomaly_report.title IS '제목';
COMMENT ON COLUMN report.anomaly_report.summary IS '목록 부제';
COMMENT ON COLUMN report.anomaly_report.report_type IS 'PERIODIC 또는 ISSUE';
COMMENT ON COLUMN report.anomaly_report.report_status IS 'DRAFT, IN_REVIEW, APPROVED, REJECTED';
COMMENT ON COLUMN report.anomaly_report.generation_method IS 'MANUAL, TEMPLATE, LLM, SYSTEM';
COMMENT ON COLUMN report.anomaly_report.creator_type IS 'USER 또는 SYSTEM';
COMMENT ON COLUMN report.anomaly_report.report_schedule_id IS '자동 생성 스케줄';
COMMENT ON COLUMN report.anomaly_report.period_from IS '통계 시작 시각';
COMMENT ON COLUMN report.anomaly_report.period_to IS '통계 종료 시각';
COMMENT ON COLUMN report.anomaly_report.patrol_area_id IS '대상 구역';
COMMENT ON COLUMN report.anomaly_report.filter_criteria IS '생성 조건 Snapshot';
COMMENT ON COLUMN report.anomaly_report.current_version_no IS '현재 버전 번호';
COMMENT ON COLUMN report.anomaly_report.created_by IS '생성 관리자';
COMMENT ON COLUMN report.anomaly_report.approved_by IS '승인 관리자';
COMMENT ON COLUMN report.anomaly_report.approved_at IS '승인 시각';
COMMENT ON COLUMN report.anomaly_report.created_at IS '생성 시각';
COMMENT ON COLUMN report.anomaly_report.updated_at IS '수정 시각';

-- report.report_version: 보고서 본문·통계 Snapshot·LLM 및 프롬프트 버전·PDF 파일을 버전별로 보존한다.
CREATE TABLE report.report_version (
    report_version_id UUID DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    report_id UUID NOT NULL,
    version_no INTEGER NOT NULL,
    content TEXT NOT NULL,
    statistics JSONB NOT NULL,
    llm_model VARCHAR(100),
    prompt_version VARCHAR(50),
    change_note TEXT,
    created_by UUID,
    pdf_file_id UUID,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT uq_report_version UNIQUE (report_id, version_no),
    CONSTRAINT ck_report_version_positive CHECK (version_no >= 1)
);
COMMENT ON TABLE report.report_version IS '보고서 본문·통계 Snapshot·LLM 및 프롬프트 버전·PDF 파일을 버전별로 보존한다.';
COMMENT ON COLUMN report.report_version.report_version_id IS '버전 식별자';
COMMENT ON COLUMN report.report_version.report_id IS '보고서';
COMMENT ON COLUMN report.report_version.version_no IS '버전 번호';
COMMENT ON COLUMN report.report_version.content IS '보고서 본문';
COMMENT ON COLUMN report.report_version.statistics IS '재현 가능한 통계 Snapshot';
COMMENT ON COLUMN report.report_version.llm_model IS 'LLM 모델명';
COMMENT ON COLUMN report.report_version.prompt_version IS '프롬프트 버전';
COMMENT ON COLUMN report.report_version.change_note IS '수정 메모';
COMMENT ON COLUMN report.report_version.created_by IS '버전 생성 관리자';
COMMENT ON COLUMN report.report_version.pdf_file_id IS '내보낸 PDF';
COMMENT ON COLUMN report.report_version.created_at IS '생성 시각';

-- report.report_defect_source: 보고서 생성에 사용된 결함 ID를 연결해 근거 추적성을 보장한다.
CREATE TABLE report.report_defect_source (
    report_id UUID NOT NULL,
    defect_id UUID NOT NULL,
    CONSTRAINT pk_report_defect_source PRIMARY KEY (report_id, defect_id)
);
COMMENT ON TABLE report.report_defect_source IS '보고서 생성에 사용된 결함 ID를 연결해 근거 추적성을 보장한다.';
COMMENT ON COLUMN report.report_defect_source.report_id IS '보고서';
COMMENT ON COLUMN report.report_defect_source.defect_id IS '근거 결함';

-- report.report_mission_source: 보고서 생성에 사용된 순찰 임무 ID를 연결한다.
CREATE TABLE report.report_mission_source (
    report_id UUID NOT NULL,
    mission_id UUID NOT NULL,
    CONSTRAINT pk_report_mission_source PRIMARY KEY (report_id, mission_id)
);
COMMENT ON TABLE report.report_mission_source IS '보고서 생성에 사용된 순찰 임무 ID를 연결한다.';
COMMENT ON COLUMN report.report_mission_source.report_id IS '보고서';
COMMENT ON COLUMN report.report_mission_source.mission_id IS '근거 임무';

-- audit.audit_log: 로그인·권한 변경·명령·결함·보고서 등 관리자·시스템 작업의 전후 값과 결과를 추가 전용으로 보존한다.
CREATE TABLE audit.audit_log (
    audit_id BIGSERIAL NOT NULL PRIMARY KEY,
    request_id UUID,
    actor_user_id UUID,
    actor_type VARCHAR(20) NOT NULL,
    action_type VARCHAR(80) NOT NULL,
    target_type VARCHAR(50) NOT NULL,
    target_id UUID,
    before_value JSONB,
    after_value JSONB,
    reason TEXT,
    result VARCHAR(20) NOT NULL,
    ip_address INET,
    user_agent VARCHAR(500),
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT ck_audit_actor_type CHECK (actor_type IN ('USER','SYSTEM','ROBOT')),
    CONSTRAINT ck_audit_result CHECK (result IN ('SUCCESS','FAILURE','DENIED'))
);
COMMENT ON TABLE audit.audit_log IS '로그인·권한 변경·명령·결함·보고서 등 관리자·시스템 작업의 전후 값과 결과를 추가 전용으로 보존한다.';
COMMENT ON COLUMN audit.audit_log.audit_id IS '감사 로그 ID';
COMMENT ON COLUMN audit.audit_log.request_id IS '요청 추적 ID';
COMMENT ON COLUMN audit.audit_log.actor_user_id IS '작업 관리자';
COMMENT ON COLUMN audit.audit_log.actor_type IS 'USER, SYSTEM, ROBOT';
COMMENT ON COLUMN audit.audit_log.action_type IS '작업 코드';
COMMENT ON COLUMN audit.audit_log.target_type IS '대상 유형';
COMMENT ON COLUMN audit.audit_log.target_id IS '대상 UUID';
COMMENT ON COLUMN audit.audit_log.before_value IS '변경 전 값';
COMMENT ON COLUMN audit.audit_log.after_value IS '변경 후 값';
COMMENT ON COLUMN audit.audit_log.reason IS '작업 사유';
COMMENT ON COLUMN audit.audit_log.result IS 'SUCCESS, FAILURE, DENIED';
COMMENT ON COLUMN audit.audit_log.ip_address IS '클라이언트 IP';
COMMENT ON COLUMN audit.audit_log.user_agent IS '브라우저 User-Agent';
COMMENT ON COLUMN audit.audit_log.created_at IS '작업 시각';

-- system.app_setting: 배터리·오프라인·과열·파일 크기·병합 반경 등 운영 임계값을 JSONB로 관리한다.
CREATE TABLE system.app_setting (
    setting_key VARCHAR(100) NOT NULL PRIMARY KEY,
    setting_value JSONB NOT NULL,
    description TEXT,
    updated_by UUID,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL
);
COMMENT ON TABLE system.app_setting IS '배터리·오프라인·과열·파일 크기·병합 반경 등 운영 임계값을 JSONB로 관리한다.';
COMMENT ON COLUMN system.app_setting.setting_key IS '설정 키';
COMMENT ON COLUMN system.app_setting.setting_value IS '설정 값';
COMMENT ON COLUMN system.app_setting.description IS '설정 설명';
COMMENT ON COLUMN system.app_setting.updated_by IS '수정 관리자';
COMMENT ON COLUMN system.app_setting.updated_at IS '수정 시각';

-- system.notification: 로봇 사건·결함·보고서·시스템에서 발생한 상단 알림 원본과 클릭 이동 정보를 저장한다.
CREATE TABLE system.notification (
    notification_id UUID DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    notification_type VARCHAR(40) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    title VARCHAR(200) NOT NULL,
    message TEXT NOT NULL,
    source_type VARCHAR(50) NOT NULL,
    source_id UUID,
    action_url VARCHAR(500),
    metadata JSONB,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT ck_notification_severity CHECK (severity IN ('INFO','WARNING','CRITICAL')),
    CONSTRAINT ck_notification_source CHECK (source_type IN ('ROBOT_EVENT','DEFECT','REPORT','SYSTEM'))
);
COMMENT ON TABLE system.notification IS '로봇 사건·결함·보고서·시스템에서 발생한 상단 알림 원본과 클릭 이동 정보를 저장한다.';
COMMENT ON COLUMN system.notification.notification_id IS '알림 식별자';
COMMENT ON COLUMN system.notification.notification_type IS '알림 유형';
COMMENT ON COLUMN system.notification.severity IS 'INFO, WARNING, CRITICAL';
COMMENT ON COLUMN system.notification.title IS '알림 제목';
COMMENT ON COLUMN system.notification.message IS '알림 내용';
COMMENT ON COLUMN system.notification.source_type IS 'ROBOT_EVENT, DEFECT, REPORT, SYSTEM';
COMMENT ON COLUMN system.notification.source_id IS '다형 대상 ID';
COMMENT ON COLUMN system.notification.action_url IS '클릭 이동 경로';
COMMENT ON COLUMN system.notification.metadata IS '추가 데이터';
COMMENT ON COLUMN system.notification.expires_at IS '알림 만료 시각';
COMMENT ON COLUMN system.notification.created_at IS '생성 시각';

-- system.user_notification: 알림 원본에 대한 사용자별 읽음·숨김 상태를 저장한다.
CREATE TABLE system.user_notification (
    notification_id UUID NOT NULL,
    user_id UUID NOT NULL,
    read_at TIMESTAMPTZ,
    dismissed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT pk_user_notification PRIMARY KEY (notification_id, user_id)
);
COMMENT ON TABLE system.user_notification IS '알림 원본에 대한 사용자별 읽음·숨김 상태를 저장한다.';
COMMENT ON COLUMN system.user_notification.notification_id IS '알림';
COMMENT ON COLUMN system.user_notification.user_id IS '관리자';
COMMENT ON COLUMN system.user_notification.read_at IS '읽음 시각';
COMMENT ON COLUMN system.user_notification.dismissed_at IS '숨김 시각';
COMMENT ON COLUMN system.user_notification.created_at IS '전달 시각';

-- system.outbox_event: DB 트랜잭션과 MQTT 명령·SSE·향후 ingest receipt 발행을 안정적으로 연결한다.
CREATE TABLE system.outbox_event (
    outbox_event_id UUID DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id UUID,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    event_status VARCHAR(20) DEFAULT 'PENDING' NOT NULL,
    retry_count INTEGER DEFAULT 0 NOT NULL,
    available_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    processed_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT ck_outbox_status CHECK (event_status IN ('PENDING','PROCESSING','COMPLETED','FAILED')),
    CONSTRAINT ck_outbox_retry CHECK (retry_count >= 0)
);
COMMENT ON TABLE system.outbox_event IS 'DB 트랜잭션과 MQTT 명령·SSE·향후 ingest receipt 발행을 안정적으로 연결한다.';
COMMENT ON COLUMN system.outbox_event.outbox_event_id IS 'Outbox 이벤트 ID';
COMMENT ON COLUMN system.outbox_event.aggregate_type IS 'Aggregate 유형';
COMMENT ON COLUMN system.outbox_event.aggregate_id IS 'Aggregate ID';
COMMENT ON COLUMN system.outbox_event.event_type IS '내부 이벤트 유형';
COMMENT ON COLUMN system.outbox_event.payload IS '발행 Payload';
COMMENT ON COLUMN system.outbox_event.event_status IS 'PENDING, PROCESSING, COMPLETED, FAILED';
COMMENT ON COLUMN system.outbox_event.retry_count IS '재시도 횟수';
COMMENT ON COLUMN system.outbox_event.available_at IS '처리 가능 시각';
COMMENT ON COLUMN system.outbox_event.processed_at IS '처리 완료 시각';
COMMENT ON COLUMN system.outbox_event.last_error IS '최근 오류';
COMMENT ON COLUMN system.outbox_event.created_at IS '생성 시각';

-- integration.mqtt_ingest_log: MQTT 메시지의 검증·저장·병합·중복·거부·오류 및 향후 receipt 발행 상태를 운영 추적한다.
CREATE TABLE integration.mqtt_ingest_log (
    ingest_id BIGSERIAL NOT NULL PRIMARY KEY,
    direction VARCHAR(10) NOT NULL,
    topic VARCHAR(255) NOT NULL,
    qos SMALLINT NOT NULL,
    retained BOOLEAN NOT NULL,
    robot_code VARCHAR(30),
    schema_version VARCHAR(20),
    message_key UUID,
    boot_id UUID,
    seq BIGINT,
    received_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    processing_status VARCHAR(20) NOT NULL,
    aggregate_type VARCHAR(50),
    aggregate_id UUID,
    incident_code VARCHAR(40),
    error_code VARCHAR(80),
    error_message TEXT,
    raw_payload JSONB,
    receipt_published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT ck_mqtt_ingest_direction CHECK (direction IN ('INBOUND','OUTBOUND')),
    CONSTRAINT ck_mqtt_ingest_qos CHECK (qos IN (0,1)),
    CONSTRAINT ck_mqtt_ingest_status CHECK (processing_status IN ('RECEIVED','STORED','MERGED','DUPLICATE','REJECTED','ERROR'))
);
COMMENT ON TABLE integration.mqtt_ingest_log IS 'MQTT 메시지의 검증·저장·병합·중복·거부·오류 및 향후 receipt 발행 상태를 운영 추적한다.';
COMMENT ON COLUMN integration.mqtt_ingest_log.ingest_id IS '기술 로그 ID';
COMMENT ON COLUMN integration.mqtt_ingest_log.direction IS 'INBOUND 또는 OUTBOUND';
COMMENT ON COLUMN integration.mqtt_ingest_log.topic IS 'MQTT Topic';
COMMENT ON COLUMN integration.mqtt_ingest_log.qos IS 'QoS 0 또는 1';
COMMENT ON COLUMN integration.mqtt_ingest_log.retained IS 'Retain 여부';
COMMENT ON COLUMN integration.mqtt_ingest_log.robot_code IS '로봇 코드 Snapshot';
COMMENT ON COLUMN integration.mqtt_ingest_log.schema_version IS '스키마 버전';
COMMENT ON COLUMN integration.mqtt_ingest_log.message_key IS 'event_id, message_id, command_id 등';
COMMENT ON COLUMN integration.mqtt_ingest_log.boot_id IS '부팅 ID';
COMMENT ON COLUMN integration.mqtt_ingest_log.seq IS '순번';
COMMENT ON COLUMN integration.mqtt_ingest_log.received_at IS '수신 시각';
COMMENT ON COLUMN integration.mqtt_ingest_log.published_at IS '발행 시각';
COMMENT ON COLUMN integration.mqtt_ingest_log.processing_status IS 'RECEIVED, STORED, MERGED, DUPLICATE, REJECTED, ERROR';
COMMENT ON COLUMN integration.mqtt_ingest_log.aggregate_type IS '저장 Aggregate 유형';
COMMENT ON COLUMN integration.mqtt_ingest_log.aggregate_id IS '저장 Aggregate ID';
COMMENT ON COLUMN integration.mqtt_ingest_log.incident_code IS '생성된 결함 코드';
COMMENT ON COLUMN integration.mqtt_ingest_log.error_code IS '오류 코드';
COMMENT ON COLUMN integration.mqtt_ingest_log.error_message IS '오류 상세';
COMMENT ON COLUMN integration.mqtt_ingest_log.raw_payload IS '원본 Payload';
COMMENT ON COLUMN integration.mqtt_ingest_log.receipt_published_at IS 'ingest receipt 발행 시각';
COMMENT ON COLUMN integration.mqtt_ingest_log.created_at IS '생성 시각';

-- Foreign keys
ALTER TABLE auth.admin_user ADD CONSTRAINT fk_admin_profile_file FOREIGN KEY (profile_file_id) REFERENCES storage.file_object(file_id) ON DELETE SET NULL;
ALTER TABLE auth.role ADD CONSTRAINT fk_role_created_by FOREIGN KEY (created_by) REFERENCES auth.admin_user(user_id) ON DELETE SET NULL;
ALTER TABLE auth.user_role ADD CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES auth.admin_user(user_id) ON DELETE CASCADE;
ALTER TABLE auth.user_role ADD CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES auth.role(role_id) ON DELETE CASCADE;
ALTER TABLE auth.user_role ADD CONSTRAINT fk_user_role_assigned_by FOREIGN KEY (assigned_by) REFERENCES auth.admin_user(user_id) ON DELETE SET NULL;
ALTER TABLE auth.menu ADD CONSTRAINT fk_menu_parent FOREIGN KEY (parent_menu_id) REFERENCES auth.menu(menu_id) ON DELETE SET NULL;
ALTER TABLE auth.permission ADD CONSTRAINT fk_permission_menu FOREIGN KEY (menu_id) REFERENCES auth.menu(menu_id) ON DELETE SET NULL;
ALTER TABLE auth.role_permission ADD CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES auth.role(role_id) ON DELETE CASCADE;
ALTER TABLE auth.role_permission ADD CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES auth.permission(permission_id) ON DELETE CASCADE;
ALTER TABLE auth.role_permission ADD CONSTRAINT fk_role_permission_created_by FOREIGN KEY (created_by) REFERENCES auth.admin_user(user_id) ON DELETE SET NULL;
ALTER TABLE auth.user_permission_override ADD CONSTRAINT fk_user_override_user FOREIGN KEY (user_id) REFERENCES auth.admin_user(user_id) ON DELETE CASCADE;
ALTER TABLE auth.user_permission_override ADD CONSTRAINT fk_user_override_permission FOREIGN KEY (permission_id) REFERENCES auth.permission(permission_id) ON DELETE CASCADE;
ALTER TABLE auth.user_permission_override ADD CONSTRAINT fk_user_override_granted_by FOREIGN KEY (granted_by) REFERENCES auth.admin_user(user_id) ON DELETE RESTRICT;
ALTER TABLE robot.robot ADD CONSTRAINT fk_robot_registered_by FOREIGN KEY (registered_by) REFERENCES auth.admin_user(user_id) ON DELETE SET NULL;
ALTER TABLE robot.robot_credential ADD CONSTRAINT fk_robot_credential_robot FOREIGN KEY (robot_id) REFERENCES robot.robot(robot_id) ON DELETE CASCADE;
ALTER TABLE robot.robot_credential ADD CONSTRAINT fk_robot_credential_created_by FOREIGN KEY (created_by) REFERENCES auth.admin_user(user_id) ON DELETE SET NULL;
ALTER TABLE auth.user_robot_assignment ADD CONSTRAINT fk_user_robot_user FOREIGN KEY (user_id) REFERENCES auth.admin_user(user_id) ON DELETE CASCADE;
ALTER TABLE auth.user_robot_assignment ADD CONSTRAINT fk_user_robot_robot FOREIGN KEY (robot_id) REFERENCES robot.robot(robot_id) ON DELETE CASCADE;
ALTER TABLE auth.user_robot_assignment ADD CONSTRAINT fk_user_robot_assigned_by FOREIGN KEY (assigned_by) REFERENCES auth.admin_user(user_id) ON DELETE SET NULL;
ALTER TABLE robot.patrol_area ADD CONSTRAINT fk_patrol_area_created_by FOREIGN KEY (created_by) REFERENCES auth.admin_user(user_id) ON DELETE RESTRICT;
ALTER TABLE robot.patrol_route ADD CONSTRAINT fk_patrol_route_area FOREIGN KEY (patrol_area_id) REFERENCES robot.patrol_area(patrol_area_id) ON DELETE RESTRICT;
ALTER TABLE robot.patrol_route ADD CONSTRAINT fk_patrol_route_created_by FOREIGN KEY (created_by) REFERENCES auth.admin_user(user_id) ON DELETE RESTRICT;
ALTER TABLE robot.mission ADD CONSTRAINT fk_mission_robot FOREIGN KEY (robot_id) REFERENCES robot.robot(robot_id) ON DELETE RESTRICT;
ALTER TABLE robot.mission ADD CONSTRAINT fk_mission_area FOREIGN KEY (patrol_area_id) REFERENCES robot.patrol_area(patrol_area_id) ON DELETE RESTRICT;
ALTER TABLE robot.mission ADD CONSTRAINT fk_mission_route FOREIGN KEY (patrol_route_id) REFERENCES robot.patrol_route(patrol_route_id) ON DELETE SET NULL;
ALTER TABLE robot.mission ADD CONSTRAINT fk_mission_created_by FOREIGN KEY (created_by) REFERENCES auth.admin_user(user_id) ON DELETE RESTRICT;
ALTER TABLE robot.robot_command ADD CONSTRAINT fk_command_robot FOREIGN KEY (robot_id) REFERENCES robot.robot(robot_id) ON DELETE RESTRICT;
ALTER TABLE robot.robot_command ADD CONSTRAINT fk_command_mission FOREIGN KEY (mission_id) REFERENCES robot.mission(mission_id) ON DELETE SET NULL;
ALTER TABLE robot.robot_command ADD CONSTRAINT fk_command_requested_by FOREIGN KEY (requested_by_user_id) REFERENCES auth.admin_user(user_id) ON DELETE SET NULL;
ALTER TABLE telemetry.robot_boot_session ADD CONSTRAINT fk_boot_session_robot FOREIGN KEY (robot_id) REFERENCES robot.robot(robot_id) ON DELETE RESTRICT;
ALTER TABLE telemetry.robot_telemetry ADD CONSTRAINT fk_telemetry_robot FOREIGN KEY (robot_id) REFERENCES robot.robot(robot_id) ON DELETE RESTRICT;
ALTER TABLE telemetry.robot_telemetry ADD CONSTRAINT fk_telemetry_boot FOREIGN KEY (boot_id) REFERENCES telemetry.robot_boot_session(boot_id) ON DELETE RESTRICT;
ALTER TABLE telemetry.robot_telemetry ADD CONSTRAINT fk_telemetry_mission FOREIGN KEY (mission_id) REFERENCES robot.mission(mission_id) ON DELETE SET NULL;
ALTER TABLE telemetry.robot_current_state ADD CONSTRAINT fk_current_state_robot FOREIGN KEY (robot_id) REFERENCES robot.robot(robot_id) ON DELETE CASCADE;
ALTER TABLE telemetry.robot_current_state ADD CONSTRAINT fk_current_state_boot FOREIGN KEY (boot_id) REFERENCES telemetry.robot_boot_session(boot_id) ON DELETE SET NULL;
ALTER TABLE telemetry.robot_current_state ADD CONSTRAINT fk_current_state_mission FOREIGN KEY (mission_id) REFERENCES robot.mission(mission_id) ON DELETE SET NULL;
ALTER TABLE telemetry.robot_current_state ADD CONSTRAINT fk_current_state_telemetry FOREIGN KEY (last_telemetry_message_id) REFERENCES telemetry.robot_telemetry(message_id) ON DELETE SET NULL;
ALTER TABLE telemetry.robot_event ADD CONSTRAINT fk_robot_event_robot FOREIGN KEY (robot_id) REFERENCES robot.robot(robot_id) ON DELETE RESTRICT;
ALTER TABLE telemetry.robot_event ADD CONSTRAINT fk_robot_event_boot FOREIGN KEY (boot_id) REFERENCES telemetry.robot_boot_session(boot_id) ON DELETE RESTRICT;
ALTER TABLE telemetry.robot_event ADD CONSTRAINT fk_robot_event_mission FOREIGN KEY (mission_id) REFERENCES robot.mission(mission_id) ON DELETE SET NULL;
ALTER TABLE telemetry.robot_battery_event ADD CONSTRAINT fk_battery_event_base FOREIGN KEY (event_id) REFERENCES telemetry.robot_event(event_id) ON DELETE CASCADE;
ALTER TABLE telemetry.robot_safety_event ADD CONSTRAINT fk_safety_event_base FOREIGN KEY (event_id) REFERENCES telemetry.robot_event(event_id) ON DELETE CASCADE;
ALTER TABLE telemetry.robot_mission_event ADD CONSTRAINT fk_mission_event_base FOREIGN KEY (event_id) REFERENCES telemetry.robot_event(event_id) ON DELETE CASCADE;
ALTER TABLE robot.mission_status_history ADD CONSTRAINT fk_mission_history_mission FOREIGN KEY (mission_id) REFERENCES robot.mission(mission_id) ON DELETE CASCADE;
ALTER TABLE robot.mission_status_history ADD CONSTRAINT fk_mission_history_event FOREIGN KEY (robot_event_id) REFERENCES telemetry.robot_event(event_id) ON DELETE SET NULL;
ALTER TABLE robot.mission_status_history ADD CONSTRAINT fk_mission_history_changed_by FOREIGN KEY (changed_by) REFERENCES auth.admin_user(user_id) ON DELETE SET NULL;
ALTER TABLE robot.mission_trajectory ADD CONSTRAINT fk_trajectory_mission FOREIGN KEY (mission_id) REFERENCES robot.mission(mission_id) ON DELETE CASCADE;
ALTER TABLE robot.robot_command_result ADD CONSTRAINT fk_command_result_command FOREIGN KEY (command_id) REFERENCES robot.robot_command(command_id) ON DELETE CASCADE;
ALTER TABLE robot.robot_command_result ADD CONSTRAINT fk_command_result_robot FOREIGN KEY (robot_id) REFERENCES robot.robot(robot_id) ON DELETE RESTRICT;
ALTER TABLE storage.robot_image ADD CONSTRAINT fk_robot_image_file FOREIGN KEY (image_id) REFERENCES storage.file_object(file_id) ON DELETE CASCADE;
ALTER TABLE storage.robot_image ADD CONSTRAINT fk_robot_image_robot FOREIGN KEY (robot_id) REFERENCES robot.robot(robot_id) ON DELETE RESTRICT;
ALTER TABLE storage.robot_image ADD CONSTRAINT fk_robot_image_event FOREIGN KEY (linked_robot_event_id) REFERENCES telemetry.robot_event(event_id) ON DELETE SET NULL;
ALTER TABLE defect.defect ADD CONSTRAINT fk_defect_area FOREIGN KEY (patrol_area_id) REFERENCES robot.patrol_area(patrol_area_id) ON DELETE SET NULL;
ALTER TABLE defect.defect ADD CONSTRAINT fk_defect_route FOREIGN KEY (patrol_route_id) REFERENCES robot.patrol_route(patrol_route_id) ON DELETE SET NULL;
ALTER TABLE defect.defect ADD CONSTRAINT fk_defect_merged_into FOREIGN KEY (merged_into_defect_id) REFERENCES defect.defect(defect_id) ON DELETE SET NULL;
ALTER TABLE defect.defect_observation ADD CONSTRAINT fk_observation_event FOREIGN KEY (event_id) REFERENCES telemetry.robot_event(event_id) ON DELETE RESTRICT;
ALTER TABLE defect.defect_observation ADD CONSTRAINT fk_observation_defect FOREIGN KEY (defect_id) REFERENCES defect.defect(defect_id) ON DELETE RESTRICT;
ALTER TABLE defect.defect_observation ADD CONSTRAINT fk_observation_image FOREIGN KEY (image_id) REFERENCES storage.robot_image(image_id) ON DELETE SET NULL;
ALTER TABLE defect.defect_status_history ADD CONSTRAINT fk_defect_history_defect FOREIGN KEY (defect_id) REFERENCES defect.defect(defect_id) ON DELETE CASCADE;
ALTER TABLE defect.defect_status_history ADD CONSTRAINT fk_defect_history_changed_by FOREIGN KEY (changed_by) REFERENCES auth.admin_user(user_id) ON DELETE SET NULL;
ALTER TABLE defect.defect_merge_history ADD CONSTRAINT fk_merge_source FOREIGN KEY (source_defect_id) REFERENCES defect.defect(defect_id) ON DELETE RESTRICT;
ALTER TABLE defect.defect_merge_history ADD CONSTRAINT fk_merge_target FOREIGN KEY (target_defect_id) REFERENCES defect.defect(defect_id) ON DELETE RESTRICT;
ALTER TABLE defect.defect_merge_history ADD CONSTRAINT fk_merge_by FOREIGN KEY (merged_by) REFERENCES auth.admin_user(user_id) ON DELETE RESTRICT;
ALTER TABLE defect.defect_merge_history ADD CONSTRAINT fk_merge_reverted_by FOREIGN KEY (reverted_by) REFERENCES auth.admin_user(user_id) ON DELETE SET NULL;
ALTER TABLE report.report_schedule ADD CONSTRAINT fk_report_schedule_area FOREIGN KEY (patrol_area_id) REFERENCES robot.patrol_area(patrol_area_id) ON DELETE SET NULL;
ALTER TABLE report.report_schedule ADD CONSTRAINT fk_report_schedule_created_by FOREIGN KEY (created_by) REFERENCES auth.admin_user(user_id) ON DELETE RESTRICT;
ALTER TABLE report.anomaly_report ADD CONSTRAINT fk_report_schedule FOREIGN KEY (report_schedule_id) REFERENCES report.report_schedule(report_schedule_id) ON DELETE SET NULL;
ALTER TABLE report.anomaly_report ADD CONSTRAINT fk_report_area FOREIGN KEY (patrol_area_id) REFERENCES robot.patrol_area(patrol_area_id) ON DELETE SET NULL;
ALTER TABLE report.anomaly_report ADD CONSTRAINT fk_report_created_by FOREIGN KEY (created_by) REFERENCES auth.admin_user(user_id) ON DELETE SET NULL;
ALTER TABLE report.anomaly_report ADD CONSTRAINT fk_report_approved_by FOREIGN KEY (approved_by) REFERENCES auth.admin_user(user_id) ON DELETE SET NULL;
ALTER TABLE report.report_version ADD CONSTRAINT fk_report_version_report FOREIGN KEY (report_id) REFERENCES report.anomaly_report(report_id) ON DELETE CASCADE;
ALTER TABLE report.report_version ADD CONSTRAINT fk_report_version_created_by FOREIGN KEY (created_by) REFERENCES auth.admin_user(user_id) ON DELETE SET NULL;
ALTER TABLE report.report_version ADD CONSTRAINT fk_report_version_pdf FOREIGN KEY (pdf_file_id) REFERENCES storage.file_object(file_id) ON DELETE SET NULL;
ALTER TABLE report.report_defect_source ADD CONSTRAINT fk_report_defect_report FOREIGN KEY (report_id) REFERENCES report.anomaly_report(report_id) ON DELETE CASCADE;
ALTER TABLE report.report_defect_source ADD CONSTRAINT fk_report_defect_defect FOREIGN KEY (defect_id) REFERENCES defect.defect(defect_id) ON DELETE RESTRICT;
ALTER TABLE report.report_mission_source ADD CONSTRAINT fk_report_mission_report FOREIGN KEY (report_id) REFERENCES report.anomaly_report(report_id) ON DELETE CASCADE;
ALTER TABLE report.report_mission_source ADD CONSTRAINT fk_report_mission_mission FOREIGN KEY (mission_id) REFERENCES robot.mission(mission_id) ON DELETE RESTRICT;
ALTER TABLE audit.audit_log ADD CONSTRAINT fk_audit_actor FOREIGN KEY (actor_user_id) REFERENCES auth.admin_user(user_id) ON DELETE SET NULL;
ALTER TABLE system.app_setting ADD CONSTRAINT fk_app_setting_updated_by FOREIGN KEY (updated_by) REFERENCES auth.admin_user(user_id) ON DELETE SET NULL;
ALTER TABLE system.user_notification ADD CONSTRAINT fk_user_notification_notification FOREIGN KEY (notification_id) REFERENCES system.notification(notification_id) ON DELETE CASCADE;
ALTER TABLE system.user_notification ADD CONSTRAINT fk_user_notification_user FOREIGN KEY (user_id) REFERENCES auth.admin_user(user_id) ON DELETE CASCADE;

-- Indexes
CREATE INDEX idx_file_object_type_status ON storage.file_object (file_type, upload_status, created_at DESC);
CREATE INDEX idx_admin_user_active ON auth.admin_user (is_active, display_name);
CREATE INDEX idx_role_active_order ON auth.role (is_active, display_order, role_name);
CREATE UNIQUE INDEX uq_user_primary_role ON auth.user_role (user_id) WHERE is_primary = TRUE AND is_active = TRUE;
CREATE INDEX idx_user_role_active ON auth.user_role (role_id, is_active);
CREATE INDEX idx_menu_parent_order ON auth.menu (parent_menu_id, is_active, display_order);
CREATE INDEX idx_permission_resource ON auth.permission (resource_type, resource_code, action, is_active);
CREATE INDEX idx_user_override_expiry ON auth.user_permission_override (expires_at) WHERE expires_at IS NOT NULL;
CREATE INDEX idx_robot_operational_status ON robot.robot (operational_status, robot_code);
CREATE UNIQUE INDEX uq_robot_active_credential ON robot.robot_credential (robot_id, credential_type, principal_name) WHERE credential_status = 'ACTIVE';
CREATE INDEX idx_robot_credential_expiry ON robot.robot_credential (expires_at) WHERE credential_status = 'ACTIVE';
CREATE INDEX idx_user_robot_active ON auth.user_robot_assignment (user_id, is_active);
CREATE INDEX idx_patrol_area_geometry ON robot.patrol_area USING GIST (area_geometry);
CREATE INDEX idx_patrol_area_active ON robot.patrol_area (is_active, zone_id);
CREATE INDEX idx_patrol_route_geometry ON robot.patrol_route USING GIST (route_geometry);
CREATE INDEX idx_patrol_route_area ON robot.patrol_route (patrol_area_id, is_active, display_order);
CREATE INDEX idx_mission_robot_status ON robot.mission (robot_id, mission_status, created_at DESC);
CREATE INDEX idx_mission_area_schedule ON robot.mission (patrol_area_id, scheduled_start_at);
CREATE INDEX idx_command_robot_status ON robot.robot_command (robot_id, command_status, issued_at DESC);
CREATE INDEX idx_command_expiry ON robot.robot_command (expires_at) WHERE command_status IN ('CREATED','PUBLISHED','ACCEPTED');
CREATE INDEX idx_boot_session_robot ON telemetry.robot_boot_session (robot_id, connected_at DESC);
CREATE INDEX idx_boot_session_status ON telemetry.robot_boot_session (presence_status, last_presence_received_at DESC);
CREATE INDEX idx_telemetry_robot_time ON telemetry.robot_telemetry (robot_id, sampled_at DESC);
CREATE INDEX idx_telemetry_mission_time ON telemetry.robot_telemetry (mission_id, sampled_at) WHERE mission_id IS NOT NULL;
CREATE INDEX idx_telemetry_location ON telemetry.robot_telemetry USING GIST (location);
CREATE INDEX idx_current_state_status ON telemetry.robot_current_state (presence_status, overall_health_status, mode);
CREATE INDEX idx_current_state_location ON telemetry.robot_current_state USING GIST (location);
CREATE INDEX idx_robot_event_time ON telemetry.robot_event (robot_id, occurred_at DESC);
CREATE INDEX idx_robot_event_type ON telemetry.robot_event (event_type, occurred_at DESC);
CREATE INDEX idx_robot_event_mission ON telemetry.robot_event (mission_id, occurred_at) WHERE mission_id IS NOT NULL;
CREATE INDEX idx_mission_history_time ON robot.mission_status_history (mission_id, occurred_at DESC);
CREATE INDEX idx_mission_trajectory_geometry ON robot.mission_trajectory USING GIST (trajectory_geometry);
CREATE INDEX idx_command_result_command_time ON robot.robot_command_result (command_id, server_received_at);
CREATE INDEX idx_robot_image_robot_time ON storage.robot_image (robot_id, upload_received_at DESC);
CREATE INDEX idx_defect_location ON defect.defect USING GIST (location);
CREATE INDEX idx_defect_filter ON defect.defect (workflow_status, defect_type, risk_level, last_seen_at DESC);
CREATE INDEX idx_defect_zone_time ON defect.defect (zone_id, last_seen_at DESC);
CREATE INDEX idx_observation_defect_time ON defect.defect_observation (defect_id, created_at DESC);
CREATE INDEX idx_observation_location ON defect.defect_observation USING GIST (location);
CREATE UNIQUE INDEX uq_defect_representative_observation ON defect.defect_observation (defect_id) WHERE is_representative = TRUE;
CREATE INDEX idx_defect_history_time ON defect.defect_status_history (defect_id, created_at DESC);
CREATE INDEX idx_merge_target_time ON defect.defect_merge_history (target_defect_id, merged_at DESC);
CREATE INDEX idx_report_schedule_next_run ON report.report_schedule (is_active, next_run_at);
CREATE INDEX idx_report_list ON report.anomaly_report (report_type, report_status, created_at DESC);
CREATE INDEX idx_report_period ON report.anomaly_report (period_from, period_to);
CREATE INDEX idx_report_version_report ON report.report_version (report_id, version_no DESC);
CREATE INDEX idx_audit_actor_time ON audit.audit_log (actor_user_id, created_at DESC);
CREATE INDEX idx_audit_target_time ON audit.audit_log (target_type, target_id, created_at DESC);
CREATE INDEX idx_audit_action_time ON audit.audit_log (action_type, created_at DESC);
CREATE INDEX idx_notification_time ON system.notification (severity, created_at DESC);
CREATE INDEX idx_user_notification_unread ON system.user_notification (user_id, created_at DESC) WHERE read_at IS NULL AND dismissed_at IS NULL;
CREATE INDEX idx_outbox_pending ON system.outbox_event (event_status, available_at) WHERE event_status IN ('PENDING','FAILED');
CREATE INDEX idx_mqtt_ingest_topic_time ON integration.mqtt_ingest_log (topic, created_at DESC);
CREATE INDEX idx_mqtt_ingest_message_key ON integration.mqtt_ingest_log (message_key) WHERE message_key IS NOT NULL;
CREATE INDEX idx_mqtt_ingest_error ON integration.mqtt_ingest_log (processing_status, created_at DESC) WHERE processing_status IN ('REJECTED','ERROR');

COMMIT;
