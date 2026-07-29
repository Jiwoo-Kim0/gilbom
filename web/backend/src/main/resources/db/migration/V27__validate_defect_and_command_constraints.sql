-- V23이 NOT VALID로 추가한 제약을 검증 상태로 승격한다. V23 주석이 예고한
-- "기존 데이터 정리 후 별도 배포"가 이 migration이다.
--
-- NOT VALID는 신규 INSERT/UPDATE에는 적용되지만 제약을 걸기 전에 이미 있던 행은
-- 검사하지 않고, 플래너도 제약을 신뢰하지 않는다. VALIDATE는 기존 행을 전수 검사한
-- 뒤 제약을 validated로 승격한다.
--
-- 안전성 근거:
--  1) V23 이후에 쓰인 행은 이미 FK가 적용된 상태로 들어갔으므로 orphan일 수 없다.
--  2) 따라서 위험은 V23 적용 시점에 이미 존재하던 행뿐인데, 참조 대상 테이블
--     (robot.patrol_area, robot.patrol_route, robot.mission, telemetry.robot_event,
--     storage.robot_image)은 V22에서 처음 생겼다. 즉 V22 이전에는 이 칼럼들을
--     채울 방법이 없었다.
--  3) 적용 대상 DB에서 defect.defect, defect.defect_observation,
--     robot.robot_command 세 테이블이 모두 0행임을 확인했다.
--
-- VALIDATE는 SHARE UPDATE EXCLUSIVE 락만 잡으므로 읽기·쓰기를 막지 않는다.
-- 위반 행이 있으면 이 migration이 실패하고 Flyway가 기동을 막는다. 그때는 해당
-- 칼럼을 NULL로 비우거나 행을 정리한 뒤 재시도해야 한다.

ALTER TABLE robot.robot_command
    VALIDATE CONSTRAINT fk_robot_command_mission_robot;

ALTER TABLE robot.robot_command
    VALIDATE CONSTRAINT fk_robot_command_patrol_area;

ALTER TABLE defect.defect
    VALIDATE CONSTRAINT fk_defect_patrol_area;

ALTER TABLE defect.defect
    VALIDATE CONSTRAINT ck_defect_route_requires_area;

ALTER TABLE defect.defect
    VALIDATE CONSTRAINT fk_defect_patrol_route_area;

ALTER TABLE defect.defect_observation
    VALIDATE CONSTRAINT fk_observation_robot_event;

ALTER TABLE defect.defect_observation
    VALIDATE CONSTRAINT fk_observation_robot_image;
