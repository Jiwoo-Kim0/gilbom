-- =====================================================================
-- 길봄 DB v1.0 - ERDCloud Import 전용 DDL
-- 원본: PostgreSQL 16 + PostGIS / 41 tables / 9 schemas
--
-- [ERDCloud 변환 규칙]
--  1. 스키마 미지원 -> schema_table 형태로 접두어 병합 (예: auth.role -> auth_role)
--  2. PostGIS 타입 미지원 -> GEOMETRY(...) 를 VARCHAR(255) 로 치환 (주석에 원본 타입 표기)
--  3. ALTER TABLE FK -> 인라인 REFERENCES 로 변환 (ERDCloud가 관계선을 자동 생성)
--  4. TIMESTAMPTZ -> DATETIME, BIGSERIAL -> BIGINT, INET -> VARCHAR(45)
--  5. CHECK / DEFAULT / 부분 INDEX 는 ERD 가독성을 위해 제거
--  6. 테이블 생성 순서를 FK 의존성에 맞게 재배열
-- =====================================================================


-- =====================================================================
-- 1. storage
-- =====================================================================

CREATE TABLE storage_file_object (
    file_id             VARCHAR(36)   NOT NULL COMMENT 'PK 파일 내부 식별자 (UUID)',
    bucket_name         VARCHAR(100)  NOT NULL COMMENT 'S3 버킷명',
    object_key          VARCHAR(500)  NOT NULL COMMENT 'UQ 버킷 내부 객체 키',
    file_type           VARCHAR(30)   NOT NULL COMMENT 'ROBOT_INCIDENT_IMAGE/PROFILE_IMAGE/REPORT_PDF/OTHER',
    original_file_name  VARCHAR(255)  NULL     COMMENT '원본 파일명',
    mime_type           VARCHAR(100)  NOT NULL COMMENT 'MIME 유형',
    size_bytes          BIGINT        NULL     COMMENT '파일 크기(Byte)',
    sha256              VARCHAR(64)   NULL     COMMENT 'SHA-256 무결성 해시',
    anonymized          BOOLEAN       NOT NULL COMMENT '비식별화 완료 여부',
    upload_status       VARCHAR(20)   NOT NULL COMMENT 'PENDING/UPLOADED/FAILED/DELETED',
    uploaded_at         DATETIME      NULL     COMMENT '업로드 완료 시각',
    deleted_at          DATETIME      NULL     COMMENT '논리 삭제 시각',
    created_at          DATETIME      NOT NULL COMMENT '레코드 생성 시각',
    PRIMARY KEY (file_id)
) COMMENT 'S3 파일 메타데이터';


-- =====================================================================
-- 2. auth
-- =====================================================================

CREATE TABLE auth_admin_user (
    user_id             VARCHAR(36)   NOT NULL COMMENT 'PK 내부 사용자 식별자',
    user_code           VARCHAR(30)   NOT NULL COMMENT 'UQ 외부/MQTT 요청자 코드 (ADM-0042)',
    username            VARCHAR(50)   NOT NULL COMMENT 'UQ 로그인 아이디',
    password_hash       VARCHAR(255)  NOT NULL COMMENT '단방향 비밀번호 해시',
    display_name        VARCHAR(100)  NOT NULL COMMENT '관리자 표시 이름',
    email               VARCHAR(150)  NOT NULL COMMENT 'UQ 이메일',
    profile_file_id     VARCHAR(36)   NULL     COMMENT 'FK 프로필 이미지 파일',
    department_name     VARCHAR(100)  NULL     COMMENT '부서명',
    job_title           VARCHAR(100)  NULL     COMMENT '직급/직책',
    office_name         VARCHAR(100)  NULL     COMMENT '근무 조직/지점',
    is_active           BOOLEAN       NOT NULL COMMENT '로그인 가능 여부',
    password_changed_at DATETIME      NULL     COMMENT '최근 비밀번호 변경 시각',
    last_login_at       DATETIME      NULL     COMMENT '최근 로그인 시각',
    deleted_at          DATETIME      NULL     COMMENT '소프트 삭제 시각',
    created_at          DATETIME      NOT NULL COMMENT '가입 시각',
    updated_at          DATETIME      NOT NULL COMMENT '최종 수정 시각',
    PRIMARY KEY (user_id),
    FOREIGN KEY (profile_file_id) REFERENCES storage_file_object (file_id)
) COMMENT '관리자 계정/프로필';


CREATE TABLE auth_role (
    role_id       VARCHAR(36)   NOT NULL COMMENT 'PK 역할 식별자',
    role_code     VARCHAR(50)   NOT NULL COMMENT 'UQ 역할 코드',
    role_name     VARCHAR(100)  NOT NULL COMMENT '화면 표시명',
    description   TEXT          NULL     COMMENT '역할 설명',
    is_system     BOOLEAN       NOT NULL COMMENT '시스템 기본 역할 여부',
    is_active     BOOLEAN       NOT NULL COMMENT '활성 여부',
    display_order INTEGER       NOT NULL COMMENT '표시 순서',
    icon_key      VARCHAR(100)  NULL     COMMENT 'UI 아이콘 키',
    color_hex     VARCHAR(7)    NULL     COMMENT 'UI 표시 색상',
    created_by    VARCHAR(36)   NULL     COMMENT 'FK 역할 생성 관리자',
    created_at    DATETIME      NOT NULL COMMENT '생성 시각',
    updated_at    DATETIME      NOT NULL COMMENT '수정 시각',
    PRIMARY KEY (role_id),
    FOREIGN KEY (created_by) REFERENCES auth_admin_user (user_id)
) COMMENT '역할 마스터';


CREATE TABLE auth_user_role (
    user_id     VARCHAR(36) NOT NULL COMMENT 'PK,FK 관리자',
    role_id     VARCHAR(36) NOT NULL COMMENT 'PK,FK 역할',
    is_primary  BOOLEAN     NOT NULL COMMENT '대표 역할 여부',
    is_active   BOOLEAN     NOT NULL COMMENT '현재 유효 여부',
    assigned_by VARCHAR(36) NULL     COMMENT 'FK 역할 부여 관리자',
    assigned_at DATETIME    NOT NULL COMMENT '역할 부여 시각',
    ended_at    DATETIME    NULL     COMMENT '역할 해제 시각',
    PRIMARY KEY (user_id, role_id),
    FOREIGN KEY (user_id) REFERENCES auth_admin_user (user_id),
    FOREIGN KEY (role_id) REFERENCES auth_role (role_id),
    FOREIGN KEY (assigned_by) REFERENCES auth_admin_user (user_id)
) COMMENT '사용자-역할 연결';


CREATE TABLE auth_menu (
    menu_id        VARCHAR(36)  NOT NULL COMMENT 'PK 메뉴 식별자',
    menu_code      VARCHAR(50)  NOT NULL COMMENT 'UQ 메뉴 코드',
    menu_name      VARCHAR(100) NOT NULL COMMENT '메뉴명',
    menu_type      VARCHAR(20)  NOT NULL COMMENT 'GROUP 또는 PAGE',
    parent_menu_id VARCHAR(36)  NULL     COMMENT 'FK 상위 메뉴 (자기참조)',
    route_path     VARCHAR(255) NULL     COMMENT 'React 라우트 경로',
    icon_key       VARCHAR(100) NULL     COMMENT '아이콘 키',
    display_order  INTEGER      NOT NULL COMMENT '표시 순서',
    is_active      BOOLEAN      NOT NULL COMMENT '활성 여부',
    created_at     DATETIME     NOT NULL COMMENT '생성 시각',
    updated_at     DATETIME     NOT NULL COMMENT '수정 시각',
    PRIMARY KEY (menu_id),
    FOREIGN KEY (parent_menu_id) REFERENCES auth_menu (menu_id)
) COMMENT '관리자 웹 메뉴';


