-- Spring Session JDBC 공식 PostgreSQL 구조를 전용 schema에 둔다.
-- 세션 ID와 serialized attribute는 인증·감사 업무 테이블에 복제하지 않는다.
CREATE TABLE session_store.spring_session (
    primary_id CHAR(36) NOT NULL,
    session_id CHAR(36) NOT NULL,
    creation_time BIGINT NOT NULL,
    last_access_time BIGINT NOT NULL,
    max_inactive_interval INTEGER NOT NULL,
    expiry_time BIGINT NOT NULL,
    principal_name VARCHAR(100),
    CONSTRAINT pk_spring_session PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX uq_spring_session_id
    ON session_store.spring_session (session_id);
CREATE INDEX ix_spring_session_expiry_time
    ON session_store.spring_session (expiry_time);
CREATE INDEX ix_spring_session_principal_name
    ON session_store.spring_session (principal_name)
    WHERE principal_name IS NOT NULL;

COMMENT ON TABLE session_store.spring_session IS 'Spring Session JDBC 세션 metadata';
COMMENT ON COLUMN session_store.spring_session.primary_id IS 'attribute FK에 사용하는 내부 세션 식별자';
COMMENT ON COLUMN session_store.spring_session.session_id IS '브라우저 cookie가 참조하는 불투명 세션 ID. 로그 기록 금지';
COMMENT ON COLUMN session_store.spring_session.expiry_time IS '유휴 만료 계산에 사용하는 epoch milliseconds';

CREATE TABLE session_store.spring_session_attributes (
    session_primary_id CHAR(36) NOT NULL,
    attribute_name VARCHAR(200) NOT NULL,
    attribute_bytes BYTEA NOT NULL,
    CONSTRAINT pk_spring_session_attributes
        PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT fk_spring_session_attributes
        FOREIGN KEY (session_primary_id)
        REFERENCES session_store.spring_session (primary_id)
        ON DELETE CASCADE
);

COMMENT ON TABLE session_store.spring_session_attributes IS 'Spring SecurityContext·CSRF 등 serialized session attribute';
COMMENT ON COLUMN session_store.spring_session_attributes.attribute_bytes IS '애플리케이션 로그·API로 노출하면 안 되는 직렬화 데이터';

-- 표준 Session table에 없는 MFA 대기 상태와 절대 만료는 조회·폐기 가능한 companion table로 관리한다.
CREATE TABLE session_store.session_security_state (
    session_primary_id CHAR(36) NOT NULL,
    user_id UUID,
    authentication_state VARCHAR(40) DEFAULT 'ANONYMOUS' NOT NULL,
    absolute_expiry_time BIGINT,
    mfa_challenge_expires_at BIGINT,
    mfa_failure_count SMALLINT DEFAULT 0 NOT NULL,
    created_time BIGINT NOT NULL,
    updated_time BIGINT NOT NULL,
    CONSTRAINT pk_session_security_state PRIMARY KEY (session_primary_id),
    CONSTRAINT ck_session_authentication_state CHECK (
        authentication_state IN (
            'ANONYMOUS',
            'MFA_ENROLLMENT_REQUIRED',
            'MFA_REQUIRED',
            'AUTHENTICATED'
        )
    ),
    CONSTRAINT ck_session_mfa_failure_count CHECK (mfa_failure_count BETWEEN 0 AND 5),
    CONSTRAINT ck_session_security_timestamps CHECK (updated_time >= created_time),
    CONSTRAINT ck_session_security_context CHECK (
        (
            authentication_state = 'ANONYMOUS'
            AND user_id IS NULL
            AND mfa_challenge_expires_at IS NULL
        )
        OR
        (
            authentication_state IN ('MFA_ENROLLMENT_REQUIRED', 'MFA_REQUIRED')
            AND user_id IS NOT NULL
            AND absolute_expiry_time IS NOT NULL
            AND mfa_challenge_expires_at IS NOT NULL
        )
        OR
        (
            authentication_state = 'AUTHENTICATED'
            AND user_id IS NOT NULL
            AND absolute_expiry_time IS NOT NULL
            AND mfa_challenge_expires_at IS NULL
        )
    ),
    CONSTRAINT fk_session_security_session
        FOREIGN KEY (session_primary_id)
        REFERENCES session_store.spring_session (primary_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_session_security_user
        FOREIGN KEY (user_id)
        REFERENCES auth.admin_user (user_id)
        ON DELETE CASCADE
);

CREATE INDEX ix_session_security_user
    ON session_store.session_security_state (user_id, absolute_expiry_time)
    WHERE user_id IS NOT NULL;

COMMENT ON TABLE session_store.session_security_state IS 'MFA 진행 상태, 실패 횟수, 인증 세션 절대 만료 metadata';
COMMENT ON COLUMN session_store.session_security_state.absolute_expiry_time IS 'MFA 성공 또는 challenge 시작 기준 절대 만료 epoch milliseconds';
COMMENT ON COLUMN session_store.session_security_state.mfa_challenge_expires_at IS '5분 MFA challenge 만료 epoch milliseconds';
COMMENT ON COLUMN session_store.session_security_state.mfa_failure_count IS 'challenge별 실패 횟수. 5회 도달 시 폐기';

-- Spring Session repository와 인증 서비스가 필요한 세션 DML만 허용한다.
GRANT USAGE ON SCHEMA session_store TO "${applicationRole}";
GRANT SELECT, INSERT, UPDATE, DELETE
    ON session_store.spring_session,
       session_store.spring_session_attributes,
       session_store.session_security_state
    TO "${applicationRole}";
