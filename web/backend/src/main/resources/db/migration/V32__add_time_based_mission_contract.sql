-- Mission 진행률은 계획 Route의 GPS 투영값이 아니라 Robot Telemetry의
-- mission.elapsed_sec와 계획 수행시간의 비율로 계산한다.

ALTER TABLE robot.mission
    ADD COLUMN planned_duration_sec INTEGER DEFAULT 3600 NOT NULL;

ALTER TABLE robot.mission
    ADD CONSTRAINT ck_mission_planned_duration
        CHECK (planned_duration_sec > 0);

COMMENT ON COLUMN robot.mission.planned_duration_sec IS
    '계획 수행시간(초). 진행률 분모이며 기존 Mission은 3600초로 Backfill';

-- 기존 Area에는 DB·API FK 호환만 위한 내부 Dummy Route를 준비한다.
-- 이 Geometry는 Robot 명령, 지도, 진행률 계산에 사용하지 않는다.
WITH area_geometry AS (
    SELECT
        area.patrol_area_id,
        area.created_by,
        area.updated_by,
        area.created_at,
        area.updated_at,
        area.boundary,
        ST_PointOnSurface(area.boundary) AS inside_point
    FROM robot.patrol_area area
    WHERE area.lifecycle_status = 'ACTIVE'
      AND NOT EXISTS (
          SELECT 1
          FROM robot.patrol_route route
          WHERE route.patrol_area_id = area.patrol_area_id
            AND route.route_code = 'AUTONOMOUS_DEFAULT'
      )
),
dummy_geometry AS (
    SELECT
        area_geometry.*,
        ST_MakeLine(
            inside_point,
            ST_LineInterpolatePoint(
                ST_ShortestLine(
                    inside_point,
                    ST_Boundary(boundary)
                ),
                0.5
            )
        ) AS route_geometry
    FROM area_geometry
)
INSERT INTO robot.patrol_route (
    patrol_route_id,
    patrol_area_id,
    route_code,
    route_name,
    route_version,
    route_geometry,
    lifecycle_status,
    created_by,
    updated_by,
    created_at,
    updated_at
)
SELECT
    gen_random_uuid(),
    patrol_area_id,
    'AUTONOMOUS_DEFAULT',
    '자율 순찰 내부 경로',
    1,
    route_geometry,
    'ACTIVE',
    created_by,
    updated_by,
    created_at,
    updated_at
FROM dummy_geometry;

COMMENT ON INDEX robot.uq_patrol_route_active_version IS
    'AUTONOMOUS_DEFAULT를 포함해 Area·논리 Route별 ACTIVE 버전은 하나';