CREATE TABLE auth_permission (
    permission_id   VARCHAR(36)  NOT NULL COMMENT 'PK 권한 식별자',
    permission_code VARCHAR(100) NOT NULL COMMENT 'UQ 권한 코드',
    permission_name VARCHAR(100) NOT NULL COMMENT '권한 표시명',
    description     TEXT         NULL     COMMENT '권한 설명',
    resource_type   VARCHAR(30)  NOT NULL COMMENT 'PAGE/API/ACTION',
    resource_code   VARCHAR(100) NOT NULL COMMENT '대상 리소스 코드',
    action          VARCHAR(30)  NOT NULL COMMENT 'VIEW/CREATE/UPDATE/DELETE/EXECUTE/APPROVE',
    menu_id         VARCHAR(36)  NULL     COMMENT 'FK 연결 메뉴',
    is_active       BOOLEAN      NOT NULL COMMENT '활성 여부',
    created_at      DATETIME     NOT NULL COMMENT '생성 시각',
    updated_at      DATETIME     NOT NULL COMMENT '수정 시각',
    PRIMARY KEY (permission_id),
    FOREIGN KEY (menu_id) REFERENCES auth_menu (menu_id)
) COMMENT '기능 권한 마스터';


CREATE TABLE auth_role_permission (
    role_id       VARCHAR(36) NOT NULL COMMENT 'PK,FK 역할',
    permission_id VARCHAR(36) NOT NULL COMMENT 'PK,FK 권한',
    created_by    VARCHAR(36) NULL     COMMENT 'FK 권한 부여 관리자',
    created_at    DATETIME    NOT NULL COMMENT '권한 부여 시각',
    PRIMARY KEY (role_id, permission_id),
    FOREIGN KEY (role_id) REFERENCES auth_role (role_id),
    FOREIGN KEY (permission_id) REFERENCES auth_permission (permission_id),
    FOREIGN KEY (created_by) REFERENCES auth_admin_user (user_id)
) COMMENT '역할별 기본 권한';


CREATE TABLE auth_user_permission_override (
    user_id       VARCHAR(36) NOT NULL COMMENT 'PK,FK 관리자',
    permission_id VARCHAR(36) NOT NULL COMMENT 'PK,FK 권한',
    effect        VARCHAR(10) NOT NULL COMMENT 'ALLOW 또는 DENY',
    reason        TEXT        NULL     COMMENT '예외 사유',
    expires_at    DATETIME    NULL     COMMENT '임시 권한 만료 시각',
    granted_by    VARCHAR(36) NOT NULL COMMENT 'FK 예외 권한 부여자',
    created_at    DATETIME    NOT NULL COMMENT '생성 시각',
    updated_at    DATETIME    NOT NULL COMMENT '수정 시각',
    PRIMARY KEY (user_id, permission_id),
    FOREIGN KEY (user_id) REFERENCES auth_admin_user (user_id),
    FOREIGN KEY (permission_id) REFERENCES auth_permission (permission_id),
    FOREIGN KEY (granted_by) REFERENCES auth_admin_user (user_id)
) COMMENT '사용자별 권한 예외';


-- =====================================================================
-- 3. robot
-- =====================================================================

CREATE TABLE robot_robot (
    robot_id           VARCHAR(36)  NOT NULL COMMENT 'PK 내부 로봇 식별자',
    robot_code         VARCHAR(30)  NOT NULL COMMENT 'UQ MQTT robot_id (RBT-001)',
    robot_name         VARCHAR(100) NOT NULL COMMENT '관리자 표시명',
    serial_number      VARCHAR(100) NULL     COMMENT 'UQ 기기 시리얼번호',
    mqtt_client_id     VARCHAR(100) NOT NULL COMMENT 'UQ 고정 MQTT Client ID',
    mqtt_username      VARCHAR(100) NOT NULL COMMENT 'UQ 기기별 MQTT 계정',
    hardware_model     VARCHAR(100) NULL     COMMENT '하드웨어 모델',
    firmware_version   VARCHAR(50)  NULL     COMMENT '최근 Presence 보고 펌웨어',
    operational_status VARCHAR(20)  NOT NULL COMMENT 'ACTIVE/INACTIVE/MAINTENANCE/DISABLED',
    registered_by      VARCHAR(36)  NULL     COMMENT 'FK 등록 관리자',
    registered_at      DATETIME     NOT NULL COMMENT '등록 시각',
    created_at         DATETIME     NOT NULL COMMENT '생성 시각',
    updated_at         DATETIME     NOT NULL COMMENT '수정 시각',
    PRIMARY KEY (robot_id),
    FOREIGN KEY (registered_by) REFERENCES auth_admin_user (user_id)
) COMMENT '로봇 기본정보';


CREATE TABLE robot_robot_credential (
    credential_id           VARCHAR(36)  NOT NULL COMMENT 'PK 인증정보 식별자',
    robot_id                VARCHAR(36)  NOT NULL COMMENT 'FK 대상 로봇',
    credential_type         VARCHAR(30)  NOT NULL COMMENT 'MQTT_PASSWORD/IMAGE_API_KEY/TLS_CERT',
    principal_name          VARCHAR(100) NOT NULL COMMENT 'MQTT 사용자명 또는 Key ID',
    secret_hash             VARCHAR(255) NULL     COMMENT '비밀번호/API Key 해시',
    certificate_fingerprint VARCHAR(128) NULL     COMMENT 'TLS 인증서 지문',
    credential_status       VARCHAR(20)  NOT NULL COMMENT 'ACTIVE/EXPIRED/REVOKED',
    valid_from              DATETIME     NOT NULL COMMENT '유효 시작 시각',
    expires_at              DATETIME     NULL     COMMENT '만료 시각',
    last_used_at            DATETIME     NULL     COMMENT '최근 사용 시각',
    created_by              VARCHAR(36)  NULL     COMMENT 'FK 생성 관리자',
    created_at              DATETIME     NOT NULL COMMENT '생성 시각',
    revoked_at              DATETIME     NULL     COMMENT '폐기 시각',
    PRIMARY KEY (credential_id),
    FOREIGN KEY (robot_id) REFERENCES robot_robot (robot_id),
    FOREIGN KEY (created_by) REFERENCES auth_admin_user (user_id)
) COMMENT '로봇 인증정보 (MQTT TLS/API Key)';


CREATE TABLE auth_user_robot_assignment (
    user_id         VARCHAR(36) NOT NULL COMMENT 'PK,FK 관리자',
    robot_id        VARCHAR(36) NOT NULL COMMENT 'PK,FK 담당 로봇',
    assignment_type VARCHAR(30) NOT NULL COMMENT 'OWNER/OPERATOR/VIEWER',
    is_active       BOOLEAN     NOT NULL COMMENT '현재 유효 여부',
    assigned_by     VARCHAR(36) NULL     COMMENT 'FK 배정 관리자',
    assigned_at     DATETIME    NOT NULL COMMENT '배정 시각',
    ended_at        DATETIME    NULL     COMMENT '배정 종료 시각',
    PRIMARY KEY (user_id, robot_id),
    FOREIGN KEY (user_id) REFERENCES auth_admin_user (user_id),
    FOREIGN KEY (robot_id) REFERENCES robot_robot (robot_id),
    FOREIGN KEY (assigned_by) REFERENCES auth_admin_user (user_id)
) COMMENT '관리자 담당 로봇';


CREATE TABLE robot_patrol_area (
    patrol_area_id VARCHAR(36)  NOT NULL COMMENT 'PK 순찰 구역 식별자',
    zone_id        VARCHAR(50)  NOT NULL COMMENT 'UQ 순찰 구역 업무 코드',
    area_name      VARCHAR(100) NOT NULL COMMENT '구역명',
    area_geometry  VARCHAR(255) NOT NULL COMMENT '[PostGIS] GEOMETRY(POLYGON,4326) 구역 Polygon',
    description    TEXT         NULL     COMMENT '구역 설명',
    is_active      BOOLEAN      NOT NULL COMMENT '활성 여부',
    created_by     VARCHAR(36)  NOT NULL COMMENT 'FK 생성 관리자',
    created_at     DATETIME     NOT NULL COMMENT '생성 시각',
    updated_at     DATETIME     NOT NULL COMMENT '수정 시각',
    PRIMARY KEY (patrol_area_id),
    FOREIGN KEY (created_by) REFERENCES auth_admin_user (user_id)
) COMMENT '순찰 구역 (Polygon)';


