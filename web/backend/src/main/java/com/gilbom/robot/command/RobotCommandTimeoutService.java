package com.gilbom.robot.command;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.robot.realtime.RobotRealtimeEvent;

@Service
public class RobotCommandTimeoutService {

    private final JdbcTemplate jdbcTemplate;
    private final ApplicationEventPublisher eventPublisher;

    public RobotCommandTimeoutService(
            JdbcTemplate jdbcTemplate,
            ApplicationEventPublisher eventPublisher
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public int expireCommands(Instant detectedAt) {
        List<ExpiredTarget> targets = jdbcTemplate.query("""
                SELECT
                    command.command_id,
                    command.robot_id,
                    robot.mqtt_id
                FROM robot.robot_command command
                JOIN robot.robot robot
                  ON robot.robot_id = command.robot_id
                WHERE command.command_status IN (
                    'CREATED',
                    'SENT',
                    'ACCEPTED'
                )
                  AND command.expires_at <= ?
                FOR UPDATE OF command SKIP LOCKED
                """,
                (resultSet, rowNumber) -> new ExpiredTarget(
                        resultSet.getObject("command_id", UUID.class),
                        resultSet.getObject("robot_id", UUID.class),
                        resultSet.getString("mqtt_id")),
                utc(detectedAt));

        int expired = 0;
        for (ExpiredTarget target : targets) {
            int updated = jdbcTemplate.update("""
                    UPDATE robot.robot_command
                    SET command_status = 'EXPIRED',
                        completed_at = ?,
                        last_error_code = 'COMMAND_TTL_EXPIRED',
                        updated_at = GREATEST(updated_at, ?)
                    WHERE command_id = ?
                      AND command_status IN (
                          'CREATED',
                          'SENT',
                          'ACCEPTED'
                      )
                    """,
                    utc(detectedAt),
                    utc(detectedAt),
                    target.commandId());
            if (updated == 1) {
                expired++;
                eventPublisher.publishEvent(
                        RobotRealtimeEvent.commandStatusChanged(
                                target.robotId(),
                                target.mqttId(),
                                detectedAt));
            }
        }
        return expired;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record ExpiredTarget(
            UUID commandId,
            UUID robotId,
            String mqttId
    ) {
    }
}
