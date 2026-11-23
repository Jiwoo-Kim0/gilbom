package com.gilbom.robot.mission;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MissionProgressService {

    private final JdbcTemplate jdbcTemplate;

    public MissionProgressService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 가장 최신인 Mission 경과시간으로 진행률을 단조 증가시킨다.
     * 100%는 완료 Event만 확정할 수 있으므로 Telemetry 값은 99.99%로 제한한다.
     */
    public boolean refresh(
            UUID missionId,
            UUID messageId,
            OffsetDateTime sampledAt,
            int elapsedSeconds
    ) {
        if (elapsedSeconds < 0) {
            throw new IllegalArgumentException(
                    "elapsedSeconds must not be negative");
        }
        int updated = jdbcTemplate.update("""
                WITH progress_candidate AS (
                    SELECT
                        mission.mission_id,
                        LEAST(
                            99.99::NUMERIC,
                            ROUND(
                                (
                                    CAST(? AS NUMERIC)
                                    / mission.planned_duration_sec
                                    * 100
                                ),
                                2
                            )
                        ) AS progress_percent
                    FROM robot.mission mission
                    JOIN telemetry.robot_current_state current_state
                      ON current_state.robot_id = mission.robot_id
                    WHERE mission.mission_id = ?
                      AND mission.status IN ('RUNNING', 'PAUSED')
                      AND current_state.connectivity_status = 'ONLINE'
                      AND mission.started_at IS NOT NULL
                      AND ? >= mission.started_at
                      AND NOT EXISTS (
                          SELECT 1
                          FROM telemetry.robot_telemetry newer
                          WHERE newer.mission_id =
                                mission.mission_id
                            AND newer.mission_elapsed_sec IS NOT NULL
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
                elapsedSeconds,
                missionId,
                sampledAt,
                sampledAt,
                sampledAt,
                messageId,
                sampledAt);
        return updated == 1;
    }
}