CREATE TABLE robot_patrol_route (
    patrol_route_id    VARCHAR(36)   NOT NULL COMMENT 'PK 경로 식별자',
    patrol_area_id     VARCHAR(36)   NOT NULL COMMENT 'FK 소속 순찰 구역',
    route_code         VARCHAR(30)   NOT NULL COMMENT 'UQ 경로 코드',
    route_name         VARCHAR(100)  NOT NULL COMMENT '경로명',
    route_geometry     VARCHAR(255)  NOT NULL COMMENT '[PostGIS] GEOMETRY(LINESTRING,4326) 계획 경로',
    planned_distance_m DECIMAL(12,2) NULL     COMMENT '계획 거리(m)',
    display_order      INTEGER       NOT NULL COMMENT '표시 순서',
    is_active          BOOLEAN       NOT NULL COMMENT '활성 여부',
    created_by         VARCHAR(36)   NOT NULL COMMENT 'FK 생성 관리자',
    created_at         DATETIME      NOT NULL COMMENT '생성 시각',
    updated_at         DATETIME      NOT NULL COMMENT '수정 시각',
    PRIMARY KEY (patrol_route_id),
    FOREIGN KEY (patrol_area_id) REFERENCES robot_patrol_area (patrol_area_id),
    FOREIGN KEY (created_by) REFERENCES auth_admin_user (user_id)
) COMMENT '계획 순찰 경로 (LineString)';


CREATE TABLE robot_mission (
    mission_id              VARCHAR(36)   NOT NULL COMMENT 'PK 내부 임무 식별자',
    mission_code            VARCHAR(40)   NOT NULL COMMENT 'UQ MQTT mission_id (MIS-20260723-001)',
    mission_name            VARCHAR(100)  NOT NULL COMMENT '임무명',
    description             TEXT          NULL     COMMENT '임무 설명',
    robot_id                VARCHAR(36)   NOT NULL COMMENT 'FK 수행 로봇',
    patrol_area_id          VARCHAR(36)   NOT NULL COMMENT 'FK 순찰 구역',
    patrol_route_id         VARCHAR(36)   NULL     COMMENT 'FK 계획 경로',
    mission_status          VARCHAR(20)   NOT NULL COMMENT 'READY/ASSIGNED/RUNNING/PAUSED/COMPLETED/CANCELLED/FAILED',
    priority                SMALLINT      NOT NULL COMMENT '우선순위 1~5',
    scheduled_start_at      DATETIME      NULL     COMMENT '예정 시작 시각',
    scheduled_end_at        DATETIME      NULL     COMMENT '예정 종료 시각',
    started_at              DATETIME      NULL     COMMENT '실제 시작 시각',
    completed_at            DATETIME      NULL     COMMENT '완료 시각',
    progress_percent        DECIMAL(5,2)  NOT NULL COMMENT '진행률',
    estimated_completion_at DATETIME      NULL     COMMENT '예상 완료 시각',
    incident_count          INTEGER       NOT NULL COMMENT '임무 이상 사건 수',
    normal_detection_count  INTEGER       NOT NULL COMMENT '정상 점자블록 탐지 집계',
    result_distance_m       DECIMAL(12,2) NULL     COMMENT '임무 완료 거리',
    completion_reason       VARCHAR(50)   NULL     COMMENT '완료/중단 이유',
    created_by              VARCHAR(36)   NOT NULL COMMENT 'FK 생성 관리자',
    created_at              DATETIME      NOT NULL COMMENT '생성 시각',
    updated_at              DATETIME      NOT NULL COMMENT '수정 시각',
    PRIMARY KEY (mission_id),
    FOREIGN KEY (robot_id) REFERENCES robot_robot (robot_id),
    FOREIGN KEY (patrol_area_id) REFERENCES robot_patrol_area (patrol_area_id),
    FOREIGN KEY (patrol_route_id) REFERENCES robot_patrol_route (patrol_route_id),
    FOREIGN KEY (created_by) REFERENCES auth_admin_user (user_id)
) COMMENT '순찰 임무';


CREATE TABLE robot_robot_command (
    command_id              VARCHAR(36) NOT NULL COMMENT 'PK MQTT command_id',
    schema_version          VARCHAR(20) NOT NULL COMMENT 'MQTT 스키마 버전',
    robot_id                VARCHAR(36) NOT NULL COMMENT 'FK 대상 로봇',
    mission_id              VARCHAR(36) NULL     COMMENT 'FK 관련 임무',
    command_type            VARCHAR(40) NOT NULL COMMENT 'START_MISSION/PAUSE/RESUME/CANCEL/EMERGENCY_STOP 등',
    parameters              JSON        NULL     COMMENT 'MQTT parameters',
    requested_by_actor_type VARCHAR(20) NOT NULL COMMENT 'ADMIN 또는 SYSTEM',
    requested_by_user_id    VARCHAR(36) NULL     COMMENT 'FK 관리자 요청자',
    requested_by_actor_code VARCHAR(30) NULL     COMMENT 'MQTT actor_id Snapshot',
    command_status          VARCHAR(20) NOT NULL COMMENT 'CREATED/PUBLISHED/ACCEPTED/COMPLETED/FAILED/REJECTED/EXPIRED',
    issued_at               DATETIME    NOT NULL COMMENT '발급 시각',
    expires_at              DATETIME    NOT NULL COMMENT '만료 시각',
    message_expiry_sec      INTEGER     NOT NULL COMMENT 'MQTT5 message expiry',
    published_at            DATETIME    NULL     COMMENT 'MQTT 발행 시각',
    created_at              DATETIME    NOT NULL COMMENT '생성 시각',
    updated_at              DATETIME    NOT NULL COMMENT '수정 시각',
    PRIMARY KEY (command_id),
    FOREIGN KEY (robot_id) REFERENCES robot_robot (robot_id),
    FOREIGN KEY (mission_id) REFERENCES robot_mission (mission_id),
    FOREIGN KEY (requested_by_user_id) REFERENCES auth_admin_user (user_id)
) COMMENT '로봇 관제 명령';


CREATE TABLE robot_robot_command_result (
    result_id          VARCHAR(36) NOT NULL COMMENT 'PK MQTT result_id',
    command_id         VARCHAR(36) NOT NULL COMMENT 'FK 원본 명령',
    robot_id           VARCHAR(36) NOT NULL COMMENT 'FK 응답 로봇',
    result_status      VARCHAR(20) NOT NULL COMMENT 'ACCEPTED/COMPLETED/FAILED/REJECTED/EXPIRED',
    received_at        DATETIME    NOT NULL COMMENT '로봇이 명령을 받은 시각',
    completed_at       DATETIME    NULL     COMMENT '실행 완료 시각',
    server_received_at DATETIME    NOT NULL COMMENT '서버 MQTT 수신 시각',
    reason_code        VARCHAR(80) NOT NULL COMMENT 'OK/SAFETY_CONDITION_NOT_CLEARED 등',
    state_mode         VARCHAR(40) NULL     COMMENT '실행 직후 로봇 모드',
    state_reason_code  VARCHAR(80) NULL     COMMENT '실행 직후 정지/상태 이유',
    raw_payload        JSON        NOT NULL COMMENT '원본 MQTT JSON',
    created_at         DATETIME    NOT NULL COMMENT '저장 시각',
    PRIMARY KEY (result_id),
    FOREIGN KEY (command_id) REFERENCES robot_robot_command (command_id),
    FOREIGN KEY (robot_id) REFERENCES robot_robot (robot_id)
) COMMENT '로봇 명령 실행 결과';


-- =====================================================================
-- 4. telemetry
-- =====================================================================

