-- 순찰 구역 코드는 robot.patrol_area.area_code를 그대로 MQTT와 결함 도메인에 전달한다.
-- 기존 A-01 전용 형식을 제거하고 area_code를 해석하지 않는 문자열로 그대로 보존한다.

ALTER TABLE defect.defect
    DROP CONSTRAINT ck_defect_zone_id;

ALTER TABLE defect.defect
    ALTER COLUMN zone_id TYPE VARCHAR(50);

ALTER TABLE defect.defect_observation
    DROP CONSTRAINT ck_observation_zone_id;

ALTER TABLE defect.defect_observation
    ALTER COLUMN zone_id TYPE VARCHAR(50);

ALTER TABLE defect.incident_ingestion_issue
    DROP CONSTRAINT ck_incident_ingestion_issue_zone;

ALTER TABLE defect.incident_ingestion_issue
    ALTER COLUMN zone_id TYPE VARCHAR(50);

COMMENT ON COLUMN defect.defect.zone_id IS
    '순찰 구역 업무 코드. robot.patrol_area.area_code 값을 해석하지 않고 보존';
COMMENT ON COLUMN defect.defect_observation.zone_id IS
    'Incident가 전달한 순찰 구역 업무 코드를 해석하지 않고 보존';
COMMENT ON COLUMN defect.incident_ingestion_issue.zone_id IS
    '해결되지 않은 Incident의 순찰 구역 업무 코드. 없으면 NULL';
