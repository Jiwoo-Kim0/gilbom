-- robot_id remains the internal UUID. mqtt_id is the external identity shared
-- by the MQTT username, topic segment and payload.
ALTER SEQUENCE robot.robot_code_sequence RENAME TO mqtt_id_sequence;

ALTER TABLE robot.robot RENAME COLUMN robot_code TO mqtt_id;
ALTER TABLE robot.robot RENAME CONSTRAINT uq_robot_code TO uq_robot_mqtt_id;
ALTER TABLE robot.robot RENAME CONSTRAINT ck_robot_code_not_blank TO ck_robot_mqtt_id_not_blank;

COMMENT ON TABLE robot.robot IS
    'Robot master data. robot_id is the internal UUID and mqtt_id is the external MQTT identity.';
COMMENT ON COLUMN robot.robot.mqtt_id IS
    'Server-issued identity shared by MQTT username, topic segment and payload.';

GRANT USAGE, SELECT ON SEQUENCE robot.mqtt_id_sequence TO "${applicationRole}";
