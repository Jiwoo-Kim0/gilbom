package com.gilbom.robot.mission;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

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

    public MissionCompletionCommandCreationService(
            JdbcTemplate jdbcTemplate,
            RobotCommandRepository commandRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.commandRepository = commandRepository;
    }

    @Transactional
    public List<RobotCommandRecord> createDueCommands(Instant detectedAt) {
        OffsetDateTime issuedAt = utc(detectedAt);
        OffsetDateTime expiresAt = issuedAt.plusSeconds(30);
        List<UUID> commandIds = jdbcTemplate.query("""
                WITH due_missions AS (
                    SELECT
                        mission.mission_id,
                        mission.robot_id,
                        mission.patrol_area_id
                    FROM robot.mission mission
                    WHERE mission.deleted_at IS NULL
                      AND mission.status = 'RUNNING'
                      AND mission.started_at IS NOT NULL
                      AND mission.started_at
                            + mission.planned_duration_sec
                                * INTERVAL '1 second' <= ?
                      AND NOT EXISTS (
                          SELECT 1
                          FROM robot.robot_command existing_command
                          WHERE existing_command.mission_id = mission.mission_id
                            AND existing_command.command_type = 'COMPLETE_MISSION'
                            AND existing_command.command_status IN (
                                'CREATED',
                                'SENT',
                                'ACCEPTED',
                                'COMPLETED'
                            )
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
                    '{}'::jsonb,
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
