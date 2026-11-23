-- Extend the completion read model so COMPLETE_MISSION is gated by the
-- robot-reported mission and completed events can reference their command.

ALTER TABLE telemetry.robot_current_state
    ADD COLUMN mission_id UUID;

ALTER TABLE telemetry.robot_current_state
    ADD CONSTRAINT fk_robot_current_state_mission_robot
        FOREIGN KEY (mission_id, robot_id)
        REFERENCES robot.mission (mission_id, robot_id)
        ON DELETE RESTRICT
        NOT VALID;

CREATE INDEX ix_robot_current_completion_eligibility
    ON telemetry.robot_current_state (
        robot_id,
        mission_id,
        connectivity_status,
        mission_status,
        telemetry_received_at
    );

COMMENT ON COLUMN telemetry.robot_current_state.mission_id IS
    'Internal UUID of the mission reported by the latest State or Telemetry';

ALTER TABLE telemetry.robot_event
    ADD COLUMN causation_command_id UUID;

ALTER TABLE telemetry.robot_event
    ADD CONSTRAINT ck_robot_event_completion_causation
        CHECK (
            causation_command_id IS NULL
            OR event_type = 'MISSION_COMPLETED'
        );

ALTER TABLE telemetry.robot_event
    ADD CONSTRAINT fk_robot_event_causation_command
        FOREIGN KEY (causation_command_id)
        REFERENCES robot.robot_command (command_id)
        ON DELETE RESTRICT
        NOT VALID;

CREATE UNIQUE INDEX uq_robot_event_completion_causation
    ON telemetry.robot_event (causation_command_id)
    WHERE causation_command_id IS NOT NULL;

CREATE INDEX ix_robot_command_completion_retry
    ON robot.robot_command (
        mission_id,
        command_type,
        issued_at DESC,
        command_id DESC
    )
    WHERE command_type = 'COMPLETE_MISSION';

COMMENT ON COLUMN telemetry.robot_event.causation_command_id IS
    'COMPLETE_MISSION command UUID that caused MISSION_COMPLETED';
