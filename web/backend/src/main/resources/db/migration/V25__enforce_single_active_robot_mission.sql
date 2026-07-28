-- PAUSE/RESUME/CANCEL MQTT command payloads do not carry mission_id.
-- Keep current-mission resolution deterministic for each robot.
CREATE UNIQUE INDEX uq_mission_robot_active
    ON robot.mission (robot_id)
    WHERE status IN ('READY', 'RUNNING', 'PAUSED');

COMMENT ON INDEX robot.uq_mission_robot_active IS
    'A robot can have only one command-addressable mission';
