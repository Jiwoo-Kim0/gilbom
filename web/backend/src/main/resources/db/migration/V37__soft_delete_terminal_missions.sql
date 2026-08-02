-- 실행 이력이 있는 종료 Mission은 Telemetry·Event·Command·상태 이력을 보존한 채
-- 관리자 목록에서 논리 삭제할 수 있게 한다. CREATED Mission의 기존 물리 삭제는 유지한다.

ALTER TABLE robot.mission
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN deleted_by UUID;

ALTER TABLE robot.mission
    ADD CONSTRAINT fk_mission_deleted_by
        FOREIGN KEY (deleted_by)
        REFERENCES auth.admin_user (user_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT ck_mission_soft_delete CHECK (
        (deleted_at IS NULL AND deleted_by IS NULL)
        OR (
            deleted_at IS NOT NULL
            AND deleted_by IS NOT NULL
            AND deleted_at >= created_at
            AND status IN ('COMPLETED', 'CANCELLED', 'FAILED')
        )
    );

CREATE INDEX ix_mission_visible_status_started
    ON robot.mission (status, started_at DESC, mission_id)
    WHERE deleted_at IS NULL;

COMMENT ON COLUMN robot.mission.deleted_at IS
    '종료 Mission을 목록에서 숨긴 관리자 논리 삭제 시각';
COMMENT ON COLUMN robot.mission.deleted_by IS
    '종료 Mission 논리 삭제를 실행한 관리자';

-- 정상 종료(COMPLETED)와 취소(CANCELLED)를 명령 계약에서 분리한다.
ALTER TABLE robot.robot_command
    DROP CONSTRAINT ck_robot_command_type;

ALTER TABLE robot.robot_command
    ADD CONSTRAINT ck_robot_command_type CHECK (
        command_type IN (
            'START_MISSION',
            'PAUSE_MISSION',
            'RESUME_MISSION',
            'COMPLETE_MISSION',
            'CANCEL_MISSION',
            'EMERGENCY_STOP',
            'CLEAR_EMERGENCY_STOP'
        )
    );

CREATE UNIQUE INDEX uq_robot_command_system_complete_mission
    ON robot.robot_command (mission_id)
    WHERE requested_by_type = 'SYSTEM'
      AND command_type = 'COMPLETE_MISSION';