CREATE TABLE telemetry_robot_boot_session (
    boot_id                   VARCHAR(36) NOT NULL COMMENT 'PK 로봇이 부팅 시 생성한 UUID',
    robot_id                  VARCHAR(36) NOT NULL COMMENT 'FK 로봇',
    schema_version            VARCHAR(20) NOT NULL COMMENT 'Presence 스키마 버전',
    firmware_version          VARCHAR(50) NULL     COMMENT '부팅 시 펌웨어 버전',
    presence_status           VARCHAR(20) NOT NULL COMMENT 'ONLINE 또는 OFFLINE',
    connected_at              DATETIME    NULL     COMMENT 'Birth 연결 시각',
    disconnected_at           DATETIME    NULL     COMMENT '종료/LWT 서버 수신 시각',
    disconnect_reason         VARCHAR(50) NULL     COMMENT 'UNGRACEFUL_DISCONNECT/GRACEFUL_SHUTDOWN 등',
    graceful_shutdown         BOOLEAN     NULL     COMMENT '정상 종료 여부',
    last_presence_received_at DATETIME    NOT NULL COMMENT '마지막 Presence 수신 시각',
    created_at                DATETIME    NOT NULL COMMENT '생성 시각',
    updated_at                DATETIME    NOT NULL COMMENT '수정 시각',
    PRIMARY KEY (boot_id),
    FOREIGN KEY (robot_id) REFERENCES robot_robot (robot_id)
) COMMENT '로봇 부팅/Presence 세션';


CREATE TABLE telemetry_robot_telemetry (
    message_id                VARCHAR(36)  NOT NULL COMMENT 'PK MQTT message_id',
    schema_version            VARCHAR(20)  NOT NULL COMMENT '페이로드 버전',
    robot_id                  VARCHAR(36)  NOT NULL COMMENT 'FK 로봇',
    boot_id                   VARCHAR(36)  NOT NULL COMMENT 'FK 부팅 세션',
    seq                       BIGINT       NOT NULL COMMENT '부팅 내 공용 순번',
    sampled_at                DATETIME     NOT NULL COMMENT '로봇 표본 시각',
    received_at               DATETIME     NOT NULL COMMENT '서버 수신 시각',
    mission_id                VARCHAR(36)  NULL     COMMENT 'FK 수행 임무',
    position_valid            BOOLEAN      NOT NULL COMMENT 'GPS fix 유효 여부',
    location                  VARCHAR(255) NULL     COMMENT '[PostGIS] GEOMETRY(POINT,4326) 현재 위치',
    location_accuracy_m       FLOAT        NULL     COMMENT 'GPS 오차 추정',
    heading_deg               FLOAT        NULL     COMMENT '진행 방향',
    motion_moving             BOOLEAN      NULL     COMMENT '이동 여부',
    speed_mps                 FLOAT        NULL     COMMENT '현재 속도',
    battery_valid             BOOLEAN      NOT NULL COMMENT '배터리 값 유효 여부',
    battery_percent           DECIMAL(5,2) NULL     COMMENT '배터리 잔량',
    battery_state             VARCHAR(20)  NOT NULL COMMENT 'NORMAL/LOW/CRITICAL/UNKNOWN',
    battery_source            VARCHAR(20)  NULL     COMMENT 'MEASURED 또는 ESTIMATED',
    battery_voltage_v         FLOAT        NULL     COMMENT '배터리 전압',
    battery_current_a         FLOAT        NULL     COMMENT '배터리 전류',
    battery_charging          BOOLEAN      NULL     COMMENT '충전 여부',
    battery_temperature_c     FLOAT        NULL     COMMENT '배터리 온도',
    estimated_runtime_sec     INTEGER      NULL     COMMENT '예상 잔여 운용시간',
    jetson_temperature_c      FLOAT        NOT NULL COMMENT 'Jetson 열 존 최고 온도',
    environment_temperature_c FLOAT        NULL     COMMENT '외기 온도',
    environment_humidity_pct  FLOAT        NULL     COMMENT '외기 습도',
    mission_state             VARCHAR(30)  NULL     COMMENT 'RUNNING/PAUSED 등',
    mission_elapsed_sec       INTEGER      NULL     COMMENT '임무 경과시간',
    mission_incident_count    INTEGER      NULL     COMMENT '현재 임무 사건 수',
    raw_payload               JSON         NOT NULL COMMENT '원본 MQTT JSON',
    created_at                DATETIME     NOT NULL COMMENT '저장 시각',
    PRIMARY KEY (message_id),
    FOREIGN KEY (robot_id) REFERENCES robot_robot (robot_id),
    FOREIGN KEY (boot_id) REFERENCES telemetry_robot_boot_session (boot_id),
    FOREIGN KEY (mission_id) REFERENCES robot_mission (mission_id)
) COMMENT '주기 텔레메트리 (QoS0, 5초)';


CREATE TABLE telemetry_robot_current_state (
    robot_id                  VARCHAR(36)  NOT NULL COMMENT 'PK,FK 로봇',
    boot_id                   VARCHAR(36)  NULL     COMMENT 'FK 현재 부팅 세션',
    presence_status           VARCHAR(20)  NOT NULL COMMENT 'ONLINE/OFFLINE/UNKNOWN',
    presence_reason           VARCHAR(50)  NULL     COMMENT '종료/오프라인 이유',
    presence_received_at      DATETIME     NULL     COMMENT 'Presence 수신 시각',
    state_seq                 BIGINT       NULL     COMMENT '최근 State 순번',
    state_updated_at          DATETIME     NULL     COMMENT '로봇 State 시각',
    state_received_at         DATETIME     NULL     COMMENT '서버 State 수신 시각',
    mode                      VARCHAR(40)  NOT NULL COMMENT 'BOOTING/READY/PATROLLING/PAUSED/SAFE_STOP/ERROR 등',
    reason_code               VARCHAR(80)  NOT NULL COMMENT '정지/오류 원인 코드',
    mission_id                VARCHAR(36)  NULL     COMMENT 'FK 현재 임무',
    mission_state             VARCHAR(30)  NULL     COMMENT '현재 임무 상태',
    battery_valid             BOOLEAN      NOT NULL COMMENT '배터리 유효 여부',
    battery_percent           DECIMAL(5,2) NULL     COMMENT '배터리 잔량',
    battery_state             VARCHAR(20)  NOT NULL COMMENT 'NORMAL/LOW/CRITICAL/UNKNOWN',
    health_camera             VARCHAR(20)  NOT NULL COMMENT '카메라 건강 상태',
    health_lidar              VARCHAR(20)  NOT NULL COMMENT 'LiDAR 건강 상태',
    health_gps                VARCHAR(20)  NOT NULL COMMENT 'GPS 건강 상태',
    health_battery            VARCHAR(20)  NOT NULL COMMENT '배터리 건강 상태',
    health_motor              VARCHAR(20)  NOT NULL COMMENT '모터 건강 상태',
    health_storage            VARCHAR(20)  NOT NULL COMMENT '저장장치 건강 상태',
    overall_health_status     VARCHAR(20)  NOT NULL COMMENT '서버 계산 NORMAL/WARNING/CRITICAL',
    last_telemetry_message_id VARCHAR(36)  NULL     COMMENT 'FK 최근 텔레메트리',
    telemetry_sampled_at      DATETIME     NULL     COMMENT '최근 표본 시각',
    telemetry_received_at     DATETIME     NULL     COMMENT '최근 표본 서버 수신 시각',
    position_valid            BOOLEAN      NOT NULL COMMENT '현재 지도 위치 유효 여부',
    location                  VARCHAR(255) NULL     COMMENT '[PostGIS] GEOMETRY(POINT,4326) 현재 유효 위치',
    location_accuracy_m       FLOAT        NULL     COMMENT 'GPS 오차',
    heading_deg               FLOAT        NULL     COMMENT '진행 방향',
    motion_moving             BOOLEAN      NULL     COMMENT '이동 여부',
    speed_mps                 FLOAT        NULL     COMMENT '현재 속도',
    battery_source            VARCHAR(20)  NULL     COMMENT 'MEASURED/ESTIMATED',
    battery_voltage_v         FLOAT        NULL     COMMENT '전압',
    battery_current_a         FLOAT        NULL     COMMENT '전류',
    battery_charging          BOOLEAN      NULL     COMMENT '충전 여부',
    battery_temperature_c     FLOAT        NULL     COMMENT '배터리 온도',
    estimated_runtime_sec     INTEGER      NULL     COMMENT '예상 잔여 운용시간',
    jetson_temperature_c      FLOAT        NULL     COMMENT 'Jetson 온도',
    environment_temperature_c FLOAT        NULL     COMMENT '외기 온도',
    environment_humidity_pct  FLOAT        NULL     COMMENT '외기 습도',
    mission_elapsed_sec       INTEGER      NULL     COMMENT '임무 경과시간',
    mission_incident_count    INTEGER      NULL     COMMENT '임무 사건 수',
    charging_status           VARCHAR(30)  NOT NULL COMMENT 'NOT_CHARGING/RETURNING/CHARGING/FULL',
    state_raw_payload         JSON         NULL     COMMENT '최근 State 원본',
    updated_at                DATETIME     NOT NULL COMMENT 'Read Model 갱신 시각',
    PRIMARY KEY (robot_id),
    FOREIGN KEY (robot_id) REFERENCES robot_robot (robot_id),
    FOREIGN KEY (boot_id) REFERENCES telemetry_robot_boot_session (boot_id),
    FOREIGN KEY (mission_id) REFERENCES robot_mission (mission_id),
    FOREIGN KEY (last_telemetry_message_id) REFERENCES telemetry_robot_telemetry (message_id)
) COMMENT '로봇 최신 관제 Read Model';


