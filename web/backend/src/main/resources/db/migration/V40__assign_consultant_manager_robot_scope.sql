-- V39에서 추가한 외부 검증용 MANAGER는 기능 권한만 있고 로봇 객체 범위가 없어
-- 로봇과 로봇에서 파생된 결함을 모두 0건으로 조회했다.
--
-- 시스템 ADMIN의 전체 범위를 일반 역할에 확장하지 않고, 배포 시점에 존재하는
-- 로봇을 검증 계정에 명시적으로 OWNER 배정해 기존 객체 범위 계약을 유지한다.
INSERT INTO auth.user_robot_assignment (
    user_id,
    robot_id,
    assignment_type,
    reason,
    is_active,
    assigned_by
)
SELECT
    '39000000-0000-4000-8000-000000000001',
    robot.robot_id,
    'OWNER',
    '외부 컨설턴트 MANAGER 계정의 로봇·결함 검증 범위',
    TRUE,
    '39000000-0000-4000-8000-000000000001'
FROM robot.robot robot
WHERE NOT EXISTS (
    SELECT 1
    FROM auth.user_robot_assignment assignment
    WHERE assignment.user_id =
          '39000000-0000-4000-8000-000000000001'
      AND assignment.robot_id = robot.robot_id
      AND assignment.is_active = TRUE
);
