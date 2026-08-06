package com.gilbom.robot.mission;

import java.time.Instant;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.robot.command.RobotCommandRecord;
import com.gilbom.robot.command.RobotCommandRepository;

@Service
public class MissionCompletionCommandCreationService {

    private static final int BATCH_SIZE = 100;

    private final JdbcTemplate jdbcTemplate;
    private final RobotCommandRepository commandRepository;
    private final Duration completionTelemetryMaxAge;

    public MissionCompletionCommandCreationService(
            JdbcTemplate jdbcTemplate,
            RobotCommandRepository commandRepository,
            @Value(
                    "${gilbom.robot.mission."
                            + "completion-telemetry-max-age:15s}"
            ) Duration completionTelemetryMaxAge
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.commandRepository = commandRepository;
        this.completionTelemetryMaxAge = completionTelemetryMaxAge;
    }

    @Transactional
    public List<RobotCommandRecord> createDueCommands(Instant detectedAt) {
        OffsetDateTime issuedAt = utc(detectedAt);
        OffsetDateTime expiresAt = issuedAt.plusSeconds(30);
        OffsetDateTime telemetryThreshold = utc(
                detectedAt.minus(completionTelemetryMaxAge));
        List<UUID> commandIds = jdbcTemplate.query("""
                WITH due_missions AS (
                    SELECT
                        mission.mission_id,
                        mission.mission_code,
                        mission.robot_id,
                        mission.patrol_area_id
                    FROM robot.mission mission
                    JOIN robot.robot robot
                      ON robot.robot_id = mission.robot_id
                    JOIN telemetry.robot_current_state current_state
                      ON current_state.robot_id = mission.robot_id
                    LEFT JOIN LATERAL (
                        SELECT existing_command.command_status
                        FROM robot.robot_command existing_command
                        WHERE existing_command.mission_id = mission.mission_id
                          AND existing_command.command_type = 'COMPLETE_MISSION'
                        ORDER BY
                            existing_command.issued_at DESC,
                            existing_command.command_id DESC
                        LIMIT 1
                    ) latest_completion ON TRUE
                    WHERE mission.deleted_at IS NULL
                      AND mission.status = 'RUNNING'
                      AND mission.started_at IS NOT NULL
                      AND mission.started_at
                            + mission.planned_duration_sec
                                * INTERVAL '1 second' <= ?
                      AND robot.operational_status = 'ACTIVE'
                      AND current_state.connectivity_status = 'ONLINE'
                      AND current_state.robot_mode = 'PATROLLING'
                      AND current_state.mission_id = mission.mission_id
                      AND current_state.mission_status = 'RUNNING'
                      AND current_state.telemetry_received_at >= ?
                      AND (
                          latest_completion.command_status IS NULL
                          OR latest_completion.command_status = 'EXPIRED'
                      )
                    ORDER BY
                        mission.started_at
                            + mission.planned_duration_sec
                                * INTERVAL '1 second',
                        mission.mission_id
                    FOR UPDATE OF mission SKIP LOCKED
                    LIMIT ?
                )
                INSERT INTO robot.robot_command (
                    command_id,
                    schema_version,
                    robot_id,
                    mission_id,
                    patrol_area_id,
                    command_type,
                    command_status,
                    parameters,
                    requested_by_type,
                    requested_by_user_id,
                    issued_at,
                    expires_at
                )
                SELECT
                    gen_random_uuid(),
                    '2.0',
                    due_mission.robot_id,
                    due_mission.mission_id,
                    due_mission.patrol_area_id,
                    'COMPLETE_MISSION',
                    'CREATED',
                    JSONB_BUILD_OBJECT(
                        'mission_id',
                        due_mission.mission_code,
                        'completion_reason',
                        'PLANNED_DURATION_REACHED'
                    ),
                    'SYSTEM',
                    NULL,
                    ?,
                    ?
                FROM due_missions due_mission
                ON CONFLICT DO NOTHING
                RETURNING command_id
                """,
                (resultSet, rowNumber) -> resultSet.getObject(
                        "command_id",
                        UUID.class),
                issuedAt,
                telemetryThreshold,
                BATCH_SIZE,
                issuedAt,
                expiresAt);

        return commandIds.stream()
                .map(commandId -> commandRepository.findById(commandId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Created completion command was not found")))
                .toList();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
