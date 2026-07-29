package com.gilbom.defect;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 결함 변경 경로의 SQL이다. 상태 이력은 V12의 append-only trigger가 보호하므로
 * INSERT만 수행하고, 대상 행은 항상 객체 범위 조건과 함께 잠근다.
 */
@Repository
public class DefectRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public DefectRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 변경 대상 결함을 객체 범위와 함께 조회하고 행을 잠근다.
     *
     * @param scopePredicate {@link DefectScopeService}의 조건절
     */
    Optional<DefectState> lockForUpdate(
            UUID userId,
            UUID defectId,
            String scopePredicate
    ) {
        List<DefectState> states = jdbcTemplate.query("""
                SELECT
                    d.defect_id,
                    d.incident_code,
                    d.block_type,
                    d.defect_type,
                    d.risk_level,
                    d.risk_score,
                    d.workflow_status,
                    d.resolution,
                    d.resolution_note,
                    ST_X(d.location) AS longitude,
                    ST_Y(d.location) AS latitude,
                    d.location_accuracy_m,
                    d.zone_id,
                    d.location_label,
                    d.review_required,
                    d.patrol_area_id,
                    d.merged_into_defect_id,
                    d.first_seen_at,
                    d.last_seen_at,
                    d.observation_count
                FROM defect.defect d
                WHERE d.defect_id = :defectId
                  AND
                """ + scopePredicate + """
                FOR UPDATE OF d
                """,
                Map.of("userId", userId, "defectId", defectId),
                (resultSet, rowNumber) -> new DefectState(
                        resultSet.getObject("defect_id", UUID.class),
                        resultSet.getString("incident_code"),
                        resultSet.getString("block_type"),
                        resultSet.getString("defect_type"),
                        resultSet.getString("risk_level"),
                        resultSet.getBigDecimal("risk_score"),
                        resultSet.getString("workflow_status"),
                        resultSet.getString("resolution"),
                        resultSet.getString("resolution_note"),
                        resultSet.getDouble("longitude"),
                        resultSet.getDouble("latitude"),
                        nullableDouble(resultSet, "location_accuracy_m"),
                        resultSet.getString("zone_id"),
                        resultSet.getString("location_label"),
                        resultSet.getBoolean("review_required"),
                        resultSet.getObject("patrol_area_id", UUID.class),
                        resultSet.getObject("merged_into_defect_id", UUID.class),
                        timestampToInstant(resultSet.getTimestamp("first_seen_at")),
                        timestampToInstant(resultSet.getTimestamp("last_seen_at")),
                        resultSet.getInt("observation_count")));
        return states.stream().findFirst();
    }

    int updateAttributes(UUID defectId, DefectAttributeChange change) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("defectId", defectId)
                .addValue("blockType", change.blockType())
                .addValue("defectType", change.defectType())
                .addValue("riskLevel", change.riskLevel())
                .addValue("zoneId", change.zoneId())
                .addValue("locationLabel", change.locationLabel())
                .addValue("locationAccuracyM", change.locationAccuracyM())
                .addValue("reviewRequired", change.reviewRequired())
                .addValue("longitude", change.longitude())
                .addValue("latitude", change.latitude());

        // 좌표는 두 값이 함께 와야 의미가 있으므로 교체 여부를 별도 플래그로 넘긴다.
        boolean locationChanged =
                change.longitude() != null && change.latitude() != null;
        parameters.addValue("locationChanged", locationChanged);

        // COALESCE로 null 항목은 기존 값을 유지한다. null 파라미터는 Postgres가
        // 타입을 추론할 수 없어 "could not determine data type"이 나므로 모두
        // 명시적으로 CAST한다.
        return jdbcTemplate.update("""
                UPDATE defect.defect
                SET block_type = COALESCE(
                        CAST(:blockType AS varchar), block_type),
                    defect_type = COALESCE(
                        CAST(:defectType AS varchar), defect_type),
                    risk_level = COALESCE(
                        CAST(:riskLevel AS varchar), risk_level),
                    zone_id = COALESCE(
                        CAST(:zoneId AS varchar), zone_id),
                    location_label = COALESCE(
                        CAST(:locationLabel AS varchar), location_label),
                    location_accuracy_m = COALESCE(
                        CAST(:locationAccuracyM AS real), location_accuracy_m),
                    review_required = COALESCE(
                        CAST(:reviewRequired AS boolean), review_required),
                    location = CASE
                        WHEN CAST(:locationChanged AS boolean) THEN ST_SetSRID(
                            ST_MakePoint(
                                CAST(:longitude AS double precision),
                                CAST(:latitude AS double precision)),
                            4326)
                        ELSE location
                    END,
                    updated_at = NOW()
                WHERE defect_id = :defectId
                """,
                parameters);
    }

    int updateWorkflowStatus(
            UUID defectId,
            String expectedStatus,
            String workflowStatus,
            String resolution,
            String resolutionNote
    ) {
        // expectedStatus를 조건에 넣어 같은 결함에 대한 동시 전이를 한 건만 통과시킨다.
        return jdbcTemplate.update("""
                UPDATE defect.defect
                SET workflow_status = :workflowStatus,
                    resolution = CAST(:resolution AS varchar),
                    resolution_note = CAST(:resolutionNote AS text),
                    updated_at = NOW()
                WHERE defect_id = :defectId
                  AND workflow_status = :expectedStatus
                """,
                new MapSqlParameterSource()
                        .addValue("defectId", defectId)
                        .addValue("expectedStatus", expectedStatus)
                        .addValue("workflowStatus", workflowStatus)
                        .addValue("resolution", resolution)
                        .addValue("resolutionNote", resolutionNote));
    }

    void appendStatusHistory(DefectHistoryEntry entry) {
        int affected = jdbcTemplate.update("""
                INSERT INTO defect.defect_status_history (
                    defect_id,
                    changed_by,
                    change_type,
                    before_status,
                    after_status,
                    before_values,
                    after_values,
                    reason
                ) VALUES (
                    :defectId,
                    :changedBy,
                    :changeType,
                    :beforeStatus,
                    :afterStatus,
                    CAST(:beforeValues AS jsonb),
                    CAST(:afterValues AS jsonb),
                    :reason
                )
                """,
                new MapSqlParameterSource()
                        .addValue("defectId", entry.defectId())
                        .addValue("changedBy", entry.changedBy())
                        .addValue("changeType", entry.changeType())
                        .addValue("beforeStatus", entry.beforeStatus())
                        .addValue("afterStatus", entry.afterStatus())
                        .addValue("beforeValues", json(entry.beforeValues()))
                        .addValue("afterValues", json(entry.afterValues()))
                        .addValue("reason", entry.reason()));
        if (affected != 1) {
            throw new IllegalStateException(
                    "Defect status history was not persisted");
        }
    }

    int markMergedInto(UUID sourceDefectId, UUID targetDefectId) {
        return jdbcTemplate.update("""
                UPDATE defect.defect
                SET merged_into_defect_id = :targetDefectId,
                    updated_at = NOW()
                WHERE defect_id = :sourceDefectId
                  AND merged_into_defect_id IS NULL
                """,
                Map.of(
                        "sourceDefectId", sourceDefectId,
                        "targetDefectId", targetDefectId));
    }

    void appendMergeHistory(
            UUID sourceDefectId,
            UUID targetDefectId,
            UUID mergedBy,
            String reason
    ) {
        jdbcTemplate.update("""
                INSERT INTO defect.defect_merge_history (
                    source_defect_id,
                    target_defect_id,
                    merge_status,
                    merge_reason,
                    merged_by
                ) VALUES (
                    :sourceDefectId,
                    :targetDefectId,
                    'MERGED',
                    :reason,
                    :mergedBy
                )
                """,
                Map.of(
                        "sourceDefectId", sourceDefectId,
                        "targetDefectId", targetDefectId,
                        "mergedBy", mergedBy,
                        "reason", reason));
    }

    /**
     * 병합 후 대표 결함의 집계값을 관측에서 다시 계산한다.
     *
     * <p>V12는 defect_observation에 UPDATE 권한을 주지 않으므로 관측의 defect_id를
     * 옮기지 않는다. 대신 merged_into_defect_id 연결을 따라가며 집계한다.
     */
    DefectAggregate recalculateAggregate(UUID targetDefectId) {
        return jdbcTemplate.queryForObject("""
                WITH scoped AS (
                    SELECT o.confidence, o.created_at, o.event_id
                    FROM defect.defect_observation o
                    WHERE o.defect_id = :targetDefectId
                       OR o.defect_id IN (
                           SELECT merged.defect_id
                           FROM defect.defect merged
                           WHERE merged.merged_into_defect_id = :targetDefectId
                       )
                ),
                bounds AS (
                    SELECT
                        COUNT(*) AS observation_count,
                        MIN(created_at) AS first_seen_at,
                        MAX(created_at) AS last_seen_at
                    FROM scoped
                ),
                latest AS (
                    SELECT confidence
                    FROM scoped
                    ORDER BY created_at DESC, event_id ASC
                    LIMIT 1
                )
                UPDATE defect.defect d
                SET observation_count = GREATEST(bounds.observation_count, 1),
                    first_seen_at = LEAST(
                        d.first_seen_at,
                        COALESCE(bounds.first_seen_at, d.first_seen_at)),
                    last_seen_at = GREATEST(
                        d.last_seen_at,
                        COALESCE(bounds.last_seen_at, d.last_seen_at)),
                    latest_confidence = COALESCE(
                        (SELECT confidence FROM latest),
                        d.latest_confidence),
                    updated_at = NOW()
                FROM bounds
                WHERE d.defect_id = :targetDefectId
                RETURNING
                    d.observation_count,
                    d.first_seen_at,
                    d.last_seen_at
                """,
                Map.of("targetDefectId", targetDefectId),
                (resultSet, rowNumber) -> new DefectAggregate(
                        resultSet.getInt("observation_count"),
                        timestampToInstant(resultSet.getTimestamp("first_seen_at")),
                        timestampToInstant(resultSet.getTimestamp("last_seen_at"))));
    }

    UUID insertExportJob(
            UUID requestedBy,
            String exportFormat,
            Map<String, ?> filterCriteria
    ) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO defect.defect_export_job (
                    requested_by,
                    export_format,
                    job_status,
                    filter_criteria
                ) VALUES (
                    :requestedBy,
                    :exportFormat,
                    'QUEUED',
                    CAST(:filterCriteria AS jsonb)
                )
                RETURNING export_job_id
                """,
                new MapSqlParameterSource()
                        .addValue("requestedBy", requestedBy)
                        .addValue("exportFormat", exportFormat)
                        .addValue("filterCriteria", json(filterCriteria)),
                UUID.class);
    }

    private String json(Map<String, ?> values) {
        if (values == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Defect change snapshot could not be serialized");
        }
    }

    private static Instant timestampToInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    /**
     * location_accuracy_m은 REAL이라 getObject가 Float를 돌려준다.
     */
    private static Double nullableDouble(ResultSet resultSet, String column)
            throws SQLException {
        double value = resultSet.getDouble(column);
        return resultSet.wasNull() ? null : value;
    }

    record DefectState(
            UUID defectId,
            String incidentCode,
            String blockType,
            String defectType,
            String riskLevel,
            BigDecimal riskScore,
            String workflowStatus,
            String resolution,
            String resolutionNote,
            double longitude,
            double latitude,
            Double locationAccuracyM,
            String zoneId,
            String locationLabel,
            boolean reviewRequired,
            UUID patrolAreaId,
            UUID mergedIntoDefectId,
            Instant firstSeenAt,
            Instant lastSeenAt,
            int observationCount
    ) {

        boolean isMergedAway() {
            return mergedIntoDefectId != null;
        }
    }

    record DefectAttributeChange(
            String blockType,
            String defectType,
            String riskLevel,
            String zoneId,
            String locationLabel,
            Double locationAccuracyM,
            Boolean reviewRequired,
            Double longitude,
            Double latitude
    ) {
    }

    record DefectAggregate(
            int observationCount,
            Instant firstSeenAt,
            Instant lastSeenAt
    ) {
    }

    /**
     * before/after snapshot은 호출부에서 Map.of로 만드는 값 타입이 섞이므로
     * 와일드카드로 받는다.
     */
    record DefectHistoryEntry(
            UUID defectId,
            UUID changedBy,
            String changeType,
            String beforeStatus,
            String afterStatus,
            Map<String, ?> beforeValues,
            Map<String, ?> afterValues,
            String reason
    ) {

        /**
         * 상태 전이가 아닌 변경(유형·위험도·위치)은 전후 상태 칼럼을 비운다.
         */
        static DefectHistoryEntry of(
                UUID defectId,
                UUID changedBy,
                String changeType,
                Map<String, ?> beforeValues,
                Map<String, ?> afterValues,
                String reason
        ) {
            return new DefectHistoryEntry(
                    defectId,
                    changedBy,
                    changeType,
                    null,
                    null,
                    beforeValues,
                    afterValues,
                    reason);
        }
    }
}
