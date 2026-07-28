package com.gilbom.robot.command;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.gilbom.robot.application.RobotScopeService;
import com.gilbom.robot.data.api.request.RobotCommandCreateRequest;
import com.gilbom.robot.data.api.response.RobotCommandResultResponse;
import com.gilbom.robot.data.mqtt.v2.outbound.CommandRequestPayload;

@Repository
public class RobotCommandRepository {

    private static final String SELECT_COMMAND = """
            SELECT
                command.command_id,
                command.robot_id,
                r.mqtt_id,
                command.command_type,
                command.command_status,
                command.parameters::text AS parameters,
                command.requested_by_type,
                command.requested_by_user_id,
                requester.user_code AS requested_by_user_code,
                command.issued_at,
                command.expires_at,
                command.sent_at,
                command.accepted_at,
                command.completed_at,
                command.publish_attempt_count,
                command.last_error_code,
                command.created_at,
                command.updated_at
            FROM robot.robot_command command
            JOIN robot.robot r
              ON r.robot_id = command.robot_id
            LEFT JOIN auth.admin_user requester
              ON requester.user_id = command.requested_by_user_id
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RobotCommandRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper.copy().setPropertyNamingStrategy(
                PropertyNamingStrategies.SNAKE_CASE);
    }

    public Optional<RobotCommandRecord> findById(UUID commandId) {
        List<RobotCommandRecord> commands = jdbcTemplate.query(
                SELECT_COMMAND + """
                        WHERE command.command_id = :commandId
                        """,
                Map.of("commandId", commandId),
                (resultSet, rowNumber) -> mapCommand(resultSet));
        return commands.stream().findFirst();
    }

    public Optional<RobotCommandRecord> findVisibleById(
            UUID userId,
            UUID commandId
    ) {
        List<RobotCommandRecord> commands = jdbcTemplate.query(
                SELECT_COMMAND + """
                        WHERE command.command_id = :commandId
                          AND
                        """ + RobotScopeService.VISIBLE_ROBOT_PREDICATE,
                Map.of(
                        "userId", userId,
                        "commandId", commandId),
                (resultSet, rowNumber) -> mapCommand(resultSet));
        return commands.stream().findFirst();
    }

    public long countVisibleForRobot(UUID userId, UUID robotId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM robot.robot_command command
                JOIN robot.robot r
                  ON r.robot_id = command.robot_id
                WHERE command.robot_id = :robotId
                  AND
                """ + RobotScopeService.VISIBLE_ROBOT_PREDICATE,
                Map.of(
                        "userId", userId,
                        "robotId", robotId),
                Long.class);
        return count == null ? 0 : count;
    }

    public boolean existsVisibleRobot(UUID userId, UUID robotId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM robot.robot r
                    WHERE r.robot_id = :robotId
                      AND
                """ + RobotScopeService.VISIBLE_ROBOT_PREDICATE + """
                )
                """,
                Map.of(
                        "userId", userId,
                        "robotId", robotId),
                Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public List<RobotCommandRecord> findVisibleForRobot(
            UUID userId,
            UUID robotId,
            int size,
            long offset
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("robotId", robotId)
                .addValue("size", size)
                .addValue("offset", offset);
        return jdbcTemplate.query(
                SELECT_COMMAND + """
                        WHERE command.robot_id = :robotId
                          AND
                        """ + RobotScopeService.VISIBLE_ROBOT_PREDICATE + """
                        ORDER BY command.issued_at DESC, command.command_id DESC
                        LIMIT :size
                        OFFSET :offset
                        """,
                parameters,
                (resultSet, rowNumber) -> mapCommand(resultSet));
    }

    public List<RobotCommandResultResponse> findVisibleResults(
            UUID userId,
            UUID commandId
    ) {
        return jdbcTemplate.query("""
                SELECT
                    result.result_id,
                    result.command_id,
                    result.robot_id,
                    result.result_status,
                    result.robot_received_at,
                    result.robot_completed_at,
                    result.reason_code,
                    result.resulting_mode,
                    result.resulting_reason_code,
                    result.server_received_at
                FROM robot.robot_command_result result
                JOIN robot.robot_command command
                  ON command.command_id = result.command_id
                JOIN robot.robot r
                  ON r.robot_id = command.robot_id
                WHERE command.command_id = :commandId
                  AND
                """ + RobotScopeService.VISIBLE_ROBOT_PREDICATE + """
                ORDER BY result.server_received_at ASC, result.result_id ASC
                """,
                Map.of(
                        "userId", userId,
                        "commandId", commandId),
                (resultSet, rowNumber) -> new RobotCommandResultResponse(
                        resultSet.getObject("result_id", UUID.class),
                        resultSet.getObject("command_id", UUID.class),
                        resultSet.getObject("robot_id", UUID.class),
                        resultSet.getString("result_status"),
                        instant(resultSet, "robot_received_at"),
                        instant(resultSet, "robot_completed_at"),
                        resultSet.getString("reason_code"),
                        resultSet.getString("resulting_mode"),
                        resultSet.getString("resulting_reason_code"),
                        instant(resultSet, "server_received_at")));
    }

    private RobotCommandRecord mapCommand(ResultSet resultSet)
            throws SQLException {
        return new RobotCommandRecord(
                resultSet.getObject("command_id", UUID.class),
                resultSet.getObject("robot_id", UUID.class),
                resultSet.getString("mqtt_id"),
                CommandRequestPayload.CommandType.valueOf(
                        resultSet.getString("command_type")),
                RobotCommandStatus.valueOf(
                        resultSet.getString("command_status")),
                parseParameters(resultSet.getString("parameters")),
                resultSet.getString("requested_by_type"),
                resultSet.getObject("requested_by_user_id", UUID.class),
                resultSet.getString("requested_by_user_code"),
                instant(resultSet, "issued_at"),
                instant(resultSet, "expires_at"),
                instant(resultSet, "sent_at"),
                instant(resultSet, "accepted_at"),
                instant(resultSet, "completed_at"),
                resultSet.getInt("publish_attempt_count"),
                resultSet.getString("last_error_code"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"));
    }

    private RobotCommandCreateRequest.Parameters parseParameters(String json) {
        try {
            return objectMapper.readValue(
                    json,
                    RobotCommandCreateRequest.Parameters.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Stored command parameters are invalid",
                    exception);
        }
    }

    private static Instant instant(ResultSet resultSet, String column)
            throws SQLException {
        OffsetDateTime value = resultSet.getObject(
                column,
                OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
