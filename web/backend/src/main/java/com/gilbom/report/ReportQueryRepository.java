package com.gilbom.report;

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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gilbom.report.ReportDtos.PageResponse;
import com.gilbom.report.ReportDtos.ReportListItemResponse;
import com.gilbom.report.ReportDtos.ReportVersionResponse;
import com.gilbom.report.ReportDtos.ReportVersionSummaryResponse;

@Repository
public class ReportQueryRepository {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT =
            new TypeReference<>() {
            };

    private static final String LIST_SELECT = """
            SELECT
                report.report_id,
                report.report_code,
                report.title,
                report.summary,
                report.report_type,
                report.report_status,
                report.generation_method,
                report.period_from,
                report.period_to,
                report.current_version_no,
                report.approved_version_no,
                report.creator_type,
                report.created_by,
                header_creator.display_name
                    AS created_by_display_name,
                current_version.created_by AS updated_by,
                current_creator.display_name
                    AS updated_by_display_name,
                report.approved_by,
                approver.display_name
                    AS approved_by_display_name,
                report.approved_at,
                report.created_at,
                report.updated_at
            FROM report.anomaly_report report
            JOIN report.report_version current_version
              ON current_version.report_id = report.report_id
             AND current_version.version_no =
                 report.current_version_no
            LEFT JOIN auth.admin_user header_creator
              ON header_creator.user_id = report.created_by
            LEFT JOIN auth.admin_user current_creator
              ON current_creator.user_id =
                 current_version.created_by
            LEFT JOIN auth.admin_user approver
              ON approver.user_id = report.approved_by
            """;

