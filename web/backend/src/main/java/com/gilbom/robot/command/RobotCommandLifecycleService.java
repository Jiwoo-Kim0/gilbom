package com.gilbom.robot.command;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.robot.realtime.RobotRealtimeEvent;

@Service
public class RobotCommandLifecycleService {

    private final JdbcTemplate jdbcTemplate;
    private final RobotCommandRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    public RobotCommandLifecycleService(
            JdbcTemplate jdbcTemplate,
            RobotCommandRepository repository,
            ApplicationEventPublisher eventPublisher
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public RobotCommandRecord markSent(UUID commandId, Instant sentAt) {
        int updated = jdbcTemplate.update("""
                UPDATE robot.robot_command
                SET command_status = 'SENT',
                    sent_at = ?,
                    publish_attempt_count = publish_attempt_count + 1,
                    last_error_code = NULL,
                    updated_at = GREATEST(updated_at, ?)
                WHERE command_id = ?
                  AND command_status = 'CREATED'
                """,
                utc(sentAt),
                utc(sentAt),
                commandId);
        RobotCommandRecord command = require(commandId);
        if (updated == 1) {
            publish(command, sentAt);
        }
        return command;
    }

    @Transactional
    public RobotCommandRecord markPublishFailed(
            UUID commandId,
            Instant failedAt,
            String errorCode
    ) {
        int updated = jdbcTemplate.update("""
                UPDATE robot.robot_command
                SET command_status = 'PUBLISH_FAILED',
                    publish_attempt_count = publish_attempt_count + 1,
                    last_error_code = ?,
                    completed_at = ?,
                    updated_at = GREATEST(updated_at, ?)
                WHERE command_id = ?
                  AND command_status = 'CREATED'
                """,
                errorCode,
                utc(failedAt),
                utc(failedAt),
                commandId);
        RobotCommandRecord command = require(commandId);
        if (updated == 1) {
            publish(command, failedAt);
        }
        return command;
    }

    private RobotCommandRecord require(UUID commandId) {
        return repository.findById(commandId)
                .orElseThrow(() -> new IllegalStateException(
                        "Command lifecycle row was not found"));
    }

    private void publish(RobotCommandRecord command, Instant observedAt) {
        eventPublisher.publishEvent(
                RobotRealtimeEvent.commandStatusChanged(
                        command.robotId(),
                        command.mqttId(),
                        observedAt));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
