package com.gilbom.report;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Repository
public class ReportRepository {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT =
            new TypeReference<>() {
            };

    private static final String REPORT_SELECT = """
            SELECT
                report.report_id,
                report.report_code,
                report.report_type,
                report.report_status,
                report.generation_method,
                report.creator_type,
                report.current_version_no,
                report.approved_version_no,
                report.created_by,
                header_creator.display_name AS created_by_display_name,
                report.approved_by,
                approver.display_name AS approved_by_display_name,
                report.approved_at,
                report.deleted_at,
                report.approved_pdf_version_no,
                report.approved_pdf_storage_key,
                report.approved_pdf_sha256,
                report.approved_pdf_created_at,
                COALESCE(
                    report.filter_criteria,
                    '{}'::jsonb
                )::text AS filter_criteria_json,
                report.created_at AS report_created_at,
                report.updated_at AS report_updated_at,
                version.title,
                version.summary,
                version.period_from,
                version.period_to,
                version.content,
                version.statistics::text AS statistics_json,
                version.evidence_snapshot::text AS evidence_json,
                version.created_by AS version_created_by,
                version_creator.display_name
                    AS version_created_by_display_name,
                version.creator_type AS version_creator_type,
                version.generation_method AS version_generation_method,
                version.llm_model,
                version.prompt_version,
                version.change_note,
                version.created_at AS version_created_at
            FROM report.anomaly_report report
            JOIN report.report_version version
              ON version.report_id = report.report_id
             AND version.version_no = report.current_version_no
            LEFT JOIN auth.admin_user header_creator
              ON header_creator.user_id = report.created_by
            LEFT JOIN auth.admin_user version_creator
              ON version_creator.user_id = version.created_by
            LEFT JOIN auth.admin_user approver
              ON approver.user_id = report.approved_by
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ReportRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void insertHeader(
            UUID reportId,
            String reportCode,
            ReportType reportType,
            ReportContent content,
            Map<String, Object> filterCriteria,
            ReportActor actor,
            Instant createdAt
    ) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO report.anomaly_report (
                    report_id,
                    report_code,
                    title,
                    summary,
                    report_type,
                    report_status,
                    generation_method,
                    creator_type,
                    period_from,
                    period_to,
                    filter_criteria,
                    current_version_no,
                    created_by,
                    created_at,
                    updated_at
                ) VALUES (
                    ?, ?, ?, ?, ?,
                    'DRAFT',
                    ?, ?,
                    ?, ?,
                    CAST(? AS jsonb),
                    1,
                    ?, ?, ?
                )
                """,
                reportId,
                reportCode,
                content.title(),
                content.summary(),
                reportType.name(),
                content.generationMethod().name(),
                actor.creatorType().name(),
                utc(content.periodFrom()),
                utc(content.periodTo()),
                json(filterCriteria),
                actor.userId(),
                utc(createdAt),
                utc(createdAt));
        requireSingleRow(inserted, "Report header was not inserted");
    }

    public void insertVersion(
            UUID reportId,
            int versionNo,
            ReportContent content,
            ReportActor actor,
            Instant createdAt
    ) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO report.report_version (
                    report_id,
                    version_no,
                    title,
                    summary,
                    period_from,
                    period_to,
                    content,
                    statistics,
                    evidence_snapshot,
                    llm_model,
                    prompt_version,
                    generation_method,
                    change_note,
                    creator_type,
                    created_by,
                    created_at
                ) VALUES (
                    ?, ?, ?, ?,
                    ?, ?, ?,
                    CAST(? AS jsonb),
                    CAST(? AS jsonb),
                    ?, ?, ?, ?, ?, ?, ?
                )
                """,
                reportId,
                versionNo,
                content.title(),
                content.summary(),
                utc(content.periodFrom()),
                utc(content.periodTo()),
                content.body(),
                json(content.statistics()),
                json(content.evidenceSnapshot()),
                content.llmModel(),
                content.promptVersion(),
                content.generationMethod().name(),
                content.changeNote(),
                actor.creatorType().name(),
                actor.userId(),
                utc(createdAt));
        requireSingleRow(inserted, "Report version was not inserted");
    }

    public void advanceVersion(
            UUID reportId,
            int versionNo,
            ReportStatus targetStatus,
            ReportContent content,
            Instant updatedAt
    ) {
        int updated = jdbcTemplate.update("""
                UPDATE report.anomaly_report
                SET title = ?,
                    summary = ?,
                    period_from = ?,
                    period_to = ?,
                    report_status = ?,
                    current_version_no = ?,
                    updated_at = ?
                WHERE report_id = ?
                  AND deleted_at IS NULL
                """,
                content.title(),
                content.summary(),
                utc(content.periodFrom()),
                utc(content.periodTo()),
                targetStatus.name(),
                versionNo,
                utc(updatedAt),
                reportId);
        requireSingleRow(updated, "Report version pointer was not updated");
    }

    public void changeStatus(
            UUID reportId,
            ReportStatus targetStatus,
            Instant updatedAt
    ) {
        int updated = jdbcTemplate.update("""
                UPDATE report.anomaly_report
                SET report_status = ?,
                    updated_at = ?
                WHERE report_id = ?
                  AND deleted_at IS NULL
                """,
                targetStatus.name(),
                utc(updatedAt),
                reportId);
        requireSingleRow(updated, "Report status was not updated");
    }

    public void approve(
            UUID reportId,
            int approvedVersionNo,
            UUID approvedBy,
            Instant approvedAt
    ) {
        int updated = jdbcTemplate.update("""
                UPDATE report.anomaly_report
                SET report_status = 'APPROVED',
                    approved_version_no = ?,
                    approved_by = ?,
                    approved_at = ?,
                    updated_at = ?
                WHERE report_id = ?
                  AND deleted_at IS NULL
                """,
                approvedVersionNo,
                approvedBy,
                utc(approvedAt),
                utc(approvedAt),
                reportId);
        requireSingleRow(updated, "Report approval was not stored");
    }

    public void softDelete(UUID reportId, Instant deletedAt) {
        int updated = jdbcTemplate.update("""
                UPDATE report.anomaly_report
                SET deleted_at = ?,
                    updated_at = ?
                WHERE report_id = ?
                  AND deleted_at IS NULL
                """,
                utc(deletedAt),
                utc(deletedAt),
                reportId);
        requireSingleRow(updated, "Report was not soft-deleted");
    }

    public void bindApprovedPdf(
            UUID reportId,
            int approvedVersionNo,
            String storageKey,
            byte[] sha256,
            Instant createdAt
    ) {
        int updated = jdbcTemplate.update("""
                UPDATE report.anomaly_report
                SET approved_pdf_version_no = ?,
                    approved_pdf_storage_key = ?,
                    approved_pdf_sha256 = ?,
                    approved_pdf_created_at = ?,
                    updated_at = ?
                WHERE report_id = ?
                  AND report_status = 'APPROVED'
                  AND approved_version_no = ?
                  AND approved_pdf_version_no IS NULL
                  AND deleted_at IS NULL
                """,
                approvedVersionNo,
                storageKey,
                sha256,
                utc(createdAt),
                utc(createdAt),
                reportId,
                approvedVersionNo);
        requireSingleRow(updated, "Approved PDF could not be bound");
    }

    public Optional<ReportRecord> findById(UUID reportId) {
        return queryOne(
                REPORT_SELECT + " WHERE report.report_id = ?",
                reportId);
    }

    public Optional<ReportRecord> findActiveById(UUID reportId) {
        return queryOne(
                REPORT_SELECT
                        + """
                         WHERE report.report_id = ?
                           AND report.deleted_at IS NULL
                        """,
                reportId);
    }

    public Optional<ReportRecord> findActiveByIdForUpdate(UUID reportId) {
        List<UUID> locked = jdbcTemplate.query("""
                        SELECT report_id
                        FROM report.anomaly_report
                        WHERE report_id = ?
                          AND deleted_at IS NULL
                        FOR UPDATE
                        """,
                (resultSet, rowNumber) ->
                        resultSet.getObject("report_id", UUID.class),
                reportId);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        // READ COMMITTED에서 lock 대기 중 version pointer가 바뀔 수
        // 있으므로, 잠금 획득 후 최신 header/version snapshot을 다시 읽는다.
        return findActiveById(reportId);
    }

    public List<ReportRecord> findAllActive() {
        return jdbcTemplate.query(
                REPORT_SELECT
                        + """
                         WHERE report.deleted_at IS NULL
                         ORDER BY report.created_at DESC
                        """,
                (resultSet, rowNumber) -> map(resultSet));
    }

    private Optional<ReportRecord> queryOne(
            String sql,
            UUID reportId
    ) {
        return jdbcTemplate.query(
                sql,
                resultSet -> resultSet.next()
                        ? Optional.of(map(resultSet))
                        : Optional.empty(),
                reportId);
    }

    private ReportRecord map(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        return new ReportRecord(
                resultSet.getObject("report_id", UUID.class),
                resultSet.getString("report_code"),
                ReportType.valueOf(resultSet.getString("report_type")),
                ReportStatus.valueOf(
                        resultSet.getString("report_status")),
                ReportGenerationMethod.valueOf(
                        resultSet.getString("generation_method")),
                ReportCreatorType.valueOf(
                        resultSet.getString("creator_type")),
                resultSet.getInt("current_version_no"),
                integer(resultSet, "approved_version_no"),
                resultSet.getObject("created_by", UUID.class),
                resultSet.getString("created_by_display_name"),
                resultSet.getObject("approved_by", UUID.class),
                resultSet.getString("approved_by_display_name"),
                instant(resultSet, "approved_at"),
                instant(resultSet, "deleted_at"),
                integer(resultSet, "approved_pdf_version_no"),
                resultSet.getString("approved_pdf_storage_key"),
                resultSet.getBytes("approved_pdf_sha256"),
                instant(resultSet, "approved_pdf_created_at"),
                object(resultSet.getString("filter_criteria_json")),
                instant(resultSet, "report_created_at"),
                instant(resultSet, "report_updated_at"),
                resultSet.getString("title"),
                resultSet.getString("summary"),
                instant(resultSet, "period_from"),
                instant(resultSet, "period_to"),
                resultSet.getString("content"),
                object(resultSet.getString("statistics_json")),
                object(resultSet.getString("evidence_json")),
                resultSet.getObject("version_created_by", UUID.class),
                resultSet.getString(
                        "version_created_by_display_name"),
                ReportCreatorType.valueOf(
                        resultSet.getString("version_creator_type")),
                ReportGenerationMethod.valueOf(
                        resultSet.getString(
                                "version_generation_method")),
                resultSet.getString("llm_model"),
                resultSet.getString("prompt_version"),
                resultSet.getString("change_note"),
                instant(resultSet, "version_created_at"));
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

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Report JSON could not be serialized",
                    exception);
        }
    }

    private static Integer integer(
            java.sql.ResultSet resultSet,
            String column
    ) throws java.sql.SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
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

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void requireSingleRow(int affected, String message) {
        if (affected != 1) {
            throw new IllegalStateException(message);
        }
    }
}