    private static final String VERSION_SELECT = """
            SELECT
                version.report_id,
                version.version_no,
                version.title,
                version.summary,
                version.period_from,
                version.period_to,
                version.generation_method,
                version.generation_fallback_reason,
                version.llm_model,
                version.prompt_version,
                version.change_note,
                version.creator_type,
                version.created_by,
                creator.display_name AS created_by_display_name,
                version.created_at,
                version.content,
                version.statistics::text AS statistics_json,
                version.evidence_snapshot::text AS evidence_json
            FROM report.report_version version
            JOIN report.anomaly_report report
              ON report.report_id = version.report_id
             AND report.deleted_at IS NULL
            LEFT JOIN auth.admin_user creator
              ON creator.user_id = version.created_by
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ReportQueryRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public PageResponse<ReportListItemResponse> findReports(
            int page,
            int size,
            ReportListFilter filter
    ) {
        MapSqlParameterSource parameters =
                new MapSqlParameterSource()
                        .addValue("size", size)
                        .addValue(
                                "offset",
                                Math.multiplyExact((long) page, size));
        String predicate = predicate(filter, parameters);

        Long total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM report.anomaly_report report
                WHERE report.deleted_at IS NULL
                """ + predicate,
                parameters,
                Long.class);
        List<ReportListItemResponse> content =
                jdbcTemplate.query(
                        LIST_SELECT
                                + """
                                WHERE report.deleted_at IS NULL
                                """
                                + predicate
                                + filter.orderBy()
                                + """
                                LIMIT :size
                                OFFSET :offset
                                """,
                        parameters,
                        (resultSet, rowNumber) ->
                                mapListItem(resultSet));
        long totalElements = total == null ? 0 : total;
        return new PageResponse<>(
                content,
                page,
                size,
                totalElements,
                Math.toIntExact(
                        Math.ceilDiv(totalElements, size)));
    }

    public PageResponse<ReportVersionSummaryResponse> findVersions(
            UUID reportId,
            int page,
            int size
    ) {
        Map<String, Object> parameters = Map.of(
                "reportId", reportId,
                "size", size,
                "offset", Math.multiplyExact((long) page, size));
        Long total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM report.report_version version
                JOIN report.anomaly_report report
                  ON report.report_id = version.report_id
                 AND report.deleted_at IS NULL
                WHERE version.report_id = :reportId
                """,
                parameters,
                Long.class);
        List<ReportVersionSummaryResponse> versions =
                jdbcTemplate.query(
                        VERSION_SELECT
                                + """
                                WHERE version.report_id = :reportId
                                ORDER BY version.version_no DESC
                                LIMIT :size
                                OFFSET :offset
                                """,
                        parameters,
                        (resultSet, rowNumber) ->
                                mapVersionSummary(resultSet));
        long totalElements = total == null ? 0 : total;
        return new PageResponse<>(
                versions,
                page,
                size,
                totalElements,
                Math.toIntExact(
                        Math.ceilDiv(totalElements, size)));
    }

    public Optional<ReportVersionResponse> findVersion(
            UUID reportId,
            int versionNo
    ) {
        List<ReportVersionResponse> versions =
                jdbcTemplate.query(
                        VERSION_SELECT
                                + """
                                WHERE version.report_id = :reportId
                                  AND version.version_no = :versionNo
                                """,
                        Map.of(
                                "reportId", reportId,
                                "versionNo", versionNo),
                        (resultSet, rowNumber) ->
                                new ReportVersionResponse(
                                        mapVersionSummary(resultSet),
                                        resultSet.getString("content"),
                                        object(resultSet.getString(
                                                "statistics_json")),
                                        object(resultSet.getString(
                                                "evidence_json"))));
        return versions.stream().findFirst();
    }

    public String findLastRejectionReason(UUID reportId) {
        List<String> reasons = jdbcTemplate.queryForList("""
                SELECT reason
                FROM audit.audit_log
                WHERE target_type = 'REPORT'
                  AND target_id = :reportId
                  AND action_type = 'REPORT_REJECTED'
                  AND result = 'SUCCESS'
                  AND reason IS NOT NULL
                ORDER BY audit_id DESC
                LIMIT 1
                """,
                Map.of("reportId", reportId),
                String.class);
        return reasons.isEmpty() ? null : reasons.getFirst();
    }

    private static String predicate(
            ReportListFilter filter,
            MapSqlParameterSource parameters
    ) {
        StringBuilder sql = new StringBuilder();
        if (filter.search() != null) {
            sql.append("""
                    AND (
                        report.title ILIKE CONCAT(
                            '%',
                            :search,
                            '%'
                        )
                        OR report.report_code ILIKE CONCAT(
                            '%',
                            :search,
                            '%'
                        )
                    )
                    """);
            parameters.addValue("search", filter.search());
        }
        if (!filter.statuses().isEmpty()) {
            sql.append(
                    "AND report.report_status IN (:statuses)\n");
            parameters.addValue(
                    "statuses",
                    filter.statuses().stream()
                            .map(Enum::name)
                            .toList());
        }
        if (filter.createdBy() != null) {
            sql.append("AND report.created_by = :createdBy\n");
            parameters.addValue("createdBy", filter.createdBy());
        }
        // 요청 기간과 보고서 기간이 겹치는 행을 반환한다.
        if (filter.periodFrom() != null) {
            sql.append(
                    "AND report.period_to >= :periodFrom\n");
            parameters.addValue(
                    "periodFrom",
                    OffsetDateTime.ofInstant(
                            filter.periodFrom(),
                            java.time.ZoneOffset.UTC));
        }
        if (filter.periodTo() != null) {
            sql.append(
                    "AND report.period_from <= :periodTo\n");
            parameters.addValue(
                    "periodTo",
                    OffsetDateTime.ofInstant(
                            filter.periodTo(),
                            java.time.ZoneOffset.UTC));
        }
        if (filter.reportType() != null) {
            sql.append("AND report.report_type = :reportType\n");
            parameters.addValue(
                    "reportType",
                    filter.reportType().name());
        }
        if (filter.generationMethod() != null) {
            sql.append("""
                    AND report.generation_method =
                        :generationMethod
                    """);
            parameters.addValue(
                    "generationMethod",
                    filter.generationMethod().name());
        }
        return sql.toString();
    }

    private static ReportListItemResponse mapListItem(
            ResultSet resultSet
    ) throws SQLException {
        return new ReportListItemResponse(
                resultSet.getObject("report_id", UUID.class),
                resultSet.getString("report_code"),
                resultSet.getString("title"),
                resultSet.getString("summary"),
                ReportType.valueOf(
                        resultSet.getString("report_type")),
                ReportStatus.valueOf(
                        resultSet.getString("report_status")),
                ReportGenerationMethod.valueOf(
                        resultSet.getString("generation_method")),
                instant(resultSet, "period_from"),
                instant(resultSet, "period_to"),
                resultSet.getInt("current_version_no"),
                integer(resultSet, "approved_version_no"),
                ReportCreatorType.valueOf(
                        resultSet.getString("creator_type")),
                resultSet.getObject("created_by", UUID.class),
                resultSet.getString("created_by_display_name"),
                resultSet.getObject("updated_by", UUID.class),
                resultSet.getString("updated_by_display_name"),
                resultSet.getObject("approved_by", UUID.class),
                resultSet.getString("approved_by_display_name"),
                instant(resultSet, "approved_at"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"));
    }

    private static ReportVersionSummaryResponse mapVersionSummary(
            ResultSet resultSet
    ) throws SQLException {
        return new ReportVersionSummaryResponse(
                resultSet.getObject("report_id", UUID.class),
                resultSet.getInt("version_no"),
                resultSet.getString("title"),
                resultSet.getString("summary"),
                instant(resultSet, "period_from"),
                instant(resultSet, "period_to"),
                ReportGenerationMethod.valueOf(
                        resultSet.getString("generation_method")),
                fallbackReason(resultSet.getString(
                        "generation_fallback_reason")),
                resultSet.getString("llm_model"),
                resultSet.getString("prompt_version"),
                resultSet.getString("change_note"),
                ReportCreatorType.valueOf(
                        resultSet.getString("creator_type")),
                resultSet.getObject("created_by", UUID.class),
                resultSet.getString("created_by_display_name"),
                instant(resultSet, "created_at"));
    }

    private static ReportGenerationFallbackReason fallbackReason(
                    String value
            ) {
        return value == null
                ? null
                : ReportGenerationFallbackReason.valueOf(value);
    }

    private Map<String, Object> object(String value) {
        try {
            return objectMapper.readValue(value, JSON_OBJECT);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Stored report JSON could not be read",
                    exception);
        }
    }

    private static Integer integer(
            ResultSet resultSet,
            String field
    ) throws SQLException {
        int value = resultSet.getInt(field);
        return resultSet.wasNull() ? null : value;
    }

    private static Instant instant(
            ResultSet resultSet,
            String field
    ) throws SQLException {
        OffsetDateTime value = resultSet.getObject(
                field,
                OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
