-- 로봇이 증거 이미지를 올리는 POST /api/v1/images의 인증 수단이다.
--
-- 1) authorization-matrix.md 279~283행은 이미지 API를 "유효한 로봇 X-API-Key"로
--    인증하고, Key의 로봇과 body robot_id가 일치하는지 검사하도록 정의한다.
--    이 요청은 브라우저 세션과 CSRF 대상이 아니므로 관리자 인증과 저장소를 공유하지
--    않는다.
-- 2) 로봇 MQTT 인증정보(robot.robot_credential)는 Broker 접속용이고 발급 주체와
--    수명이 다르다. HTTPS 업로드 인증을 그 위에 얹으면 한쪽을 회수할 때 다른 쪽이
--    함께 끊기므로 별도 테이블로 둔다.
--
-- 해시는 SHA-256이다. 이 값은 서버가 만드는 고엔트로피 난수(32바이트)여서 사용자
-- 비밀번호처럼 사전 공격 대상이 아니고, 모든 업로드 요청마다 검증하는 경로라
-- Argon2의 의도적인 지연이 그대로 처리량 손실이 된다.
CREATE TABLE storage.robot_image_api_key (
    api_key_id UUID DEFAULT gen_random_uuid() NOT NULL,
    robot_id UUID NOT NULL,
    -- 전체 키를 보관하지 않고도 어떤 키인지 사람이 식별하고 로그에 남길 수 있는 앞부분이다.
    key_prefix VARCHAR(12) NOT NULL,
    key_hash CHAR(64) NOT NULL,
    key_status VARCHAR(20) DEFAULT 'ACTIVE' NOT NULL,
    issued_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    expires_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT pk_robot_image_api_key PRIMARY KEY (api_key_id),
    CONSTRAINT uq_robot_image_api_key_hash UNIQUE (key_hash),
    CONSTRAINT uq_robot_image_api_key_prefix UNIQUE (key_prefix),
    CONSTRAINT ck_robot_image_api_key_hash CHECK (
        key_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_robot_image_api_key_prefix CHECK (
        key_prefix = BTRIM(key_prefix)
        AND key_prefix <> ''
    ),
    CONSTRAINT ck_robot_image_api_key_status CHECK (
        key_status IN ('ACTIVE', 'REVOKED')
    ),
    -- 회수한 키만 회수 시각을 가진다. 상태와 시각이 어긋난 행을 만들 수 없다.
    CONSTRAINT ck_robot_image_api_key_lifecycle CHECK (
        (key_status = 'REVOKED' AND revoked_at IS NOT NULL)
        OR (key_status = 'ACTIVE' AND revoked_at IS NULL)
    ),
    CONSTRAINT ck_robot_image_api_key_timestamps CHECK (
        (expires_at IS NULL OR expires_at > issued_at)
        AND (last_used_at IS NULL OR last_used_at >= issued_at)
        AND (revoked_at IS NULL OR revoked_at >= issued_at)
    ),
    CONSTRAINT fk_robot_image_api_key_robot
        FOREIGN KEY (robot_id) REFERENCES robot.robot (robot_id)
        ON DELETE RESTRICT
);

-- 키를 교체할 때 이전 키를 REVOKED로 남겨 이력을 보존하되, 동시에 살아 있는 키는
-- 로봇당 하나로 제한한다. 유출 시 회수 대상이 명확해야 한다.
CREATE UNIQUE INDEX uq_robot_image_api_key_active_robot
    ON storage.robot_image_api_key (robot_id)
    WHERE key_status = 'ACTIVE';

COMMENT ON TABLE storage.robot_image_api_key IS
    'POST /api/v1/images의 로봇 X-API-Key 저장소. 원문 키는 저장하지 않고 SHA-256 해시만 보관한다';
COMMENT ON COLUMN storage.robot_image_api_key.key_prefix IS
    '키 앞부분. 로그와 운영 조회에서 키를 식별하는 용도이며 인증에는 사용하지 않는다';
COMMENT ON COLUMN storage.robot_image_api_key.key_hash IS
    '키 원문의 SHA-256 hex. 원문은 발급 시점 한 번만 노출되고 서버에 남지 않는다';
COMMENT ON COLUMN storage.robot_image_api_key.last_used_at IS
    '마지막 업로드 성공 시각. 사용하지 않는 키를 찾아 회수하기 위한 운영 지표';

-- 애플리케이션은 키를 검증(SELECT)하고 마지막 사용 시각을 갱신(UPDATE)한다.
-- 발급·회수는 당분간 운영자가 psql로 수행하므로 INSERT·DELETE는 부여하지 않는다.
-- (scripts/issue-robot-image-api-key.py가 키와 INSERT 문을 함께 생성한다)
GRANT SELECT, UPDATE ON storage.robot_image_api_key TO "${applicationRole}";
