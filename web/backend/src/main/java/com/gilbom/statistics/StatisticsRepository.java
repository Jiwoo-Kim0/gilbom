package com.gilbom.statistics;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.gilbom.defect.DefectScopeService;
import com.gilbom.robot.application.RobotScopeService;
import com.gilbom.statistics.StatisticsDtos.DefectType;
import com.gilbom.statistics.StatisticsDtos.DefectWorkflowStatus;

@Repository
public class StatisticsRepository {

    private static final String DEFECTS_BY_STATUS_SQL = """
            SELECT
                d.workflow_status,
                COUNT(*) AS defect_count
            FROM defect.defect d
            WHERE
            """ + DefectScopeService.VISIBLE_DEFECT_PREDICATE + """
              AND d.merged_into_defect_id IS NULL
              AND d.last_seen_at >= :from
              AND d.last_seen_at < :to
            GROUP BY d.workflow_status
            """;

    private static final String DEFECTS_BY_TYPE_SQL = """
            SELECT
                d.defect_type,
                COUNT(DISTINCT d.defect_id) AS defect_count
            FROM defect.defect d
            WHERE
            """ + DefectScopeService.VISIBLE_DEFECT_PREDICATE + """
              AND d.merged_into_defect_id IS NULL
              AND d.last_seen_at >= :from
              AND d.last_seen_at < :to
            GROUP BY d.defect_type
            """;

    private static final String PATROL_AREA_EXISTS_SQL = """
            SELECT EXISTS (
                SELECT 1
                FROM robot.patrol_area
                WHERE patrol_area_id = :patrolAreaId
            )
            """;

    private static final String PATROL_AREA_COUNT_SQL = """
            SELECT COUNT(*)
            FROM robot.patrol_area area
            WHERE (
                CAST(:patrolAreaId AS uuid) IS NULL
                OR area.patrol_area_id = :patrolAreaId
            )
            """;