CREATE TABLE telemetry_robot_event (
    event_id         VARCHAR(36) NOT NULL COMMENT 'PK MQTT event_id (QoS1 멱등키)',
    schema_version   VARCHAR(20) NOT NULL COMMENT '페이로드 계약 버전',
    event_type       VARCHAR(80) NOT NULL COMMENT '사건 유형',
    topic_category   VARCHAR(20) NOT NULL COMMENT 'INCIDENT/BATTERY/SAFETY/MISSION',
    robot_id         VARCHAR(36) NOT NULL COMMENT 'FK 로봇',
    boot_id          VARCHAR(36) NOT NULL COMMENT 'FK 부팅 세션',
    seq              BIGINT      NOT NULL COMMENT '부팅 내 공용 순번',
    source_component VARCHAR(30) NOT NULL COMMENT 'ai/power/safety/mission',
    occurred_at      DATETIME    NOT NULL COMMENT '실제 사건 발생 시각',
    sent_at          DATETIME    NOT NULL COMMENT 'MQTT 발행 시각',
    received_at      DATETIME    NOT NULL COMMENT '서버 수신 시각',
    mission_id       VARCHAR(36) NULL     COMMENT 'FK 관련 임무',
    server_severity  VARCHAR(20) NULL     COMMENT '서버 계산 INFO/WARNING/CRITICAL',
    duplicate_count  INTEGER     NOT NULL COMMENT '동일 event_id 재수신 수',
    last_received_at DATETIME    NOT NULL COMMENT '마지막 중복 수신 시각',
    raw_payload      JSON        NOT NULL COMMENT '원본 MQTT JSON',
    created_at       DATETIME    NOT NULL COMMENT '최초 저장 시각',
    PRIMARY KEY (event_id),
    FOREIGN KEY (robot_id) REFERENCES robot_robot (robot_id),
    FOREIGN KEY (boot_id) REFERENCES telemetry_robot_boot_session (boot_id),
    FOREIGN KEY (mission_id) REFERENCES robot_mission (mission_id)
) COMMENT 'MQTT 사건 공통 Envelope';


CREATE TABLE telemetry_robot_battery_event (
    event_id             VARCHAR(36)  NOT NULL COMMENT 'PK,FK 공통 사건 ID',
    previous_state       VARCHAR(20)  NULL     COMMENT '이전 배터리 상태',
    battery_valid        BOOLEAN      NOT NULL COMMENT '배터리 값 유효 여부',
    battery_percent      DECIMAL(5,2) NULL     COMMENT '사건 시 배터리 잔량',
    battery_state        VARCHAR(20)  NOT NULL COMMENT '현재 상태',
    threshold_pct        DECIMAL(5,2) NULL     COMMENT '전이 임계값',
    action_mission_state VARCHAR(30)  NULL     COMMENT '로봇이 적용한 임무 상태',
    action_robot_mode    VARCHAR(40)  NULL     COMMENT '로봇이 적용한 운행 모드',
    PRIMARY KEY (event_id),
    FOREIGN KEY (event_id) REFERENCES telemetry_robot_event (event_id)
) COMMENT '배터리 상태 전이 사건';


CREATE TABLE telemetry_robot_safety_event (
    event_id            VARCHAR(36) NOT NULL COMMENT 'PK,FK 공통 사건 ID',
    reason_code         VARCHAR(80) NOT NULL COMMENT 'OBSTACLE/BOUNDARY_LOST 등',
    distance_m          FLOAT       NULL     COMMENT '장애물 거리',
    mode_before         VARCHAR(40) NULL     COMMENT '전이 전 모드',
    mode_after          VARCHAR(40) NULL     COMMENT '전이 후 모드',
    auto_resume_allowed BOOLEAN     NULL     COMMENT '자동 재출발 가능 여부',
    PRIMARY KEY (event_id),
    FOREIGN KEY (event_id) REFERENCES telemetry_robot_event (event_id)
) COMMENT '주행 안전 사건';


CREATE TABLE telemetry_robot_mission_event (
    event_id               VARCHAR(36)   NOT NULL COMMENT 'PK,FK 공통 사건 ID',
    duration_sec           INTEGER       NULL     COMMENT '임무 수행시간',
    distance_m             DECIMAL(12,2) NULL     COMMENT '임무 이동 거리',
    incident_count         INTEGER       NULL     COMMENT '이상 사건 수',
    normal_detection_count INTEGER       NULL     COMMENT '정상 블록 탐지 집계',
    completion_reason      VARCHAR(80)   NULL     COMMENT 'ROUTE_COMPLETED 등',
    PRIMARY KEY (event_id),
    FOREIGN KEY (event_id) REFERENCES telemetry_robot_event (event_id)
) COMMENT '임무 상태 사건';


CREATE TABLE robot_mission_status_history (
    history_id     BIGINT      NOT NULL COMMENT 'PK 이력 식별자 (BIGSERIAL)',
    mission_id     VARCHAR(36) NOT NULL COMMENT 'FK 임무',
    robot_event_id VARCHAR(36) NULL     COMMENT 'FK 원인이 된 MQTT 임무 사건',
    before_status  VARCHAR(20) NULL     COMMENT '이전 상태',
    after_status   VARCHAR(20) NOT NULL COMMENT '변경 상태',
    change_source  VARCHAR(20) NOT NULL COMMENT 'ADMIN/ROBOT/SYSTEM',
    changed_by     VARCHAR(36) NULL     COMMENT 'FK 관리자 변경자',
    reason_code    VARCHAR(50) NULL     COMMENT '변경 이유 코드',
    reason         TEXT        NULL     COMMENT '변경 사유',
    occurred_at    DATETIME    NOT NULL COMMENT '실제 변경 시각',
    created_at     DATETIME    NOT NULL COMMENT '저장 시각',
    PRIMARY KEY (history_id),
    FOREIGN KEY (mission_id) REFERENCES robot_mission (mission_id),
    FOREIGN KEY (robot_event_id) REFERENCES telemetry_robot_event (event_id),
    FOREIGN KEY (changed_by) REFERENCES auth_admin_user (user_id)
) COMMENT '임무 상태 변경 이력';


