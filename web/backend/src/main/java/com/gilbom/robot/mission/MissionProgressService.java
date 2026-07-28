package com.gilbom.robot.mission;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MissionProgressService {

    private static final int MAXIMUM_ROUTE_DISTANCE_METERS = 30;

    private final JdbcTemplate jdbcTemplate;

    public MissionProgressService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 가장 최신인 유효 위치만 경로에 투영해 Mission 진행률을 단조 증가시킨다.
     * 100%는 완료 Event만 확정할 수 있으므로 위치 기반 값은 99.99%로 제한한다.
     */
    public boolean refresh(
            UUID missionId,
            UUID messageId,
            OffsetDateTime sampledAt,
            double longitude,
            double latitude
    ) {
        int updated = jdbcTemplate.update("""
                WITH progress_candidate AS (
                    SELECT
                        mission.mission_id,
                        CASE
                            WHEN GeometryType(
                                ST_LineMerge(route.route_geometry)
                            ) = 'LINESTRING'
                             AND ST_DWithin(
                                ST_SetSRID(
                                    ST_MakePoint(
                                        CAST(? AS DOUBLE PRECISION),
                                        CAST(? AS DOUBLE PRECISION)
                                    ),
                                    4326
                                )::GEOGRAPHY,
                                route.route_geometry::GEOGRAPHY,
                                ?
                             )
                            THEN LEAST(
                                99.99::NUMERIC,
                                ROUND(
                                    (
                                        ST_LineLocatePoint(
                                            ST_LineMerge(
                                                route.route_geometry
                                            ),
                                            ST_SetSRID(
                                                ST_MakePoint(
                                                    CAST(
                                                        ? AS DOUBLE PRECISION
                                                    ),
                                                    CAST(
                                                        ? AS DOUBLE PRECISION
                                                    )
                                                ),
                                                4326
                                            )
                                        ) * 100
                                    )::NUMERIC,
                                    2
                                )
                            )
                            ELSE NULL
                        END AS progress_percent
                    FROM robot.mission mission
                    JOIN robot.patrol_route route
                      ON route.patrol_route_id =
                         mission.patrol_route_id
                     AND route.patrol_area_id =
                         mission.patrol_area_id
                    WHERE mission.mission_id = ?
                      AND mission.status = 'RUNNING'
                      AND mission.started_at IS NOT NULL
                      AND ? >= mission.started_at
                      AND NOT EXISTS (
                          SELECT 1
                          FROM telemetry.robot_telemetry newer
                          WHERE newer.mission_id =
                                mission.mission_id
                            AND newer.position_valid = TRUE
                            AND newer.position IS NOT NULL
                            AND (
                                newer.sampled_at > ?
                                OR (
                                    newer.sampled_at = ?
                                    AND newer.message_id > ?
                                )
                            )
                      )
                )
                UPDATE robot.mission mission
                SET progress_percent = GREATEST(
                        mission.progress_percent,
                        candidate.progress_percent
                    ),
                    updated_at = GREATEST(
                        mission.updated_at,
                        ?
                    )
                FROM progress_candidate candidate
                WHERE mission.mission_id = candidate.mission_id
                  AND candidate.progress_percent IS NOT NULL
                  AND candidate.progress_percent >
                      mission.progress_percent
                """,
                longitude,
                latitude,
                MAXIMUM_ROUTE_DISTANCE_METERS,
                longitude,
                latitude,
                missionId,
                sampledAt,
                sampledAt,
                sampledAt,
                messageId,
                sampledAt);
        return updated == 1;
    }
}
