package com.gilbom.report.generation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.gilbom.defect.DefectScopeService;
import com.gilbom.report.generation.ReportLlmInput.DefectEvidence;
import com.gilbom.report.generation.ReportLlmInput.DefectRiskCount;
import com.gilbom.report.generation.ReportLlmInput.DefectStatusCount;
import com.gilbom.report.generation.ReportLlmInput.DefectTypeCount;
import com.gilbom.report.generation.ReportLlmInput.RiskLevel;
import com.gilbom.robot.application.RobotScopeService;
import com.gilbom.statistics.StatisticsDtos.DefectType;
import com.gilbom.statistics.StatisticsDtos.DefectWorkflowStatus;

/**
 * 한 SQL snapshot에서 LLM용 집계와 상위 결함 근거를 함께 읽는다.
 */
@Repository
public class ReportGenerationDataRepository {

    private static final UUID EMPTY_UUID =
            new UUID(0L, 0L);

    private static final String SNAPSHOT_SQL = """
            WITH visible_missions AS (
                SELECT
                    mission.mission_id,
                    mission.robot_id,
                    mission.patrol_area_id,
                    mission.status,
                    mission.result_duration_sec
                FROM robot.mission mission
                JOIN robot.robot r
                  ON r.robot_id = mission.robot_id
                WHERE
            """ + RobotScopeService.VISIBLE_ROBOT_PREDICATE + """
                  AND mission.started_at >= :from
                  AND mission.started_at < :to
                  AND (
                      CAST(:patrolAreaId AS uuid) IS NULL
                      OR mission.patrol_area_id = :patrolAreaId
                  )
                  AND (
                      :filterRobotIds = FALSE
                      OR mission.robot_id IN (:robotIds)
                  )
            ),
            visible_defects AS (
                SELECT
                    d.defect_id,
                    d.incident_code,
                    d.defect_type,
                    d.risk_level,
                    d.workflow_status,
                    d.last_seen_at,
                    d.patrol_area_id,
                    COALESCE(area.area_name, d.zone_id)
                        AS location_label
                FROM defect.defect d
                LEFT JOIN robot.patrol_area area
                  ON area.patrol_area_id = d.patrol_area_id
                WHERE
            """ + DefectScopeService.VISIBLE_DEFECT_PREDICATE + """
                  AND d.merged_into_defect_id IS NULL
                  AND d.last_seen_at >= :from
                  AND d.last_seen_at < :to
                  AND (
                      CAST(:patrolAreaId AS uuid) IS NULL
                      OR d.patrol_area_id = :patrolAreaId
                  )
                  AND (
                      :filterDefectTypes = FALSE
                      OR d.defect_type IN (:defectTypes)
                  )
                  AND (
                      :filterWorkflowStatuses = FALSE
                      OR d.workflow_status IN (:workflowStatuses)
                  )
                  AND (
                      :filterRobotIds = FALSE
                      OR EXISTS (
                          SELECT 1
                          FROM defect.defect_observation selected_robot
                          WHERE selected_robot.defect_id = d.defect_id
                            AND selected_robot.robot_id IN (:robotIds)
                      )
                  )
            ),
            visible_observations AS (
                SELECT
                    observation.defect_id,
                    observation.event_id,
                    observation.is_representative,
                    observation.created_at,
                    event.mission_id
                FROM defect.defect_observation observation
                JOIN visible_defects selected
                  ON selected.defect_id = observation.defect_id
                JOIN robot.robot r
                  ON r.robot_id = observation.robot_id
                LEFT JOIN telemetry.robot_event event
                  ON event.event_id = observation.event_id
                 AND event.robot_id = observation.robot_id
                WHERE
            """ + RobotScopeService.VISIBLE_ROBOT_PREDICATE + """
                  AND observation.created_at >= :from
                  AND observation.created_at < :to
                  AND (
                      :filterRobotIds = FALSE
                      OR observation.robot_id IN (:robotIds)
                  )
            ),
            visible_incidents AS (
                SELECT event.event_id
                FROM telemetry.robot_event event
                JOIN robot.robot r
                  ON r.robot_id = event.robot_id
                LEFT JOIN robot.mission mission
                  ON mission.mission_id = event.mission_id
                 AND mission.robot_id = event.robot_id
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
                  AND (
                      :filterRobotIds = FALSE
                      OR event.robot_id IN (:robotIds)
                  )
            ),
            mission_metrics AS (
                SELECT
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
                FROM visible_missions
            ),
            defect_metrics AS (
                SELECT COUNT(*) AS defect_count
                FROM visible_defects
            ),
            observation_metrics AS (
                SELECT COUNT(*) AS observation_count
                FROM visible_observations
            ),
            incident_metrics AS (
                SELECT COUNT(DISTINCT event_id)
                    AS incident_event_count
                FROM visible_incidents
            ),
            status_values(value, ordinal) AS (
                VALUES
                    ('NEW', 1),
                    ('ACCEPTED', 2),
                    ('IN_PROGRESS', 3),
                    ('RESOLVED', 4),
                    ('FALSE_POSITIVE', 5),
                    ('REJECTED', 6)
            ),
            status_counts AS (
                SELECT
                    status.value AS workflow_status,
                    COALESCE(COUNT(defect.defect_id), 0)
                        AS defect_count,
                    status.ordinal
                FROM status_values status
                LEFT JOIN visible_defects defect
                  ON defect.workflow_status = status.value
                GROUP BY status.value, status.ordinal
            ),
            type_values(value, ordinal) AS (
                VALUES
                    ('DAMAGED', 1),
                    ('CONTAMINATED', 2),
                    ('UPLIFTED', 3),
                    ('BLOCKED', 4),
                    ('MISSING_SUSPECTED', 5),
                    ('INSTALLATION_INVALID', 6),
                    ('UNKNOWN_ANOMALY', 7)
            ),
            type_counts AS (
                SELECT
                    type.value AS defect_type,
                    COALESCE(COUNT(defect.defect_id), 0)
                        AS defect_count,
                    type.ordinal
                FROM type_values type
                LEFT JOIN visible_defects defect
                  ON defect.defect_type = type.value
                GROUP BY type.value, type.ordinal
            ),
            risk_values(value, ordinal) AS (
                VALUES
                    ('LOW', 1),
                    ('MEDIUM', 2),
                    ('HIGH', 3),
                    ('CRITICAL', 4)
            ),
            risk_counts AS (
                SELECT
                    risk.value AS risk_level,
                    COALESCE(COUNT(defect.defect_id), 0)
                        AS defect_count,
                    risk.ordinal
                FROM risk_values risk
                LEFT JOIN visible_defects defect
                  ON defect.risk_level = risk.value
                GROUP BY risk.value, risk.ordinal
            ),
            top_defects AS (
                SELECT
                    defect.*,
                    observation.mission_id
                FROM visible_defects defect
                LEFT JOIN LATERAL (
                    SELECT visible.mission_id
                    FROM visible_observations visible
                    WHERE visible.defect_id = defect.defect_id
                    ORDER BY
                        visible.is_representative DESC,
                        visible.created_at DESC,
                        visible.event_id
                    LIMIT 1
                ) observation ON TRUE
                -- 단건 보고서는 근거를 이 결함 하나로 고정한다. 통계 CTE는
                -- visible_defects를 그대로 쓰므로 기간·구역 현황이 유지된다.
                WHERE CAST(:defectId AS uuid) IS NULL
                   OR defect.defect_id = CAST(:defectId AS uuid)
                ORDER BY
                    CASE defect.risk_level
                        WHEN 'CRITICAL' THEN 4
                        WHEN 'HIGH' THEN 3
                        WHEN 'MEDIUM' THEN 2
                        ELSE 1
                    END DESC,
                    defect.last_seen_at DESC,
                    defect.defect_id
                LIMIT 20
            )
            SELECT
                mission.mission_count,
                mission.completed_mission_count,
                mission.cancelled_mission_count,
                mission.failed_mission_count,
                mission.total_mission_duration_seconds,
                incident.incident_event_count,
                defect.defect_count,
                observation.observation_count,
                (
                    SELECT COALESCE(
                        jsonb_agg(
                            jsonb_build_object(
                                'workflowStatus',
                                workflow_status,
                                'defectCount',
                                defect_count
                            )
                            ORDER BY ordinal
                        ),
                        '[]'::jsonb
                    )
                    FROM status_counts
                )::text AS status_counts_json,
                (
                    SELECT COALESCE(
                        jsonb_agg(
                            jsonb_build_object(
                                'defectType',
                                defect_type,
                                'defectCount',
                                defect_count
                            )
                            ORDER BY ordinal
                        ),
                        '[]'::jsonb
                    )
                    FROM type_counts
                )::text AS type_counts_json,
                (
                    SELECT COALESCE(
                        jsonb_agg(
                            jsonb_build_object(
                                'riskLevel',
                                risk_level,
                                'defectCount',
                                defect_count
                            )
                            ORDER BY ordinal
                        ),
                        '[]'::jsonb
                    )
                    FROM risk_counts
                )::text AS risk_counts_json,
                (
                    SELECT COALESCE(
                        jsonb_agg(
                            jsonb_build_object(
                                'defectId',
                                defect_id,
                                'incidentCode',
                                incident_code,
                                'defectType',
                                defect_type,
                                'riskLevel',
                                risk_level,
                                'workflowStatus',
                                workflow_status,
                                'lastSeenAt',
                                last_seen_at,
                                'locationLabel',
                                location_label,
                                'missionId',
                                mission_id,
                                'patrolAreaId',
                                patrol_area_id
                            )
                            ORDER BY
                                CASE risk_level
                                    WHEN 'CRITICAL' THEN 4
                                    WHEN 'HIGH' THEN 3
                                    WHEN 'MEDIUM' THEN 2
                                    ELSE 1
                                END DESC,
                                last_seen_at DESC,
                                defect_id
                        ),
                        '[]'::jsonb
                    )
                    FROM top_defects
                )::text AS evidence_json
            FROM mission_metrics mission
            CROSS JOIN defect_metrics defect
            CROSS JOIN observation_metrics observation
            CROSS JOIN incident_metrics incident
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final com.fasterxml.jackson.databind.ObjectMapper
            objectMapper;

    public ReportGenerationDataRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Snapshot load(
            UUID userId,
            Instant from,
            Instant to,
            ReportGenerationCriteria criteria
    ) {
        MapSqlParameterSource parameters =
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue(
                                "from",
                                from.atOffset(ZoneOffset.UTC))
                        .addValue(
                                "to",
                                to.atOffset(ZoneOffset.UTC))
                        .addValue(
                                "patrolAreaId",
                                criteria.patrolAreaId())
                        .addValue(
                                "filterRobotIds",
                                !criteria.robotIds().isEmpty())
                        .addValue(
                                "robotIds",
                                nonEmpty(criteria.robotIds()))
                        .addValue(
                                "filterDefectTypes",
                                !criteria.defectTypes().isEmpty())
                        .addValue(
                                "defectTypes",
                                nonEmptyNames(criteria.defectTypes()))
                        .addValue(
                                "filterWorkflowStatuses",
                                !criteria.workflowStatuses().isEmpty())
                        .addValue(
                                "workflowStatuses",
                                nonEmptyNames(
                                        criteria.workflowStatuses()))
                        .addValue("defectId", criteria.defectId());
        return jdbcTemplate.queryForObject(
                SNAPSHOT_SQL,
                parameters,
                (resultSet, rowNumber) -> map(resultSet));
    }

    private Snapshot map(ResultSet resultSet) throws SQLException {
        long missionCount =
                resultSet.getLong("mission_count");
        long completedMissionCount =
                resultSet.getLong("completed_mission_count");
        ReportLlmInput.Aggregate aggregate =
                new ReportLlmInput.Aggregate(
                        missionCount,
                        completedMissionCount,
                        resultSet.getLong(
                                "cancelled_mission_count"),
                        resultSet.getLong(
                                "failed_mission_count"),
                        completionRate(
                                completedMissionCount,
                                missionCount),
                        resultSet.getLong(
                                "total_mission_duration_seconds"),
                        resultSet.getLong(
                                "incident_event_count"),
                        resultSet.getLong("defect_count"),
                        resultSet.getLong("observation_count"));
        return new Snapshot(
                aggregate,
                readList(
                        resultSet.getString(
                                "status_counts_json"),
                        StatusCountJson.class).stream()
                        .map(value -> new DefectStatusCount(
                                DefectWorkflowStatus.valueOf(
                                        value.workflowStatus()),
                                value.defectCount()))
                        .toList(),
                readList(
                        resultSet.getString("type_counts_json"),
                        TypeCountJson.class).stream()
                        .map(value -> new DefectTypeCount(
                                DefectType.valueOf(
                                        value.defectType()),
                                value.defectCount()))
                        .toList(),
                readList(
                        resultSet.getString("risk_counts_json"),
                        RiskCountJson.class).stream()
                        .map(value -> new DefectRiskCount(
                                RiskLevel.valueOf(
                                        value.riskLevel()),
                                value.defectCount()))
                        .toList(),
                readList(
                        resultSet.getString("evidence_json"),
                        EvidenceJson.class).stream()
                        .map(EvidenceJson::toEvidence)
                        .toList(),
                readList(
                        resultSet.getString("evidence_json"),
                        EvidenceJson.class));
    }

    private <T> List<T> readList(
            String value,
            Class<T> elementType
    ) {
        try {
            com.fasterxml.jackson.databind.JavaType type =
                    objectMapper.getTypeFactory()
                            .constructCollectionType(
                                    List.class,
                                    elementType);
            return objectMapper.readValue(value, type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException
                exception) {
            throw new IllegalStateException(
                    "Report generation snapshot is invalid",
                    exception);
        }
    }

    private static BigDecimal completionRate(
            long completed,
            long total
    ) {
        if (total == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return BigDecimal.valueOf(completed)
                .multiply(BigDecimal.valueOf(100))
                .divide(
                        BigDecimal.valueOf(total),
                        2,
                        RoundingMode.HALF_UP);
    }

    private static List<UUID> nonEmpty(List<UUID> values) {
        return values.isEmpty()
                ? List.of(EMPTY_UUID)
                : values;
    }

    private static List<String> nonEmptyNames(
            List<? extends Enum<?>> values
    ) {
        return values.isEmpty()
                ? List.of("__NONE__")
                : values.stream().map(Enum::name).toList();
    }

    public record Snapshot(
            ReportLlmInput.Aggregate aggregate,
            List<DefectStatusCount> statusCounts,
            List<DefectTypeCount> typeCounts,
            List<DefectRiskCount> riskCounts,
            List<DefectEvidence> evidence,
            List<EvidenceJson> evidenceMetadata
    ) {
    }

    private record StatusCountJson(
            String workflowStatus,
            long defectCount
    ) {
    }

    private record TypeCountJson(
            String defectType,
            long defectCount
    ) {
    }

    private record RiskCountJson(
            String riskLevel,
            long defectCount
    ) {
    }

    public record EvidenceJson(
            UUID defectId,
            String incidentCode,
            String defectType,
            String riskLevel,
            String workflowStatus,
            Instant lastSeenAt,
            String locationLabel,
            UUID missionId,
            UUID patrolAreaId
    ) {

        DefectEvidence toEvidence() {
            return new DefectEvidence(
                    defectId,
                    incidentCode,
                    DefectType.valueOf(defectType),
                    RiskLevel.valueOf(riskLevel),
                    DefectWorkflowStatus.valueOf(
                            workflowStatus),
                    lastSeenAt,
                    locationLabel,
                    null);
        }
    }
}