CREATE TABLE robot_mission_trajectory (
    mission_id          VARCHAR(36)   NOT NULL COMMENT 'PK,FK 임무',
    trajectory_geometry VARCHAR(255)  NULL     COMMENT '[PostGIS] GEOMETRY(LINESTRING,4326) 실제 이동 궤적',
    point_count         INTEGER       NOT NULL COMMENT '포인트 수',
    distance_m          DECIMAL(12,2) NOT NULL COMMENT '집계 거리',
    first_point_at      DATETIME      NULL     COMMENT '첫 포인트 시각',
    last_point_at       DATETIME      NULL     COMMENT '마지막 포인트 시각',
    updated_at          DATETIME      NOT NULL COMMENT '집계 갱신 시각',
    PRIMARY KEY (mission_id),
    FOREIGN KEY (mission_id) REFERENCES robot_mission (mission_id)
) COMMENT '[P1] 임무 실제 궤적 집계';


CREATE TABLE storage_robot_image (
    image_id              VARCHAR(36) NOT NULL COMMENT 'PK,FK 서버 발급 image_id = file_id',
    event_id              VARCHAR(36) NOT NULL COMMENT 'UQ 사건과의 멱등 연결 키',
    robot_id              VARCHAR(36) NOT NULL COMMENT 'FK 업로드 로봇',
    linked_robot_event_id VARCHAR(36) NULL     COMMENT 'FK 사후 연결된 MQTT 사건',
    image_width           INTEGER     NULL     COMMENT '이미지 폭',
    image_height          INTEGER     NULL     COMMENT '이미지 높이',
    upload_received_at    DATETIME    NOT NULL COMMENT 'HTTPS 업로드 수신 시각',
    created_at            DATETIME    NOT NULL COMMENT '생성 시각',
    updated_at            DATETIME    NOT NULL COMMENT '수정 시각',
    PRIMARY KEY (image_id),
    FOREIGN KEY (image_id) REFERENCES storage_file_object (file_id),
    FOREIGN KEY (robot_id) REFERENCES robot_robot (robot_id),
    FOREIGN KEY (linked_robot_event_id) REFERENCES telemetry_robot_event (event_id)
) COMMENT '로봇 사건 이미지';


-- =====================================================================
-- 5. defect
-- =====================================================================

CREATE TABLE defect_defect (
    defect_id             VARCHAR(36)  NOT NULL COMMENT 'PK 내부 결함 식별자',
    incident_code         VARCHAR(40)  NOT NULL COMMENT 'UQ 외부 표시 코드 (INC-20260723-000031)',
    asset_type            VARCHAR(30)  NOT NULL COMMENT '시설물 유형 (BRAILLE_BLOCK)',
    block_type            VARCHAR(20)  NOT NULL COMMENT 'LINEAR/DOT/UNKNOWN',
    defect_type           VARCHAR(40)  NOT NULL COMMENT 'DAMAGED 및 예약 결함 유형',
    risk_level            VARCHAR(20)  NOT NULL COMMENT '서버 계산 LOW/MEDIUM/HIGH/CRITICAL',
    risk_score            DECIMAL(6,3) NULL     COMMENT '위험도 점수',
    risk_rule_version     VARCHAR(30)  NULL     COMMENT '위험도 규칙 버전',
    risk_calculated_at    DATETIME     NULL     COMMENT '위험도 계산 시각',
    workflow_status       VARCHAR(30)  NOT NULL COMMENT 'NEW/ACCEPTED/IN_PROGRESS/RESOLVED/FALSE_POSITIVE/REJECTED',
    resolution            VARCHAR(40)  NULL     COMMENT 'REPAIRED/CLEANED/DUPLICATE 등',
    resolution_note       TEXT         NULL     COMMENT '처리 결과 설명',
    location              VARCHAR(255) NOT NULL COMMENT '[PostGIS] GEOMETRY(POINT,4326) 대표 위치',
    location_accuracy_m   FLOAT        NULL     COMMENT '대표 위치 오차',
    zone_id               VARCHAR(50)  NOT NULL COMMENT '구역 코드',
    patrol_area_id        VARCHAR(36)  NULL     COMMENT 'FK 매핑된 순찰 구역',
    patrol_route_id       VARCHAR(36)  NULL     COMMENT 'FK 매핑된 순찰 경로',
    location_label        VARCHAR(200) NULL     COMMENT '관리자 표시 위치명',
    first_seen_at         DATETIME     NOT NULL COMMENT '최초 관측 시각',
    last_seen_at          DATETIME     NOT NULL COMMENT '최근 관측 시각',
    observation_count     INTEGER      NOT NULL COMMENT '관측 횟수 캐시',
    latest_confidence     DECIMAL(4,3) NULL     COMMENT '최근 AI 신뢰도',
    review_required       BOOLEAN      NOT NULL COMMENT '수동 검토 필요 여부',
    merged_into_defect_id VARCHAR(36)  NULL     COMMENT 'FK 병합 대상 결함 (자기참조)',
    created_at            DATETIME     NOT NULL COMMENT '생성 시각',
    updated_at            DATETIME     NOT NULL COMMENT '수정 시각',
    PRIMARY KEY (defect_id),
    FOREIGN KEY (patrol_area_id) REFERENCES robot_patrol_area (patrol_area_id),
    FOREIGN KEY (patrol_route_id) REFERENCES robot_patrol_route (patrol_route_id),
    FOREIGN KEY (merged_into_defect_id) REFERENCES defect_defect (defect_id)
) COMMENT '서버 관리 결함';


CREATE TABLE defect_defect_observation (
    event_id             VARCHAR(36)  NOT NULL COMMENT 'PK,FK MQTT incident event_id',
    defect_id            VARCHAR(36)  NOT NULL COMMENT 'FK 병합된 관리 결함',
    location_valid       BOOLEAN      NOT NULL COMMENT '위치 유효 여부',
    location             VARCHAR(255) NULL     COMMENT '[PostGIS] GEOMETRY(POINT,4326) 첫 탐지 위치',
    location_accuracy_m  FLOAT        NULL     COMMENT 'GPS 오차',
    zone_id              VARCHAR(50)  NOT NULL COMMENT '구역 코드',
    asset_type           VARCHAR(30)  NOT NULL COMMENT 'BRAILLE_BLOCK',
    block_type           VARCHAR(20)  NOT NULL COMMENT 'LINEAR/DOT/UNKNOWN',
    detected_defect_type VARCHAR(40)  NOT NULL COMMENT 'AI가 보낸 결함 유형',
    confidence           DECIMAL(4,3) NOT NULL COMMENT 'AI 신뢰도',
    damage_ratio         DECIMAL(5,4) NULL     COMMENT '파손 면적 비율',
    attributes           JSON         NULL     COMMENT 'AI 확장 속성',
    image_id             VARCHAR(36)  NULL     COMMENT 'FK 근거 이미지',
    evidence_captured_at DATETIME     NULL     COMMENT '대표 프레임 촬영 시각',
    image_width          INTEGER      NULL     COMMENT '이미지 폭',
    image_height         INTEGER      NULL     COMMENT '이미지 높이',
    bbox_norm            JSON         NULL     COMMENT '[x_min,y_min,x_max,y_max] 정규화 좌표',
    model_name           VARCHAR(100) NOT NULL COMMENT 'AI 모델명',
    model_version        VARCHAR(50)  NOT NULL COMMENT 'AI 모델 버전',
    is_representative    BOOLEAN      NOT NULL COMMENT '결함 대표 관측 여부',
    created_at           DATETIME     NOT NULL COMMENT '생성 시각',
    PRIMARY KEY (event_id),
    FOREIGN KEY (event_id) REFERENCES telemetry_robot_event (event_id),
    FOREIGN KEY (defect_id) REFERENCES defect_defect (defect_id),
    FOREIGN KEY (image_id) REFERENCES storage_robot_image (image_id)
) COMMENT '점자블록 이상 관측';