    private static final String STATISTICS_BY_AREA_SQL = """
            WITH selected_areas AS (
                SELECT
                    area.patrol_area_id,
                    area.area_code,
                    area.area_name
                FROM robot.patrol_area area
                WHERE (
                    CAST(:patrolAreaId AS uuid) IS NULL
                    OR area.patrol_area_id = :patrolAreaId
                )
                ORDER BY area.area_code, area.patrol_area_id
                LIMIT :size
                OFFSET :offset
            ),
            visible_mission_source AS (
                SELECT
                    mission.mission_id,
                    mission.patrol_area_id,
                    mission.status,
                    mission.result_duration_sec
                FROM robot.mission mission
                JOIN robot.robot r
                  ON r.robot_id = mission.robot_id
                WHERE
            """ + RobotScopeService.VISIBLE_ROBOT_PREDICATE + """
                  AND mission.deleted_at IS NULL
                  AND mission.started_at >= :from
                  AND mission.started_at < :to
                  AND (
                      CAST(:patrolAreaId AS uuid) IS NULL
                      OR mission.patrol_area_id = :patrolAreaId
                  )
            ),
            mission_aggregate AS (
                SELECT
                    patrol_area_id,
                    COUNT(*) AS mission_count,
                    COUNT(*) FILTER (
                        WHERE status = 'COMPLETED'
                    ) AS completed_mission_count,
                    COUNT(*) FILTER (
                        WHERE status = 'CANCELLED'
                    ) AS cancelled_mission_count,
                    COUNT(*) FILTER (
                        WHERE status = 'FAILED'
                    ) AS failed_mission_count,
                    COALESCE(
                        SUM(COALESCE(result_duration_sec, 0)),
                        0
                    ) AS total_mission_duration_seconds
                FROM visible_mission_source
                GROUP BY patrol_area_id
            ),
            visible_incident_source AS (
                SELECT
                    event.event_id,
                    mission.patrol_area_id
                FROM telemetry.robot_event event
                JOIN robot.mission mission
                  ON mission.mission_id = event.mission_id
                 AND mission.robot_id = event.robot_id
                JOIN robot.robot r
                  ON r.robot_id = event.robot_id
                WHERE
            """ + RobotScopeService.VISIBLE_ROBOT_PREDICATE + """
                  AND event.event_type =
                      'BRAILLE_BLOCK_INCIDENT_DETECTED'
                  AND event.occurred_at >= :from
                  AND event.occurred_at < :to
                  AND (
                      CAST(:patrolAreaId AS uuid) IS NULL
                      OR mission.patrol_area_id = :patrolAreaId
                  )
            ),
            incident_aggregate AS (
                SELECT
                    patrol_area_id,
                    COUNT(DISTINCT event_id) AS incident_event_count
                FROM visible_incident_source
                GROUP BY patrol_area_id
            ),
            visible_defect_source AS (
                SELECT
                    d.defect_id,
                    d.patrol_area_id
                FROM defect.defect d
                WHERE
            """ + DefectScopeService.VISIBLE_DEFECT_PREDICATE + """
                  AND d.merged_into_defect_id IS NULL
                  AND d.last_seen_at >= :from
                  AND d.last_seen_at < :to
                  AND (
                      CAST(:patrolAreaId AS uuid) IS NULL
                      OR d.patrol_area_id = :patrolAreaId
                  )
            ),
            defect_aggregate AS (
                SELECT
                    patrol_area_id,
                    COUNT(DISTINCT defect_id) AS defect_count
                FROM visible_defect_source
                GROUP BY patrol_area_id
            ),
            visible_observation_source AS (
                SELECT
                    observation.event_id,
                    defect.patrol_area_id
                FROM defect.defect_observation observation
                JOIN defect.defect defect
                  ON defect.defect_id = observation.defect_id
                JOIN robot.robot r
                  ON r.robot_id = observation.robot_id
                WHERE
            """ + RobotScopeService.VISIBLE_ROBOT_PREDICATE + """
                  AND observation.created_at >= :from
                  AND observation.created_at < :to
                  AND (
                      CAST(:patrolAreaId AS uuid) IS NULL
                      OR defect.patrol_area_id = :patrolAreaId
                  )
            ),
            observation_aggregate AS (
                SELECT
                    patrol_area_id,
                    COUNT(DISTINCT event_id) AS observation_count
                FROM visible_observation_source
                GROUP BY patrol_area_id
            )
            SELECT
                area.patrol_area_id,
                area.area_code,
                area.area_name,
                COALESCE(mission.mission_count, 0) AS mission_count,
                COALESCE(
                    mission.completed_mission_count,
                    0
                ) AS completed_mission_count,
                COALESCE(
                    mission.cancelled_mission_count,
                    0
                ) AS cancelled_mission_count,
                COALESCE(
                    mission.failed_mission_count,
                    0
                ) AS failed_mission_count,
                COALESCE(
                    mission.total_mission_duration_seconds,
                    0
                ) AS total_mission_duration_seconds,
                COALESCE(
                    incident.incident_event_count,
                    0
                ) AS incident_event_count,
                COALESCE(defect.defect_count, 0) AS defect_count,
                COALESCE(
                    observation.observation_count,
                    0
                ) AS observation_count
            FROM selected_areas area
            LEFT JOIN mission_aggregate mission
              ON mission.patrol_area_id = area.patrol_area_id
            LEFT JOIN incident_aggregate incident
              ON incident.patrol_area_id = area.patrol_area_id
            LEFT JOIN defect_aggregate defect
              ON defect.patrol_area_id = area.patrol_area_id
            LEFT JOIN observation_aggregate observation
              ON observation.patrol_area_id = area.patrol_area_id
            ORDER BY area.area_code, area.patrol_area_id
            """;

