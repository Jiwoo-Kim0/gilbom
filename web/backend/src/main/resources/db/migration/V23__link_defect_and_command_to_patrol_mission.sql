-- V22에서 생성한 Patrol·Mission·Event·Image 정본을 기존 Command·Defect UUID
-- 칼럼과 연결한다. NOT VALID FK는 기존 orphan을 즉시 검사하지 않지만 이후 쓰기에는
-- 관계 무결성을 적용한다. 기존 데이터 정리 후 별도 배포에서 VALIDATE한다.

ALTER TABLE robot.robot_command
    ADD CONSTRAINT fk_robot_command_mission_robot
        FOREIGN KEY (mission_id, robot_id)
        REFERENCES robot.mission (mission_id, robot_id)
        ON DELETE RESTRICT
        NOT VALID;

ALTER TABLE robot.robot_command
    ADD CONSTRAINT fk_robot_command_patrol_area
        FOREIGN KEY (patrol_area_id)
        REFERENCES robot.patrol_area (patrol_area_id)
        ON DELETE RESTRICT
        NOT VALID;

CREATE INDEX ix_robot_command_mission_issued
    ON robot.robot_command (mission_id, issued_at DESC, command_id)
    WHERE mission_id IS NOT NULL;

ALTER TABLE defect.defect
    ADD CONSTRAINT fk_defect_patrol_area
        FOREIGN KEY (patrol_area_id)
        REFERENCES robot.patrol_area (patrol_area_id)
        ON DELETE RESTRICT
        NOT VALID;

ALTER TABLE defect.defect
    ADD CONSTRAINT ck_defect_route_requires_area
        CHECK (
            patrol_route_id IS NULL
            OR patrol_area_id IS NOT NULL
        )
        NOT VALID;

ALTER TABLE defect.defect
    ADD CONSTRAINT fk_defect_patrol_route_area
        FOREIGN KEY (patrol_route_id, patrol_area_id)
        REFERENCES robot.patrol_route (
            patrol_route_id,
            patrol_area_id
        )
        ON DELETE RESTRICT
        NOT VALID;

ALTER TABLE defect.defect_observation
    ADD CONSTRAINT fk_observation_robot_event
        FOREIGN KEY (event_id, robot_id)
        REFERENCES telemetry.robot_event (event_id, robot_id)
        ON DELETE RESTRICT
        NOT VALID;

ALTER TABLE defect.defect_observation
    ADD CONSTRAINT fk_observation_robot_image
        FOREIGN KEY (image_id, robot_id)
        REFERENCES storage.robot_image (image_id, robot_id)
        ON DELETE RESTRICT
        NOT VALID;

COMMENT ON COLUMN robot.robot_command.mission_id IS
    'START_MISSION의 문자열 mission_id를 mission_code로 조회한 Mission UUID';
COMMENT ON COLUMN robot.robot_command.patrol_area_id IS
    'START_MISSION의 zone_id와 일치함을 검증한 Patrol Area UUID';
COMMENT ON COLUMN defect.defect_observation.event_id IS
    'telemetry.robot_event의 Incident 원본과 같은 UUID';
COMMENT ON COLUMN defect.defect_observation.image_id IS
    '같은 Robot이 사전 업로드한 storage.robot_image UUID';