CREATE TABLE defect_defect_status_history (
    history_id     BIGINT      NOT NULL COMMENT 'PK 이력 식별자 (BIGSERIAL)',
    defect_id      VARCHAR(36) NOT NULL COMMENT 'FK 대상 결함',
    changed_by     VARCHAR(36) NULL     COMMENT 'FK 변경 관리자',
    change_type    VARCHAR(40) NOT NULL COMMENT 'STATUS_CHANGED/TYPE_CHANGED/RISK_CHANGED 등',
    before_status  VARCHAR(30) NULL     COMMENT '이전 행정 상태',
    after_status   VARCHAR(30) NULL     COMMENT '변경 행정 상태',
    before_values  JSON        NULL     COMMENT '변경 전 Snapshot',
    after_values   JSON        NULL     COMMENT '변경 후 Snapshot',
    reason         TEXT        NULL     COMMENT '변경 사유',
    created_at     DATETIME    NOT NULL COMMENT '변경 시각',
    PRIMARY KEY (history_id),
    FOREIGN KEY (defect_id) REFERENCES defect_defect (defect_id),
    FOREIGN KEY (changed_by) REFERENCES auth_admin_user (user_id)
) COMMENT '결함 행정 상태/수정 이력';


CREATE TABLE defect_defect_merge_history (
    merge_id         VARCHAR(36) NOT NULL COMMENT 'PK 병합 이력 식별자',
    source_defect_id VARCHAR(36) NOT NULL COMMENT 'FK 병합된 원본 결함',
    target_defect_id VARCHAR(36) NOT NULL COMMENT 'FK 대표 결함',
    merge_status     VARCHAR(20) NOT NULL COMMENT 'MERGED 또는 REVERTED',
    merge_reason     TEXT        NULL     COMMENT '병합 사유',
    merged_by        VARCHAR(36) NOT NULL COMMENT 'FK 병합 관리자',
    merged_at        DATETIME    NOT NULL COMMENT '병합 시각',
    reverted_by      VARCHAR(36) NULL     COMMENT 'FK 취소 관리자',
    reverted_at      DATETIME    NULL     COMMENT '병합 취소 시각',
    revert_reason    TEXT        NULL     COMMENT '취소 사유',
    PRIMARY KEY (merge_id),
    FOREIGN KEY (source_defect_id) REFERENCES defect_defect (defect_id),
    FOREIGN KEY (target_defect_id) REFERENCES defect_defect (defect_id),
    FOREIGN KEY (merged_by) REFERENCES auth_admin_user (user_id),
    FOREIGN KEY (reverted_by) REFERENCES auth_admin_user (user_id)
) COMMENT '[P1] 결함 공간 병합 이력';


-- =====================================================================
-- 6. report
-- =====================================================================

CREATE TABLE report_report_schedule (
    report_schedule_id VARCHAR(36)  NOT NULL COMMENT 'PK 스케줄 식별자',
    schedule_name      VARCHAR(100) NOT NULL COMMENT '스케줄명',
    cron_expression    VARCHAR(100) NOT NULL COMMENT 'Cron 표현식',
    report_type        VARCHAR(20)  NOT NULL COMMENT 'PERIODIC 또는 ISSUE',
    patrol_area_id     VARCHAR(36)  NULL     COMMENT 'FK 대상 순찰 구역',
    filter_criteria    JSON         NULL     COMMENT '생성 필터',
    is_active          BOOLEAN      NOT NULL COMMENT '활성 여부',
    last_run_at        DATETIME     NULL     COMMENT '최근 실행 시각',
    next_run_at        DATETIME     NULL     COMMENT '다음 실행 시각',
    created_by         VARCHAR(36)  NOT NULL COMMENT 'FK 생성 관리자',
    created_at         DATETIME     NOT NULL COMMENT '생성 시각',
    updated_at         DATETIME     NOT NULL COMMENT '수정 시각',
    PRIMARY KEY (report_schedule_id),
    FOREIGN KEY (patrol_area_id) REFERENCES robot_patrol_area (patrol_area_id),
    FOREIGN KEY (created_by) REFERENCES auth_admin_user (user_id)
) COMMENT '[P1] 정기 보고서 스케줄';


CREATE TABLE report_anomaly_report (
    report_id          VARCHAR(36)  NOT NULL COMMENT 'PK 보고서 식별자',
    report_code        VARCHAR(30)  NOT NULL COMMENT 'UQ 외부 보고서 코드',
    title              VARCHAR(200) NOT NULL COMMENT '제목',
    summary            VARCHAR(255) NULL     COMMENT '목록 부제',
    report_type        VARCHAR(20)  NOT NULL COMMENT 'PERIODIC 또는 ISSUE',
    report_status      VARCHAR(20)  NOT NULL COMMENT 'DRAFT/IN_REVIEW/APPROVED/REJECTED',
    generation_method  VARCHAR(20)  NOT NULL COMMENT 'MANUAL/TEMPLATE/LLM/SYSTEM',
    creator_type       VARCHAR(20)  NOT NULL COMMENT 'USER 또는 SYSTEM',
    report_schedule_id VARCHAR(36)  NULL     COMMENT 'FK 자동 생성 스케줄',
    period_from        DATETIME     NOT NULL COMMENT '통계 시작 시각',
    period_to          DATETIME     NOT NULL COMMENT '통계 종료 시각',
    patrol_area_id     VARCHAR(36)  NULL     COMMENT 'FK 대상 구역',
    filter_criteria    JSON         NULL     COMMENT '생성 조건 Snapshot',
    current_version_no INTEGER      NOT NULL COMMENT '현재 버전 번호',
    created_by         VARCHAR(36)  NULL     COMMENT 'FK 생성 관리자',
    approved_by        VARCHAR(36)  NULL     COMMENT 'FK 승인 관리자',
    approved_at        DATETIME     NULL     COMMENT '승인 시각',
    created_at         DATETIME     NOT NULL COMMENT '생성 시각',
    updated_at         DATETIME     NOT NULL COMMENT '수정 시각',
    PRIMARY KEY (report_id),
    FOREIGN KEY (report_schedule_id) REFERENCES report_report_schedule (report_schedule_id),
    FOREIGN KEY (patrol_area_id) REFERENCES robot_patrol_area (patrol_area_id),
    FOREIGN KEY (created_by) REFERENCES auth_admin_user (user_id),
    FOREIGN KEY (approved_by) REFERENCES auth_admin_user (user_id)
) COMMENT '이상/정기 보고서';


CREATE TABLE report_report_version (
    report_version_id VARCHAR(36)  NOT NULL COMMENT 'PK 버전 식별자',
    report_id         VARCHAR(36)  NOT NULL COMMENT 'FK 보고서',
    version_no        INTEGER      NOT NULL COMMENT '버전 번호',
    content           TEXT         NOT NULL COMMENT '보고서 본문',
    statistics        JSON         NOT NULL COMMENT '재현 가능한 통계 Snapshot',
    llm_model         VARCHAR(100) NULL     COMMENT 'LLM 모델명',
    prompt_version    VARCHAR(50)  NULL     COMMENT '프롬프트 버전',
    change_note       TEXT         NULL     COMMENT '수정 메모',
    created_by        VARCHAR(36)  NULL     COMMENT 'FK 버전 생성 관리자',
    pdf_file_id       VARCHAR(36)  NULL     COMMENT 'FK 내보낸 PDF',
    created_at        DATETIME     NOT NULL COMMENT '생성 시각',
    PRIMARY KEY (report_version_id),
    FOREIGN KEY (report_id) REFERENCES report_anomaly_report (report_id),
    FOREIGN KEY (created_by) REFERENCES auth_admin_user (user_id),
    FOREIGN KEY (pdf_file_id) REFERENCES storage_file_object (file_id)
) COMMENT '보고서 버전';


CREATE TABLE report_report_defect_source (
    report_id VARCHAR(36) NOT NULL COMMENT 'PK,FK 보고서',
    defect_id VARCHAR(36) NOT NULL COMMENT 'PK,FK 근거 결함',
    PRIMARY KEY (report_id, defect_id),
    FOREIGN KEY (report_id) REFERENCES report_anomaly_report (report_id),
    FOREIGN KEY (defect_id) REFERENCES defect_defect (defect_id)
) COMMENT '보고서-결함 근거';


