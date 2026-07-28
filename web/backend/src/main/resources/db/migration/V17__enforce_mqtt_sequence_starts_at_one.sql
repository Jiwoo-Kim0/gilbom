-- MQTT v2의 공용 seq는 새 boot_id마다 1부터 시작한다.
-- 기존 환경에 0인 데이터가 있다면 값을 임의로 변경하지 않고 migration을 실패시켜
-- 잘못된 Wire 계약 데이터를 운영자가 명시적으로 확인하게 한다.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM telemetry.robot_telemetry
        WHERE seq < 1
    ) OR EXISTS (
        SELECT 1
        FROM telemetry.robot_current_state
        WHERE last_seen_seq < 1
           OR state_seq < 1
           OR telemetry_seq < 1
    ) THEN
        RAISE EXCEPTION
            'MQTT seq contract migration requires every stored seq to be at least 1';
    END IF;
END;
$$;

ALTER TABLE telemetry.robot_telemetry
    DROP CONSTRAINT ck_robot_telemetry_seq,
    ADD CONSTRAINT ck_robot_telemetry_seq CHECK (seq >= 1);

ALTER TABLE telemetry.robot_current_state
    DROP CONSTRAINT ck_robot_current_last_seen_seq,
    DROP CONSTRAINT ck_robot_current_state_seq,
    DROP CONSTRAINT ck_robot_current_telemetry_seq,
    ADD CONSTRAINT ck_robot_current_last_seen_seq CHECK (
        last_seen_seq IS NULL OR last_seen_seq >= 1
    ),
    ADD CONSTRAINT ck_robot_current_state_seq CHECK (
        state_seq IS NULL OR state_seq >= 1
    ),
    ADD CONSTRAINT ck_robot_current_telemetry_seq CHECK (
        telemetry_seq IS NULL OR telemetry_seq >= 1
    );

COMMENT ON COLUMN telemetry.robot_telemetry.seq IS
    '로봇이 boot 안에서 생성한 1 이상의 공용 순번';
COMMENT ON COLUMN telemetry.robot_current_state.last_seen_seq IS
    '현재 boot에서 수신한 State·Telemetry·Event의 1 이상 최댓값';
