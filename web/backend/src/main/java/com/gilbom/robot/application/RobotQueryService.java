package com.gilbom.robot.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.robot.data.api.response.RobotDetailResponse;
import com.gilbom.robot.data.api.response.RobotListItemResponse;
import com.gilbom.robot.data.api.response.RobotListResponse;
import com.gilbom.robot.data.api.response.RobotMapMarkerResponse;
import com.gilbom.robot.data.api.response.RobotMapResponse;
import com.gilbom.robot.data.api.response.RobotSummaryResponse;

@Service
public class RobotQueryService {

    private static final String SEARCH_PREDICATE = """
            AND (
                r.mqtt_id ILIKE CONCAT('%', :search, '%')
                OR r.robot_name ILIKE CONCAT('%', :search, '%')
                OR r.serial_number ILIKE CONCAT('%', :search, '%')
            )
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public RobotQueryService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public RobotListResponse findRobots(
            UUID userId,
            int page,
            int size,
            String rawSearch
    ) {
        String search = normalizeSearch(rawSearch);
        String searchPredicate = search == null ? "" : SEARCH_PREDICATE;
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("size", size)
                .addValue("offset", Math.multiplyExact((long) page, size));
        if (search != null) {
            parameters.addValue("search", search);
        }

        Long total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM robot.robot r
                WHERE
                """ + RobotScopeService.VISIBLE_ROBOT_PREDICATE
                        + searchPredicate,
                parameters,
                Long.class);

        List<RobotListItemResponse> content = jdbcTemplate.query("""
                SELECT
                    r.robot_id,
                    r.mqtt_id,
                    r.robot_name,
                    r.serial_number,
                    r.operational_status,
                    state.connectivity_status,
                    state.last_received_at,
                    state.battery_percent,
                    state.charging,
                    state.jetson_temp_c,
                    state.speed_mps,
                    state.robot_mode,
                    CASE
                        WHEN managed_mission.status IS NULL
                            THEN state.mission_status
                        WHEN managed_mission.deleted_at IS NULL
                            THEN managed_mission.status
                        ELSE NULL
                    END AS mission_status,
                    NOT EXISTS (
                        SELECT 1
                        FROM robot.mission active_mission
                        WHERE active_mission.robot_id = r.robot_id
                          AND active_mission.deleted_at IS NULL
                          AND active_mission.status IN (
                              'CREATED',
                              'READY',
                              'RUNNING',
                              'PAUSED'
                          )
                    ) AS mission_assignable,
                    state.overall_health_status
                FROM robot.robot r
                JOIN telemetry.robot_current_state state
                  ON state.robot_id = r.robot_id
                LEFT JOIN LATERAL (
                    SELECT
                        mission.status,
                        mission.deleted_at
                    FROM robot.mission mission
                    WHERE mission.robot_id = r.robot_id
                    ORDER BY
                        CASE
                            WHEN mission.deleted_at IS NULL
                              AND mission.status IN (
                                  'CREATED',
                                  'READY',
                                  'RUNNING',
                                  'PAUSED'
                              ) THEN 0
                            WHEN mission.deleted_at IS NULL THEN 1
                            ELSE 2
                        END,
                        mission.updated_at DESC,
                        mission.mission_id DESC
                    LIMIT 1
                ) managed_mission ON TRUE
                WHERE
                """ + RobotScopeService.VISIBLE_ROBOT_PREDICATE
                        + searchPredicate + """
                ORDER BY r.robot_name ASC, r.robot_id ASC
                LIMIT :size
                OFFSET :offset
                """,
                parameters,
                (resultSet, rowNumber) -> new RobotListItemResponse(
                        resultSet.getObject("robot_id", UUID.class),
                        resultSet.getString("mqtt_id"),
                        resultSet.getString("robot_name"),
                        resultSet.getString("serial_number"),
                        resultSet.getString("operational_status"),
                        resultSet.getString("connectivity_status"),
                        instant(resultSet, "last_received_at"),
                        resultSet.getBigDecimal("battery_percent"),
                        resultSet.getObject("charging", Boolean.class),
                        nullableDouble(resultSet, "jetson_temp_c"),
                        nullableDouble(resultSet, "speed_mps"),
                        resultSet.getString("robot_mode"),
                        resultSet.getString("mission_status"),
                        resultSet.getBoolean("mission_assignable"),
                        resultSet.getString("overall_health_status")));

        long totalElements = total == null ? 0 : total;
        int totalPages = Math.toIntExact(
                Math.ceilDiv(totalElements, size));
        return new RobotListResponse(
                content,
                page,
                size,
                totalElements,
                totalPages);
    }

    @Transactional(readOnly = true)
    public RobotSummaryResponse summarize(UUID userId) {
        return jdbcTemplate.queryForObject("""
                SELECT
                    COUNT(*) AS total_robots,
                    COUNT(*) FILTER (
                        WHERE state.connectivity_status = 'ONLINE'
                    ) AS online_robots,
                    COUNT(*) FILTER (
                        WHERE state.connectivity_status = 'OFFLINE'
                    ) AS offline_robots,
                    COUNT(*) FILTER (
                        WHERE (
                            state.overall_health_status = 'WARNING'
                            OR state.battery_state = 'LOW'
                        )
                          AND state.overall_health_status <> 'CRITICAL'
                          AND state.battery_state IS DISTINCT FROM 'CRITICAL'
                    ) AS warning_robots,
                    COUNT(*) FILTER (
                        WHERE state.overall_health_status = 'CRITICAL'
                           OR state.battery_state = 'CRITICAL'
                    ) AS critical_robots
                FROM robot.robot r
                JOIN telemetry.robot_current_state state
                  ON state.robot_id = r.robot_id
                WHERE
                """ + RobotScopeService.VISIBLE_ROBOT_PREDICATE,
                Map.of("userId", userId),
                (resultSet, rowNumber) -> new RobotSummaryResponse(
                        resultSet.getLong("total_robots"),
                        resultSet.getLong("online_robots"),
                        resultSet.getLong("offline_robots"),
                        resultSet.getLong("warning_robots"),
                        resultSet.getLong("critical_robots")));
    }

    @Transactional(readOnly = true)
    public RobotMapResponse findMapRobots(UUID userId) {
        List<RobotMapMarkerResponse> robots = jdbcTemplate.query("""
                SELECT
                    r.robot_id,
                    r.mqtt_id,
                    r.robot_name,
                    state.connectivity_status,
                    ST_Y(state.position) AS latitude,
                    ST_X(state.position) AS longitude,
                    state.heading_deg,
                    CASE
                        WHEN managed_mission.status IS NULL
                            THEN state.mission_status
                        WHEN managed_mission.deleted_at IS NULL
                            THEN managed_mission.status
                        ELSE NULL
                    END AS mission_status,
                    state.telemetry_sampled_at
                FROM robot.robot r
                JOIN telemetry.robot_current_state state
                  ON state.robot_id = r.robot_id
                LEFT JOIN LATERAL (
                    SELECT
                        mission.status,
                        mission.deleted_at
                    FROM robot.mission mission
                    WHERE mission.robot_id = r.robot_id
                    ORDER BY
                        CASE
                            WHEN mission.deleted_at IS NULL
                              AND mission.status IN (
                                  'CREATED',
                                  'READY',
                                  'RUNNING',
                                  'PAUSED'
                              ) THEN 0
                            WHEN mission.deleted_at IS NULL THEN 1
                            ELSE 2
                        END,
                        mission.updated_at DESC,
                        mission.mission_id DESC
                    LIMIT 1
                ) managed_mission ON TRUE
                WHERE state.position_valid = TRUE
                  AND state.position IS NOT NULL
                  AND
                """ + RobotScopeService.VISIBLE_ROBOT_PREDICATE + """
                ORDER BY r.robot_name ASC, r.robot_id ASC
                """,
                Map.of("userId", userId),
                (resultSet, rowNumber) -> new RobotMapMarkerResponse(
                        resultSet.getObject("robot_id", UUID.class),
                        resultSet.getString("mqtt_id"),
                        resultSet.getString("robot_name"),
                        resultSet.getString("connectivity_status"),
                        nullableDouble(resultSet, "latitude"),
                        nullableDouble(resultSet, "longitude"),
                        nullableDouble(resultSet, "heading_deg"),
                        resultSet.getString("mission_status"),
                        instant(resultSet, "telemetry_sampled_at")));
        return new RobotMapResponse(robots);
    }

    @Transactional(readOnly = true)
    public RobotDetailResponse findRobot(UUID userId, UUID robotId) {
        List<RobotDetailResponse> robots = jdbcTemplate.query("""
                SELECT
                    r.robot_id,
                    r.mqtt_id,
                    r.robot_name,
                    r.serial_number,
                    r.operational_status,
                    r.registered_at,
                    r.updated_at AS robot_updated_at,
                    state.connectivity_status,
                    state.connection_changed_at,
                    state.last_received_at,
                    state.robot_mode,
                    state.state_reason_code,
                    CASE
                        WHEN managed_mission.status IS NULL
                            THEN state.mission_status
                        WHEN managed_mission.deleted_at IS NULL
                            THEN managed_mission.status
                        ELSE NULL
                    END AS mission_status,
                    state.battery_percent,
                    state.battery_state,
                    state.charging,
                    state.battery_temperature_c,
                    state.estimated_runtime_sec,
                    state.camera_health,
                    state.lidar_health,
                    state.gps_health,
                    state.motor_health,
                    state.storage_health,
                    CASE
                        WHEN state.position_valid = TRUE
                            THEN ST_Y(state.position)
                        ELSE NULL
                    END AS latitude,
                    CASE
                        WHEN state.position_valid = TRUE
                            THEN ST_X(state.position)
                        ELSE NULL
                    END AS longitude,
                    state.heading_deg,
                    state.moving,
                    state.speed_mps,
                    state.jetson_temp_c,
                    state.overall_health_status,
                    state.telemetry_sampled_at,
                    state.telemetry_received_at
                FROM robot.robot r
                JOIN telemetry.robot_current_state state
                  ON state.robot_id = r.robot_id
                LEFT JOIN LATERAL (
                    SELECT
                        mission.status,
                        mission.deleted_at
                    FROM robot.mission mission
                    WHERE mission.robot_id = r.robot_id
                    ORDER BY
                        CASE
                            WHEN mission.deleted_at IS NULL
                              AND mission.status IN (
                                  'CREATED',
                                  'READY',
                                  'RUNNING',
                                  'PAUSED'
                              ) THEN 0
                            WHEN mission.deleted_at IS NULL THEN 1
                            ELSE 2
                        END,
                        mission.updated_at DESC,
                        mission.mission_id DESC
                    LIMIT 1
                ) managed_mission ON TRUE
                WHERE r.robot_id = :robotId
                  AND
                """ + RobotScopeService.VISIBLE_ROBOT_PREDICATE,
                Map.of(
                        "userId", userId,
                        "robotId", robotId),
                (resultSet, rowNumber) -> mapDetail(resultSet));
        if (robots.isEmpty()) {
            throw new BusinessException(ErrorCode.ROBOT_NOT_FOUND);
        }
        return robots.getFirst();
    }

    private RobotDetailResponse mapDetail(ResultSet resultSet)
            throws SQLException {
        return new RobotDetailResponse(
                resultSet.getObject("robot_id", UUID.class),
                resultSet.getString("mqtt_id"),
                resultSet.getString("robot_name"),
                resultSet.getString("serial_number"),
                resultSet.getString("operational_status"),
                resultSet.getString("connectivity_status"),
                instant(resultSet, "connection_changed_at"),
                instant(resultSet, "last_received_at"),
                resultSet.getString("robot_mode"),
                resultSet.getString("state_reason_code"),
                resultSet.getString("mission_status"),
                resultSet.getBigDecimal("battery_percent"),
                resultSet.getString("battery_state"),
                resultSet.getObject("charging", Boolean.class),
                nullableDouble(resultSet, "battery_temperature_c"),
                resultSet.getObject("estimated_runtime_sec", Integer.class),
                resultSet.getString("camera_health"),
                resultSet.getString("lidar_health"),
                resultSet.getString("gps_health"),
                resultSet.getString("motor_health"),
                resultSet.getString("storage_health"),
                nullableDouble(resultSet, "latitude"),
                nullableDouble(resultSet, "longitude"),
                nullableDouble(resultSet, "heading_deg"),
                resultSet.getObject("moving", Boolean.class),
                nullableDouble(resultSet, "speed_mps"),
                nullableDouble(resultSet, "jetson_temp_c"),
                resultSet.getString("overall_health_status"),
                instant(resultSet, "telemetry_sampled_at"),
                instant(resultSet, "telemetry_received_at"),
                instant(resultSet, "registered_at"),
                instant(resultSet, "robot_updated_at"));
    }

    private String normalizeSearch(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        return search.trim();
    }

    private static Instant instant(ResultSet resultSet, String column)
            throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Double nullableDouble(ResultSet resultSet, String column)
            throws SQLException {
        double value = resultSet.getDouble(column);
        return resultSet.wasNull() ? null : value;
    }
}
