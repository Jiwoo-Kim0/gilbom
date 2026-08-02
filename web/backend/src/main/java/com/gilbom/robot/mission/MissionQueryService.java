package com.gilbom.robot.mission;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.robot.application.RobotScopeService;
import com.gilbom.robot.data.api.response.MissionDtos.MissionListScope;
import com.gilbom.robot.data.api.response.MissionDtos.MissionListResponse;
import com.gilbom.robot.data.api.response.MissionDtos.MissionMapCurrentPosition;
import com.gilbom.robot.data.api.response.MissionDtos.MissionMapResponse;
import com.gilbom.robot.data.api.response.MissionDtos.MissionResponse;
import com.gilbom.robot.data.api.response.MissionDtos.MissionStatus;
import com.gilbom.robot.data.api.response.MissionDtos.MissionStatusHistoryItem;
import com.gilbom.robot.data.api.response.MissionDtos.MissionStatusHistoryResponse;
import com.gilbom.robot.data.api.response.MissionDtos.MissionTrajectoryPoint;
import com.gilbom.robot.data.api.response.MissionDtos.MissionTrajectoryResponse;

@Service
public class MissionQueryService {

    private static final String MISSION_SELECT = """
            SELECT
                m.mission_id,
                m.mission_code,
                m.robot_id,
                r.mqtt_id,
                r.robot_name,
                m.patrol_area_id,
                a.area_code AS patrol_area_code,
                a.area_name AS patrol_area_name,
                m.patrol_route_id,
                route.route_code AS patrol_route_code,
                route.route_name AS patrol_route_name,
                route.route_version AS patrol_route_version,
                m.planned_duration_sec,
                latest_progress.mission_elapsed_sec AS elapsed_sec,
                CASE
                    WHEN m.status = 'COMPLETED' THEN 0
                    WHEN latest_progress.mission_elapsed_sec IS NULL
                        THEN m.planned_duration_sec
                    ELSE GREATEST(
                        m.planned_duration_sec
                            - latest_progress.mission_elapsed_sec,
                        0
                    )
                END AS remaining_duration_sec,
                latest_progress.sampled_at AS progress_observed_at,
                CASE
                    WHEN m.status IN ('RUNNING', 'PAUSED')
                    THEN latest_progress.sampled_at IS NULL
                         OR latest_progress.sampled_at
                            < CURRENT_TIMESTAMP - INTERVAL '15 seconds'
                    ELSE FALSE
                END AS progress_stale,
                m.status,
                m.scheduled_start_at,
                m.started_at,
                m.ended_at,
                m.progress_percent,
                CASE
                    WHEN m.status IN ('RUNNING', 'PAUSED')
                     AND latest_progress.mission_elapsed_sec IS NOT NULL
                    THEN latest_progress.sampled_at
                         + GREATEST(
                             m.planned_duration_sec
                                 - latest_progress.mission_elapsed_sec,
                             0
                         ) * INTERVAL '1 second'
                    ELSE NULL
                END AS estimated_completion_at,
                m.completion_reason,
                m.result_duration_sec,
                m.result_incident_count,
                m.result_normal_detection_count,
                m.result_payload::TEXT AS result_payload,
                m.created_by,
                m.updated_by,
                m.created_at,
                m.updated_at
            FROM robot.mission m
            JOIN robot.robot r
              ON r.robot_id = m.robot_id
            JOIN robot.patrol_area a
              ON a.patrol_area_id = m.patrol_area_id
            JOIN robot.patrol_route route
              ON route.patrol_route_id = m.patrol_route_id
             AND route.patrol_area_id = m.patrol_area_id
            LEFT JOIN LATERAL (
                SELECT
                    telemetry.mission_elapsed_sec,
                    telemetry.sampled_at
                FROM telemetry.robot_telemetry telemetry
                WHERE telemetry.mission_id = m.mission_id
                  AND telemetry.mission_elapsed_sec IS NOT NULL
                ORDER BY
                    telemetry.mission_elapsed_sec DESC,
                    telemetry.sampled_at DESC,
                    telemetry.message_id DESC
                LIMIT 1
            ) latest_progress ON TRUE
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public MissionQueryService(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public MissionListResponse findMissions(
            UUID userId,
            int page,
            int size,
            UUID robotId,
            UUID patrolAreaId,
            UUID patrolRouteId,
            MissionListScope scope,
            MissionStatus status,
            Instant startedFrom,
            Instant startedTo
    ) {
        requireValidPeriod(startedFrom, startedTo);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("size", size)
                .addValue("offset", Math.multiplyExact((long) page, size));
        String filters = filters(
                parameters,
                robotId,
                patrolAreaId,
                patrolRouteId,
                scope,
                status,
                startedFrom,
                startedTo);

        Long total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM robot.mission m
                JOIN robot.robot r
                  ON r.robot_id = m.robot_id
                WHERE
                """ + RobotScopeService.VISIBLE_ROBOT_PREDICATE
                        + " AND m.deleted_at IS NULL"
                        + filters,
                parameters,
                Long.class);

        List<MissionResponse> content = jdbcTemplate.query(
                MISSION_SELECT + """
                WHERE
                """ + RobotScopeService.VISIBLE_ROBOT_PREDICATE
                        + " AND m.deleted_at IS NULL"
                        + filters + "\n" + """
                ORDER BY
                    COALESCE(
                        m.started_at,
                        m.scheduled_start_at,
                        m.created_at
                    ) DESC,
                    m.mission_id DESC
                LIMIT :size
                OFFSET :offset
                """,
                parameters,
                (resultSet, rowNumber) -> readMission(resultSet));

        long totalElements = total == null ? 0 : total;
        return new MissionListResponse(
                content,
                page,
                size,
                totalElements,
                Math.toIntExact(Math.ceilDiv(totalElements, size)));
    }

    @Transactional(readOnly = true)
    public MissionResponse findMission(UUID userId, UUID missionId) {
        List<MissionResponse> missions = jdbcTemplate.query(
                MISSION_SELECT + """
                WHERE m.mission_id = :missionId
                  AND m.deleted_at IS NULL
                  AND
                """ + RobotScopeService.VISIBLE_ROBOT_PREDICATE,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("missionId", missionId),
                (resultSet, rowNumber) -> readMission(resultSet));
        if (missions.isEmpty()) {
            throw new BusinessException(ErrorCode.MISSION_NOT_FOUND);
        }
        return missions.getFirst();
    }

    @Transactional(readOnly = true)
    public MissionTrajectoryResponse findTrajectory(
            UUID userId,
            UUID missionId,
            Instant from,
            Instant to,
            int limit
    ) {
        findMission(userId, missionId);
        requireValidPeriod(from, to);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("missionId", missionId)
                .addValue("limit", limit);
        StringBuilder period = new StringBuilder();
        if (from != null) {
            period.append(" AND telemetry.sampled_at >= :from");
            parameters.addValue("from", utc(from));
        }
        if (to != null) {
            period.append(" AND telemetry.sampled_at < :to");
            parameters.addValue("to", utc(to));
        }

        List<MissionTrajectoryPoint> points = jdbcTemplate.query("""
                SELECT
                    telemetry.message_id,
                    telemetry.sampled_at,
                    telemetry.received_at,
                    ST_Y(telemetry.position) AS latitude,
                    ST_X(telemetry.position) AS longitude,
                    telemetry.heading_deg,
                    telemetry.moving,
                    telemetry.speed_mps
                FROM telemetry.robot_telemetry telemetry
                WHERE telemetry.mission_id = :missionId
                  AND telemetry.position_valid = TRUE
                  AND telemetry.position IS NOT NULL
                """ + period + "\n" + """
                ORDER BY telemetry.sampled_at ASC, telemetry.message_id ASC
                LIMIT :limit
                """,
                parameters,
                (resultSet, rowNumber) -> new MissionTrajectoryPoint(
                        resultSet.getObject("message_id", UUID.class),
                        instant(resultSet, "sampled_at"),
                        instant(resultSet, "received_at"),
                        resultSet.getDouble("latitude"),
                        resultSet.getDouble("longitude"),
                        nullableDouble(resultSet, "heading_deg"),
                        resultSet.getBoolean("moving"),
                        nullableDouble(resultSet, "speed_mps")));
        return new MissionTrajectoryResponse(missionId, points);
    }

    @Transactional(readOnly = true)
    public MissionMapResponse findMap(
            UUID userId,
            UUID missionId,
            int limit
    ) {
        List<MissionMapContext> contexts = jdbcTemplate.query("""
                SELECT
                    m.patrol_area_id,
                    ST_AsGeoJSON(area.boundary)::TEXT
                        AS patrol_area_boundary,
                    CASE
                        WHEN state.position_valid = TRUE
                         AND state.position IS NOT NULL
                        THEN ST_Y(state.position)
                        ELSE NULL
                    END AS current_latitude,
                    CASE
                        WHEN state.position_valid = TRUE
                         AND state.position IS NOT NULL
                        THEN ST_X(state.position)
                        ELSE NULL
                    END AS current_longitude,
                    state.heading_deg,
                    state.moving,
                    state.speed_mps,
                    state.connectivity_status,
                    state.telemetry_sampled_at
                FROM robot.mission m
                JOIN robot.robot r
                  ON r.robot_id = m.robot_id
                JOIN robot.patrol_area area
                  ON area.patrol_area_id = m.patrol_area_id
                LEFT JOIN telemetry.robot_current_state state
                  ON state.robot_id = m.robot_id
                WHERE m.mission_id = :missionId
                  AND m.deleted_at IS NULL
                  AND
                """ + RobotScopeService.VISIBLE_ROBOT_PREDICATE,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("missionId", missionId),
                (resultSet, rowNumber) -> new MissionMapContext(
                        resultSet.getObject(
                                "patrol_area_id",
                                UUID.class),
                        json(resultSet.getString(
                                "patrol_area_boundary")),
                        currentPosition(resultSet)));
        if (contexts.isEmpty()) {
            throw new BusinessException(ErrorCode.MISSION_NOT_FOUND);
        }

        MissionMapContext context = contexts.getFirst();
        MissionTrajectoryResponse trajectory = findTrajectory(
                userId,
                missionId,
                null,
                null,
                limit);
        return new MissionMapResponse(
                missionId,
                context.patrolAreaId(),
                context.patrolAreaBoundary(),
                trajectory.points(),
                context.currentPosition());
    }

    @Transactional(readOnly = true)
    public MissionStatusHistoryResponse findHistory(
            UUID userId,
            UUID missionId
    ) {
        findMission(userId, missionId);
        List<MissionStatusHistoryItem> history = jdbcTemplate.query("""
                SELECT
                    history.history_id,
                    history.transition_source,
                    history.before_status,
                    history.after_status,
                    history.transition_reason,
                    history.changed_by,
                    actor.user_code AS changed_by_user_code,
                    history.source_event_id,
                    history.occurred_at,
                    history.received_at,
                    history.created_at
                FROM robot.mission_status_history history
                LEFT JOIN auth.admin_user actor
                  ON actor.user_id = history.changed_by
                WHERE history.mission_id = :missionId
                ORDER BY
                    history.occurred_at ASC,
                    history.history_id ASC
                """,
                new MapSqlParameterSource("missionId", missionId),
                (resultSet, rowNumber) -> new MissionStatusHistoryItem(
                        resultSet.getObject("history_id", UUID.class),
                        resultSet.getString("transition_source"),
                        status(resultSet.getString("before_status")),
                        status(resultSet.getString("after_status")),
                        resultSet.getString("transition_reason"),
                        resultSet.getObject("changed_by", UUID.class),
                        resultSet.getString("changed_by_user_code"),
                        resultSet.getObject("source_event_id", UUID.class),
                        instant(resultSet, "occurred_at"),
                        instant(resultSet, "received_at"),
                        instant(resultSet, "created_at")));
        return new MissionStatusHistoryResponse(missionId, history);
    }

    private String filters(
            MapSqlParameterSource parameters,
            UUID robotId,
            UUID patrolAreaId,
            UUID patrolRouteId,
            MissionListScope scope,
            MissionStatus status,
            Instant startedFrom,
            Instant startedTo
    ) {
        StringBuilder filters = new StringBuilder();
        if (robotId != null) {
            filters.append(" AND m.robot_id = :robotId");
            parameters.addValue("robotId", robotId);
        }
        if (patrolAreaId != null) {
            filters.append(" AND m.patrol_area_id = :patrolAreaId");
            parameters.addValue("patrolAreaId", patrolAreaId);
        }
        if (patrolRouteId != null) {
            filters.append(" AND m.patrol_route_id = :patrolRouteId");
            parameters.addValue("patrolRouteId", patrolRouteId);
        }
        if (status != null) {
            filters.append(" AND m.status = :status");
            parameters.addValue("status", status.name());
        } else if (scope != null) {
            switch (scope) {
                case ACTIVE -> filters.append(
                        " AND m.status IN ('CREATED', 'READY', 'RUNNING', 'PAUSED')");
                case TERMINAL -> filters.append(
                        " AND m.status IN ('COMPLETED', 'CANCELLED', 'FAILED')");
                case ALL -> {
                    // 상태 범위 조건을 추가하지 않는다.
                }
            }
        }
        if (startedFrom != null) {
            filters.append(" AND m.started_at >= :startedFrom");
            parameters.addValue("startedFrom", utc(startedFrom));
        }
        if (startedTo != null) {
            filters.append(" AND m.started_at < :startedTo");
            parameters.addValue("startedTo", utc(startedTo));
        }
        return filters.toString();
    }

    private MissionResponse readMission(ResultSet resultSet)
            throws SQLException {
        return new MissionResponse(
                resultSet.getObject("mission_id", UUID.class),
                resultSet.getString("mission_code"),
                resultSet.getObject("robot_id", UUID.class),
                resultSet.getString("mqtt_id"),
                resultSet.getString("robot_name"),
                resultSet.getObject("patrol_area_id", UUID.class),
                resultSet.getString("patrol_area_code"),
                resultSet.getString("patrol_area_name"),
                resultSet.getObject("patrol_route_id", UUID.class),
                resultSet.getString("patrol_route_code"),
                resultSet.getString("patrol_route_name"),
                resultSet.getInt("patrol_route_version"),
                resultSet.getInt("planned_duration_sec"),
                resultSet.getObject("elapsed_sec", Integer.class),
                resultSet.getObject(
                        "remaining_duration_sec",
                        Integer.class),
                instant(resultSet, "progress_observed_at"),
                resultSet.getBoolean("progress_stale"),
                MissionStatus.valueOf(resultSet.getString("status")),
                instant(resultSet, "scheduled_start_at"),
                instant(resultSet, "started_at"),
                instant(resultSet, "ended_at"),
                resultSet.getBigDecimal("progress_percent"),
                instant(resultSet, "estimated_completion_at"),
                resultSet.getString("completion_reason"),
                resultSet.getObject("result_duration_sec", Integer.class),
                resultSet.getObject(
                        "result_incident_count",
                        Integer.class),
                resultSet.getObject(
                        "result_normal_detection_count",
                        Integer.class),
                json(resultSet.getString("result_payload")),
                resultSet.getObject("created_by", UUID.class),
                resultSet.getObject("updated_by", UUID.class),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"));
    }

    private void requireValidPeriod(Instant from, Instant to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new BusinessException(
                    ErrorCode.COMMON_BUSINESS_RULE_VIOLATION,
                    "조회 시작 시각은 종료 시각보다 빨라야 합니다.");
        }
    }

    private JsonNode json(String value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Mission result_payload is not valid JSON",
                    exception);
        }
    }

    private static MissionStatus status(String value) {
        return value == null ? null : MissionStatus.valueOf(value);
    }

    private static Instant instant(ResultSet resultSet, String column)
            throws SQLException {
        OffsetDateTime value = resultSet.getObject(
                column,
                OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Double nullableDouble(
            ResultSet resultSet,
            String column
    ) throws SQLException {
        double value = resultSet.getDouble(column);
        return resultSet.wasNull() ? null : value;
    }

    private static MissionMapCurrentPosition currentPosition(
            ResultSet resultSet
    ) throws SQLException {
        Double latitude = nullableDouble(resultSet, "current_latitude");
        Double longitude = nullableDouble(resultSet, "current_longitude");
        if (latitude == null || longitude == null) {
            return null;
        }
        return new MissionMapCurrentPosition(
                latitude,
                longitude,
                nullableDouble(resultSet, "heading_deg"),
                resultSet.getObject("moving", Boolean.class),
                nullableDouble(resultSet, "speed_mps"),
                resultSet.getString("connectivity_status"),
                instant(resultSet, "telemetry_sampled_at"));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record MissionMapContext(
            UUID patrolAreaId,
            JsonNode patrolAreaBoundary,
            MissionMapCurrentPosition currentPosition
    ) {
    }
}