    private static final String STATISTICS_BY_DAY_SQL = """
            WITH buckets AS (
                SELECT
                    series.bucket_start::date AS bucket_date
                FROM generate_series(
                    date_trunc(
                        'day',
                        CAST(:from AS timestamptz) AT TIME ZONE 'UTC'
                    ),
                    date_trunc(
                        'day',
                        CAST(:to AS timestamptz) AT TIME ZONE 'UTC'
                    ),
                    INTERVAL '1 day'
                ) AS series(bucket_start)
                WHERE series.bucket_start
                    < CAST(:to AS timestamptz) AT TIME ZONE 'UTC'
            ),
            visible_mission_source AS (
                SELECT
                    mission.mission_id,
                    (
                        mission.started_at AT TIME ZONE 'UTC'
                    )::date AS bucket_date,
                    mission.status,
                    mission.result_duration_sec
                FROM robot.mission mission
                JOIN robot.robot r
                  ON r.robot_id = mission.robot_id
                WHERE
            """ + RobotScopeService.VISIBLE_ROBOT_PREDICATE + """
                  AND mission.deleted_at IS NULL
                  AND mission.started_at >= :from
                  AND mission.started_at < :to
            ),
            mission_aggregate AS (
                SELECT
                    bucket_date,
                    COUNT(*) AS mission_count,
                    COUNT(*) FILTER (
                        WHERE status = 'COMPLETED'
                    ) AS completed_mission_count,
                    COUNT(*) FILTER (
                        WHERE status = 'CANCELLED'
                    ) AS cancelled_mission_count,
                    COUNT(*) FILTER (
                        WHERE status = 'FAILED'
                    ) AS failed_mission_count,
                    COALESCE(
                        SUM(COALESCE(result_duration_sec, 0)),
                        0
                    ) AS total_mission_duration_seconds
                FROM visible_mission_source
                GROUP BY bucket_date
            ),
            visible_incident_source AS (
                SELECT
                    event.event_id,
                    (
                        event.occurred_at AT TIME ZONE 'UTC'
                    )::date AS bucket_date
                FROM telemetry.robot_event event
                JOIN robot.robot r
                  ON r.robot_id = event.robot_id
                WHERE
            """ + RobotScopeService.VISIBLE_ROBOT_PREDICATE + """
                  AND event.event_type =
                      'BRAILLE_BLOCK_INCIDENT_DETECTED'
                  AND event.occurred_at >= :from
                  AND event.occurred_at < :to
            ),
            incident_aggregate AS (
                SELECT
                    bucket_date,
                    COUNT(DISTINCT event_id) AS incident_event_count
                FROM visible_incident_source
                GROUP BY bucket_date
            ),
            visible_defect_source AS (
                SELECT
                    d.defect_id,
                    (
                        d.last_seen_at AT TIME ZONE 'UTC'
                    )::date AS bucket_date
                FROM defect.defect d
                WHERE
            """ + DefectScopeService.VISIBLE_DEFECT_PREDICATE + """
                  AND d.merged_into_defect_id IS NULL
                  AND d.last_seen_at >= :from
                  AND d.last_seen_at < :to
            ),
            defect_aggregate AS (
                SELECT
                    bucket_date,
                    COUNT(DISTINCT defect_id) AS defect_count
                FROM visible_defect_source
                GROUP BY bucket_date
            ),
            visible_observation_source AS (
                SELECT
                    observation.event_id,
                    (
                        observation.created_at AT TIME ZONE 'UTC'
                    )::date AS bucket_date
                FROM defect.defect_observation observation
                JOIN robot.robot r
                  ON r.robot_id = observation.robot_id
                WHERE
            """ + RobotScopeService.VISIBLE_ROBOT_PREDICATE + """
                  AND observation.created_at >= :from
                  AND observation.created_at < :to
            ),
            observation_aggregate AS (
                SELECT
                    bucket_date,
                    COUNT(DISTINCT event_id) AS observation_count
                FROM visible_observation_source
                GROUP BY bucket_date
            )
            SELECT
                bucket.bucket_date,
                COALESCE(mission.mission_count, 0) AS mission_count,
                COALESCE(
                    mission.completed_mission_count,
                    0
                ) AS completed_mission_count,
                COALESCE(
                    mission.cancelled_mission_count,
                    0
                ) AS cancelled_mission_count,
                COALESCE(
                    mission.failed_mission_count,
                    0
                ) AS failed_mission_count,
                COALESCE(
                    mission.total_mission_duration_seconds,
                    0
                ) AS total_mission_duration_seconds,
                COALESCE(
                    incident.incident_event_count,
                    0
                ) AS incident_event_count,
                COALESCE(defect.defect_count, 0) AS defect_count,
                COALESCE(
                    observation.observation_count,
                    0
                ) AS observation_count
            FROM buckets bucket
            LEFT JOIN mission_aggregate mission
              ON mission.bucket_date = bucket.bucket_date
            LEFT JOIN incident_aggregate incident
              ON incident.bucket_date = bucket.bucket_date
            LEFT JOIN defect_aggregate defect
              ON defect.bucket_date = bucket.bucket_date
            LEFT JOIN observation_aggregate observation
              ON observation.bucket_date = bucket.bucket_date
            ORDER BY bucket.bucket_date ASC
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public StatisticsRepository(
            NamedParameterJdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean patrolAreaExists(UUID patrolAreaId) {
        Boolean exists = jdbcTemplate.queryForObject(
                PATROL_AREA_EXISTS_SQL,
                new MapSqlParameterSource()
                        .addValue("patrolAreaId", patrolAreaId),
                Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public long countPatrolAreas(UUID patrolAreaId) {
        Long count = jdbcTemplate.queryForObject(
                PATROL_AREA_COUNT_SQL,
                new MapSqlParameterSource()
                        .addValue("patrolAreaId", patrolAreaId),
                Long.class);
        return count == null ? 0 : count;
    }

    public List<WorkflowStatusCount> countDefectsByWorkflowStatus(
            UUID userId,
            StatisticsPeriod period
    ) {
        MapSqlParameterSource parameters = period.bind(
                new MapSqlParameterSource()
                        .addValue("userId", userId));
        return jdbcTemplate.query(
                DEFECTS_BY_STATUS_SQL,
                parameters,
                (resultSet, rowNumber) -> mapWorkflowStatusCount(resultSet));
    }

    public List<DefectTypeCount> countDefectsByType(
            UUID userId,
            StatisticsPeriod period
    ) {
        MapSqlParameterSource parameters = period.bind(
                new MapSqlParameterSource()
                        .addValue("userId", userId));
        return jdbcTemplate.query(
                DEFECTS_BY_TYPE_SQL,
                parameters,
                (resultSet, rowNumber) -> mapDefectTypeCount(resultSet));
    }

    public List<AreaStatisticsRow> findAreaStatistics(
            UUID userId,
            StatisticsQuery.ByArea query
    ) {
        MapSqlParameterSource parameters = query.period().bind(
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("patrolAreaId", query.patrolAreaId())
                        .addValue("size", query.page().size())
                        .addValue("offset", query.page().offset()));
        return jdbcTemplate.query(
                STATISTICS_BY_AREA_SQL,
                parameters,
                (resultSet, rowNumber) ->
                        mapAreaStatisticsRow(resultSet));
    }

    public List<PeriodStatisticsRow> findPeriodStatistics(
            UUID userId,
            StatisticsQuery.ByPeriod query
    ) {
        String sql = switch (query.granularity()) {
            case DAY -> STATISTICS_BY_DAY_SQL;
        };
        MapSqlParameterSource parameters = query.period().bind(
                new MapSqlParameterSource()
                        .addValue("userId", userId));
        return jdbcTemplate.query(
                sql,
                parameters,
                (resultSet, rowNumber) ->
                        mapPeriodStatisticsRow(resultSet));
    }

    private static WorkflowStatusCount mapWorkflowStatusCount(
            ResultSet resultSet
    ) throws SQLException {
        return new WorkflowStatusCount(
                DefectWorkflowStatus.valueOf(
                        resultSet.getString("workflow_status")),
                resultSet.getLong("defect_count"));
    }

    private static DefectTypeCount mapDefectTypeCount(
            ResultSet resultSet
    ) throws SQLException {
        return new DefectTypeCount(
                DefectType.valueOf(resultSet.getString("defect_type")),
                resultSet.getLong("defect_count"));
    }

    private static AreaStatisticsRow mapAreaStatisticsRow(
            ResultSet resultSet
    ) throws SQLException {
        return new AreaStatisticsRow(
                resultSet.getObject("patrol_area_id", UUID.class),
                resultSet.getString("area_code"),
                resultSet.getString("area_name"),
                resultSet.getLong("mission_count"),
                resultSet.getLong("completed_mission_count"),
                resultSet.getLong("cancelled_mission_count"),
                resultSet.getLong("failed_mission_count"),
                resultSet.getLong("total_mission_duration_seconds"),
                resultSet.getLong("incident_event_count"),
                resultSet.getLong("defect_count"),
                resultSet.getLong("observation_count"));
    }

    private static PeriodStatisticsRow mapPeriodStatisticsRow(
            ResultSet resultSet
    ) throws SQLException {
        return new PeriodStatisticsRow(
                resultSet.getObject("bucket_date", LocalDate.class),
                resultSet.getLong("mission_count"),
                resultSet.getLong("completed_mission_count"),
                resultSet.getLong("cancelled_mission_count"),
                resultSet.getLong("failed_mission_count"),
                resultSet.getLong("total_mission_duration_seconds"),
                resultSet.getLong("incident_event_count"),
                resultSet.getLong("defect_count"),
                resultSet.getLong("observation_count"));
    }

    record WorkflowStatusCount(
            DefectWorkflowStatus workflowStatus,
            long defectCount
    ) {
    }

    record DefectTypeCount(
            DefectType defectType,
            long defectCount
    ) {
    }

    record AreaStatisticsRow(
            UUID patrolAreaId,
            String areaCode,
            String areaName,
            long missionCount,
            long completedMissionCount,
            long cancelledMissionCount,
            long failedMissionCount,
            long totalMissionDurationSeconds,
            long incidentEventCount,
            long defectCount,
            long observationCount
    ) {
    }

    record PeriodStatisticsRow(
            LocalDate date,
            long missionCount,
            long completedMissionCount,
            long cancelledMissionCount,
            long failedMissionCount,
            long totalMissionDurationSeconds,
            long incidentEventCount,
            long defectCount,
            long observationCount
    ) {
    }
}
