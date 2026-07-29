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

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.robot.data.mqtt.v2.inbound.CommandResultPayload;
import com.gilbom.robot.mqtt.parser.MqttInboundFreshnessGuard;
import com.gilbom.robot.mqtt.topic.MqttTopicType;
import com.gilbom.robot.realtime.RobotRealtimeEvent;

@Service
public class RobotCommandResultIngestionService {

    private final JdbcTemplate jdbcTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final MqttInboundFreshnessGuard freshnessGuard;

    public RobotCommandResultIngestionService(
            JdbcTemplate jdbcTemplate,
            ApplicationEventPublisher eventPublisher,
            MqttInboundFreshnessGuard freshnessGuard
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.eventPublisher = eventPublisher;
        this.freshnessGuard = freshnessGuard;
    }

    @Transactional
    public IngestionResult ingest(
            CommandResultPayload payload,
            String rawPayload,
            Instant serverReceivedAt
    ) {
        CommandTarget target = lockCommand(
                payload.commandId(),
                payload.mqttId());
        freshnessGuard.validateExpiry(
                MqttTopicType.COMMAND_RESULT,
                target.expiresAt(),
                serverReceivedAt);

        int inserted = jdbcTemplate.update("""
                INSERT INTO robot.robot_command_result (
                    result_id,
                    command_id,
                    robot_id,
                    schema_version,
                    result_status,
                    robot_received_at,
                    robot_completed_at,
                    reason_code,
                    resulting_mode,
                    resulting_reason_code,
                    server_received_at,
                    raw_payload
                ) VALUES (
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?::jsonb
                )
                ON CONFLICT (result_id) DO NOTHING
                """,
                payload.resultId(),
                payload.commandId(),
                target.robotId(),
                payload.schemaVersion(),
                payload.status().name(),
                utc(payload.receivedAt()),
                utc(payload.completedAt()),
                payload.reasonCode(),
                payload.state().mode().name(),
                payload.state().reasonCode().name(),
                utc(serverReceivedAt),
                rawPayload);
        if (inserted == 0) {
            if (!isSameStoredResult(payload, rawPayload)) {
                throw new BusinessException(
                        ErrorCode.COMMON_CONFLICT,
                        "같은 result_id를 다른 명령 결과에 사용할 수 없습니다.");
            }
            return new IngestionResult(false, false, target.status());
        }

        RobotCommandStatus nextStatus = nextStatus(
                target.status(),
                payload.status());
        boolean changed = nextStatus != target.status();
        if (changed) {
            int updated = jdbcTemplate.update("""
                    UPDATE robot.robot_command
                    SET command_status = ?,
                        accepted_at = CASE
                            WHEN ? = 'ACCEPTED'
                                THEN COALESCE(accepted_at, ?)
                            ELSE accepted_at
                        END,
                        completed_at = CASE
                            WHEN ? <> 'ACCEPTED' THEN ?
                            ELSE completed_at
                        END,
                        last_error_code = CASE
                            WHEN ? IN ('FAILED', 'REJECTED', 'EXPIRED')
                                THEN ?
                            ELSE last_error_code
                        END,
                        updated_at = GREATEST(updated_at, ?)
                    WHERE command_id = ?
                      AND command_status = ?
                    """,
                    nextStatus.name(),
                    nextStatus.name(),
                    utc(serverReceivedAt),
                    nextStatus.name(),
                    utc(serverReceivedAt),
                    nextStatus.name(),
                    payload.reasonCode(),
                    utc(serverReceivedAt),
                    payload.commandId(),
                    target.status().name());
            if (updated != 1) {
                throw new IllegalStateException(
                        "Locked command state changed unexpectedly");
            }
            eventPublisher.publishEvent(
                    RobotRealtimeEvent.commandStatusChanged(
                            target.robotId(),
                            target.mqttId(),
                            serverReceivedAt));
        }
        return new IngestionResult(true, changed, nextStatus);
    }

    private boolean isSameStoredResult(
            CommandResultPayload payload,
            String rawPayload
    ) {
        Boolean same = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM robot.robot_command_result result
                    JOIN robot.robot robot
                      ON robot.robot_id = result.robot_id
                    WHERE result.result_id = ?
                      AND result.command_id = ?
                      AND robot.mqtt_id = ?
                      AND result.raw_payload = ?::jsonb
                )
                """,
                Boolean.class,
                payload.resultId(),
                payload.commandId(),
                payload.mqttId(),
                rawPayload);
        return Boolean.TRUE.equals(same);
    }

    private CommandTarget lockCommand(UUID commandId, String mqttId) {
        List<CommandTarget> commands = jdbcTemplate.query("""
                SELECT
                    command.robot_id,
                    robot.mqtt_id,
                    command.command_status,
                    command.expires_at
                FROM robot.robot_command command
                JOIN robot.robot robot
                  ON robot.robot_id = command.robot_id
                WHERE command.command_id = ?
                  AND robot.mqtt_id = ?
                FOR UPDATE OF command
                """,
                (resultSet, rowNumber) -> new CommandTarget(
                        resultSet.getObject("robot_id", UUID.class),
                        resultSet.getString("mqtt_id"),
                        RobotCommandStatus.valueOf(
                                resultSet.getString("command_status")),
                        instant(resultSet, "expires_at")),
                commandId,
                mqttId);
        if (commands.isEmpty()) {
            throw new BusinessException(ErrorCode.COMMAND_NOT_FOUND);
        }
        return commands.getFirst();
    }

    private static RobotCommandStatus nextStatus(
            RobotCommandStatus current,
            CommandResultPayload.Status received
    ) {
        if (current.isTerminal()) {
            return current;
        }
        if (received == CommandResultPayload.Status.ACCEPTED) {
            return current == RobotCommandStatus.ACCEPTED
                    ? current
                    : RobotCommandStatus.ACCEPTED;
        }
        return RobotCommandStatus.valueOf(received.name());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null
                ? null
                : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(
            java.sql.ResultSet resultSet,
            String column
    ) throws java.sql.SQLException {
        OffsetDateTime value = resultSet.getObject(
                column,
                OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public record IngestionResult(
            boolean stored,
            boolean commandStatusChanged,
            RobotCommandStatus commandStatus
    ) {
    }

    private record CommandTarget(
            UUID robotId,
            String mqttId,
            RobotCommandStatus status,
            Instant expiresAt
    ) {
    }
}