CREATE TABLE report_report_mission_source (
    report_id  VARCHAR(36) NOT NULL COMMENT 'PK,FK 보고서',
    mission_id VARCHAR(36) NOT NULL COMMENT 'PK,FK 근거 임무',
    PRIMARY KEY (report_id, mission_id),
    FOREIGN KEY (report_id) REFERENCES report_anomaly_report (report_id),
    FOREIGN KEY (mission_id) REFERENCES robot_mission (mission_id)
) COMMENT '보고서-임무 근거';


-- =====================================================================
-- 7. audit
-- =====================================================================

CREATE TABLE audit_audit_log (
    audit_id      BIGINT       NOT NULL COMMENT 'PK 감사 로그 ID (BIGSERIAL)',
    request_id    VARCHAR(36)  NULL     COMMENT '요청 추적 ID',
    actor_user_id VARCHAR(36)  NULL     COMMENT 'FK 작업 관리자',
    actor_type    VARCHAR(20)  NOT NULL COMMENT 'USER/SYSTEM/ROBOT',
    action_type   VARCHAR(80)  NOT NULL COMMENT '작업 코드',
    target_type   VARCHAR(50)  NOT NULL COMMENT '대상 유형',
    target_id     VARCHAR(36)  NULL     COMMENT '대상 UUID',
    before_value  JSON         NULL     COMMENT '변경 전 값',
    after_value   JSON         NULL     COMMENT '변경 후 값',
    reason        TEXT         NULL     COMMENT '작업 사유',
    result        VARCHAR(20)  NOT NULL COMMENT 'SUCCESS/FAILURE/DENIED',
    ip_address    VARCHAR(45)  NULL     COMMENT '[INET] 클라이언트 IP',
    user_agent    VARCHAR(500) NULL     COMMENT '브라우저 User-Agent',
    created_at    DATETIME     NOT NULL COMMENT '작업 시각',
    PRIMARY KEY (audit_id),
    FOREIGN KEY (actor_user_id) REFERENCES auth_admin_user (user_id)
) COMMENT '감사 로그';


-- =====================================================================
-- 8. system
-- =====================================================================

CREATE TABLE system_app_setting (
    setting_key   VARCHAR(100) NOT NULL COMMENT 'PK 설정 키',
    setting_value JSON         NOT NULL COMMENT '설정 값',
    description   TEXT         NULL     COMMENT '설정 설명',
    updated_by    VARCHAR(36)  NULL     COMMENT 'FK 수정 관리자',
    updated_at    DATETIME     NOT NULL COMMENT '수정 시각',
    PRIMARY KEY (setting_key),
    FOREIGN KEY (updated_by) REFERENCES auth_admin_user (user_id)
) COMMENT '운영 설정 (임계값)';


CREATE TABLE system_notification (
    notification_id   VARCHAR(36)  NOT NULL COMMENT 'PK 알림 식별자',
    notification_type VARCHAR(40)  NOT NULL COMMENT '알림 유형',
    severity          VARCHAR(20)  NOT NULL COMMENT 'INFO/WARNING/CRITICAL',
    title             VARCHAR(200) NOT NULL COMMENT '알림 제목',
    message           TEXT         NOT NULL COMMENT '알림 내용',
    source_type       VARCHAR(50)  NOT NULL COMMENT 'ROBOT_EVENT/DEFECT/REPORT/SYSTEM',
    source_id         VARCHAR(36)  NULL     COMMENT '다형 대상 ID (FK 없음)',
    action_url        VARCHAR(500) NULL     COMMENT '클릭 이동 경로',
    metadata          JSON         NULL     COMMENT '추가 데이터',
    expires_at        DATETIME     NULL     COMMENT '알림 만료 시각',
    created_at        DATETIME     NOT NULL COMMENT '생성 시각',
    PRIMARY KEY (notification_id)
) COMMENT '시스템 알림 원본';


CREATE TABLE system_user_notification (
    notification_id VARCHAR(36) NOT NULL COMMENT 'PK,FK 알림',
    user_id         VARCHAR(36) NOT NULL COMMENT 'PK,FK 관리자',
    read_at         DATETIME    NULL     COMMENT '읽음 시각',
    dismissed_at    DATETIME    NULL     COMMENT '숨김 시각',
    created_at      DATETIME    NOT NULL COMMENT '전달 시각',
    PRIMARY KEY (notification_id, user_id),
    FOREIGN KEY (notification_id) REFERENCES system_notification (notification_id),
    FOREIGN KEY (user_id) REFERENCES auth_admin_user (user_id)
) COMMENT '사용자별 알림 상태';


CREATE TABLE system_outbox_event (
    outbox_event_id VARCHAR(36)  NOT NULL COMMENT 'PK Outbox 이벤트 ID',
    aggregate_type  VARCHAR(50)  NOT NULL COMMENT 'Aggregate 유형',
    aggregate_id    VARCHAR(36)  NULL     COMMENT 'Aggregate ID',
    event_type      VARCHAR(100) NOT NULL COMMENT '내부 이벤트 유형',
    payload         JSON         NOT NULL COMMENT '발행 Payload',
    event_status    VARCHAR(20)  NOT NULL COMMENT 'PENDING/PROCESSING/COMPLETED/FAILED',
    retry_count     INTEGER      NOT NULL COMMENT '재시도 횟수',
    available_at    DATETIME     NOT NULL COMMENT '처리 가능 시각',
    processed_at    DATETIME     NULL     COMMENT '처리 완료 시각',
    last_error      TEXT         NULL     COMMENT '최근 오류',
    created_at      DATETIME     NOT NULL COMMENT '생성 시각',
    PRIMARY KEY (outbox_event_id)
) COMMENT '[P1] 내부 비동기 Outbox';


-- =====================================================================
-- 9. integration
-- =====================================================================

CREATE TABLE integration_mqtt_ingest_log (
    ingest_id            BIGINT       NOT NULL COMMENT 'PK 기술 로그 ID (BIGSERIAL)',
    direction            VARCHAR(10)  NOT NULL COMMENT 'INBOUND 또는 OUTBOUND',
    topic                VARCHAR(255) NOT NULL COMMENT 'MQTT Topic',
    qos                  SMALLINT     NOT NULL COMMENT 'QoS 0 또는 1',
    retained             BOOLEAN      NOT NULL COMMENT 'Retain 여부',
    robot_code           VARCHAR(30)  NULL     COMMENT '로봇 코드 Snapshot',
    schema_version       VARCHAR(20)  NULL     COMMENT '스키마 버전',
    message_key          VARCHAR(36)  NULL     COMMENT 'event_id/message_id/command_id 등',
    boot_id              VARCHAR(36)  NULL     COMMENT '부팅 ID',
    seq                  BIGINT       NULL     COMMENT '순번',
    received_at          DATETIME     NULL     COMMENT '수신 시각',
    published_at         DATETIME     NULL     COMMENT '발행 시각',
    processing_status    VARCHAR(20)  NOT NULL COMMENT 'RECEIVED/STORED/MERGED/DUPLICATE/REJECTED/ERROR',
    aggregate_type       VARCHAR(50)  NULL     COMMENT '저장 Aggregate 유형',
    aggregate_id         VARCHAR(36)  NULL     COMMENT '저장 Aggregate ID',
    incident_code        VARCHAR(40)  NULL     COMMENT '생성된 결함 코드',
    error_code           VARCHAR(80)  NULL     COMMENT '오류 코드',
    error_message        TEXT         NULL     COMMENT '오류 상세',
    raw_payload          JSON         NULL     COMMENT '원본 Payload',
    receipt_published_at DATETIME     NULL     COMMENT 'ingest receipt 발행 시각',
    created_at           DATETIME     NOT NULL COMMENT '생성 시각',
    PRIMARY KEY (ingest_id)
) COMMENT '[P1] MQTT 수신/발행 기술 로그';
